package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Adds vehicle-specific overnight and charging support to every route candidate independently. */
object JourneySupportPlanner {
    suspend fun enrich(
        plan: RoutePlanUi,
        vehicle: VehicleProfile,
        enabledKinds: Set<StopKind> = allSelectableSceneKinds,
    ): RoutePlanUi = withContext(Dispatchers.IO) {
        if (plan.candidates.isEmpty()) return@withContext plan

        val candidates = coroutineScope {
            plan.candidates.map { candidate ->
                async(Dispatchers.IO) { enrichCandidate(candidate, vehicle, enabledKinds) }
            }.awaitAll()
        }
        plan.copy(candidates = candidates)
    }

    private suspend fun enrichCandidate(
        candidate: RouteCandidateUi,
        vehicle: VehicleProfile,
        enabledKinds: Set<StopKind>,
    ): RouteCandidateUi {
        val needsOvernight = JourneyStagePolicy.overnightBreaks(
            candidate.points,
            candidate.durationSeconds,
            vehicle,
            dwellMinutes = candidate.dwellMinutes,
        ).isNotEmpty()
        val needsCharging = JourneyStagePolicy.eBikeChargeAnchors(
            candidate.points,
            candidate.distanceMeters,
            vehicle,
        ).isNotEmpty()
        if (!needsOvernight && !needsCharging) return candidate

        val support = withTimeoutOrNull(18_000) {
            runCatching { JourneySupportDiscovery.discover(candidate, vehicle, enabledKinds) }.getOrElse { emptyList() }
        }.orEmpty()
        if (support.isEmpty()) return candidate

        val supportIds = support.mapTo(mutableSetOf()) { it.id }
        val merged = buildList {
            candidate.scenePoints.forEach { if (it.id !in supportIds) add(it) }
            addAll(support)
        }
        return candidate.copy(scenePoints = merged.distinctBy { it.id })
    }
}
