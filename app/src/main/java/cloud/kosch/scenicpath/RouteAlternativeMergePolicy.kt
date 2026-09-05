package cloud.kosch.scenicpath

/** Pure append policy used by the + Route action. */
object RouteAlternativeMergePolicy {
    fun merge(
        existing: RoutePlanUi,
        refreshed: RoutePlanUi,
        requestedCount: Int,
    ): RoutePlanUi {
        val limit = requestedCount.coerceIn(1, 5)
        val merged = buildList {
            existing.candidates.forEach { candidate ->
                if (none { sameRoute(it, candidate) }) add(candidate)
            }
            refreshed.candidates.forEach { candidate ->
                if (size < limit && none { sameRoute(it, candidate) }) add(candidate)
            }
        }.take(limit)
        return refreshed.copy(
            candidates = merged,
            note = buildString {
                refreshed.note?.let(::append)
                if (isNotEmpty()) append(" · ")
                append("Existing alternatives preserved; new routes appended")
            },
        )
    }

    private fun sameRoute(a: RouteCandidateUi, b: RouteCandidateUi): Boolean {
        if (a.id == b.id) return true
        if (a.points.size < 2 || b.points.size < 2) return false
        val durationClose = kotlin.math.abs(a.durationSeconds - b.durationSeconds) < 45.0
        val distanceClose = kotlin.math.abs(a.distanceMeters - b.distanceMeters) < 350.0
        return durationClose && distanceClose && RouteDiversityPolicy.geometryOverlap(a.points, b.points) >= 0.92
    }
}
