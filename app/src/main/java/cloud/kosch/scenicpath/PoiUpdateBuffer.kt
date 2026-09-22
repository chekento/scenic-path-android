package cloud.kosch.scenicpath

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Bounded backpressure, not an unbounded queue or dropped partial route windows. */
internal class PoiUpdateBuffer(private val intervalMs: Long = 350) {
    private val batches = Channel<List<ScenePointUi>>(capacity = 6)

    suspend fun submit(points: List<ScenePointUi>) {
        // Bound each queued item, including unusually large provider responses.
        for (batch in points.chunked(256)) batches.send(batch)
    }

    fun close() { batches.close() }

    suspend fun consume(publish: suspend (List<ScenePointUi>) -> Unit) {
        for (first in batches) {
            val combined = first.toMutableList()
            // Read only the current bounded batch. Publishers cannot starve this consumer.
            repeat(5) { batches.tryReceive().getOrNull()?.let(combined::addAll) }
            currentCoroutineContext().ensureActive()
            publish(combined)
            delay(intervalMs)
        }
    }
}
