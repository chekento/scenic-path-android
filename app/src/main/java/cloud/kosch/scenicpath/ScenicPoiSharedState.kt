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
 * v0.5.7 additionally treats a populated POI set as durable journey state. A later sparse provider
 * response (for example only the newly added waypoint) is merged into the existing population
 * instead of replacing 100+ useful markers with one or two points.
 */
object ScenicPoiSharedState {
    private const val MAX_SHARED_POINTS = 240

    private val routeSignature = mutableStateOf<String?>(null)
    private val endpointSignature = mutableStateOf<String?>(null)
    private val publishedPoints = mutableStateOf<List<ScenePointUi>>(emptyList())

    fun publish(route: List<GeoPoint>, points: List<ScenePointUi>) {
        val exact = signature(route) ?: return
        val endpoint = endpointSignature(route) ?: return
        if (points.isEmpty()) return

        val sameJourney = endpointSignature.value == endpoint
        val next = if (sameJourney && publishedPoints.value.isNotEmpty()) {
            PrecisionRoutePoiDiscovery.mergeForDisplay(
                first = points,
                second = publishedPoints.value,
                maxResults = MAX_SHARED_POINTS,
            )
        } else {
            PrecisionRoutePoiDiscovery.mergeForDisplay(
                first = points,
                second = emptyList(),
                maxResults = MAX_SHARED_POINTS,
            )
        }

        if (next.isEmpty()) return
        routeSignature.value = exact
        endpointSignature.value = endpoint
        publishedPoints.value = next
    }

    fun pointsFor(route: List<GeoPoint>): List<ScenePointUi> {
        val exact = signature(route) ?: return emptyList()
        val endpoint = endpointSignature(route) ?: return emptyList()
        val sameGeometry = routeSignature.value == exact
        val sameJourney = endpointSignature.value != null && endpointSignature.value == endpoint
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
