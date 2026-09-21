package cloud.kosch.scenicpath

import kotlin.math.*

/** Distance-based route operations, independent of the routing provider's vertex density. */
internal class RoutePoiGeometry(val points: List<GeoPoint>) {
    private val cumulative = DoubleArray(points.size).also { distances ->
        for (i in 1 until points.size) distances[i] = distances[i - 1] + meters(points[i - 1], points[i])
    }
    val lengthMeters: Double = cumulative.lastOrNull() ?: 0.0

    data class Projection(val distanceMeters: Double, val alongMeters: Double)

    /** Distance to line segments, not to a small set of isolated route vertices. */
    fun project(point: GeoPoint): Projection {
        if (points.isEmpty()) return Projection(Double.POSITIVE_INFINITY, 0.0)
        var bestSquared = Double.POSITIVE_INFINITY
        var along = 0.0
        val xScale = 111_195.0 * cos(Math.toRadians(point.lat)).coerceAtLeast(0.01)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val ax = longitudeDelta(a.lon - point.lon) * xScale
            val ay = (a.lat - point.lat) * 111_195.0
            val bx = ax + longitudeDelta(b.lon - a.lon) * xScale
            val by = (b.lat - point.lat) * 111_195.0
            // Cheap rejection makes projection over the complete geometry practical.
            val nearX = if (min(ax, bx) > 0) min(ax, bx) else max(ax, bx).coerceAtMost(0.0)
            val nearY = if (min(ay, by) > 0) min(ay, by) else max(ay, by).coerceAtMost(0.0)
            if (nearX * nearX + nearY * nearY > bestSquared) continue
            val dx = bx - ax
            val dy = by - ay
            val squared = dx * dx + dy * dy
            val t = if (squared == 0.0) 0.0 else (-(ax * dx + ay * dy) / squared).coerceIn(0.0, 1.0)
            val distanceSquared = (ax + t * dx).pow(2) + (ay + t * dy).pow(2)
            if (distanceSquared < bestSquared) {
                bestSquared = distanceSquared
                along = cumulative[i - 1] + t * (cumulative[i] - cumulative[i - 1])
            }
        }
        return if (bestSquared.isFinite()) Projection(sqrt(bestSquared), along)
        else Projection(meters(point, points.first()), 0.0)
    }

    /** Includes every vertex and both endpoints; even a single long edge is subdivided. */
    fun windows(maxMeters: Double): List<List<GeoPoint>> {
        require(maxMeters > 0 && maxMeters.isFinite())
        if (points.size < 2) return emptyList()
        if (lengthMeters == 0.0) return listOf(listOf(points.first(), points.last()))
        val result = mutableListOf<List<GeoPoint>>()
        var current = mutableListOf(points.first())
        var boundary = maxMeters
        for (i in 1 until points.size) {
            val edgeStart = cumulative[i - 1]
            val edgeLength = cumulative[i] - edgeStart
            while (boundary < cumulative[i] && edgeLength > 0.0) {
                val point = interpolate(points[i - 1], points[i], (boundary - edgeStart) / edgeLength)
                if (current.last() != point) current += point
                if (current.size >= 2) result += current.toList()
                current = mutableListOf(point)
                boundary += maxMeters
            }
            if (current.last() != points[i]) current += points[i]
        }
        if (current.size >= 2) result += current.toList()
        return result
    }

    fun samples(count: Int): List<GeoPoint> {
        require(count >= 2)
        if (points.size < 2 || lengthMeters == 0.0) return points
        var edge = 1
        return (0 until count).map { index ->
            val distance = lengthMeters * index / (count - 1)
            while (edge < points.lastIndex && cumulative[edge] < distance) edge++
            val length = cumulative[edge] - cumulative[edge - 1]
            if (index == 0) points.first() else if (index == count - 1) points.last()
            else interpolate(points[edge - 1], points[edge], if (length == 0.0) 0.0 else (distance - cumulative[edge - 1]) / length)
        }
    }

    companion object {
        fun meters(a: GeoPoint, b: GeoPoint): Double {
            val lat = Math.toRadians(b.lat - a.lat)
            val lon = Math.toRadians(longitudeDelta(b.lon - a.lon))
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
            return 12_742_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        private fun longitudeDelta(value: Double) = ((value + 540.0) % 360.0) - 180.0

        private fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double) = GeoPoint(
            a.lat + (b.lat - a.lat) * fraction,
            longitudeDelta(a.lon + longitudeDelta(b.lon - a.lon) * fraction),
        )
    }
}
