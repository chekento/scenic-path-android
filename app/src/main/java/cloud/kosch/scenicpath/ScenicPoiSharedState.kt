package cloud.kosch.scenicpath

import androidx.compose.runtime.mutableStateOf
import kotlin.math.roundToInt

/**
 * Lightweight in-memory bridge between Smart Stops, route planning and the map.
 *
 * A route can change geometry when a waypoint is accepted even though its start and destination
 * stay identical. Using the complete polyline as the only cache key caused the already discovered
 * marker population to disappear during exactly that reroute. We therefore keep two identities:
 *
 * 1. an exact route signature for the current geometry;
 * 2. a stable endpoint signature for equivalent A -> B reroutes.
 *
 * POIs from the previous geometry remain available while the replacement corridor is being
 * enriched, but they are not leaked into an unrelated trip with different endpoints.
 */
object ScenicPoiSharedState {
    private val routeSignature = mutableStateOf<String?>(null)
    private val endpointSignature = mutableStateOf<String?>(null)
    private val publishedPoints = mutableStateOf<List<ScenePointUi>>(emptyList())

    fun publish(route: List<GeoPoint>, points: List<ScenePointUi>) {
        val exact = signature(route) ?: return
        if (points.isEmpty()) return

        routeSignature.value = exact
        endpointSignature.value = endpointSignature(route)
        publishedPoints.value = points
            .distinctBy { it.id }
            .take(240)
    }

    fun pointsFor(route: List<GeoPoint>): List<ScenePointUi> {
        val exact = signature(route) ?: return emptyList()
        val sameGeometry = routeSignature.value == exact
        val sameJourney = endpointSignature.value != null && endpointSignature.value == endpointSignature(route)
        return if (sameGeometry || sameJourney) publishedPoints.value else emptyList()
    }

    private fun signature(route: List<GeoPoint>): String? {
        if (route.size < 2) return null
        val middle = route[route.size / 2]
        val first = route.first()
        val last = route.last()
        return buildString {
            append(route.size)
            append(':').append(first.lat).append(',').append(first.lon)
            append(':').append(middle.lat).append(',').append(middle.lon)
            append(':').append(last.lat).append(',').append(last.lon)
        }
    }

    private fun endpointSignature(route: List<GeoPoint>): String? {
        if (route.size < 2) return null
        fun bucket(value: Double): Int = (value * 10_000.0).roundToInt()
        val first = route.first()
        val last = route.last()
        return "${bucket(first.lat)},${bucket(first.lon)}:${bucket(last.lat)},${bucket(last.lon)}"
    }
}
