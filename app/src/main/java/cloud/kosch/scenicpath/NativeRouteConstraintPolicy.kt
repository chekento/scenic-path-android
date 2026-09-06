package cloud.kosch.scenicpath

/**
 * Provider-neutral native tuning values. Keeping these pure makes Scenic constraints testable
 * without making network calls and prevents the two Valhalla clients from drifting apart.
 */
data class NativeRouteTuning(
    val useHighways: Double? = null,
    val useTolls: Double? = null,
    val useHills: Double? = null,
    val useDistance: Double? = null,
    val useTrails: Double? = null,
    val useRoads: Double? = null,
)

object NativeRouteConstraintPolicy {
    fun tuning(vehicle: VehicleProfile, preferences: ScenicPreferences, scenic: Boolean): NativeRouteTuning = when (vehicle.kind) {
        VehicleKind.CAR -> NativeRouteTuning(
            useHighways = if (preferences.avoidMotorways) 0.0 else if (scenic) lerp(0.55, 0.06, preferences.windingness) else 0.90,
            useTolls = if (preferences.avoidTolls) 0.0 else 0.50,
            useHills = if (scenic) lerp(0.10, 0.90, preferences.hilliness) else 0.35,
            useDistance = if (scenic) lerp(0.03, 0.22, preferences.windingness) else 0.0,
        )
        VehicleKind.MOTORCYCLE -> NativeRouteTuning(
            useHighways = if (preferences.avoidMotorways) 0.0 else if (scenic) lerp(0.62, 0.05, preferences.windingness) else 0.80,
            useTolls = if (preferences.avoidTolls) 0.0 else 0.50,
            useHills = if (scenic) lerp(0.10, 0.90, preferences.hilliness) else 0.35,
            useTrails = if (scenic) lerp(0.08, 0.55, preferences.windingness) else 0.0,
        )
        VehicleKind.CAMPER -> NativeRouteTuning(
            useHighways = if (preferences.avoidMotorways) 0.0 else if (scenic) 0.28 else 0.80,
            useTolls = if (preferences.avoidTolls) 0.0 else 0.50,
            useDistance = if (scenic) 0.12 else 0.0,
        )
        VehicleKind.TRUCK -> NativeRouteTuning(
            useHighways = if (preferences.avoidMotorways) 0.0 else if (scenic) 0.55 else 0.90,
            useTolls = if (preferences.avoidTolls) 0.0 else 0.50,
        )
        VehicleKind.COACH -> NativeRouteTuning(
            useHighways = if (preferences.avoidMotorways) 0.0 else if (scenic) 0.45 else 0.85,
            useTolls = if (preferences.avoidTolls) 0.0 else 0.50,
            useDistance = if (scenic) 0.08 else 0.0,
        )
        VehicleKind.BICYCLE -> NativeRouteTuning(
            // Bicycle mode has no motorway/toll controls. Bicycle type and surface permission are
            // the transport constraints; do not pretend car-style winding/hill controls are shared.
            useRoads = if (scenic) 0.05 else 0.30,
        )
    }

    fun supportsWindingAndHills(kind: VehicleKind): Boolean =
        kind == VehicleKind.CAR || kind == VehicleKind.MOTORCYCLE

    fun supportsRoadAvoidance(kind: VehicleKind): Boolean = kind != VehicleKind.BICYCLE

    private fun lerp(low: Double, high: Double, percent: Int): Double {
        val t = percent.coerceIn(0, 100) / 100.0
        return low + (high - low) * t
    }
}
