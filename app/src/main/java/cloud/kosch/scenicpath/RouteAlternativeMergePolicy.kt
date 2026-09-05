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
                append("Existing alternatives preserved; genuinely new route geometries appended")
            },
        )
    }

    /**
     * Provider candidate ids are not stable identities across planning generations. A backend can
     * legitimately return `round-2` again with a completely new loop. Geometry/time therefore
     * decide sameness; id equality alone must never block the + Route action.
     */
    private fun sameRoute(a: RouteCandidateUi, b: RouteCandidateUi): Boolean {
        if (a.points.size < 2 || b.points.size < 2) {
            return a.id == b.id &&
                kotlin.math.abs(a.durationSeconds - b.durationSeconds) < 45.0 &&
                kotlin.math.abs(a.distanceMeters - b.distanceMeters) < 350.0
        }
        val durationClose = kotlin.math.abs(a.durationSeconds - b.durationSeconds) < 45.0
        val distanceClose = kotlin.math.abs(a.distanceMeters - b.distanceMeters) < 350.0
        val overlap = RouteDiversityPolicy.geometryOverlap(a.points, b.points)
        return durationClose && distanceClose && overlap >= 0.92
    }
}
