package cloud.kosch.scenicpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalSearchStackTest {
    private fun place(id: String, title: String, lat: Double, lon: Double, subtitle: String = "") =
        PlaceSuggestion(id = id, title = title, subtitle = subtitle, point = GeoPoint(lat, lon))

    @Test
    fun exactAddressLaneWinsExplicitSearch() {
        val exact = place("exact", "Hamburger Straße 12", 53.67, 10.24, "22926 Ahrensburg")
        val photon = place("photon", "Hamburger Straße", 53.671, 10.241)
        val device = place("device", "Ahrensburg", 53.675, 10.238)

        val merged = OriginalSearchStack.mergeSuggestions(
            query = "Hamburger Straße 12",
            bias = GeoPoint(53.67, 10.24),
            exact = listOf(exact),
            photon = listOf(photon),
            standard = listOf(device),
        )

        assertEquals("exact", merged.first().id)
    }

    @Test
    fun duplicateProviderResultsAreCollapsed() {
        val samePhoton = place("photon", "Scenic Lake", 53.700001, 10.200001)
        val sameDevice = place("device", "Scenic Lake", 53.700001, 10.200001)

        val merged = OriginalSearchStack.mergeSuggestions(
            query = "Scenic Lake",
            bias = null,
            exact = emptyList(),
            photon = listOf(samePhoton),
            standard = listOf(sameDevice),
        )

        assertEquals(1, merged.size)
        assertEquals("photon", merged.single().id)
    }

    @Test
    fun maxResultLimitIsAlwaysRespected() {
        val photon = (0 until 30).map { index ->
            place("p$index", "Place $index", 53.0 + index / 1000.0, 10.0 + index / 1000.0)
        }

        val merged = OriginalSearchStack.mergeSuggestions(
            query = "Place",
            bias = null,
            exact = emptyList(),
            photon = photon,
            standard = emptyList(),
            maxResults = 16,
        )

        assertEquals(16, merged.size)
        assertTrue(merged.all { it.id.startsWith("p") })
    }
}
