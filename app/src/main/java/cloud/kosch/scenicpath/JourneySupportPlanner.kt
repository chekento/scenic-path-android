package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Adds vehicle-specific overnight and charging support to every route candidate independently. */
object JourneySupportPlanner {
    suspend fun enrich(plan: RoutePlanUi, vehicle: VehicleProfile): RoutePlanUi = withContext(Dispatchers.IO) {
        if (plan.candidates.isEmpty()) return@withContext plan

        val candidates = coroutineScope {
            plan.candidates.map { candidate ->
                async(Dispatchers.IO) { enrichCandidate(candidate, vehicle) }
            }.awaitAll()
        }
        plan.copy(candidates = candidates)
    }

    private suspend fun enrichCandidate(
        candidate: RouteCandidateUi,
        vehicle: VehicleProfile,
    ): RouteCandidateUi {
        val needsOvernight = JourneyStagePolicy.overnightBreaks(
            candidate.points,
            candidate.durationSeconds,
            vehicle,
        ).isNotEmpty()
        val needsCharging = JourneyStagePolicy.eBikeChargeAnchors(
            candidate.points,
            candidate.distanceMeters,
            vehicle,
        ).isNotEmpty()
        if (!needsOvernight && !needsCharging) return candidate

        val support = withTimeoutOrNull(14_000) {
            runCatching { JourneySupportDiscovery.discover(candidate, vehicle) }.getOrElse { emptyList() }
        }.orEmpty()
        if (support.isEmpty()) return candidate

        val supportIds = support.mapTo(mutableSetOf()) { it.id }
        val merged = buildList {
            candidate.scenePoints.forEach { if (it.id !in supportIds) add(it) }
            addAll(support)
        }
        return candidate.copy(scenePoints = merged)
    }
}
