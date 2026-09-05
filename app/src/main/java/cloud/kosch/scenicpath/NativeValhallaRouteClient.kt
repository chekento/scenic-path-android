package cloud.kosch.scenicpath

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class NativeNetworkRoute(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val points: List<GeoPoint>,
)

/** Reusable development-network client for round trips and generated alternatives. */
object NativeValhallaRouteClient {
    private const val VALHALLA_URL = "https://valhalla1.openstreetmap.de"
    private const val CLIENT_ID = "scenic-path-android-dev"

    fun routeThrough(
        nodes: List<GeoPoint>,
        preferences: ScenicPreferences,
        scenic: Boolean,
    ): NativeNetworkRoute {
        require(nodes.size >= 2) { "A route needs at least two nodes" }
        if (nodes.size == 2) return request(nodes, preferences, scenic)
        return stitch(nodes.zipWithNext().map { (from, to) -> request(listOf(from, to), preferences, scenic) })
    }

    private fun stitch(legs: List<NativeNetworkRoute>): NativeNetworkRoute {
        val points = buildList {
            legs.forEach { leg ->
                if (isEmpty()) addAll(leg.points)
                else if (leg.points.isNotEmpty()) {
                    if (RoundTripPolicy.haversineMeters(last(), leg.points.first()) < 20.0) addAll(leg.points.drop(1))
                    else addAll(leg.points)
                }
            }
        }
        if (points.size < 2) error("Valhalla returned no usable route geometry")
        return NativeNetworkRoute(
            distanceMeters = legs.sumOf { it.distanceMeters },
            durationSeconds = legs.sumOf { it.durationSeconds },
            points = points,
        )
    }

