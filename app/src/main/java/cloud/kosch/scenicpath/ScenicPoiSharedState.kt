package cloud.kosch.scenicpath

import androidx.compose.runtime.mutableStateOf
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Durable in-memory POI pool shared by Smart Stops, route planning and the map.
 *
 * The same journey can be recalculated many times while selected POIs become mandatory
 * waypoints. The routed polyline is not a stable journey identifier: Valhalla is allowed to snap
 * start and destination to slightly different road edges after every waypoint rebuild. Older
 * builds used ~11 m endpoint buckets, so a harmless road snap could make Scenic Path think a new
 * journey had started and erase every previously discovered marker.
 *
 * v0.5.14 treats two routes as the same planning journey when their routed start and destination
 * remain geographically close. Within that journey, POIs are append-only (subject to balanced
 * deduplication/capacity): a reroute through Hannover, for example, keeps the old corridor POIs
 * and adds discoveries from the newly expanded corridor. A genuinely different start or
 * destination resets the pool.
 */
object ScenicPoiSharedState {
    private const val MAX_SHARED_POINTS = 520
    private const val SAME_ENDPOINT_RADIUS_METERS = 2_500.0

    private data class JourneyEndpoints(
        val start: GeoPoint,
        val destination: GeoPoint,
    )

    private val routeSignature = mutableStateOf<String?>(null)
    private val journeyEndpoints = mutableStateOf<JourneyEndpoints?>(null)
    private val publishedPoints = mutableStateOf<List<ScenePointUi>>(emptyList())

    fun publish(route: List<GeoPoint>, points: List<ScenePointUi>) {
        val exact = signature(route) ?: return
        val endpoints = endpoints(route) ?: return
        if (points.isEmpty()) return

        val sameJourney = journeyEndpoints.value?.let { previous ->
            samePlanningJourney(previous, endpoints)
        } == true

        val next = PrecisionRoutePoiDiscovery.mergeForDisplay(
            first = points,
            second = if (sameJourney) publishedPoints.value else emptyList(),
            maxResults = MAX_SHARED_POINTS,
        )
        if (next.isEmpty()) return

        routeSignature.value = exact
        journeyEndpoints.value = endpoints
        publishedPoints.value = next
    }

    fun pointsFor(route: List<GeoPoint>): List<ScenePointUi> {
        val exact = signature(route) ?: return emptyList()
        val endpoints = endpoints(route) ?: return emptyList()
        val sameGeometry = routeSignature.value == exact
        val sameJourney = journeyEndpoints.value?.let { previous ->
            samePlanningJourney(previous, endpoints)
        } == true
        return if (sameGeometry || sameJourney) publishedPoints.value else emptyList()
    }

    /** Explicit escape hatch for a future New Journey action. */
    fun clear() {
        routeSignature.value = null
        journeyEndpoints.value = null
        publishedPoints.value = emptyList()
    }

    private fun endpoints(route: List<GeoPoint>): JourneyEndpoints? {
        if (route.size < 2) return null
        return JourneyEndpoints(route.first(), route.last())
    }

    private fun samePlanningJourney(a: JourneyEndpoints, b: JourneyEndpoints): Boolean {
        return haversineMeters(a.start, b.start) <= SAME_ENDPOINT_RADIUS_METERS &&
            haversineMeters(a.destination, b.destination) <= SAME_ENDPOINT_RADIUS_METERS
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
