package cloud.kosch.scenicpath

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spatial coverage policy for long-route POI discovery.
 *
 * Provider polylines can have very uneven vertex density. Sampling by list index therefore
 * over-represents geometrically dense sections and can leave another part of a long journey
 * almost untested. These helpers work in travelled distance instead.
 */
object RouteCoveragePolicy {
    private const val MIN_FAST_WINDOW_METERS = 90_000.0
    private const val MAX_FAST_WINDOWS = 12

    fun totalDistanceMeters(route: List<GeoPoint>): Double =
        route.zipWithNext().sumOf { (a, b) -> haversineMeters(a, b) }

    /**
     * Choose a window size that covers the complete route without truncating the destination
     * end. Ordinary journeys keep ~90 km windows; very long trips enlarge each window so the
     * fast Photon pass remains bounded to roughly twelve corridor windows.
     */
    fun fastWindowMeters(route: List<GeoPoint>): Double {
        val total = totalDistanceMeters(route)
        if (total <= 0.0) return MIN_FAST_WINDOW_METERS
        return max(MIN_FAST_WINDOW_METERS, total / MAX_FAST_WINDOWS.toDouble())
    }

    fun expectedFastWindowCount(route: List<GeoPoint>): Int {
        val total = totalDistanceMeters(route)
        if (total <= 0.0) return 0
        return ceil(total / fastWindowMeters(route)).toInt().coerceAtLeast(1)
    }

    /** Returns positions at equal travelled-distance intervals, always including both ends. */
    fun sampleByDistance(route: List<GeoPoint>, maxSamples: Int): List<GeoPoint> {
        if (route.isEmpty() || maxSamples <= 0) return emptyList()
        if (route.size == 1 || maxSamples == 1) return listOf(route.first())

        val segmentLengths = route.zipWithNext().map { (a, b) -> haversineMeters(a, b) }
        val total = segmentLengths.sum()
        if (total <= 0.0) return listOf(route.first(), route.last()).distinct()

        val count = maxSamples.coerceAtLeast(2)
        val targets = (0 until count).map { index -> total * index / (count - 1).toDouble() }
        val sampled = ArrayList<GeoPoint>(count)
        var segmentIndex = 0
        var segmentStartDistance = 0.0

        for (target in targets) {
            while (
                segmentIndex < segmentLengths.lastIndex &&
                segmentStartDistance + segmentLengths[segmentIndex] < target
            ) {
                segmentStartDistance += segmentLengths[segmentIndex]
                segmentIndex++
            }

            val length = segmentLengths.getOrElse(segmentIndex) { 0.0 }
            val a = route[segmentIndex.coerceIn(0, route.lastIndex)]
            val b = route[(segmentIndex + 1).coerceIn(0, route.lastIndex)]
            val fraction = if (length <= 0.0) 0.0 else ((target - segmentStartDistance) / length).coerceIn(0.0, 1.0)
            sampled += GeoPoint(
                lat = a.lat + (b.lat - a.lat) * fraction,
                lon = a.lon + (b.lon - a.lon) * fraction,
            )
        }

        return sampled
    }

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earth * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
