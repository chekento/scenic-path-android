package cloud.kosch.scenicpath

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mandatory Scenic waypoints are routed as real Valhalla breaks in one continuous journey.
 *
 * The earlier waypoint implementation replanned every leg through SegmentedJourneyOptimizer.
 * Short CUSTOM legs therefore re-entered ScenicJourneyOptimizer, whose legacy post-route
 * motorway trace can still reject a route after the routing engine has already produced it with
 * motorway avoidance. That is the source of the persistent false "No motorway-free route" error
 * seen on physical devices when committing an ordinary nearby Scenic POI.
 *
 * Manual waypoint routing is now deliberately simpler and more deterministic:
 * 1. build one A→B baseline only to establish forward route order;
 * 2. sort flexible mandatory POIs along that baseline;
 * 3. route origin → ordered POIs → destination in ONE OsmScenicRoutingFallback/Valhalla call;
 * 4. keep automatic Smart Stop discovery separate from mandatory waypoint geometry.
 *
 * This prevents both the legacy validator path and per-leg scenic re-optimization from turning a
 * small POI detour into a failed or looping multi-hour journey.
 */
object WaypointAwareJourneyOptimizer {
    suspend fun plan(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi {
        val rawStops = plan.stops.filter { it.mustVisit && it.point != null }
        if (rawStops.isEmpty()) {
            return SegmentedJourneyOptimizer.plan(origin, destination, plan, preferences)
        }

        val baselinePlan = plan.copy(
            routeCharacter = RouteCharacter.DIRECT,
            stops = emptyList(),
            flexibleStopOrder = false,
            autoSuggestStops = false,
        )
        val baseline = pickDirect(
            SegmentedJourneyOptimizer.plan(
                origin = origin,
                destination = destination,
                plan = baselinePlan,
                preferences = preferences.forCharacter(RouteCharacter.DIRECT).copy(maxStops = 0),
            )
        )

        val mandatoryStops = if (plan.flexibleStopOrder) {
            rawStops.sortedWith(
                compareBy<PlannedStop> { stop ->
                    routeProgressIndex(baseline.points, requireNotNull(stop.point))
                }.thenBy { stop ->
                    distanceToRouteMeters(baseline.points, requireNotNull(stop.point))
                }
            )
        } else {
            rawStops
        }

        // A manually selected POI is a hard route break. Do not start a second automatic stop
        // optimization while committing it; suggestions are independently repopulated on the map
        // after the replacement route is available.
        val orderedPlan = plan.copy(
            stops = mandatoryStops,
            flexibleStopOrder = false,
            autoSuggestStops = false,
        )

        val routed = OsmScenicRoutingFallback.plan(
            origin = origin,
            destination = destination,
            plan = orderedPlan,
            preferences = preferences,
        )

        val highlights = mandatoryStops.map(::toHighlight)
        val highlightIds = highlights.mapTo(mutableSetOf()) { it.id }
        val manualDwell = mandatoryStops.sumOf { it.dwellMinutes }
        val totalBudget = preferences.maxExtraMinutes.coerceAtLeast(0)

        val candidates = routed.candidates.map { candidate ->
            validateMandatoryStops(candidate.points, mandatoryStops)

            val driveExtra = max(
                0.0,
                (candidate.durationSeconds - baseline.durationSeconds) / 60.0,
            )
            val existingScenePoints = candidate.scenePoints.filterNot { it.id in highlightIds }
            val totalDwell = manualDwell + candidate.dwellMinutes
            val direct = candidate.character == RouteCharacter.DIRECT.name ||
                candidate.id.contains("direct", ignoreCase = true)

            candidate.copy(
                id = "waypoint-${candidate.id}",
                variantLabel = if (direct) "Direct via waypoints" else "Best match via waypoints",
                scenePoints = highlights + existingScenePoints,
                extraMinutes = driveExtra,
                driveExtraMinutes = driveExtra,
                dwellMinutes = totalDwell,
                totalExtraMinutes = driveExtra + totalDwell,
                strongestSignals = (
                    listOf(
                        "fixedWaypoints",
                        "forwardPoiOrder",
                        "singlePassWaypointRoute",
                        "motorwayAvoidanceAtRoutingCost",
                    ) + candidate.strongestSignals
                ).distinct().take(8),
            )
        }

        if (candidates.isEmpty()) error("Waypoint route returned no candidates")

        val reordered = mandatoryStops.map { it.id } != rawStops.map { it.id }
        val best = candidates.first()
        val bestDriveExtra = max(
            0.0,
            (best.durationSeconds - baseline.durationSeconds) / 60.0,
        )
        val bestTotalExtra = bestDriveExtra + manualDwell

        return RoutePlanUi(
            candidates = candidates.distinctBy(::candidateKey),
            baselineDurationSeconds = baseline.durationSeconds,
            baselineDistanceMeters = baseline.distanceMeters,
            note = buildString {
                append("${mandatoryStops.size} fixed waypoint")
                if (mandatoryStops.size != 1) append("s")
                if (reordered) append(" · automatically ordered along the route")
                append(" · ${bestDriveExtra.roundToInt()} min mandatory POI detour")
                append(" · $totalBudget min global budget")
                if (bestTotalExtra > totalBudget + 1.0) {
                    append(" · fixed POI itself exceeds the time budget")
                }
                if (plan.autoSuggestStops) {
                    append(" · Smart Stop suggestions remain available without changing waypoint geometry")
                }
            },
        )
    }

    private fun pickDirect(result: RoutePlanUi): RouteCandidateUi =
        result.candidates.firstOrNull {
            it.character == RouteCharacter.DIRECT.name ||
                it.id.contains("direct", ignoreCase = true)
        } ?: result.candidates.minByOrNull { it.durationSeconds }
        ?: error("Waypoint baseline returned no direct route")

    private fun toHighlight(stop: PlannedStop): ScenePointUi = ScenePointUi(
        id = stop.id,
        name = stop.name,
        kind = stop.kind.name,
        subtype = stop.subtype,
        point = requireNotNull(stop.point),
        relevance = 1.25,
        suggestionScore = 250.0,
        distanceFromRouteMeters = 0,
        suggestedDwellMinutes = stop.dwellMinutes,
        rating = stop.rating,
        ratingCount = stop.ratingCount,
        includedInRoute = true,
        personalMatch = 100.0,
        rationale = "fixed waypoint · must visit · ordered with route flow",
        estimatedDetourMinutes = 0.0,
    )

    private fun validateMandatoryStops(
        route: List<GeoPoint>,
        stops: List<PlannedStop>,
    ) {
        stops.forEach { stop ->
            val point = stop.point ?: return@forEach
            // Valhalla may snap a POI coordinate to its nearest accessible road/entrance. Keep
            // the hard validation, but allow that legitimate access-road snap instead of testing
            // raw POI geometry against a sub-kilometre threshold.
            if (distanceToRouteMeters(route, point) > 1_500.0) {
                error("Recalculated route bypassed fixed waypoint: ${stop.name}")
            }
        }
    }

    private fun routeProgressIndex(route: List<GeoPoint>, point: GeoPoint): Int =
        route.indices.minByOrNull { haversineMeters(route[it], point) } ?: Int.MAX_VALUE

    private fun distanceToRouteMeters(route: List<GeoPoint>, point: GeoPoint): Double =
        route.minOfOrNull { haversineMeters(it, point) } ?: Double.POSITIVE_INFINITY

    private fun candidateKey(candidate: RouteCandidateUi): String =
        "${(candidate.distanceMeters / 250.0).toInt()}:" +
            "${(candidate.durationSeconds / 60.0).toInt()}:" +
            candidate.scenePoints
                .filter { it.includedInRoute }
                .map { it.id }
                .sorted()
                .joinToString(",")

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val earth = 6_371_000.0
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val sinLat = kotlin.math.sin(dLat / 2.0)
        val sinLon = kotlin.math.sin(dLon / 2.0)
        val h = sinLat * sinLat +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * sinLon * sinLon
        return 2.0 * earth *
            kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }
}
