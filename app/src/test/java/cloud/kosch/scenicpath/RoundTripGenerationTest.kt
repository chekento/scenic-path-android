package cloud.kosch.scenicpath

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundTripGenerationTest {
    @Test
    fun plusRouteGenerationChangesLoopSeeds() {
        val origin = GeoPoint(53.675, 10.24)
        val vehicle = VehicleProfile.defaults(VehicleKind.BICYCLE).copy(eBikeEnabled = true)
        val first = RoundTripPolicy.waypointSets(
            origin = origin,
            vehicle = vehicle,
            budgetMinutes = 360,
            autoSuggestStops = true,
            count = 5,
            generation = 0,
        )
        val next = RoundTripPolicy.waypointSets(
            origin = origin,
            vehicle = vehicle,
            budgetMinutes = 360,
            autoSuggestStops = true,
            count = 5,
            generation = 1,
        )

        val firstSignatures = first.map { set -> set.joinToString("|") { "%.4f,%.4f".format(it.lat, it.lon) } }.toSet()
        val nextSignatures = next.map { set -> set.joinToString("|") { "%.4f,%.4f".format(it.lat, it.lon) } }.toSet()
        assertNotEquals(firstSignatures, nextSignatures)
        assertTrue(nextSignatures.any { it !in firstSignatures })
    }
}
