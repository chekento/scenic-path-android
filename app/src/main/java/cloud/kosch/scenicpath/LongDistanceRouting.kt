package cloud.kosch.scenicpath

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.IOException
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class RoadRoute(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val points: List<GeoPoint>,
)

/** Split on real network vertices. A guide is never presented as a vehicle-validated route. */
internal object LongDistanceRouting {
    suspend fun route(
        origin: GeoPoint,
        destination: GeoPoint,
        maxSpanMeters: Double = 600_000.0,
        requestGuide: suspend () -> List<GeoPoint>,
        requestLeg: suspend (GeoPoint, GeoPoint) -> RoadRoute,
    ): RoadRoute {
        if (distance(origin, destination) <= maxSpanMeters) {
            try {
                return requestLeg(origin, destination)
            } catch (error: Exception) {
                if (!isDistanceLimit(error)) throw error
            }
        }
        currentCoroutineContext().ensureActive()
        val guide = requestGuide()
        require(guide.size >= 2 && guide.all(::validPoint)) { "No usable road corridor was found." }
        require(distance(origin, guide.first()) < 10_000 && distance(destination, guide.last()) < 10_000) {
            "The road corridor does not reach the selected start and destination."
        }
        // Preserve exact selected endpoints; only interior anchors come from the network.
        val network = listOf(origin) + guide + destination
        suspend fun section(points: List<GeoPoint>, depth: Int): RoadRoute {
            currentCoroutineContext().ensureActive()
            return try {
                requestLeg(points.first(), points.last())
            } catch (error: Exception) {
                if (!isDistanceLimit(error) || depth >= 6 || points.size < 3) throw error
                val halves = splitByDistance(points, pathLength(points) / 2.0)
                if (halves.size < 2) throw error
                stitch(halves.map { section(it, depth + 1) })
            }
        }
        return stitch(splitByDistance(network, maxSpanMeters).map { section(it, 0) })
    }

    fun splitByDistance(points: List<GeoPoint>, maxSpanMeters: Double): List<List<GeoPoint>> {
        require(maxSpanMeters > 0 && points.size >= 2)
        val result = mutableListOf<List<GeoPoint>>()
        var start = 0
        var span = 0.0
        for (index in 1 until points.size) {
            val next = distance(points[index - 1], points[index])
            if (span + next > maxSpanMeters && index - 1 > start) {
                result += points.subList(start, index)
                start = index - 1
                span = 0.0
            }
            span += next
        }
        if (start < points.lastIndex) result += points.subList(start, points.size)
        return result
    }

    fun stitch(legs: List<RoadRoute>): RoadRoute {
        require(legs.isNotEmpty())
        val points = mutableListOf<GeoPoint>()
        legs.forEach { leg ->
            require(leg.points.size >= 2 && leg.points.all(::validPoint) &&
                leg.distanceMeters.isFinite() && leg.durationSeconds.isFinite() &&
                leg.distanceMeters >= 0 && leg.durationSeconds >= 0) { "The routing service returned an incomplete section." }
            if (points.isNotEmpty()) {
                if (distance(points.last(), leg.points.first()) > 75.0) {
                    throw IOException("The road sections could not be connected. Please try again or add a waypoint.")
                }
                if (distance(points.last(), leg.points.first()) < 1.0) points.removeAt(points.lastIndex)
            }
            points += leg.points
        }
        return RoadRoute(legs.sumOf { it.distanceMeters }, legs.sumOf { it.durationSeconds }, points)
    }

    fun isDistanceLimit(error: Throwable): Boolean {
        if (error is kotlinx.coroutines.CancellationException) return false
        val message = error.message.orEmpty().lowercase()
        return (error is RoutingHttpException && error.response.contains(Regex("\"error_code\"\\s*:\\s*154"))) ||
            message.contains("max distance") || message.contains("maximum distance") ||
            message.contains("distance limit") || message.contains("exceeds the max")
    }

    fun validPoint(point: GeoPoint) = point.lat.isFinite() && point.lon.isFinite() &&
        point.lat in -90.0..90.0 && point.lon in -180.0..180.0

    fun pathLength(points: List<GeoPoint>) = points.zipWithNext().sumOf { (a, b) -> distance(a, b) }

    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val h = sin(Math.toRadians(b.lat - a.lat) / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(Math.toRadians(b.lon - a.lon) / 2).pow(2)
        return 12_742_000 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

internal object OsmRoadCorridor {
    private val pacer = RequestPacer()
    suspend fun request(origin: GeoPoint, destination: GeoPoint, preferences: ScenicPreferences): List<GeoPoint> {
        pacer.awaitTurn()
        val bicycle = preferences.vehicle.kind == VehicleKind.BICYCLE
        // FOSSGIS's car profile has no precomputed motorway exclusion. The OSRM
        // reference car profile supports it; never drop the user's exclusion on HTTP 400.
        val endpoint = when {
            bicycle -> "https://routing.openstreetmap.de/routed-bike"
            preferences.avoidMotorways -> "https://router.project-osrm.org"
            else -> "https://routing.openstreetmap.de/routed-car"
        }
        val coordinates = "${origin.lon},${origin.lat};${destination.lon},${destination.lat}"
        val exclusion = if (!bicycle && preferences.avoidMotorways) "&exclude=motorway" else ""
        val text = CancellableNetwork.text(
            "$endpoint/route/v1/driving/$coordinates?overview=full&geometries=geojson&steps=false$exclusion",
            timeoutMs = 25_000,
        )
        val response = JSONObject(text)
        check(response.optString("code") == "Ok") { "No connected road corridor was found for this journey." }
        val coordinatesJson = response.optJSONArray("routes")?.optJSONObject(0)
            ?.optJSONObject("geometry")?.optJSONArray("coordinates") ?: error("The road corridor is empty.")
        return buildList {
            for (index in 0 until coordinatesJson.length()) {
                val pair = coordinatesJson.optJSONArray(index) ?: continue
                val point = GeoPoint(pair.optDouble(1, Double.NaN), pair.optDouble(0, Double.NaN))
                if (LongDistanceRouting.validPoint(point)) add(point)
            }
        }
    }
}
