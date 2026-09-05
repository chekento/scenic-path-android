package cloud.kosch.scenicpath

import kotlin.math.max

/** Pure geometry/stop diversity scoring for route alternatives. */
object RouteDiversityPolicy {
    private const val NEAR_ROUTE_METERS = 1_200.0

    fun diversity(a: RouteCandidateUi, b: RouteCandidateUi): Double {
        val geometry = 1.0 - geometryOverlap(a.points, b.points)
        val stop = 1.0 - stopOverlap(a.autoStopIds.toSet(), b.autoStopIds.toSet())
        return (geometry * 0.82 + stop * 0.18).coerceIn(0.0, 1.0)
    }

    fun order(
        candidates: List<RouteCandidateUi>,
        requestedCount: Int,
    ): List<RouteCandidateUi> {
        if (candidates.size <= 1) return candidates.take(requestedCount.coerceAtLeast(1))
        val limit = requestedCount.coerceIn(1, 5).coerceAtMost(candidates.size)
        val remaining = candidates.toMutableList()
        val first = remaining.removeAt(0)
        val selected = mutableListOf(first)

        while (selected.size < limit && remaining.isNotEmpty()) {
            val next = remaining.maxByOrNull { candidate ->
                val minimumDiversity = selected.minOf { diversity(it, candidate) }
                val quality = (candidate.experienceScore / 100.0).coerceIn(0.0, 1.0)
                // Alternative 2 is deliberately corridor-first. Later additions stay diverse but
                // give a little more weight back to experience quality.
                if (selected.size == 1) minimumDiversity * 0.78 + quality * 0.22
                else minimumDiversity * 0.62 + quality * 0.38
            } ?: break
            remaining.remove(next)
            selected += next
        }
        return selected.mapIndexed { index, route ->
            when (index) {
                0 -> route
                1 -> route.copy(variantLabel = route.variantLabel ?: "Alternative 2 · different corridor")
                else -> route.copy(variantLabel = route.variantLabel ?: "Alternative ${index + 1}")
            }
        }
    }

    fun geometryOverlap(a: List<GeoPoint>, b: List<GeoPoint>): Double {
        if (a.size < 2 || b.size < 2) return 0.0
        val sampleA = sample(a, 52)
        val sampleB = sample(b, 72)
        val near = sampleA.count { point ->
            sampleB.minOfOrNull { RoundTripPolicy.haversineMeters(point, it) }?.let { it <= NEAR_ROUTE_METERS } == true
        }
        return near.toDouble() / max(1, sampleA.size)
    }

    private fun stopOverlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val union = (a + b).size
        if (union == 0) return 0.0
        return a.intersect(b).size.toDouble() / union
    }

    private fun sample(points: List<GeoPoint>, count: Int): List<GeoPoint> {
        if (points.size <= count) return points
        val step = (points.size - 1).toDouble() / (count - 1)
        return (0 until count).map { index ->
            points[(index * step).toInt().coerceIn(0, points.lastIndex)]
        }
    }
}
