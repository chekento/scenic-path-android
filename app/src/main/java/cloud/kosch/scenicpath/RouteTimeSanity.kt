package cloud.kosch.scenicpath

import kotlin.math.max

/**
 * Defensive lower bound for provider travel times.
 *
 * Routing providers normally return reliable seconds, but a malformed/unsupported costing response
 * must never let Scenic Path treat 100+ km as a few minutes and then spend the entire day budget on
 * visits. These are deliberately generous *maximum plausible average* speeds, so normal provider
 * estimates remain untouched while impossible values are rejected.
 */
object RouteTimeSanity {
    fun maximumPlausibleAverageKmh(vehicle: VehicleProfile): Double = when (vehicle.kind) {
        VehicleKind.CAR -> 145.0
        VehicleKind.MOTORCYCLE -> 140.0
        VehicleKind.CAMPER -> 110.0
        VehicleKind.TRUCK -> 90.0
        VehicleKind.COACH -> 100.0
        VehicleKind.BICYCLE -> when (vehicle.bicycleType) {
            ScenicBicycleType.ROAD -> 34.0
            ScenicBicycleType.CROSS -> 30.0
            ScenicBicycleType.HYBRID -> if (vehicle.eBikeEnabled) 27.5 else 26.0
            ScenicBicycleType.CITY -> if (vehicle.eBikeEnabled) 26.0 else 23.0
            ScenicBicycleType.MOUNTAIN -> 22.0
        }
    }

    fun minimumPlausibleSeconds(distanceMeters: Double, vehicle: VehicleProfile): Double {
        if (!distanceMeters.isFinite() || distanceMeters <= 0.0) return 0.0
        val km = distanceMeters / 1000.0
        return km / maximumPlausibleAverageKmh(vehicle) * 3600.0
    }

    fun normalizeDurationSeconds(
        distanceMeters: Double,
        providerDurationSeconds: Double,
        vehicle: VehicleProfile,
    ): Double {
        val provider = providerDurationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        return max(provider, minimumPlausibleSeconds(distanceMeters, vehicle))
    }
}
