package cloud.kosch.scenicpath

import androidx.compose.runtime.mutableStateOf

/**
 * Lightweight in-memory bridge between Smart Stops and the map.
 *
 * The current native prototype still performs discovery in more than one surface. This
 * bridge guarantees that the exact locations already visible in Smart Stops become visible
 * on the map immediately, without waiting for a second provider request.
 */
object ScenicPoiSharedState {
    private val routeSignature = mutableStateOf<String?>(null)
    private val publishedPoints = mutableStateOf<List<ScenePointUi>>(emptyList())

    fun publish(route: List<GeoPoint>, points: List<ScenePointUi>) {
        val signature = signature(route) ?: return
        routeSignature.value = signature
        publishedPoints.value = points
            .distinctBy { it.id }
            .take(140)
    }

    fun pointsFor(route: List<GeoPoint>): List<ScenePointUi> {
        val signature = signature(route) ?: return emptyList()
        return if (routeSignature.value == signature) publishedPoints.value else emptyList()
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
}
