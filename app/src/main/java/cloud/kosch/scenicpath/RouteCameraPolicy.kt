package cloud.kosch.scenicpath

/** Pure camera policy: alternative routes of the same journey must not reset the user's zoom. */
object RouteCameraPolicy {
    private const val SAME_JOURNEY_ENDPOINT_TOLERANCE_METERS = 1_500.0

    fun shouldFitRoute(
        previousEndpoints: Pair<GeoPoint, GeoPoint>?,
        currentRoute: List<GeoPoint>,
    ): Boolean {
        if (currentRoute.size < 2) return false
        val previous = previousEndpoints ?: return true
        val currentStart = currentRoute.first()
        val currentEnd = currentRoute.last()
        val sameStart = RoundTripPolicy.haversineMeters(previous.first, currentStart) <= SAME_JOURNEY_ENDPOINT_TOLERANCE_METERS
        val sameEnd = RoundTripPolicy.haversineMeters(previous.second, currentEnd) <= SAME_JOURNEY_ENDPOINT_TOLERANCE_METERS
        return !(sameStart && sameEnd)
    }

    fun endpoints(route: List<GeoPoint>): Pair<GeoPoint, GeoPoint>? =
        if (route.size >= 2) route.first() to route.last() else null
}
