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
            avoidMotorways = true,
            windingness = 95,
            hilliness = 90,
        )
        val direct = source.forCharacter(RouteCharacter.DIRECT)
        assertEquals(10, direct.maxExtraMinutes)
        assertEquals(10, direct.maxExtraPercent)
        assertFalse(direct.avoidMotorways)
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
        val compact = ScenicPreferences(maxExtraMinutes = 5, maxExtraPercent = 5, avoidMotorways = true)
            .forCharacter(RouteCharacter.BALANCED)
        val generous = ScenicPreferences(maxExtraMinutes = 120, maxExtraPercent = 65, avoidMotorways = true)
            .forCharacter(RouteCharacter.BALANCED)
        assertEquals(30, compact.maxExtraMinutes)
        assertEquals(25, compact.maxExtraPercent)
        assertFalse(compact.avoidMotorways)
        assertEquals(120, generous.maxExtraMinutes)
        assertEquals(65, generous.maxExtraPercent)
        assertFalse(generous.avoidMotorways)
        assertEquals(50, generous.windingness)
        assertEquals(40, generous.hilliness)
    }

    @Test
    fun scenicAttractionsAreExplicitlySelectable() {
        assertTrue(StopKind.SCENIC in allSelectableSceneKinds)
        assertFalse(StopKind.CUSTOM in allSelectableSceneKinds)
    }

    @Test
    fun committedSceneSelectionCanIntentionallyBeEmpty() {
        ScenicSceneSelectionState.activate(emptySet())
        try {
            assertTrue(ScenicSceneSelectionState.activeKinds.isEmpty())
            assertTrue(prototypeSelectableSceneKinds.isEmpty())
        } finally {
            ScenicSceneSelectionState.reset()
        }
        assertEquals(allSelectableSceneKinds, ScenicSceneSelectionState.activeKinds)
    }

    @Test
    fun dayTripWithSameStartAndDestinationCreatesRoundTripIntent() {
        val start = GeoPoint(53.675, 10.24)
        assertTrue(
            RoundTripPolicy.shouldCreateRoundTrip(
                TripPlan(mode = PlanningMode.DAY_TRIP),
                start,
                start,
            )
        )
        assertFalse(
            RoundTripPolicy.shouldCreateRoundTrip(
                TripPlan(mode = PlanningMode.QUICK),
                start,
                start,
            )
        )
    }

    @Test
    fun roundTripBudgetScoringPrefersUsingMostOfSelectedTime() {
        assertTrue(RoundTripPolicy.utilizationScore(220.0, 240) > RoundTripPolicy.utilizationScore(120.0, 240))
        assertTrue(RoundTripPolicy.utilizationScore(220.0, 240) > RoundTripPolicy.utilizationScore(260.0, 240))
    }

    @Test
    fun roundTripSeedsCreateDifferentTourDirections() {
        val start = GeoPoint(53.675, 10.24)
        val sets = RoundTripPolicy.waypointSets(
            origin = start,
            vehicle = VehicleProfile.defaults(VehicleKind.CAR),
            budgetMinutes = 240,
            autoSuggestStops = true,
            count = 4,
        )
        assertEquals(4, sets.size)
        assertTrue(sets.all { it.size == 3 })
        assertEquals(4, sets.map { set -> "%.4f:%.4f".format(set.first().lat, set.first().lon) }.toSet().size)
    }

    @Test
    fun alternativeTwoPrefersDifferentCorridorOverNearCopy() {
        fun candidate(id: String, points: List<GeoPoint>, score: Double, stopIds: List<String>) = RouteCandidateUi(
            id = id,
            character = RouteCharacter.BEAUTIFUL.name,
            distanceMeters = 10_000.0,
            durationSeconds = 1_200.0,
            scenicScore = score,
            extraMinutes = 10.0,
            points = points,
            provider = "test",
            experienceScore = score,
            autoStopIds = stopIds,
        )
        val primary = candidate(
            "primary",
            listOf(GeoPoint(53.60, 10.00), GeoPoint(53.61, 10.05), GeoPoint(53.62, 10.10)),
            95.0,
            listOf("castle", "cafe"),
        )
        val nearCopy = candidate(
            "near-copy",
            listOf(GeoPoint(53.6005, 10.0005), GeoPoint(53.6105, 10.0505), GeoPoint(53.6205, 10.1005)),
            93.0,
            listOf("castle", "cafe"),
        )
        val different = candidate(
            "different",
            listOf(GeoPoint(53.60, 10.00), GeoPoint(53.67, 9.98), GeoPoint(53.70, 10.08), GeoPoint(53.62, 10.10)),
            82.0,
            listOf("viewpoint", "museum"),
        )

        val ordered = RouteDiversityPolicy.order(listOf(primary, nearCopy, different), 2)
        assertEquals("primary", ordered[0].id)
        assertEquals("different", ordered[1].id)
        assertTrue(RouteDiversityPolicy.diversity(primary, different) > RouteDiversityPolicy.diversity(primary, nearCopy))
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
        assertTrue(snapshot.remainingMeters > 80.0)
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
