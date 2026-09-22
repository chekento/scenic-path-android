package cloud.kosch.scenicpath

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bounded POI memory for one planning session; clearing invalidates in-flight publishers. */
object ScenicPoiSharedState {
    private const val MAX_SHARED_POINTS = 520
    private const val MAX_DISCOVERED_IDS = 16_384
    private val publishedPoints = mutableStateOf<List<ScenePointUi>>(emptyList())
    private val mergeLock = Mutex()
    private var generation = 0L
    private var activeRouteKey: String? = null
    private var discoveredIds = linkedSetOf<String>()

    @Synchronized fun epoch(): Long = generation

    /** Starts a new route-owned POI session and removes every marker from the previous route. */
    @Synchronized fun begin(route: List<GeoPoint>): Long {
        generation++
        activeRouteKey = routeKey(route)
        discoveredIds = linkedSetOf()
        publishedPoints.value = emptyList()
        return generation
    }

    suspend fun publish(route: List<GeoPoint>, points: List<ScenePointUi>, epoch: Long = epoch()) {
        if (route.size < 2 || points.isEmpty()) return
        val key = routeKey(route)
        withContext(Dispatchers.Default) {
            mergeLock.withLock {
                val previous = synchronized(this@ScenicPoiSharedState) {
                    if (epoch != generation) return@withLock
                    if (activeRouteKey == null) activeRouteKey = key
                    if (activeRouteKey != key) return@withLock
                    points.forEach {
                        if (discoveredIds.size < MAX_DISCOVERED_IDS) discoveredIds += it.id
                    }
                    publishedPoints.value
                }
                val next = PrecisionRoutePoiDiscovery.mergeForDisplay(points, previous, MAX_SHARED_POINTS, route)
                currentCoroutineContext().ensureActive()
                synchronized(this@ScenicPoiSharedState) {
                    if (epoch == generation && activeRouteKey == key && next.isNotEmpty()) publishedPoints.value = next
                }
            }
        }
    }

    fun pointsFor(route: List<GeoPoint>): List<ScenePointUi> =
        if (route.size >= 2 && activeRouteKey == routeKey(route)) publishedPoints.value else emptyList()

    fun discoveredCount(route: List<GeoPoint>): Int =
        if (route.size >= 2 && activeRouteKey == routeKey(route)) discoveredIds.size else 0

    @Synchronized fun clear() {
        generation++
        activeRouteKey = null
        discoveredIds = linkedSetOf()
        publishedPoints.value = emptyList()
    }

    private fun routeKey(route: List<GeoPoint>): String {
        if (route.size < 2) return "empty"
        val indexes = intArrayOf(
            0,
            route.lastIndex / 4,
            route.lastIndex / 2,
            route.lastIndex * 3 / 4,
            route.lastIndex,
        ).distinct()
        return buildString {
            append(route.size)
            indexes.forEach { index ->
                val point = route[index]
                append('|').append("%.5f,%.5f".format(java.util.Locale.US, point.lat, point.lon))
            }
        }
    }
}
