package cloud.kosch.scenicpath

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenicControlsTest {
    @Test
    fun motorwayAndTollConstraintsReachNativeCosting() {
        val car = VehicleProfile.defaults(VehicleKind.CAR)
        val constrained = NativeRouteConstraintPolicy.tuning(
            car,
            ScenicPreferences(avoidMotorways = true, avoidTolls = true),
            scenic = true,
        )
        assertTrue(constrained.useHighways == 0.0)
        assertTrue(constrained.useTolls == 0.0)
    }

    @Test
    fun windingAndHillControlsMateriallyChangeCarRoutingTuning() {
        val car = VehicleProfile.defaults(VehicleKind.CAR)
        val calm = NativeRouteConstraintPolicy.tuning(
            car,
            ScenicPreferences(avoidMotorways = false, windingness = 10, hilliness = 10),
            scenic = true,
        )
        val adventurous = NativeRouteConstraintPolicy.tuning(
            car,
            ScenicPreferences(avoidMotorways = false, windingness = 90, hilliness = 90),
            scenic = true,
        )
        assertTrue((adventurous.useHighways ?: 1.0) < (calm.useHighways ?: 0.0))
        assertTrue((adventurous.useDistance ?: 0.0) > (calm.useDistance ?: 1.0))
        assertTrue((adventurous.useHills ?: 0.0) > (calm.useHills ?: 1.0))
    }

    @Test
    fun motorcycleWindingControlChangesSecondaryRoadAndTrailPreference() {
        val bike = VehicleProfile.defaults(VehicleKind.MOTORCYCLE)
        val low = NativeRouteConstraintPolicy.tuning(
            bike,
            ScenicPreferences(avoidMotorways = false, windingness = 5, hilliness = 20),
            scenic = true,
        )
        val high = NativeRouteConstraintPolicy.tuning(
            bike,
            ScenicPreferences(avoidMotorways = false, windingness = 95, hilliness = 80),
            scenic = true,
        )
        assertTrue((high.useHighways ?: 1.0) < (low.useHighways ?: 0.0))
        assertTrue((high.useTrails ?: 0.0) > (low.useTrails ?: 1.0))
        assertTrue((high.useHills ?: 0.0) > (low.useHills ?: 1.0))
    }

    @Test
    fun bicycleDoesNotPretendToSupportCarStyleWindingAndHillControls() {
        assertFalse(NativeRouteConstraintPolicy.supportsWindingAndHills(VehicleKind.BICYCLE))
        assertFalse(NativeRouteConstraintPolicy.supportsRoadAvoidance(VehicleKind.BICYCLE))
        assertTrue(NativeRouteConstraintPolicy.supportsWindingAndHills(VehicleKind.CAR))
    }

    @Test
    fun scenicDnaWeightsCanReverseWhichPoiWins() {
        fun poi(id: String, kind: StopKind) = ScenePointUi(
            id = id,
            name = id,
            kind = kind.name,
            subtype = null,
            point = GeoPoint(53.6, 10.0),
            relevance = 0.8,
            suggestionScore = 0.8,
            distanceFromRouteMeters = 500,
            suggestedDwellMinutes = 20,
        )
        val museum = poi("museum", StopKind.MUSEUM)
        val view = poi("view", StopKind.VIEWPOINT)

        val museumFirst = ScenicPreferences(
            weights = ScenicWeights(museums = 1f, viewpoints = 0f),
        )
        val viewFirst = ScenicPreferences(
            weights = ScenicWeights(museums = 0f, viewpoints = 1f),
        )

        assertTrue(NativeAutoStopPolicy.utility(museum, museumFirst) > NativeAutoStopPolicy.utility(view, museumFirst))
        assertTrue(NativeAutoStopPolicy.utility(view, viewFirst) > NativeAutoStopPolicy.utility(museum, viewFirst))
    }
}
