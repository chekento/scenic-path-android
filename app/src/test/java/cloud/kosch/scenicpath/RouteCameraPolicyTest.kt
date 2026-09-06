package cloud.kosch.scenicpath

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCameraPolicyTest {
    private val routeOne = listOf(
        GeoPoint(53.67, 10.24),
        GeoPoint(51.20, 9.80),
        GeoPoint(48.78, 9.18),
    )
    private val sameJourneyAlternative = listOf(
        GeoPoint(53.671, 10.241),
        GeoPoint(51.70, 8.60),
        GeoPoint(48.779, 9.181),
    )
    private val differentDestination = listOf(
        GeoPoint(53.67, 10.24),
        GeoPoint(51.00, 10.00),
        GeoPoint(48.14, 11.58),
    )

    @Test
    fun firstRouteGetsOverviewFit() {
        assertTrue(RouteCameraPolicy.shouldFitRoute(null, routeOne))
    }

    @Test
    fun switchingAlternativeKeepsCurrentCamera() {
        val previous = RouteCameraPolicy.endpoints(routeOne)
        assertFalse(RouteCameraPolicy.shouldFitRoute(previous, sameJourneyAlternative))
    }

    @Test
    fun genuinelyDifferentJourneyGetsOverviewFit() {
        val previous = RouteCameraPolicy.endpoints(routeOne)
        assertTrue(RouteCameraPolicy.shouldFitRoute(previous, differentDestination))
    }
}
