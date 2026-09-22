package cloud.kosch.scenicpath

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the best route-wide discoveries into real waypoints for long-form planning.
 *
 * The map may show hundreds of candidates, but a route should only promote a bounded,
 * well-spaced set. This keeps the vehicle router and the map responsive while making a
 * multi-day or multi-week plan an actual itinerary instead of a list the user must curate.
 */
internal object ScenicAutoStopPlanner {
    const val AUTOMATIC_SUBTITLE = "Smart Stop · automatically included"

    fun select(
        candidates: List<ScenePointUi>,
        route: List<GeoPoint>,
        explorationMinutes: Int,
        configuredMaxStops: Int,
    ): List<PlannedStop> {
        if (route.size < 2 || candidates.isEmpty()) return emptyList()
        val maxStops = automaticStopLimit(explorationMinutes, configuredMaxStops)
        if (maxStops <= 0) return emptyList()

        val geometry = RoutePoiGeometry.forRoute(route)
        val visitBudget = max(15, (explorationMinutes * 0.78).toInt())
        val minimumSpacing = (geometry.lengthMeters / (maxStops + 1) * 0.42).coerceAtLeast(8_000.0)
        val ranked = candidates
            .filter { it.point.lat.isFinite() && it.point.lon.isFinite() && !it.includedInRoute }
            .distinctBy { it.id }
            .map { it to geometry.project(it.point).alongMeters }
            .sortedWith(
                compareByDescending<Pair<ScenePointUi, Double>> { it.first.suggestionScore + it.first.relevance * 38.0 }
                    .thenBy { it.first.distanceFromRouteMeters }
                    .thenBy { it.first.id },
            )

        val selected = mutableListOf<Pair<ScenePointUi, Double>>()
        val usedLanes = mutableSetOf<String>()
        var usedVisitMinutes = 0

        fun tryAdd(candidate: Pair<ScenePointUi, Double>, enforceSpacing: Boolean): Boolean {
            val point = candidate.first
            if (selected.size >= maxStops) return false
            if (usedVisitMinutes + point.suggestedDwellMinutes > visitBudget) return false
            if (enforceSpacing && selected.any { abs(it.second - candidate.second) < minimumSpacing }) return false
            selected += candidate
            usedVisitMinutes += point.suggestedDwellMinutes
            usedLanes += scenicCategoryLaneFor(point).id
            return true
        }

        // First pass favours route coverage and category diversity. A week-long journey should
        // not become six restaurants around the departure city just because they score highest.
        ranked.forEach { candidate ->
            if (selected.size >= maxStops) return@forEach
            val lane = scenicCategoryLaneFor(candidate.first).id
            if (lane !in usedLanes || selected.isEmpty()) tryAdd(candidate, enforceSpacing = true)
        }
        ranked.forEach { candidate ->
            if (selected.size >= maxStops) return@forEach
            tryAdd(candidate, enforceSpacing = true)
        }
        ranked.forEach { candidate ->
            if (selected.size >= maxStops) return@forEach
            tryAdd(candidate, enforceSpacing = false)
        }

        return selected
            .sortedBy { it.second }
            .map { (point, _) ->
                PlannedStop(
                    id = point.id,
                    name = point.name,
                    kind = StopKind.entries.firstOrNull { it.name == point.kind } ?: StopKind.SCENIC,
                    dwellMinutes = point.suggestedDwellMinutes,
                    locked = false,
                    mustVisit = true,
                    point = point.point,
                    subtitle = AUTOMATIC_SUBTITLE,
                    rating = point.rating,
                    ratingCount = point.ratingCount,
                    subtype = point.subtype,
                )
            }
    }

    fun isAutomatic(stop: PlannedStop): Boolean = stop.subtitle == AUTOMATIC_SUBTITLE

    fun suggestedStopCapacity(explorationMinutes: Int): Int = when {
        explorationMinutes >= 10_080 -> 24
        explorationMinutes >= 4_320 -> 18
        explorationMinutes >= 1_440 -> 12
        explorationMinutes >= 720 -> 8
        explorationMinutes >= 360 -> 6
        explorationMinutes >= 240 -> 5
        explorationMinutes >= 120 -> 4
        explorationMinutes >= 60 -> 3
        explorationMinutes >= 30 -> 2
        explorationMinutes >= 15 -> 1
        else -> 0
    }

    private fun automaticStopLimit(explorationMinutes: Int, configuredMaxStops: Int): Int {
        val budgetLimit = suggestedStopCapacity(explorationMinutes)
        // maxStops is a mode preference, but the current UI has no separate manual stop-count
        // control. A longer explicit exploration budget therefore earns a sensible itinerary
        // capacity instead of being silently capped at the old five-stop default.
        return min(max(configuredMaxStops, min(budgetLimit, 24)), budgetLimit)
    }
}
