package cloud.kosch.scenicpath

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One overnight-relevant end point after a complete planned travel day. */
data class JourneyStageBreak(
    val day: Int,
    val point: GeoPoint,
    val elapsedTravelMinutes: Int,
    val routeFraction: Double,
)

/** Pure policy for multi-day travel and e-bike range planning. */
object JourneyStagePolicy {
    private const val MAX_SHARED_DRIVER_DAY_MINUTES = 16 * 60

    /**
     * The user configures a comfortable maximum per driver/rider. Multiple motor-vehicle drivers
     * can share the driving, but an elapsed travel day is still capped at 16 hours so the planner
     * never turns driver count into an unlimited no-sleep assumption.
     */
    fun effectiveDailyMinutes(vehicle: VehicleProfile): Int {
        val perDriver = (vehicle.dailyTravelHours * 60.0).toInt().coerceAtLeast(120)
        if (vehicle.kind == VehicleKind.BICYCLE) return perDriver
        return (perDriver * vehicle.effectiveDriverCount).coerceAtMost(MAX_SHARED_DRIVER_DAY_MINUTES)
    }

    /**
     * Returns only intermediate day ends. Visit/dwell time consumes the same real travel day as
     * driving. Without a complete per-stop timeline we distribute dwell across route progress;
     * this deliberately errs toward an earlier overnight rather than pretending visits cost no time.
     */
    fun overnightBreaks(
        route: List<GeoPoint>,
        durationSeconds: Double,
        vehicle: VehicleProfile,
        dwellMinutes: Int = 0,
    ): List<JourneyStageBreak> {
        if (!vehicle.overnightPlanningEnabled || route.size < 2 || durationSeconds <= 0.0) return emptyList()
        val driveMinutes = durationSeconds / 60.0
        val totalMinutes = driveMinutes + dwellMinutes.coerceAtLeast(0)
        val dailyMinutes = effectiveDailyMinutes(vehicle).toDouble()
        if (totalMinutes <= dailyMinutes + 10.0) return emptyList()

        val breakCount = floor((totalMinutes - 1.0) / dailyMinutes).toInt().coerceAtLeast(0)
        return (1..breakCount).map { day ->
            val elapsed = day * dailyMinutes
            val itineraryFraction = (elapsed / totalMinutes).coerceIn(0.0, 1.0)
            JourneyStageBreak(
                day = day,
                point = pointAtFraction(route, itineraryFraction),
                elapsedTravelMinutes = elapsed.toInt(),
                routeFraction = itineraryFraction,
            )
        }
    }

    fun usableEBikeRangeKm(vehicle: VehicleProfile): Double {
        if (vehicle.kind != VehicleKind.BICYCLE || !vehicle.eBikeEnabled) return Double.POSITIVE_INFINITY
        return vehicle.eBikeRangeKm * (1.0 - vehicle.eBikeReservePercent.coerceIn(0, 40) / 100.0)
    }

    /**
     * Battery anchors are intentionally conservative: a search is requested before the configured
     * usable range is exhausted. The destination itself is not emitted as a charging stop.
     */
    fun eBikeChargeAnchors(
        route: List<GeoPoint>,
        routeDistanceMeters: Double,
        vehicle: VehicleProfile,
    ): List<GeoPoint> {
        if (route.size < 2 || routeDistanceMeters <= 0.0) return emptyList()
        val usableMeters = usableEBikeRangeKm(vehicle) * 1000.0
        if (!usableMeters.isFinite() || usableMeters <= 0.0 || routeDistanceMeters <= usableMeters) return emptyList()

        val result = mutableListOf<GeoPoint>()
        var targetMeters = usableMeters
        while (targetMeters < routeDistanceMeters - 1_000.0 && result.size < 24) {
            result += pointAtFraction(route, (targetMeters / routeDistanceMeters).coerceIn(0.0, 1.0))
            targetMeters += usableMeters
        }
        return result
    }

    internal fun pointAtFraction(route: List<GeoPoint>, fraction: Double): GeoPoint {
        if (route.isEmpty()) return GeoPoint(0.0, 0.0)
        if (route.size == 1 || fraction <= 0.0) return route.first()
        if (fraction >= 1.0) return route.last()

        val lengths = route.zipWithNext().map { (a, b) -> haversineMeters(a, b) }
        val total = lengths.sum()
        if (total <= 0.0) return route[(fraction * route.lastIndex).toInt().coerceIn(0, route.lastIndex)]
        val target = total * fraction
        var accumulated = 0.0
        for (index in lengths.indices) {
            val length = lengths[index]
            if (accumulated + length >= target || index == lengths.lastIndex) {
                val local = if (length <= 0.0) 0.0 else ((target - accumulated) / length).coerceIn(0.0, 1.0)
                val a = route[index]
                val b = route[index + 1]
                return GeoPoint(
                    lat = a.lat + (b.lat - a.lat) * local,
                    lon = a.lon + (b.lon - a.lon) * local,
                )
            }
            accumulated += length
        }
        return route.last()
    }

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earth * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
