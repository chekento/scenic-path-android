package cloud.kosch.scenicpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTimeSanityTest {
    @Test
    fun impossibleBicycleTimeIsRaisedToPlausibleMinimum() {
        val bike = VehicleProfile.defaults(VehicleKind.BICYCLE)
        val normalized = RouteTimeSanity.normalizeDurationSeconds(
            distanceMeters = 108_000.0,
            providerDurationSeconds = 12.0 * 60.0,
            vehicle = bike,
        )
        assertTrue(normalized > 3.5 * 3600.0)
    }

    @Test
    fun normalProviderTimeIsPreserved() {
        val car = VehicleProfile.defaults(VehicleKind.CAR)
        val provider = 55.0 * 60.0
        assertEquals(
            provider,
            RouteTimeSanity.normalizeDurationSeconds(80_000.0, provider, car),
            0.01,
        )
    }
}
