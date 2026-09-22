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
    private val publishedPoints = mutableStateOf<List<ScenePointUi>>(emptyList())
    private val mergeLock = Mutex()
    private var generation = 0L

    @Synchronized fun epoch(): Long = generation

    suspend fun publish(route: List<GeoPoint>, points: List<ScenePointUi>, epoch: Long = epoch()) {
        if (route.size < 2 || points.isEmpty()) return
        withContext(Dispatchers.Default) {
            mergeLock.withLock {
                val previous = synchronized(this@ScenicPoiSharedState) {
                    if (epoch != generation) return@withLock
                    publishedPoints.value
                }
                val next = PrecisionRoutePoiDiscovery.mergeForDisplay(points, previous, MAX_SHARED_POINTS, route)
                currentCoroutineContext().ensureActive()
                synchronized(this@ScenicPoiSharedState) {
                    if (epoch == generation && next.isNotEmpty()) publishedPoints.value = next
                }
            }
        }
    }

    fun pointsFor(route: List<GeoPoint>): List<ScenePointUi> =
        if (route.size >= 2) publishedPoints.value else emptyList()

    @Synchronized fun clear() {
        generation++
        publishedPoints.value = emptyList()
    }
}
