package cloud.kosch.scenicpath

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Pure planning policy shared by the native round-trip planner and JVM tests. */
object RoundTripPolicy {
    private const val SAME_ENDPOINT_METERS = 350.0

    fun shouldCreateRoundTrip(plan: TripPlan, origin: GeoPoint, destination: GeoPoint): Boolean =
        plan.mode == PlanningMode.DAY_TRIP && haversineMeters(origin, destination) <= SAME_ENDPOINT_METERS

    fun targetDriveMinutes(
        budgetMinutes: Int,
        autoSuggestStops: Boolean,
        fixedDwellMinutes: Int = 0,
    ): Double {
        val budget = budgetMinutes.coerceAtLeast(30).toDouble()
        val reserveRatio = if (autoSuggestStops) 0.24 else 0.07
        val reserve = (budget * reserveRatio + fixedDwellMinutes).coerceAtMost(budget * 0.42)
        return (budget - reserve).coerceAtLeast(budget * 0.55)
    }

    fun waypointSets(
        origin: GeoPoint,
        vehicle: VehicleProfile,
        budgetMinutes: Int,
        autoSuggestStops: Boolean,
        count: Int,
        fixedDwellMinutes: Int = 0,
    ): List<List<GeoPoint>> {
        val desired = count.coerceIn(2, 6)
        val speedKmh = when (vehicle.kind) {
            VehicleKind.BICYCLE -> 18.0
            VehicleKind.TRUCK -> 42.0
            VehicleKind.COACH -> 46.0
            VehicleKind.CAMPER -> 48.0
            VehicleKind.MOTORCYCLE -> 52.0
            VehicleKind.CAR -> 55.0
        }
        val driveMinutes = targetDriveMinutes(budgetMinutes, autoSuggestStops, fixedDwellMinutes)
        val targetKm = speedKmh * driveMinutes / 60.0
        // origin -> A -> B -> C -> origin is about 5.46 radii for an equilateral ring.
        val baseRadiusMeters = (targetKm * 1000.0 / 5.46).coerceIn(2_500.0, 70_000.0)
        val scales = listOf(0.86, 1.0, 1.12, 0.94, 1.06, 0.80)
        return (0 until desired).map { variant ->
            val orientation = (variant * 57.0 + if (variant % 2 == 0) 12.0 else 31.0) % 120.0
            val radius = baseRadiusMeters * scales[variant % scales.size]
            listOf(
                project(origin, orientation, radius),
                project(origin, orientation + 120.0, radius),
                project(origin, orientation + 240.0, radius),
            )
        }
    }

    fun budgetUtilization(outingMinutes: Double, budgetMinutes: Int): Double {
        if (budgetMinutes <= 0) return 0.0
        return (outingMinutes / budgetMinutes).coerceIn(0.0, 1.25)
    }

    /** Prefer using most of the day-trip budget without rewarding an overrun. */
    fun utilizationScore(outingMinutes: Double, budgetMinutes: Int): Double {
        val u = budgetUtilization(outingMinutes, budgetMinutes)
        if (u > 1.03) return 0.0
        val distanceFromIdeal = kotlin.math.abs(0.93 - u)
        return (1.0 - distanceFromIdeal / 0.93).coerceIn(0.0, 1.0)
    }

    private fun project(origin: GeoPoint, bearingDegrees: Double, distanceMeters: Double): GeoPoint {
        val earth = 6_371_000.0
        val angular = distanceMeters / earth
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(origin.lat)
        val lon1 = Math.toRadians(origin.lon)
        val lat2 = asin(
            sin(lat1) * kotlin.math.cos(angular) +
                cos(lat1) * kotlin.math.sin(angular) * cos(bearing)
        )
        val lon2 = lon1 + kotlin.math.atan2(
            sin(bearing) * kotlin.math.sin(angular) * cos(lat1),
            kotlin.math.cos(angular) - sin(lat1) * sin(lat2),
        )
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    internal fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * earth * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }
}
