package cloud.kosch.scenicpath

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stable routing for one or more mandatory Scenic waypoints.
 *
 * v0.5.12 removed the legacy motorway trace false-positive by routing manual POIs through the
 * OSM/Valhalla fallback directly. Physical-device testing then exposed a second, independent
 * failure: after one successful recalculation, adding another POI could make a single multi-break
 * Valhalla request fail with Thor error 442 ("No path could be found for input").
 *
 * The important distinction is that each POI can be routable on its own while one global
 * correlation of several raw POI coordinates can still fail. POIs such as ruins, monuments,
 * parks and viewpoints are often mapped away from the drivable access road; Valhalla must snap
 * each coordinate to a road-network candidate. Requiring one multi-break request to keep all of
 * those correlations mutually compatible is unnecessarily brittle for an interactive planner.
 *
 * v0.5.13 therefore uses a deterministic pairwise commit path:
 * 1. build one clean A→B baseline to establish forward route order;
 * 2. sort flexible mandatory POIs along that baseline;
 * 3. route A→POI1, POI1→POI2, ... POIn→B independently through OsmScenicRoutingFallback;
 * 4. if a scenic leg fails but its ordinary motorway-avoiding leg is routable, keep that stable
 *    leg instead of rejecting the whole journey;
 * 5. stitch the successfully routed legs into one continuous displayed journey;
 * 6. validate that every selected POI remains close to the stitched route.
 *
 * This keeps the old motorway-validator path out of manual waypoint routing, preserves forward
 * POI order and avoids a single unreachable multi-break correlation destroying an otherwise
 * perfectly routable journey.
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

        val nodes = buildList {
            add(origin)
            mandatoryStops.forEach { add(requireNotNull(it.point)) }
            add(destination)
        }

        // Do not send all raw POI coordinates as one Valhalla multi-break request. Each pair is
        // correlated independently so adding a second/third POI cannot invalidate the road snap
        // that already worked for an earlier POI.
        val legResults = nodes.zipWithNext().mapIndexed { index, (from, to) ->
            routeLegSafely(
                from = from,
                to = to,
                plan = plan,
                preferences = preferences,
                fromLabel = nodeLabel(index, mandatoryStops, destination = false),
                toLabel = nodeLabel(index + 1, mandatoryStops, destination = index + 1 == nodes.lastIndex),
            )
        }

        val directPieces = legResults.map(::pickDirect)
        val scenicPieces = legResults.map { result -> pickScenic(result, plan.routeCharacter) }
        val highlights = mandatoryStops.map(::toHighlight)
        val manualDwell = mandatoryStops.sumOf { it.dwellMinutes }

        var direct = stitch(
            id = "waypoint-pairwise-direct",
            label = "Direct via waypoints",
            pieces = directPieces,
            requestedCharacter = RouteCharacter.DIRECT,
            manualHighlights = highlights,
            manualDwellMinutes = manualDwell,
            direct = true,
        )
        validateMandatoryStops(direct.points, mandatoryStops)

        var scenic = stitch(
            id = "waypoint-pairwise-scenic",
            label = "Best match via waypoints",
            pieces = scenicPieces,
            requestedCharacter = plan.routeCharacter,
            manualHighlights = highlights,
            manualDwellMinutes = manualDwell,
            direct = plan.routeCharacter == RouteCharacter.DIRECT,
        )
        validateMandatoryStops(scenic.points, mandatoryStops)

        val directDriveExtra = max(0.0, (direct.durationSeconds - baseline.durationSeconds) / 60.0)
        direct = direct.copy(
            extraMinutes = directDriveExtra,
            driveExtraMinutes = directDriveExtra,
            totalExtraMinutes = directDriveExtra + direct.dwellMinutes,
        )

        val scenicDriveExtra = max(0.0, (scenic.durationSeconds - baseline.durationSeconds) / 60.0)
        scenic = scenic.copy(
            extraMinutes = scenicDriveExtra,
            driveExtraMinutes = scenicDriveExtra,
            totalExtraMinutes = scenicDriveExtra + scenic.dwellMinutes,
        )

        val candidates = if (plan.routeCharacter == RouteCharacter.DIRECT) {
            listOf(direct)
        } else {
            listOf(scenic, direct).distinctBy(::candidateKey)
        }

        val totalBudget = preferences.maxExtraMinutes.coerceAtLeast(0)
        val reordered = mandatoryStops.map { it.id } != rawStops.map { it.id }
        val best = candidates.first()
        val bestTotalExtra = best.totalExtraMinutes

        return RoutePlanUi(
            candidates = candidates,
            baselineDurationSeconds = baseline.durationSeconds,
            baselineDistanceMeters = baseline.distanceMeters,
            note = buildString {
                append("${mandatoryStops.size} fixed waypoint")
                if (mandatoryStops.size != 1) append("s")
                if (reordered) append(" · automatically ordered along the route")
                append(" · pairwise road-network routing")
                append(" · ${best.driveExtraMinutes.roundToInt()} min mandatory POI detour")
                append(" · $totalBudget min global budget")
                if (bestTotalExtra > totalBudget + 1.0) {
                    append(" · fixed POIs themselves exceed the time budget")
                }
                if (plan.autoSuggestStops) {
                    append(" · Smart Stop suggestions remain available without changing fixed waypoint geometry")
                }
            },
        )
    }

    private suspend fun routeLegSafely(
        from: GeoPoint,
        to: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
        fromLabel: String,
        toLabel: String,
    ): RoutePlanUi {
        val legPlan = plan.copy(
            stops = emptyList(),
            flexibleStopOrder = false,
            autoSuggestStops = false,
        )

        val scenicAttempt = runCatching {
            OsmScenicRoutingFallback.plan(
                origin = from,
                destination = to,
                plan = legPlan,
                preferences = preferences,
            )
        }
        scenicAttempt.getOrNull()?.let { return it }

        // On a Beautiful/Custom leg the ordinary Valhalla baseline can be valid while the
        // additional scenic-shortest request fails. Retain the valid motorway-avoiding geometry
        // instead of turning a single scenic variant failure into a journey-wide error.
        if (legPlan.routeCharacter != RouteCharacter.DIRECT) {
            val directAttempt = runCatching {
                OsmScenicRoutingFallback.plan(
                    origin = from,
                    destination = to,
                    plan = legPlan.copy(routeCharacter = RouteCharacter.DIRECT),
                    preferences = preferences,
                )
            }
            directAttempt.getOrNull()?.let { return it }

            val failure = directAttempt.exceptionOrNull() ?: scenicAttempt.exceptionOrNull()
            throw IllegalStateException(
                friendlyLegFailure(fromLabel, toLabel, failure),
                failure,
            )
        }

        val failure = scenicAttempt.exceptionOrNull()
        throw IllegalStateException(
            friendlyLegFailure(fromLabel, toLabel, failure),
            failure,
        )
    }

    private fun friendlyLegFailure(fromLabel: String, toLabel: String, failure: Throwable?): String {
        val raw = failure?.message.orEmpty()
        val reason = when {
            raw.contains("error_code\":442") || raw.contains("No path could be found for input", ignoreCase = true) ->
                "Valhalla could not connect this POI to the drivable road network with the current constraints"
            raw.contains("error_code\":171") || raw.contains("No suitable edges near location", ignoreCase = true) ->
                "no drivable road could be matched near this POI"
            raw.isNotBlank() -> raw
            else -> "routing failed"
        }
        return "Could not route $fromLabel → $toLabel: $reason. Your previous route is still shown."
    }

    private fun nodeLabel(
        nodeIndex: Int,
        stops: List<PlannedStop>,
        destination: Boolean,
    ): String = when {
        nodeIndex == 0 -> "start"
        destination -> "destination"
        nodeIndex - 1 in stops.indices -> stops[nodeIndex - 1].name
        else -> "waypoint"
    }

    private fun pickDirect(result: RoutePlanUi): RouteCandidateUi =
        result.candidates.firstOrNull {
            it.character == RouteCharacter.DIRECT.name ||
                it.id.contains("direct", ignoreCase = true)
        } ?: result.candidates.minByOrNull { it.durationSeconds }
        ?: error("Waypoint leg returned no direct route")

    private fun pickScenic(result: RoutePlanUi, requested: RouteCharacter): RouteCandidateUi {
        if (requested == RouteCharacter.DIRECT) return pickDirect(result)
        return result.candidates.firstOrNull {
            it.character != RouteCharacter.DIRECT.name &&
                !it.id.contains("direct", ignoreCase = true)
        } ?: pickDirect(result)
    }

    private fun stitch(
        id: String,
        label: String,
        pieces: List<RouteCandidateUi>,
        requestedCharacter: RouteCharacter,
        manualHighlights: List<ScenePointUi>,
        manualDwellMinutes: Int,
        direct: Boolean,
    ): RouteCandidateUi {
        require(pieces.isNotEmpty()) { "Waypoint journey returned no route pieces" }

        val points = buildList {
            pieces.forEach { piece ->
                if (isEmpty()) {
                    addAll(piece.points)
                } else if (piece.points.isNotEmpty()) {
                    if (last() == piece.points.first()) addAll(piece.points.drop(1))
                    else addAll(piece.points)
                }
            }
        }
        if (points.size < 2) error("Waypoint journey route shape is empty")

        val distance = pieces.sumOf { it.distanceMeters }
        val duration = pieces.sumOf { it.durationSeconds }
        val weightTotal = pieces.sumOf { it.distanceMeters.coerceAtLeast(1.0) }
        fun weighted(selector: (RouteCandidateUi) -> Double): Double =
            pieces.sumOf { selector(it) * it.distanceMeters.coerceAtLeast(1.0) } / weightTotal

        val manualIds = manualHighlights.mapTo(mutableSetOf()) { it.id }
        val scenePoints = buildList {
            addAll(manualHighlights)
            pieces.flatMap { it.scenePoints }.forEach { point ->
                if (point.id !in manualIds) add(point)
            }
        }.distinctBy { it.id }
        val automaticDwell = pieces.sumOf { it.dwellMinutes }

        return RouteCandidateUi(
            id = id,
            character = if (direct) RouteCharacter.DIRECT.name else requestedCharacter.name,
            distanceMeters = distance,
            durationSeconds = duration,
            scenicScore = if (direct) 0.0 else weighted { it.scenicScore },
            extraMinutes = 0.0,
            points = points,
            provider = "Pairwise waypoint router · Valhalla / OpenStreetMap development",
            scenePoints = scenePoints,
            strongestSignals = (
                listOf(
                    "fixedWaypoints",
                    "forwardPoiOrder",
                    "pairwiseWaypointRouting",
                    "independentPoiRoadCorrelation",
                    "motorwayAvoidanceAtRoutingCost",
                ) + pieces.flatMap { it.strongestSignals }
            ).distinct().take(8),
            isPreviewFallback = pieces.any { it.isPreviewFallback },
            variantLabel = label,
            experienceScore = if (direct) 0.0 else weighted { it.experienceScore },
            autoStopIds = pieces.flatMap { it.autoStopIds }.distinct(),
            driveExtraMinutes = 0.0,
            dwellMinutes = manualDwellMinutes + automaticDwell,
            totalExtraMinutes = (manualDwellMinutes + automaticDwell).toDouble(),
            corridorRadiusKm = pieces.maxOfOrNull { it.corridorRadiusKm } ?: 0.0,
            dataConfidence = pieces.map { it.dataConfidence }.averageOrZero(),
        )
    }

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
        rationale = "fixed waypoint · must visit · independently road-matched · ordered with route flow",
        estimatedDetourMinutes = 0.0,
    )

    private fun validateMandatoryStops(
        route: List<GeoPoint>,
        stops: List<PlannedStop>,
    ) {
        stops.forEach { stop ->
            val point = stop.point ?: return@forEach
            // POI coordinates can represent the feature centroid rather than its driveway or
            // entrance. Pairwise Valhalla routing may therefore snap to an accessible road nearby.
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

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
