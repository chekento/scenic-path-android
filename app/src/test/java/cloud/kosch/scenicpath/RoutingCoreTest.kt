package cloud.kosch.scenicpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingCoreTest {

    @Test
    fun directCharacterUsesStrictDetourEnvelope() {
        val source = ScenicPreferences(
            maxExtraMinutes = 240,
            maxExtraPercent = 90,
            windingness = 95,
            hilliness = 90,
        )

        val direct = source.forCharacter(RouteCharacter.DIRECT)

        assertEquals(10, direct.maxExtraMinutes)
        assertEquals(10, direct.maxExtraPercent)
        assertEquals(20, direct.windingness)
        assertEquals(20, direct.hilliness)
    }

    @Test
    fun beautifulCharacterKeepsAUserBudgetThatIsAlreadyLarger() {
        val source = ScenicPreferences(
            maxExtraMinutes = 180,
            maxExtraPercent = 70,
            avoidMotorways = false,
            windingness = 25,
            hilliness = 20,
        )

        val beautiful = source.forCharacter(RouteCharacter.BEAUTIFUL)

        assertEquals(180, beautiful.maxExtraMinutes)
        assertEquals(70, beautiful.maxExtraPercent)
        assertTrue(beautiful.avoidMotorways)
        assertEquals(75, beautiful.windingness)
        assertEquals(60, beautiful.hilliness)
    }

    @Test
    fun balancedCharacterProvidesMinimumExplorationWithoutDiscardingLargerBudget() {
        val compact = ScenicPreferences(maxExtraMinutes = 5, maxExtraPercent = 5)
            .forCharacter(RouteCharacter.BALANCED)
        val generous = ScenicPreferences(maxExtraMinutes = 120, maxExtraPercent = 65)
            .forCharacter(RouteCharacter.BALANCED)

        assertEquals(30, compact.maxExtraMinutes)
        assertEquals(25, compact.maxExtraPercent)
        assertEquals(120, generous.maxExtraMinutes)
        assertEquals(65, generous.maxExtraPercent)
        assertEquals(50, generous.windingness)
        assertEquals(40, generous.hilliness)
    }

    @Test
    fun liveNavigationRecognizesArrivalAtRouteEnd() {
        val route = listOf(
            GeoPoint(53.0000, 10.0000),
            GeoPoint(53.0010, 10.0000),
            GeoPoint(53.0020, 10.0000),
        )

        val snapshot = LiveNavigationEngine.snapshot(
            route = route,
            location = route.last(),
            speedMetersPerSecond = 0f,
            gpsBearingDegrees = null,
            stops = emptyList(),
        )

        assertTrue(snapshot.arrived)
        assertEquals(NavigationTurn.ARRIVE, snapshot.nextManeuver?.turn)
        assertTrue(snapshot.remainingMeters < 80.0)
    }

    @Test
    fun liveNavigationFlagsLocationThatIsClearlyOffRoute() {
        val route = listOf(
            GeoPoint(53.0000, 10.0000),
            GeoPoint(53.0010, 10.0000),
            GeoPoint(53.0020, 10.0000),
        )

        val snapshot = LiveNavigationEngine.snapshot(
            route = route,
            location = GeoPoint(53.0030, 10.0050),
            speedMetersPerSecond = 12f,
            gpsBearingDegrees = 90f,
            stops = emptyList(),
        )

        assertTrue(snapshot.offRoute)
        assertTrue(snapshot.offRouteMeters > 90.0)
        assertFalse(snapshot.arrived)
    }

    @Test
    fun liveNavigationTracksNextPlannedStop() {
        val route = listOf(
            GeoPoint(53.0000, 10.0000),
            GeoPoint(53.0010, 10.0000),
            GeoPoint(53.0020, 10.0000),
            GeoPoint(53.0030, 10.0000),
        )
        val stop = PlannedStop(
            id = "view-1",
            name = "Viewpoint",
            kind = StopKind.SCENIC,
            point = route[2],
        )

        val snapshot = LiveNavigationEngine.snapshot(
            route = route,
            location = route.first(),
            speedMetersPerSecond = 10f,
            gpsBearingDegrees = null,
            stops = listOf(stop),
        )

        assertEquals(stop.id, snapshot.nextStop?.id)
        assertTrue((snapshot.nextStopDistanceMeters ?: 0.0) > 0.0)
        assertFalse(snapshot.arrived)
    }

    @Test
    fun heavyVehicleDefaultsCarryPhysicalRestrictions() {
        val truck = VehicleProfile.defaults(VehicleKind.TRUCK)
        val car = VehicleProfile.defaults(VehicleKind.CAR)

        assertTrue(truck.hasPhysicalRestrictions)
        assertFalse(car.hasPhysicalRestrictions)
        assertTrue(truck.heightMeters > car.heightMeters)
        assertTrue(truck.weightTons > car.weightTons)
    }
}
