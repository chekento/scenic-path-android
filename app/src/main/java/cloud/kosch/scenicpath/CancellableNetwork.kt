package cloud.kosch.scenicpath

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.resumeWithException

/** Blocking Android APIs must not hold up cancellation or the next search. */
internal object CancellableNetwork {
    private val workers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "scenic-network").apply { isDaemon = true }
    }

    suspend fun <T> blocking(onCancel: () -> Unit = {}, block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            val future = workers.submit {
                try {
                    val value = block()
                    if (continuation.isActive) continuation.resumeWith(Result.success(value))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation {
                future.cancel(true)
                onCancel()
            }
        }

    suspend fun text(url: String, body: String? = null, timeoutMs: Int = 18_000): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 5_000
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} (+https://kosch.cloud)")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return blocking(onCancel = { connection.disconnect() }) {
            try {
                if (body != null) connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw RoutingHttpException(status, text.take(500))
                text
            } finally {
                connection.disconnect()
            }
        }
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
