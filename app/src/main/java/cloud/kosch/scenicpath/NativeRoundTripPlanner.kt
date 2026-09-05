package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Debug/device round-trip planner. Geographic shaping points create candidate corridors only;
 * they are never exposed as attractions. Real Smart Stops are then discovered and, where the
 * selected day-trip budget permits, inserted as actual routed waypoints.
 */
object NativeRoundTripPlanner {
    suspend fun plan(
        origin: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi = withContext(Dispatchers.IO) {
        val effective = preferences.forCharacter(plan.routeCharacter)
        val budget = effective.maxExtraMinutes.coerceAtLeast(30)
        val fixedStops = plan.stops.filter { it.mustVisit && it.point != null }
        val fixedDwell = fixedStops.sumOf { it.dwellMinutes }
        val requested = plan.requestedAlternatives.coerceIn(2, 5)
        val generation = maxOf(plan.alternativeGeneration, requested - 2)
        val waypointSets = RoundTripPolicy.waypointSets(
            origin = origin,
            vehicle = effective.vehicle,
            budgetMinutes = budget,
            autoSuggestStops = plan.autoSuggestStops,
            count = (requested + 3).coerceAtMost(6),
            fixedDwellMinutes = fixedDwell,
            generation = generation,
        )

        val rawRoutes = coroutineScope {
            waypointSets.mapIndexed { index, shaping ->
                async(Dispatchers.IO) {
                    val ordered = orderLoopWaypoints(origin, shaping, fixedStops)
                    runCatching {
                        NativeValhallaRouteClient.routeThrough(
                            nodes = listOf(origin) + ordered + origin,
                            preferences = effective,
                            scenic = plan.routeCharacter != RouteCharacter.DIRECT,
                        )
                    }.getOrNull()?.let { Triple(index, shaping, it) }
                }
            }.awaitAll().filterNotNull()
        }
        if (rawRoutes.isEmpty()) error("No routable scenic loop could be created around this start point")

        // Route construction may evaluate several loop geometries in parallel, but public POI
        // providers are intentionally coordinated and serialized. This prevents the third/fourth
        // alternative from exploding into dozens of simultaneous Photon/Overpass requests.
        val enriched = coroutineScope {
            rawRoutes.map { (index, shaping, route) ->
                async(Dispatchers.IO) {
                    val discoveries = if (plan.autoSuggestStops && plan.enabledSceneKinds.isNotEmpty()) {
                        discoverRoundTripPois(route.points, plan.enabledSceneKinds, budget)
                    } else emptyList()
                    RoundRaw(index, shaping, route, discoveries)
                }
            }.awaitAll()
        }

        val usedStopIds = mutableSetOf<String>()
        val candidates = enriched
            .sortedBy { item ->
                kotlin.math.abs(
                    RoundTripPolicy.budgetUtilization(
                        item.route.durationSeconds / 60.0 + fixedDwell,
                        budget,
                    ) - 0.82
                )
            }
            .mapNotNull { item ->
                buildCandidate(
                    origin = origin,
                    item = item,
                    plan = plan,
                    preferences = effective,
                    fixedStops = fixedStops,
                    budget = budget,
                    usedStopIds = usedStopIds,
                    generation = generation,
                )
            }

        if (candidates.isEmpty()) error("Round-trip candidates exceeded the selected time budget")
        val qualityOrdered = candidates.sortedByDescending { candidate ->
            val utilization = RoundTripPolicy.utilizationScore(candidate.budgetUsedMinutes ?: 0.0, budget)
            candidate.experienceScore * 0.72 + utilization * 28.0
        }
        val diverse = RouteDiversityPolicy.order(qualityOrdered, requested)
        diverse.forEach { candidate ->
            if (candidate.scenePoints.isNotEmpty()) {
                ScenicPoiSharedState.publish(candidate.points, candidate.scenePoints)
                RoutePoiDiscoveryCoordinator.seed(candidate.points, plan.enabledSceneKinds, candidate.scenePoints)
            }
        }
        RoutePlanUi(
            candidates = diverse,
            baselineDurationSeconds = null,
            baselineDistanceMeters = null,
            note = buildString {
                append("Round trip · returns to start · target $budget min total outing")
                append(" · ${diverse.size} deliberately different loop${if (diverse.size == 1) "" else "s"}")
                if (generation > 0) append(" · alternative generation ${generation + 1}")
                val best = diverse.firstOrNull()?.budgetUsedMinutes?.roundToInt()
                if (best != null) append(" · best route uses about $best min")
                if (diverse.firstOrNull()?.autoStopIds?.isNotEmpty() == true) append(" · real Smart Stop waypoints included")
            },
        )
    }

    private suspend fun discoverRoundTripPois(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        budgetMinutes: Int,
    ): List<ScenePointUi> = RoutePoiDiscoveryCoordinator.discover(
        route = route,
        enabledKinds = enabledKinds,
        maxResults = 220,
        broad = budgetMinutes >= 120,
    )

    private data class RoundRaw(
        val index: Int,
        val shaping: List<GeoPoint>,
        val route: NativeNetworkRoute,
        val discoveries: List<ScenePointUi>,
    )

    private fun buildCandidate(
        origin: GeoPoint,
        item: RoundRaw,
        plan: TripPlan,
        preferences: ScenicPreferences,
        fixedStops: List<PlannedStop>,
        budget: Int,
        usedStopIds: MutableSet<String>,
        generation: Int,
    ): RouteCandidateUi? {
        val fixedDwell = fixedStops.sumOf { it.dwellMinutes }
        val baseOuting = item.route.durationSeconds / 60.0 + fixedDwell
        if (baseOuting > budget * 1.08) return null

        val eligible = item.discoveries
            .filter { NativeAutoStopPolicy.foodMatches(it, preferences) }
            .filterNot { it.id in usedStopIds }
        var selected = if (plan.autoSuggestStops) {
            NativeAutoStopPolicy.select(eligible, preferences, plan.enabledSceneKinds)
        } else emptyList()
        var routed = item.route

        while (selected.isNotEmpty()) {
            val anchors = buildList {
                item.shaping.forEachIndexed { index, point -> add("shape-$index" to point) }
                fixedStops.forEach { stop -> stop.point?.let { add(stop.id to it) } }
                selected.forEach { add(it.id to it.point) }
            }.distinctBy { it.first }
                .sortedBy { routeProgressIndex(item.route.points, it.second) }
                .map { it.second }

            val trial = runCatching {
                NativeValhallaRouteClient.routeThrough(
                    nodes = listOf(origin) + anchors + origin,
                    preferences = preferences,
                    scenic = true,
                )
            }.getOrNull()
            if (trial != null) {
                val outing = trial.durationSeconds / 60.0 + fixedDwell + selected.sumOf { it.suggestedDwellMinutes }
                if (outing <= budget * 1.03) {
                    routed = trial
                    break
                }
            }
            val weakest = NativeAutoStopPolicy.weakestRemovable(selected, preferences, plan.enabledSceneKinds)
            if (weakest == null) {
                selected = emptyList()
                break
            }
            selected = selected.filterNot { it.id == weakest.id }
        }

        val autoDwell = selected.sumOf { it.suggestedDwellMinutes }
        val outing = routed.durationSeconds / 60.0 + fixedDwell + autoDwell
        if (outing > budget * 1.05) return null
        selected.forEach { usedStopIds += it.id }
        val included = selected.mapTo(mutableSetOf()) { it.id }
        val fixedHighlights = fixedStops.mapNotNull(::fixedHighlight)
        val scenePoints = buildList {
            addAll(fixedHighlights)
            item.discoveries.forEach { point ->
                add(
                    point.copy(
                        includedInRoute = point.id in included,
                        rationale = if (point.id in included) "Different round-tour highlight · routed inside the day budget" else point.rationale,
                    )
                )
            }
        }.distinctBy { it.id }

        val poiQuality = item.discoveries.take(12).map { it.relevance.coerceIn(0.0, 1.0) }.averageOrZero()
        val utilization = RoundTripPolicy.utilizationScore(outing, budget)
        val scenic = (55.0 + poiQuality * 25.0 + utilization * 20.0).coerceIn(0.0, 100.0)
        return RouteCandidateUi(
            id = "native-round-g$generation-${item.index}",
            character = if (plan.routeCharacter == RouteCharacter.DIRECT) RouteCharacter.DIRECT.name else RouteCharacter.BEAUTIFUL.name,
            distanceMeters = routed.distanceMeters,
            durationSeconds = routed.durationSeconds,
            scenicScore = scenic,
            extraMinutes = routed.durationSeconds / 60.0,
            points = routed.points,
            provider = "Valhalla / OpenStreetMap · round-trip generator",
            scenePoints = scenePoints,
            strongestSignals = listOf("roundTrip", "budgetUtilization", "routeDiversity") + if (included.isNotEmpty()) listOf("automaticSmartStops") else emptyList(),
            variantLabel = if (generation == 0 && item.index == 0) "Best round tour" else "Round tour ${item.index + 1}",
            experienceScore = scenic,
            autoStopIds = selected.map { it.id },
            driveExtraMinutes = routed.durationSeconds / 60.0,
            dwellMinutes = fixedDwell + autoDwell,
            totalExtraMinutes = outing,
            corridorRadiusKm = item.shaping.maxOfOrNull { RoundTripPolicy.haversineMeters(origin, it) }?.div(1000.0) ?: 0.0,
            dataConfidence = if (item.discoveries.isEmpty()) 0.72 else 0.92,
            budgetUsedMinutes = outing,
            budgetMinutes = budget,
            isRoundTrip = true,
        )
    }

    private fun fixedHighlight(stop: PlannedStop): ScenePointUi? = stop.point?.let { point ->
        ScenePointUi(
            id = stop.id,
            name = stop.name,
            kind = stop.kind.name,
            subtype = stop.subtype,
            point = point,
            relevance = 1.3,
            suggestionScore = 300.0,
            distanceFromRouteMeters = 0,
            suggestedDwellMinutes = stop.dwellMinutes,
            rating = stop.rating,
            ratingCount = stop.ratingCount,
            includedInRoute = true,
            personalMatch = 100.0,
            rationale = "fixed round-trip waypoint",
        )
    }

    private fun orderLoopWaypoints(origin: GeoPoint, shaping: List<GeoPoint>, fixedStops: List<PlannedStop>): List<GeoPoint> {
        if (shaping.isEmpty()) return fixedStops.mapNotNull { it.point }
        val startBearing = bearing(origin, shaping.first())
        return buildList {
            shaping.forEachIndexed { index, point -> add(LoopPoint(point, true, index, normalizedBearing(startBearing, bearing(origin, point)))) }
            fixedStops.forEachIndexed { index, stop -> stop.point?.let { add(LoopPoint(it, false, index, normalizedBearing(startBearing, bearing(origin, it)))) } }
        }.sortedWith(compareBy<LoopPoint> { it.progress }.thenByDescending { it.shape }.thenBy { it.index }).map { it.point }
    }

    private data class LoopPoint(val point: GeoPoint, val shape: Boolean, val index: Int, val progress: Double)

    private fun normalizedBearing(start: Double, value: Double): Double = (value - start + 360.0) % 360.0

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun routeProgressIndex(route: List<GeoPoint>, point: GeoPoint): Int =
        route.indices.minByOrNull { RoundTripPolicy.haversineMeters(route[it], point) } ?: Int.MAX_VALUE

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
