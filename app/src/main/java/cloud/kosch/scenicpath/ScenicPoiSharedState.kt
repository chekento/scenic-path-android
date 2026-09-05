package cloud.kosch.scenicpath

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Durable POI memory keyed by routed geometry.
 *
 * Alternatives are real, independent routes. Keeping one append-only POI pool for an entire
 * planning session made markers from Route 1 remain visible after switching to Route 2. This
 * store therefore keeps a bounded pool per route fingerprint. The optional all-routes mode is
 * explicit instead of accidental.
 */
object ScenicPoiSharedState {
    private const val MAX_SHARED_POINTS_PER_ROUTE = 520
    private const val MAX_ROUTE_POOLS = 8

    private var publishedByRoute: Map<String, List<ScenePointUi>> by mutableStateOf(emptyMap())

    var showAllRoutes: Boolean by mutableStateOf(false)
        private set

    fun setShowAllRoutes(enabled: Boolean) {
        showAllRoutes = enabled
    }

    fun publish(route: List<GeoPoint>, points: List<ScenePointUi>) {
        if (route.size < 2 || points.isEmpty()) return
        val key = routeKey(route)
        val current = publishedByRoute[key].orEmpty()
        val next = PrecisionRoutePoiDiscovery.mergeForDisplay(
            first = points,
            second = current,
            maxResults = MAX_SHARED_POINTS_PER_ROUTE,
        )
        if (next.isEmpty()) return

        val updated = LinkedHashMap(publishedByRoute)
        updated.remove(key)
        updated[key] = next
        while (updated.size > MAX_ROUTE_POOLS) {
            updated.remove(updated.keys.first())
        }
        publishedByRoute = updated
    }

    fun pointsFor(route: List<GeoPoint>): List<ScenePointUi> {
        if (route.size < 2) return emptyList()
        if (!showAllRoutes) return publishedByRoute[routeKey(route)].orEmpty()
        return PrecisionRoutePoiDiscovery.mergeForDisplay(
            first = publishedByRoute.values.flatten(),
            second = emptyList(),
            maxResults = MAX_SHARED_POINTS_PER_ROUTE,
        )
    }

    fun clearRoute(route: List<GeoPoint>) {
        if (route.size < 2) return
        val key = routeKey(route)
        if (key !in publishedByRoute) return
        publishedByRoute = publishedByRoute - key
    }

    fun clear() {
        publishedByRoute = emptyMap()
        showAllRoutes = false
    }

    internal fun routeKey(route: List<GeoPoint>): String {
        if (route.size < 2) return "empty"
        val samples = RouteCoveragePolicy.sampleByDistance(route, 12)
        val signature = samples.joinToString("|") { point ->
            String.format(Locale.US, "%.4f,%.4f", point.lat, point.lon)
        }
        return "${route.size}:${signature.hashCode()}"
    }
}
