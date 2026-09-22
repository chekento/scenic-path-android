package cloud.kosch.scenicpath

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.io.Reader
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Blocking Android APIs must not hold up cancellation or the next search. */
internal object CancellableNetwork {
    private fun pool(size: Int, name: String) = ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32), { task -> Thread(task, name).apply { isDaemon = true } })
    private val workers = pool(4, "scenic-network")
    private val poiWorkers = pool(3, "scenic-poi-network")

    suspend fun <T> blocking(onCancel: () -> Unit = {}, background: Boolean = false, block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            val executor = if (background) poiWorkers else workers
            val future = try {
                executor.submit {
                    if (!continuation.isActive) return@submit
                    try {
                        val value = block()
                        if (continuation.isActive) continuation.resumeWith(Result.success(value))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                continuation.resumeWithException(IOException("Too many pending network requests", error))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                future.cancel(true)
                executor.remove(future as Runnable)
                runCatching(onCancel)
            }
        }

    suspend fun text(
        url: String, body: String? = null, timeoutMs: Int = 18_000,
        contentType: String = "application/json; charset=utf-8", background: Boolean = false,
        maxChars: Int = 16 * 1024 * 1024,
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 5_000
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} (+https://kosch.cloud)")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }
        return blocking(onCancel = { connection.disconnect() }, background = background) {
            try {
                if (body != null) connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { readLimited(it, if (status in 200..299) maxChars else 16_384) }.orEmpty()
                if (status !in 200..299) throw RoutingHttpException(status, text.take(500))
                text
            } finally {
                connection.disconnect()
            }
        }
    }
    internal fun readLimited(reader: Reader, maxChars: Int): String {
        val output = StringBuilder(minOf(maxChars, 16_384))
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (output.length + count > maxChars) throw IOException("Response exceeds the memory limit")
            output.append(buffer, 0, count)
        }
        return output.toString()
    }

}

internal class RoutingHttpException(val status: Int, val response: String) : IOException("HTTP $status: $response")

/** Public routing services allow at most one request per second per client. */
internal class RequestPacer {
    private val mutex = Mutex()
    private var lastStart = 0L
    suspend fun awaitTurn() = mutex.withLock {
        val remaining = 1_050 - (System.nanoTime() - lastStart) / 1_000_000
        if (remaining > 0) delay(remaining)
        lastStart = System.nanoTime()
    }
}

internal suspend fun <T> optionalRequest(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/** Separate workers prevent corridor enrichment from blocking search, routes or tapped POIs. */
internal object PoiNetwork {
    suspend fun text(url: String, body: String? = null, timeoutMs: Int = 8_500): String =
        CancellableNetwork.text(url, body, timeoutMs, "application/x-www-form-urlencoded; charset=utf-8",
            background = true, maxChars = 2 * 1024 * 1024)
}
