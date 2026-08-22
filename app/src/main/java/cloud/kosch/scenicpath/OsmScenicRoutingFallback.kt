package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Physical-device development fallback while the Scenic Path backend is not yet public.
 *
 * Uses the FOSSGIS Valhalla demo API for OSM-native routing. Scene discovery is delegated
 * to OsmSceneDiscovery, which has small-window Overpass queries and endpoint failover.
 */
object OsmScenicRoutingFallback {
    private const val VALHALLA_URL = "https://valhalla1.openstreetmap.de"
    private const val CLIENT_ID = "scenic-path-android-dev"

    suspend fun plan(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi = withContext(Dispatchers.IO) {
        val effective = preferences.forCharacter(plan.routeCharacter)
        val fixedStops = plan.stops.mapNotNull { it.point }

        val baseline = requestValhalla(
            locations = listOf(origin) + fixedStops + destination,
            avoidMotorways = false,
            avoidTolls = effective.avoidTolls,
        )

        val initialScenic = requestValhalla(
            locations = listOf(origin) + fixedStops + destination,
            avoidMotorways = effective.avoidMotorways,
            avoidTolls = effective.avoidTolls,
        )

        val discovered = if (plan.autoSuggestStops) {
            try {
                OsmSceneDiscovery.discover(
                    route = initialScenic.points,
                    enabledKinds = plan.enabledSceneKinds,
                    maxResults = 24,
                )
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val autoStops = selectAutoStops(discovered, plan, effective)
        val scenicWithStops = if (autoStops.isNotEmpty()) {
            runCatching {
                requestValhalla(
                    locations = listOf(origin) + fixedStops + autoStops.map { it.point } + destination,
                    avoidMotorways = effective.avoidMotorways,
                    avoidTolls = effective.avoidTolls,
                )
            }.getOrNull()
        } else {
            null
        }

        val acceptedAutoStops = if (scenicWithStops != null) {
            val travelExtraMinutes = max(0.0, (scenicWithStops.durationSeconds - baseline.durationSeconds) / 60.0)
            val dwellMinutes = autoStops.sumOf { it.suggestedDwellMinutes }
            if (travelExtraMinutes + dwellMinutes <= effective.maxExtraMinutes + 1.0) autoStops else emptyList()
        } else {
            emptyList()
        }

        val selectedScenic = if (acceptedAutoStops.isNotEmpty()) scenicWithStops!! else initialScenic
        val extraMinutes = max(0.0, (selectedScenic.durationSeconds - baseline.durationSeconds) / 60.0)
        val scenicScore = debugScenicScore(
            avoidMotorways = effective.avoidMotorways,
            route = selectedScenic.points,
            scenePoints = discovered,
        )
        val signals = buildList {
            if (effective.avoidMotorways) add("motorwayAvoidance")
            if (acceptedAutoStops.isNotEmpty()) add("autoHighlights")
            if (discovered.any { it.kind == StopKind.VIEWPOINT.name }) add("viewpoints")
            if (discovered.any { it.kind == StopKind.WATER.name }) add("water")
            if (discovered.any { it.kind == StopKind.NATURE.name || it.kind == StopKind.PARK.name }) add("forest")
            if (discovered.any { it.kind == StopKind.MONUMENT.name }) add("monuments")
        }

        val scenicCandidate = RouteCandidateUi(
            id = "osm-scenic-device",
            character = plan.routeCharacter.name,
            distanceMeters = selectedScenic.distanceMeters,
            durationSeconds = selectedScenic.durationSeconds,
            scenicScore = scenicScore,
            extraMinutes = extraMinutes,
            points = selectedScenic.points,
            provider = "Valhalla · OpenStreetMap development",
            scenePoints = discovered.take(18),
            strongestSignals = signals.take(4),
            isPreviewFallback = false,
        )

        val directCandidate = RouteCandidateUi(
            id = "osm-direct-device",
            character = RouteCharacter.DIRECT.name,
            distanceMeters = baseline.distanceMeters,
            durationSeconds = baseline.durationSeconds,
            scenicScore = 0.0,
            extraMinutes = 0.0,
            points = baseline.points,
            provider = "Valhalla · OpenStreetMap development",
            isPreviewFallback = false,
        )

        val candidates = when (plan.routeCharacter) {
            RouteCharacter.DIRECT -> listOf(directCandidate, scenicCandidate).distinctBy(::routeKey)
            else -> listOf(scenicCandidate, directCandidate).distinctBy(::routeKey)
        }

        RoutePlanUi(
            candidates = candidates,
            baselineDurationSeconds = baseline.durationSeconds,
            baselineDistanceMeters = baseline.distanceMeters,
            note = buildString {
                append("OSM development route via Valhalla")
                if (effective.avoidMotorways) append(" · motorway-free route validated")
                when {
                    acceptedAutoStops.isNotEmpty() -> {
                        append(" · ${acceptedAutoStops.size} scenic waypoint")
                        if (acceptedAutoStops.size != 1) append("s")
                        append(" included")
                    }
                    discovered.isNotEmpty() -> append(" · ${discovered.size} scenic locations found")
                    plan.autoSuggestStops -> append(" · no scene data returned by public discovery services")
                }
            },
        )
    }

    private data class RawRoute(
        val distanceMeters: Double,
        val durationSeconds: Double,
        val points: List<GeoPoint>,
    )

    private fun requestValhalla(
        locations: List<GeoPoint>,
        avoidMotorways: Boolean,
        avoidTolls: Boolean,
    ): RawRoute {
        val body = JSONObject().apply {
            put("locations", JSONArray().apply {
                locations.forEach { point ->
                    put(JSONObject().apply {
                        put("lat", point.lat)
                        put("lon", point.lon)
                        put("type", "break")
                    })
                }
            })
            put("costing", "auto")
            put("costing_options", JSONObject().put("auto", JSONObject().apply {
                put("use_highways", if (avoidMotorways) 0.0 else 1.0)
                put("use_tolls", if (avoidTolls) 0.0 else 0.5)
                put("use_ferry", 0.35)
            }))
            put("directions_options", JSONObject().put("units", "kilometers").put("language", "de-DE"))
        }

        val connection = openValhalla("/route", 10_000).apply {
            doOutput = true
            outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        }

        try {
            val response = JSONObject(connection.readTextOrThrow())
            val trip = response.optJSONObject("trip") ?: error("Valhalla returned no trip")
            val summary = trip.optJSONObject("summary") ?: error("Valhalla returned no summary")
            val legs = trip.optJSONArray("legs") ?: JSONArray()
            val points = buildList {
                for (index in 0 until legs.length()) {
                    val encoded = legs.optJSONObject(index)?.optString("shape").orEmpty()
                    if (encoded.isBlank()) continue
                    val decoded = decodePolyline6(encoded)
                    if (isNotEmpty() && decoded.isNotEmpty() && last() == decoded.first()) {
                        addAll(decoded.drop(1))
                    } else {
                        addAll(decoded)
                    }
                }
            }
            if (points.size < 2) error("Valhalla route shape is empty")
            if (avoidMotorways) validateMotorwayFree(points)
            return RawRoute(
                distanceMeters = summary.optDouble("length", 0.0) * 1000.0,
                durationSeconds = summary.optDouble("time", 0.0),
                points = points,
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Hard guard: a costing preference is not enough for an explicit motorway ban.
     * The actual routed edges are checked and the route is rejected if any motorway remains.
     */
    private fun validateMotorwayFree(points: List<GeoPoint>) {
        val shape = routeSamples(points, 120)
        val body = JSONObject().apply {
            put("shape", JSONArray().apply {
                shape.forEach { point ->
                    put(JSONObject().put("lat", point.lat).put("lon", point.lon))
                }
            })
            put("costing", "auto")
            put("shape_match", "walk_or_snap")
            put("filters", JSONObject().apply {
                put("action", "include")
                put("attributes", JSONArray(listOf("edge.road_class", "edge.length")))
            })
        }
        val connection = openValhalla("/trace_attributes", 12_000).apply {
            doOutput = true
            outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        }
        try {
            val response = JSONObject(connection.readTextOrThrow())
            val edges = response.optJSONArray("edges") ?: error("Could not validate road classes")
            var motorwayLengthKm = 0.0
            for (index in 0 until edges.length()) {
                val edge = edges.optJSONObject(index) ?: continue
                if (edge.optString("road_class") == "motorway") {
                    motorwayLengthKm += edge.optDouble("length", 0.0)
                }
            }
            if (motorwayLengthKm > 0.001) {
                error("No motorway-free route found for the selected constraints")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openValhalla(path: String, timeoutMs: Int): HttpURLConnection =
        (URL("$VALHALLA_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
            setRequestProperty("X-Client-Id", CLIENT_ID)
        }

    private fun selectAutoStops(
        suggestions: List<ScenePointUi>,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): List<ScenePointUi> {
        if (!plan.autoSuggestStops || suggestions.isEmpty()) return emptyList()
        val maximum = when {
            preferences.maxExtraMinutes >= 150 -> min(4, preferences.maxStops)
            preferences.maxExtraMinutes >= 90 -> min(3, preferences.maxStops)
            preferences.maxExtraMinutes >= 45 -> min(2, preferences.maxStops)
            preferences.maxExtraMinutes >= 20 -> min(1, preferences.maxStops)
            else -> 0
        }
        if (maximum <= 0) return emptyList()

        var remaining = preferences.maxExtraMinutes
        val picked = mutableListOf<ScenePointUi>()
        suggestions.forEach { candidate ->
            if (picked.size >= maximum) return@forEach
            val dwell = candidate.suggestedDwellMinutes
            if (dwell + 10 > remaining) return@forEach
            val diverse = picked.none { it.kind == candidate.kind } || picked.size >= maximum - 1
            if (!diverse) return@forEach
            picked += candidate
            remaining -= dwell
        }
        return picked
    }

    private fun debugScenicScore(
        avoidMotorways: Boolean,
        route: List<GeoPoint>,
        scenePoints: List<ScenePointUi>,
    ): Double {
        val bendScore = if (route.size >= 4) {
            val samples = routeSamples(route, 25)
            var turns = 0.0
            for (i in 1 until samples.lastIndex) {
                val a = bearing(samples[i - 1], samples[i])
                val b = bearing(samples[i], samples[i + 1])
                var delta = abs(a - b)
                if (delta > 180) delta = 360 - delta
                turns += min(delta, 90.0) / 90.0
            }
            (turns / max(1, samples.size - 2)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val poiScore = (scenePoints.take(6).sumOf { it.relevance } / 6.0).coerceIn(0.0, 1.0)
        return (35 + bendScore * 30 + poiScore * 25 + if (avoidMotorways) 10 else 0).coerceIn(0.0, 100.0)
    }

    private fun routeSamples(route: List<GeoPoint>, maxSamples: Int): List<GeoPoint> {
        if (route.size <= maxSamples) return route
        val step = (route.size - 1).toDouble() / (maxSamples - 1).coerceAtLeast(1)
        return (0 until maxSamples).map { index ->
            route[(index * step).roundToInt().coerceIn(0, route.lastIndex)]
        }
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
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && index < encoded.length)
                return if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            }
            lat += nextDelta()
            lon += nextDelta()
            points += GeoPoint(lat / 1_000_000.0, lon / 1_000_000.0)
        }
        return points
    }

    private fun HttpURLConnection.readTextOrThrow(): String {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("HTTP $code ${text.take(180)}")
        return text
    }

    private fun routeKey(route: RouteCandidateUi): String =
        "${(route.distanceMeters / 250).roundToInt()}:${(route.durationSeconds / 60).roundToInt()}"

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    @Suppress("unused")
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
