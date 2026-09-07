package cloud.kosch.scenicpath

import kotlinx.coroutines.CancellationException

/** Provider failures may trigger a fallback. Cancelling the caller must never trigger more work. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
