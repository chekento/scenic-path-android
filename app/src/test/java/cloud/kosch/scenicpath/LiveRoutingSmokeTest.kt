package cloud.kosch.scenicpath

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real planner check. Public service failures are reported separately from regressions. */
class LiveRoutingSmokeTest {
    @Test fun hamburgToLisbonUsesTheCompleteVehiclePlanner() = runBlocking {
        assumeTrue(System.getenv("SCENIC_LIVE_ROUTING") == "1")
        val start = GeoPoint(53.5511, 9.9937)
        val end = GeoPoint(38.7223, -9.1393)
        val result = withTimeout(180_000) {
            VehicleAwareJourneyPlanner.plan(start, end,
                TripPlan(routeCharacter = RouteCharacter.DIRECT, autoSuggestStops = false), ScenicPreferences())
        }
        val route = result.candidates.first()
        assertTrue(route.distanceMeters > 2_000_000)
        assertTrue(route.durationSeconds > 0)
        assertTrue(LongDistanceRouting.distance(start, route.points.first()) < 1_000)
        assertTrue(LongDistanceRouting.distance(end, route.points.last()) < 1_000)
        assertTrue(route.points.size > 100)
        println("LIVE Hamburg–Lisbon: ${route.distanceMeters / 1000} km, ${route.points.size} road vertices, endpoint checks passed")
    }
}