    private fun request(
        locations: List<GeoPoint>,
        preferences: ScenicPreferences,
        scenic: Boolean,
    ): NativeNetworkRoute {
        val vehicle = preferences.vehicle
        val costing = costingName(vehicle.kind)
        val options = costingOptions(vehicle, preferences, scenic)
        val body = JSONObject().apply {
            put("locations", JSONArray().apply {
                locations.forEach { point ->
                    put(JSONObject().put("lat", point.lat).put("lon", point.lon).put("type", "break"))
                }
            })
            put("costing", costing)
            put("costing_options", JSONObject().put(costing, options))
            put("directions_options", JSONObject().put("units", "kilometers").put("language", "de-DE"))
        }

        val connection = (URL("$VALHALLA_URL/route").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 16_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
            setRequestProperty("X-Client-Id", CLIENT_ID)
            outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Valhalla $costing HTTP $code ${text.take(180)}")
            val trip = JSONObject(text.ifBlank { "{}" }).optJSONObject("trip") ?: error("Valhalla returned no trip")
            val summary = trip.optJSONObject("summary") ?: error("Valhalla returned no summary")
            val legs = trip.optJSONArray("legs") ?: JSONArray()
            val points = buildList {
                for (index in 0 until legs.length()) {
                    val shape = legs.optJSONObject(index)?.optString("shape").orEmpty()
                    if (shape.isBlank()) continue
                    val decoded = decodePolyline6(shape)
                    if (isNotEmpty() && decoded.isNotEmpty() && RoundTripPolicy.haversineMeters(last(), decoded.first()) < 5.0) {
                        addAll(decoded.drop(1))
                    } else addAll(decoded)
                }
            }
            if (points.size < 2) error("Valhalla $costing route shape is empty")
            NativeNetworkRoute(
                distanceMeters = summary.optDouble("length", 0.0) * 1000.0,
                durationSeconds = summary.optDouble("time", 0.0),
                points = points,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun costingName(kind: VehicleKind): String = when (kind) {
        VehicleKind.CAR, VehicleKind.CAMPER -> "auto"
        VehicleKind.MOTORCYCLE -> "motorcycle"
        VehicleKind.TRUCK -> "truck"
        VehicleKind.COACH -> "bus"
        VehicleKind.BICYCLE -> "bicycle"
    }

    private fun costingOptions(
        vehicle: VehicleProfile,
        preferences: ScenicPreferences,
        scenic: Boolean,
    ): JSONObject = when (vehicle.kind) {
        VehicleKind.BICYCLE -> JSONObject().apply {
            put("bicycle_type", vehicle.bicycleType.apiValue)
            put("use_roads", if (scenic) 0.05 else 0.30)
            put("use_hills", (preferences.hilliness / 100.0).coerceIn(0.05, 0.85))
            put("avoid_bad_surfaces", if (vehicle.allowUnpavedBikePaths) 0.35 else 0.92)
            put("use_ferry", 0.35)
        }
        VehicleKind.MOTORCYCLE -> JSONObject().apply {
            put("use_highways", if (preferences.avoidMotorways) 0.0 else if (scenic) 0.18 else 0.8)
            put("use_tolls", if (preferences.avoidTolls) 0.0 else 0.5)
            put("use_trails", if (scenic) (preferences.windingness / 100.0 * 0.55).coerceIn(0.1, 0.55) else 0.0)
            put("use_ferry", 0.35)
            put("top_speed", 140)
        }
        VehicleKind.TRUCK -> JSONObject().apply {
            putHeavyEnvelope(vehicle)
            put("use_highways", if (preferences.avoidMotorways) 0.0 else if (scenic) 0.55 else 0.9)
            put("use_tolls", if (preferences.avoidTolls) 0.0 else 0.5)
            put("use_truck_route", 0.85)
            put("low_class_factor", if (scenic) 1.7 else 2.0)
            put("low_class_penalty", 45)
            put("hgv_no_access_penalty", 43200)
            put("top_speed", 90)
        }
        VehicleKind.COACH -> JSONObject().apply {
            putAutoEnvelope(vehicle)
            put("use_highways", if (preferences.avoidMotorways) 0.0 else if (scenic) 0.45 else 0.85)
            put("use_tolls", if (preferences.avoidTolls) 0.0 else 0.5)
            put("use_ferry", 0.25)
            put("use_distance", if (scenic) 0.08 else 0.0)
            put("top_speed", 100)
        }
        VehicleKind.CAMPER -> JSONObject().apply {
            putAutoEnvelope(vehicle)
            put("use_highways", if (preferences.avoidMotorways) 0.0 else if (scenic) 0.28 else 0.8)
            put("use_tolls", if (preferences.avoidTolls) 0.0 else 0.5)
            put("use_ferry", 0.25)
            put("use_distance", if (scenic) 0.12 else 0.0)
            put("exclude_unpaved", true)
            put("top_speed", 115)
        }
        VehicleKind.CAR -> JSONObject().apply {
            putAutoEnvelope(vehicle)
            put("use_highways", if (preferences.avoidMotorways) 0.0 else if (scenic) 0.15 else 0.9)
            put("use_tolls", if (preferences.avoidTolls) 0.0 else 0.5)
            put("use_ferry", 0.35)
            put("use_distance", if (scenic) 0.15 else 0.0)
            put("exclude_unpaved", true)
        }
    }

    private fun JSONObject.putAutoEnvelope(vehicle: VehicleProfile) {
        put("height", vehicle.heightMeters)
        put("width", vehicle.widthMeters)
        put("length", vehicle.lengthMeters)
        put("weight", vehicle.weightTons)
    }

    private fun JSONObject.putHeavyEnvelope(vehicle: VehicleProfile) {
        putAutoEnvelope(vehicle)
        put("axle_load", vehicle.axleLoadTons)
        put("axle_count", vehicle.axleCount)
    }

    private fun decodePolyline6(encoded: String): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            fun nextDelta(): Int {
                var result = 0
                var shift = 0
                var b: Int
                do {
                    if (index >= encoded.length) return 0
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                return if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            }
            lat += nextDelta()
            lon += nextDelta()
            points += GeoPoint(lat / 1_000_000.0, lon / 1_000_000.0)
        }
        return points
    }
}
