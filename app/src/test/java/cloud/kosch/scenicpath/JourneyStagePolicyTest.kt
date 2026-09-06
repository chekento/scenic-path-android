package cloud.kosch.scenicpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyStagePolicyTest {
    private val route = listOf(
        GeoPoint(47.87, 12.65),
        GeoPoint(49.50, 11.00),
        GeoPoint(51.00, 10.00),
        GeoPoint(53.67, 10.24),
    )

    @Test
    fun shortJourneyDoesNotOfferOvernightStop() {
        val car = VehicleProfile.defaults(VehicleKind.CAR).copy(dailyTravelHours = 7.0)
        assertTrue(JourneyStagePolicy.overnightBreaks(route, 6.5 * 3600.0, car).isEmpty())
    }

    @Test
    fun visitTimeCanTurnSameDriveIntoMultiDayItinerary() {
        val car = VehicleProfile.defaults(VehicleKind.CAR).copy(dailyTravelHours = 7.0)
        assertTrue(JourneyStagePolicy.overnightBreaks(route, 6.0 * 3600.0, car).isEmpty())
        val withMuseum = JourneyStagePolicy.overnightBreaks(
            route = route,
            durationSeconds = 6.0 * 3600.0,
            vehicle = car,
            dwellMinutes = 120,
        )
        assertEquals(1, withMuseum.size)
        assertTrue(withMuseum.single().routeFraction < 0.90)
    }

    @Test
    fun twentyHourJourneyCreatesTwoSevenHourDayEnds() {
        val car = VehicleProfile.defaults(VehicleKind.CAR).copy(
            dailyTravelHours = 7.0,
            driverCount = 1,
        )
        val breaks = JourneyStagePolicy.overnightBreaks(route, 20.0 * 3600.0, car)
        assertEquals(2, breaks.size)
        assertEquals(420, breaks[0].elapsedTravelMinutes)
        assertEquals(840, breaks[1].elapsedTravelMinutes)
        assertTrue(breaks[0].routeFraction < breaks[1].routeFraction)
        assertTrue(breaks.last().routeFraction < 1.0)
    }

    @Test
    fun alternatingDriversExtendButDoNotRemoveHealthyDayBoundary() {
        val car = VehicleProfile.defaults(VehicleKind.CAR).copy(
            dailyTravelHours = 7.0,
            driverCount = 2,
        )
        assertEquals(14 * 60, JourneyStagePolicy.effectiveDailyMinutes(car))
        val breaks = JourneyStagePolicy.overnightBreaks(route, 20.0 * 3600.0, car)
        assertEquals(1, breaks.size)
        assertEquals(14 * 60, breaks.single().elapsedTravelMinutes)
    }

    @Test
    fun bicycleAlwaysUsesOneRiderForDailyWindow() {
        val bike = VehicleProfile.defaults(VehicleKind.BICYCLE).copy(
            dailyTravelHours = 5.0,
            driverCount = 3,
        )
        assertEquals(5 * 60, JourneyStagePolicy.effectiveDailyMinutes(bike))
    }

    @Test
    fun eBikeRangeUsesConfiguredReserveAndCreatesChargeAnchors() {
        val bike = VehicleProfile.defaults(VehicleKind.BICYCLE).copy(
            eBikeEnabled = true,
            eBikeRangeKm = 80.0,
            eBikeReservePercent = 15,
        )
        assertEquals(68.0, JourneyStagePolicy.usableEBikeRangeKm(bike), 0.01)
        assertTrue(JourneyStagePolicy.eBikeChargeAnchors(route, 60_000.0, bike).isEmpty())
        val anchors = JourneyStagePolicy.eBikeChargeAnchors(route, 210_000.0, bike)
        assertEquals(3, anchors.size)
    }

    @Test
    fun differentRouteGeometriesProduceDifferentPoiKeys() {
        val north = route
        val east = alternativeRoute()
        assertTrue(ScenicPoiSharedState.routeKey(north) != ScenicPoiSharedState.routeKey(east))
    }

    @Test
    fun poiMemoryIsSeparatedByRouteUnlessAllRoutesIsExplicitlyEnabled() {
        val north = route
        val east = alternativeRoute()
        val a = ScenePointUi(
            id = "north-view",
            name = "North view",
            kind = StopKind.VIEWPOINT.name,
            subtype = "viewpoint",
            point = north[1],
            relevance = 1.0,
        )
        val b = ScenePointUi(
            id = "east-view",
            name = "East view",
            kind = StopKind.VIEWPOINT.name,
            subtype = "viewpoint",
            point = east[1],
            relevance = 1.0,
        )

        ScenicSceneSelectionState.reset()
        ScenicPoiSharedState.clear()
        try {
            ScenicPoiSharedState.publish(north, listOf(a))
            ScenicPoiSharedState.publish(east, listOf(b))
            assertEquals(listOf("north-view"), ScenicPoiSharedState.pointsFor(north).map { it.id })
            assertEquals(listOf("east-view"), ScenicPoiSharedState.pointsFor(east).map { it.id })

            ScenicPoiSharedState.updateShowAllRoutes(true)
            assertEquals(setOf("north-view", "east-view"), ScenicPoiSharedState.pointsFor(north).map { it.id }.toSet())
        } finally {
            ScenicPoiSharedState.clear()
        }
    }

    private fun alternativeRoute() = listOf(
        GeoPoint(47.87, 12.65),
        GeoPoint(49.50, 13.50),
        GeoPoint(51.00, 12.80),
        GeoPoint(53.67, 10.24),
    )
}
