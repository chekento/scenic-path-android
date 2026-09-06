package cloud.kosch.scenicpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCoveragePolicyTest {

    @Test
    fun longRouteIsNeverCappedAtFirstSixDiscoveryWindows() {
        // Roughly 1,000 km north/south polyline. The old Photon implementation used
        // splitRoute(..., 90 km).take(6), so everything after ~540 km disappeared.
        val route = (0..90).map { index ->
            GeoPoint(lat = 47.0 + index * 0.1, lon = 10.0)
        }

        val windows = RouteCoveragePolicy.expectedFastWindowCount(route)
        assertTrue("Long journeys must search beyond the historical six-window cutoff", windows > 6)
        assertTrue("Fast discovery should remain bounded for provider friendliness", windows <= 12)
    }

    @Test
    fun distanceSamplingIncludesSparseDestinationQuarter() {
        val denseSouth = (0..80).map { index ->
            GeoPoint(lat = 47.0 + index * (5.0 / 80.0), lon = 10.0)
        }
        // Deliberately sparse northern geometry: index-based sampling can under-represent this.
        val route = denseSouth + listOf(
            GeoPoint(54.0, 10.0),
            GeoPoint(56.0, 10.0),
        )

        val sampled = RouteCoveragePolicy.sampleByDistance(route, 10)

        assertEquals(route.first(), sampled.first())
        assertEquals(route.last(), sampled.last())
        assertTrue(
            "The sparse destination quarter must still receive several distance samples",
            sampled.count { it.lat >= 53.5 } >= 3,
        )
    }
}
