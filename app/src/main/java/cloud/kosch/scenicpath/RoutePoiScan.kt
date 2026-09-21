package cloud.kosch.scenicpath

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** Shared limits apply across complementary passes, including Smart Stops. */
internal object RoutePoiScan {
    val overpass = Semaphore(3)
    val photon = Semaphore(3)

    /** Start, destination, middle, then progressively fill gaps; never truncate the tail. */
    fun order(size: Int): List<Int> {
        if (size <= 0) return emptyList()
        if (size == 1) return listOf(0)
        val result = mutableListOf(0, size - 1)
        val gaps = ArrayDeque<Pair<Int, Int>>()
        gaps.add(1 to size - 2)
        while (gaps.isNotEmpty()) {
            val (first, last) = gaps.removeFirst()
            if (first > last) continue
            val middle = (first + last) / 2
            result += middle
            gaps.add(first to middle - 1)
            gaps.add(middle + 1 to last)
        }
        return result
    }

    /** Bounded workers keep scanning after individual failures and publish each finished window. */
    suspend fun <T> collect(
        windows: List<T>,
        parallelism: Int = 3,
        onPartial: suspend (List<ScenePointUi>) -> Unit = {},
        query: suspend (T) -> List<ScenePointUi>,
    ): List<ScenePointUi> = coroutineScope {
        require(parallelism > 0)
        val next = AtomicInteger()
        val ordered = order(windows.size)
        val results = Array(windows.size) { emptyList<ScenePointUi>() }
        val publishLock = Mutex()
        List(minOf(parallelism, windows.size)) {
            async {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val position = next.getAndIncrement()
                    if (position >= ordered.size) break
                    val index = ordered[position]
                    val points = try {
                        query(windows[index])
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                    currentCoroutineContext().ensureActive()
                    results[index] = points
                    if (points.isNotEmpty()) publishLock.withLock { onPartial(points) }
                }
            }
        }.awaitAll()
        results.flatMap { it }
    }
}
