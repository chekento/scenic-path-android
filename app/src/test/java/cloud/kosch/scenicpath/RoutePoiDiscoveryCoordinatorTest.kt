package cloud.kosch.scenicpath

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePoiDiscoveryCoordinatorTest {
    @Test
    fun broadMapPassEscalatesImmediatelyAfterEmptyPlannerPass() {
        val now = 10_000L
        assertTrue(
            RoutePoiDiscoveryCoordinator.shouldRetryEmpty(
                broad = true,
                retryAfterMs = now + 8_000,
                nowMs = now,
                fastAttempted = true,
                rapidAttempted = true,
                precisionAttempted = false,
            )
        )
    }

    @Test
    fun cooldownAppliesOnlyAfterAllRelevantStagesWereTried() {
        val now = 10_000L
        assertFalse(
            RoutePoiDiscoveryCoordinator.shouldRetryEmpty(
                broad = true,
                retryAfterMs = now + 8_000,
                nowMs = now,
                fastAttempted = true,
                rapidAttempted = true,
                precisionAttempted = true,
            )
        )
        assertTrue(
            RoutePoiDiscoveryCoordinator.shouldRetryEmpty(
                broad = true,
                retryAfterMs = now - 1,
                nowMs = now,
                fastAttempted = true,
                rapidAttempted = true,
                precisionAttempted = true,
            )
        )
    }

    @Test
    fun missingFastOrRapidStageAlwaysAllowsRetry() {
        val now = 10_000L
        assertTrue(
            RoutePoiDiscoveryCoordinator.shouldRetryEmpty(
                broad = false,
                retryAfterMs = now + 8_000,
                nowMs = now,
                fastAttempted = false,
                rapidAttempted = false,
                precisionAttempted = false,
            )
        )
    }
}
