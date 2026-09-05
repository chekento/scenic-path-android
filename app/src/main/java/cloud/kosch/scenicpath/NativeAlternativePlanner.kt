package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Adds route variants to the native/debug planner by forcing unused Scenic POIs as real waypoints.
 * This is intentionally different from simply asking for the same provider route twice.
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
        val manualIds = plan.stops.mapTo(mutableSetOf()) { it.id }
        val used = primary.autoStopIds.toMutableSet()
        val available = primary.scenePoints
            .filterNot { it.id in manualIds || it.id in used || it.includedInRoute }
            .filter { NativeAutoStopPolicy.foodMatches(it, preferences) }
            .sortedByDescending { point ->
                point.distanceFromRouteMeters.coerceAtLeast(0) / 120.0 + NativeAutoStopPolicy.utility(point, preferences)
            }

        val generated = mutableListOf<RouteCandidateUi>()
        val baselineSeconds = base.baselineDurationSeconds ?: base.candidates.minOf { it.durationSeconds }
        val fixedStops = plan.stops.filter { it.mustVisit && it.point != null }
        val fixedDwell = fixedStops.sumOf { it.dwellMinutes }
        val stopsPerAlternative = when {
            preferences.maxExtraMinutes >= 210 -> 3
            preferences.maxExtraMinutes >= 100 -> 2
            else -> 1
        }

        for (variant in 1 until requested) {
            val selected = available
                .filterNot { it.id in used }
                .drop((variant - 1) * stopsPerAlternative)
                .take(stopsPerAlternative)
            if (selected.isEmpty()) break

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

            val selectedIds = selected.mapTo(mutableSetOf()) { it.id }
            val scenePoints = primary.scenePoints.map { point ->
                point.copy(
                    includedInRoute = point.id in selectedIds || point.id in manualIds,
                    rationale = if (point.id in selectedIds) "Alternative corridor highlight · deliberately different from the primary route" else point.rationale,
                )
            }
            val provisional = RouteCandidateUi(
                id = "native-alt-${variant + 1}",
                character = plan.routeCharacter.name,
                distanceMeters = routed.distanceMeters,
                durationSeconds = routed.durationSeconds,
                scenicScore = (primary.scenicScore * 0.92 + selected.map { it.relevance }.averageOrZero() * 8.0).coerceIn(0.0, 100.0),
                extraMinutes = driveExtra,
                points = routed.points,
                provider = "Valhalla / OpenStreetMap · generated alternative",
                scenePoints = scenePoints,
                strongestSignals = (primary.strongestSignals + listOf("alternativeCorridor", "differentWaypoints")).distinct().take(8),
                variantLabel = "Alternative ${variant + 1} · different places",
                experienceScore = (primary.experienceScore * 0.90 + selected.map { NativeAutoStopPolicy.utility(it, preferences) }.averageOrZero() * 0.10).coerceIn(0.0, 100.0),
                autoStopIds = selected.map { it.id },
                driveExtraMinutes = driveExtra,
                dwellMinutes = fixedDwell + autoDwell,
                totalExtraMinutes = totalExtra,
                corridorRadiusKm = primary.corridorRadiusKm,
                dataConfidence = 0.9,
                budgetUsedMinutes = if (plan.mode == PlanningMode.DAY_TRIP) totalExtra else null,
                budgetMinutes = if (plan.mode == PlanningMode.DAY_TRIP) preferences.maxExtraMinutes else null,
            )
            val diversity = RouteDiversityPolicy.diversity(primary, provisional)
            if (diversity < 0.10 && generated.isNotEmpty()) continue
            generated += provisional.copy(
                experienceScore = (provisional.experienceScore + diversity * 12.0).coerceAtMost(100.0)
            )
            used += selectedIds
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
                append("Alternative 2 prioritizes another corridor and unused Scenic waypoints")
                if (requested > 2) append(" · + Route can expand to $requested variants")
            },
        )
    }

    private fun routeProgressIndex(route: List<GeoPoint>, point: GeoPoint): Int =
        route.indices.minByOrNull { RoundTripPolicy.haversineMeters(route[it], point) } ?: Int.MAX_VALUE

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
