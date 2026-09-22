package cloud.kosch.scenicpath

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ProgressiveSearchTest {
    private fun place(id: String) = PlaceSuggestion(id, "Hamburg", "Germany", GeoPoint(53.55, 10.0))

    @Test fun fastSuggestionsAppearBeforeSlowDeviceCompletes() = runBlocking {
        val releaseSlow = CompletableDeferred<Unit>()
        val first = CompletableDeferred<List<PlaceSuggestion>>()
        val search = async {
            OriginalSearchStack.searchLanes("Hamburg", onPartial = { if (it.isNotEmpty()) first.complete(it) },
                standard = { releaseSlow.await(); listOf(place("device")) },
                photon = { listOf(place("photon")) }, exact = { emptyList() })
        }
        assertEquals("photon", withTimeout(2_000) { first.await() }.first().id)
        assertFalse(search.isCompleted)
        releaseSlow.complete(Unit)
        assertEquals("photon", search.await().first().id)
    }

    @Test fun failedProviderCannotEraseSuccessfulSuggestions() = runBlocking {
        val results = OriginalSearchStack.searchLanes("Hamburg", standard = { error("unavailable") },
            photon = { listOf(place("photon")) }, exact = { error("timeout") })
        assertEquals("photon", results.single().id)
    }

    @Test fun cancellingQueryCancelsAllOutstandingLanes() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var partials = 0
        val search = async {
            OriginalSearchStack.searchLanes("Hamburg", onPartial = { partials++ },
                standard = { started.complete(Unit); awaitCancellation() },
                photon = { awaitCancellation() }, exact = { awaitCancellation() })
        }
        started.await()
        search.cancelAndJoin()
        assertEquals(0, partials)
    }

    @Test fun invalidCoordinatesAndDuplicateIdsCannotCrashOrMisroutePicker() {
        val result = OriginalSearchStack.mergeSuggestions("Hamburg", null, emptyList(),
            listOf(place("same"), place("same").copy(title = "Other"), place("bad").copy(point = GeoPoint(Double.NaN, 0.0))), emptyList())
        assertEquals(listOf("same"), result.map { it.id })
    }
}
