package cloud.kosch.scenicpath

import kotlin.math.*

/** Distance-based route operations, independent of the routing provider's vertex density. */
internal class RoutePoiGeometry(val points: List<GeoPoint>) {
    private val cumulative = DoubleArray(points.size).also { distances ->
        for (i in 1 until points.size) distances[i] = distances[i - 1] + meters(points[i - 1], points[i])
    }
    val lengthMeters: Double = cumulative.lastOrNull() ?: 0.0

    data class Projection(val distanceMeters: Double, val alongMeters: Double)

    // A balanced segment tree rejects whole stretches instead of walking 70,000 vertices
    // for every POI. Longitude bounds are unwrapped so dateline crossings stay correct.
    private class Node(
        val first: Int, val last: Int,
        val south: Double, val north: Double, val west: Double, val east: Double,
        val left: Node? = null, val right: Node? = null,
    )
    private val root: Node? = if (points.size < 2) null else {
        val longitude = DoubleArray(points.size)
        longitude[0] = points.first().lon
        for (i in 1 until points.size) longitude[i] = longitude[i - 1] + longitudeDelta(points[i].lon - points[i - 1].lon)
        fun build(first: Int, last: Int): Node {
            if (last - first < 24) {
                var south = Double.POSITIVE_INFINITY
                var north = Double.NEGATIVE_INFINITY
                var west = Double.POSITIVE_INFINITY
                var east = Double.NEGATIVE_INFINITY
                for (i in first - 1..last) {
                    south = min(south, points[i].lat); north = max(north, points[i].lat)
                    west = min(west, longitude[i]); east = max(east, longitude[i])
                }
                return Node(first, last, south, north, west, east)
            }
            val middle = (first + last) / 2
            val left = build(first, middle)
            val right = build(middle + 1, last)
            return Node(first, last, min(left.south, right.south), max(left.north, right.north),
                min(left.west, right.west), max(left.east, right.east), left, right)
        }
        build(1, points.lastIndex)
    }
    private val projections = object : LinkedHashMap<GeoPoint, Projection>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GeoPoint, Projection>?) = size > 2_048
    }

    /** Exact nearest segment under the same local metric used by the corridor filter. */
    fun project(point: GeoPoint): Projection {
        synchronized(projections) { projections[point]?.let { return it } }
        if (points.isEmpty()) return Projection(Double.POSITIVE_INFINITY, 0.0)
        var bestSquared = Double.POSITIVE_INFINITY
        var along = 0.0
        val xScale = 111_195.0 * cos(Math.toRadians(point.lat)).coerceAtLeast(0.01)
        fun lowerBound(node: Node): Double {
            val longitude = point.lon + 360.0 * round(((node.west + node.east) / 2.0 - point.lon) / 360.0)
            val x = if (node.east - node.west >= 180.0) 0.0
                else max(max(node.west - longitude, longitude - node.east), 0.0) * xScale
            val y = max(max(node.south - point.lat, point.lat - node.north), 0.0) * 111_195.0
            return x * x + y * y
        }
        fun visit(node: Node) {
            if (lowerBound(node) > bestSquared) return
            val left = node.left
            val right = node.right
            if (left != null && right != null) {
                if (lowerBound(left) <= lowerBound(right)) { visit(left); visit(right) }
                else { visit(right); visit(left) }
                return
            }
            for (i in node.first..node.last) {
                val a = points[i - 1]
                val b = points[i]
                val ax = longitudeDelta(a.lon - point.lon) * xScale
                val ay = (a.lat - point.lat) * 111_195.0
                val bx = ax + longitudeDelta(b.lon - a.lon) * xScale
                val by = (b.lat - point.lat) * 111_195.0
                val dx = bx - ax
                val dy = by - ay
                val squared = dx * dx + dy * dy
                val t = if (squared == 0.0) 0.0 else (-(ax * dx + ay * dy) / squared).coerceIn(0.0, 1.0)
                val distanceSquared = (ax + t * dx).pow(2) + (ay + t * dy).pow(2)
                val candidateAlong = cumulative[i - 1] + t * (cumulative[i] - cumulative[i - 1])
                if (distanceSquared < bestSquared || (distanceSquared == bestSquared && candidateAlong < along)) {
                    bestSquared = distanceSquared
                    along = candidateAlong
                }
            }
        }
        root?.let(::visit)
        val result = if (bestSquared.isFinite()) Projection(sqrt(bestSquared), along)
            else Projection(meters(point, points.first()), 0.0)
        synchronized(projections) { projections[point] = result }
        return result
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
        // Identity lookup avoids hashing a complete route at every update; bounded across reroutes.
        private val recent = ArrayDeque<RoutePoiGeometry>()
        @Synchronized fun forRoute(points: List<GeoPoint>): RoutePoiGeometry {
            recent.firstOrNull { it.points === points }?.let { return it }
            return RoutePoiGeometry(points).also {
                if (recent.size == 2) recent.removeFirst()
                recent.addLast(it)
            }
        }

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
