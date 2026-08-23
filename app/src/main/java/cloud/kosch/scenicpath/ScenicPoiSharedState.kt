package cloud.kosch.scenicpath

import androidx.compose.runtime.mutableStateOf

/**
 * Durable POI memory for the currently open planning session.
 *
 * The map itself owns the lifecycle boundary: while a calculated route is being edited and
 * recalculated, route geometry may change arbitrarily and this pool is append-only (subject to
 * balanced deduplication/capacity). When ScenicMap receives an empty route because start or
 * destination was changed, the pool is explicitly cleared before the next journey is built.
 *
 * This deliberately avoids deriving journey identity from routed coordinates. Valhalla may snap
 * the same logical start/destination to different road edges, so coordinates are routing output,
 * not a reliable planning-session id.
 */
object ScenicPoiSharedState {
    private const val MAX_SHARED_POINTS = 520

    private val publishedPoints = mutableStateOf<List<ScenePointUi>>(emptyList())

    fun publish(route: List<GeoPoint>, points: List<ScenePointUi>) {
        if (route.size < 2 || points.isEmpty()) return
        val next = PrecisionRoutePoiDiscovery.mergeForDisplay(
            first = points,
            second = publishedPoints.value,
            maxResults = MAX_SHARED_POINTS,
        )
        if (next.isNotEmpty()) publishedPoints.value = next
    }

    fun pointsFor(route: List<GeoPoint>): List<ScenePointUi> {
        return if (route.size >= 2) publishedPoints.value else emptyList()
    }

    fun clear() {
        publishedPoints.value = emptyList()
    }
}
