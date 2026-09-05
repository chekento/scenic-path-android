package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * Adds route variants to the native/debug planner by forcing unused Scenic POIs as real waypoints.
 * Each + Route generation deliberately moves deeper into a new POI/corridor pool instead of
 * re-requesting the same alternative set.
 */
object NativeAlternativePlanner {
    suspend fun augment(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
        base: RoutePlanUi,
    ): RoutePlanUi = withContext(Dispatchers.IO) {
        val requested = plan.requestedAlternatives.coerceIn(1, 5)
        if (requested <= 1 || base.candidates.isEmpty() || plan.routeCharacter == RouteCharacter.DIRECT) {
            return@withContext base.copy(candidates = base.candidates.take(requested))
        }

        val primary = base.candidates.first()
        val generation = maxOf(plan.alternativeGeneration, requested - 2)
        val manualIds = plan.stops.mapTo(mutableSetOf()) { it.id }
        val used = primary.autoStopIds.toMutableSet()

        val broad = if (preferences.maxExtraMinutes >= 90 && plan.enabledSceneKinds.isNotEmpty()) {
            withTimeoutOrNull(10_000) {
                runCatching {
                    PrecisionRoutePoiDiscovery.discover(
                        route = primary.points,
                        enabledKinds = plan.enabledSceneKinds,
                        maxResults = 180,
                        radiusMeters = NativeAutoStopPolicy.distanceLimitMeters(preferences.maxExtraMinutes),
                        maxSamples = if (preferences.maxExtraMinutes >= 240) 14 else 10,
                    )
                }.getOrElse { emptyList() }
            }.orEmpty()
        } else emptyList()

        val discoveryPool = PrecisionRoutePoiDiscovery.mergeForDisplay(
            first = primary.scenePoints,
            second = broad,
            maxResults = 260,
        )
        val available = discoveryPool
            .filterNot { it.id in manualIds || it.id in used || it.includedInRoute }
            .filter { NativeAutoStopPolicy.foodMatches(it, preferences) }
            .sortedByDescending { point ->
                point.distanceFromRouteMeters.coerceAtLeast(0) / 120.0 + NativeAutoStopPolicy.utility(point, preferences)
            }

        val generated = mutableListOf<RouteCandidateUi>()
        val baselineSeconds = base.baselineDurationSeconds ?: base.candidates.minOf { it.durationSeconds }
        val fixedStops = plan.stops.filter { it.mustVisit && it.point != null }
        val fixedDwell = fixedStops.sumOf { it.dwellMinutes }
        val stopsPerAlternative = NativeAutoStopPolicy.limit(preferences.maxExtraMinutes, preferences.maxStops)
            .coerceIn(1, 4)
        val generationOffset = generation * stopsPerAlternative

        for (variant in 1 until requested) {
            val selected = available
                .filterNot { it.id in used }
                .drop(generationOffset + (variant - 1) * stopsPerAlternative)
                .take(stopsPerAlternative)
            if (selected.isEmpty()) continue

            val anchors = buildList {
                fixedStops.forEach { stop -> stop.point?.let { add(stop.id to it) } }
                selected.forEach { add(it.id to it.point) }
            }.distinctBy { it.first }
                .sortedBy { routeProgressIndex(primary.points, it.second) }
                .map { it.second }

            val routed = runCatching {
                NativeValhallaRouteClient.routeThrough(
                    nodes = listOf(origin) + anchors + destination,
                    preferences = preferences,
                    scenic = true,
                )
            }.getOrNull() ?: continue

            val driveExtra = max(0.0, (routed.durationSeconds - baselineSeconds) / 60.0)
            val autoDwell = selected.sumOf { it.suggestedDwellMinutes }
            val totalExtra = driveExtra + fixedDwell + autoDwell
            val withinMinutes = totalExtra <= preferences.maxExtraMinutes + 1.0
            val withinPercent = baselineSeconds <= 0 || routed.durationSeconds <= baselineSeconds * (1 + preferences.maxExtraPercent / 100.0) + 1
            if (!withinMinutes || !withinPercent) continue

            val routeSpecificPois = withTimeoutOrNull(8_000) {
                runCatching {
                    RapidRoutePoiDiscovery.discover(
                        route = routed.points,
                        enabledKinds = plan.enabledSceneKinds,
                        maxResults = 140,
                    )
                }.getOrElse { emptyList() }
            }.orEmpty()
            val selectedIds = selected.mapTo(mutableSetOf()) { it.id }
            val scenePool = PrecisionRoutePoiDiscovery.mergeForDisplay(
                first = routeSpecificPois + discoveryPool,
                second = selected,
                maxResults = 260,
            )
            val scenePoints = scenePool.map { point ->
                point.copy(
                    includedInRoute = point.id in selectedIds || point.id in manualIds,
                    rationale = when {
                        point.id in selectedIds -> "Alternative corridor highlight · deliberately different from earlier route generations"
                        point in routeSpecificPois -> listOfNotNull("POI on this alternative's own corridor", point.rationale).joinToString(" · ")
                        else -> point.rationale
                    },
                )
            }
            val provisional = RouteCandidateUi(
                id = "native-alt-g$generation-${variant + 1}",
                character = plan.routeCharacter.name,
                distanceMeters = routed.distanceMeters,
                durationSeconds = routed.durationSeconds,
                scenicScore = (primary.scenicScore * 0.90 + selected.map { it.relevance }.averageOrZero() * 10.0).coerceIn(0.0, 100.0),
                extraMinutes = driveExtra,
                points = routed.points,
                provider = "Valhalla / OpenStreetMap · generated alternative",
                scenePoints = scenePoints,
                strongestSignals = (primary.strongestSignals + listOf("alternativeCorridor", "differentWaypoints", "routeSpecificPois")).distinct().take(8),
                variantLabel = "Alternative ${variant + 1} · new corridor",
                experienceScore = (primary.experienceScore * 0.88 + selected.map { NativeAutoStopPolicy.utility(it, preferences) }.averageOrZero() * 0.12).coerceIn(0.0, 100.0),
                autoStopIds = selected.map { it.id },
                driveExtraMinutes = driveExtra,
                dwellMinutes = fixedDwell + autoDwell,
                totalExtraMinutes = totalExtra,
                corridorRadiusKm = max(primary.corridorRadiusKm, NativeAutoStopPolicy.distanceLimitMeters(preferences.maxExtraMinutes) / 1000.0),
                dataConfidence = if (routeSpecificPois.isEmpty()) 0.86 else 0.93,
                budgetUsedMinutes = if (plan.mode == PlanningMode.DAY_TRIP) totalExtra else null,
                budgetMinutes = if (plan.mode == PlanningMode.DAY_TRIP) preferences.maxExtraMinutes else null,
            )
            val diversity = RouteDiversityPolicy.diversity(primary, provisional)
            if (diversity < 0.08 && generated.isNotEmpty()) continue
            val accepted = provisional.copy(
                experienceScore = (provisional.experienceScore + diversity * 14.0).coerceAtMost(100.0)
            )
            generated += accepted
            used += selectedIds
            if (accepted.scenePoints.isNotEmpty()) ScenicPoiSharedState.publish(accepted.points, accepted.scenePoints)
        }

        val existingFallbacks = base.candidates.drop(1)
        val pool = listOf(primary) + generated + existingFallbacks
        val dayTripAdjusted = if (plan.mode == PlanningMode.DAY_TRIP) {
            pool.map { candidate ->
                val usedMinutes = candidate.totalExtraMinutes
                val utilization = RoundTripPolicy.utilizationScore(usedMinutes, preferences.maxExtraMinutes)
                candidate.copy(
                    budgetUsedMinutes = usedMinutes,
                    budgetMinutes = preferences.maxExtraMinutes,
                    experienceScore = (candidate.experienceScore * 0.72 + utilization * 28.0).coerceIn(0.0, 100.0),
                )
            }.sortedByDescending { it.experienceScore }
        } else pool
        base.copy(
            candidates = RouteDiversityPolicy.order(dayTripAdjusted, requested),
            note = buildString {
                base.note?.let { append(it); append(" · ") }
                append("Alternative generation ${generation + 1} explores unused POIs and another corridor")
                if (requested > 2) append(" · + Route can expand to $requested variants")
            },
        )
    }

    private fun routeProgressIndex(route: List<GeoPoint>, point: GeoPoint): Int =
        route.indices.minByOrNull { RoundTripPolicy.haversineMeters(route[it], point) } ?: Int.MAX_VALUE

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
