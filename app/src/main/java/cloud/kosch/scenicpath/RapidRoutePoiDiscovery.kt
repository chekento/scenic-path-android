package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * First-screen POI pass for long routes.
 *
 * The complete route is always covered. Short/medium journeys use ~65 km windows; very long
 * journeys enlarge the windows so the fast parallel pass remains bounded to roughly twelve
 * network requests instead of silently truncating the destination end.
 */
object RapidRoutePoiDiscovery {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int = 100,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty()) return@withContext emptyList()

        val routeForDistance = RouteCoveragePolicy.sampleByDistance(route, 520)
        val totalMeters = RouteCoveragePolicy.totalDistanceMeters(route)
        val windowMeters = maxOf(65_000.0, totalMeters / 12.0)
        val windows = splitRoute(route, windowMeters)
        val results = coroutineScope {
            windows.mapIndexed { index, segment ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(5_800) {
                        runCatching {
                            queryWindow(index, segment, routeForDistance, enabledKinds)
                        }.getOrElse { emptyList() }
                    }.orEmpty()
                }
            }.awaitAll()
        }.flatten()

        PrecisionRoutePoiDiscovery.mergeForDisplay(results, emptyList(), maxResults)
    }

    private fun queryWindow(
        windowIndex: Int,
        segment: List<GeoPoint>,
        routeForDistance: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
    ): List<ScenePointUi> {
        val box = bbox(segment, 11_000)
        val b = "${box.south},${box.west},${box.north},${box.east}"
        val statements = buildList {
            if (StopKind.FOOD in enabledKinds) add("nwr($b)[amenity~\"^(restaurant|cafe|biergarten|food_court)$\"][name];out center 55;")
            if (StopKind.MUSEUM in enabledKinds) add("nwr($b)[tourism=museum][name];out center 35;")
            if (StopKind.VIEWPOINT in enabledKinds) {
                add("nwr($b)[tourism=viewpoint][name];out center 35;")
                add("nwr($b)[man_made=observation_tower][name];out center 18;")
            }
            if (StopKind.MONUMENT in enabledKinds) {
                add("nwr($b)[historic~\"^(castle|manor|palace|fort|ruins|monument|memorial|archaeological_site|battlefield|city_gate)$\"][name];out center 48;")
                add("nwr($b)[heritage][name];out center 26;")
            }
            if (StopKind.ART in enabledKinds) {
                add("nwr($b)[tourism~\"^(artwork|gallery)$\"][name];out center 30;")
                add("nwr($b)[amenity~\"^(arts_centre|theatre)$\"][name];out center 24;")
            }
            if (StopKind.WORSHIP in enabledKinds) add("nwr($b)[amenity=place_of_worship][name];out center 45;")
            if (StopKind.ARCHITECTURE in enabledKinds) {
                add("nwr($b)[man_made~\"^(tower|lighthouse|water_tower|windmill|watermill)$\"][name];out center 30;")
                add("nwr($b)[bridge][name];out center 24;")
            }
            if (StopKind.SCENIC in enabledKinds) {
                add("nwr($b)[tourism~\"^(attraction|zoo|theme_park|aquarium)$\"][name];out center 28;")
            }
        }
        if (statements.isEmpty()) return emptyList()

        val query = "[out:json][timeout:8];${statements.joinToString("")}"
        val elements = execute(windowIndex, query)
        val points = mutableListOf<ScenePointUi>()

        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val point = elementPoint(element) ?: continue
            val subtype = subtype(tags) ?: continue
            val kind = sceneKindForRawType(subtype)
            if (kind !in enabledKinds) continue
            val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: continue
            if (distance > 13_000) continue
            val name = tags.optString("name:de").ifBlank { tags.optString("name") }.trim()
            if (name.isBlank()) continue
            val relevance = when (kind) {
                StopKind.VIEWPOINT -> 1.0
                StopKind.MONUMENT -> 0.98
                StopKind.MUSEUM -> 0.97
                StopKind.FOOD -> 0.93
                StopKind.ARCHITECTURE -> 0.89
                StopKind.ART -> 0.87
                StopKind.WORSHIP -> 0.84
                else -> 0.82
            }
            val metadata = (if (tags.optString("wikidata").isNotBlank()) 9 else 0) +
                (if (tags.optString("wikipedia").isNotBlank()) 10 else 0) +
                (if (tags.optString("heritage").isNotBlank()) 6 else 0)
            val website = tags.optString("website").ifBlank { tags.optString("contact:website") }
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }

            points += ScenePointUi(
                id = "rapid-osm-${element.optString("type")}-${element.optLong("id")}",
                name = name,
                kind = kind.name,
                subtype = subtype,
                point = point,
                relevance = relevance,
                suggestionScore = (relevance * 100 + metadata - distance / 520.0).coerceAtLeast(1.0),
                distanceFromRouteMeters = distance.roundToInt(),
                suggestedDwellMinutes = dwell(subtype, kind),
                url = website,
                attribution = "© OpenStreetMap contributors",
                rationale = when (kind) {
                    StopKind.FOOD -> tags.optString("cuisine").takeIf { it.isNotBlank() }?.let { "restaurant/café · ${it.replace(';', ',')}" }
                        ?: "restaurant/café"
                    StopKind.MONUMENT, StopKind.MUSEUM, StopKind.ART, StopKind.WORSHIP, StopKind.ARCHITECTURE -> "human-interest route candidate"
                    else -> null
                },
            )
        }
        return points
    }

    private fun execute(windowIndex: Int, query: String): JSONArray {
        val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        var lastError: Throwable? = null
        for (attempt in 0..1) {
            val endpoint = endpoints[(windowIndex + attempt) % endpoints.size]
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1_800
                readTimeout = 4_500
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
            }
            try {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("Overpass HTTP $code")
                return JSONObject(text.ifBlank { "{}" }).optJSONArray("elements") ?: JSONArray()
            } catch (error: Throwable) {
                lastError = error
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("Rapid POI scan unavailable")
    }

    private data class BBox(val south: Double, val west: Double, val north: Double, val east: Double)

    private fun bbox(points: List<GeoPoint>, bufferMeters: Int): BBox {
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        val midLat = (minLat + maxLat) / 2.0
        val latPad = bufferMeters / 111_320.0
        val lonPad = bufferMeters / (111_320.0 * cos(Math.toRadians(midLat)).coerceAtLeast(0.25))
        return BBox(minLat - latPad, minLon - lonPad, maxLat + latPad, maxLon + lonPad)
    }

    private fun subtype(tags: JSONObject): String? {
        val tourism = tags.optString("tourism").lowercase(Locale.ROOT)
        val amenity = tags.optString("amenity").lowercase(Locale.ROOT)
        val historic = tags.optString("historic").lowercase(Locale.ROOT)
        val castleType = tags.optString("castle_type").lowercase(Locale.ROOT)
        val manMade = tags.optString("man_made").lowercase(Locale.ROOT)
        val building = tags.optString("building").lowercase(Locale.ROOT)
        return when {
            tourism == "museum" -> "museum"
            tourism == "viewpoint" -> "viewpoint"
            tourism == "artwork" -> "artwork"
            tourism == "gallery" -> "gallery"
            tourism == "zoo" -> "zoo"
            tourism == "theme_park" -> "theme_park"
            tourism in setOf("attraction", "aquarium") -> "attraction"
            amenity == "restaurant" -> "restaurant"
            amenity == "cafe" -> "cafe"
            amenity in setOf("biergarten", "food_court") -> "restaurant"
            amenity in setOf("arts_centre", "theatre") -> "artwork"
            amenity == "place_of_worship" -> worshipSubtype(building, tags)
            historic == "castle" && castleType == "defensive" -> "defensive_castle"
            historic == "castle" && castleType == "stately" -> "stately"
            historic == "castle" && castleType == "palace" -> "palace"
            historic == "castle" && castleType in setOf("manor", "manor_house") -> "manor"
            historic == "castle" -> "castle"
            historic in setOf("manor", "manor_house") -> "manor"
            historic == "palace" -> "palace"
            historic == "ruins" -> "ruins"
            historic == "archaeological_site" -> "archaeological_site"
            historic == "battlefield" -> "battlefield"
            historic == "memorial" -> "memorial"
            historic == "monument" -> "monument"
            historic == "fort" -> "fort"
            historic.isNotBlank() || tags.optString("heritage").isNotBlank() -> "historic"
            manMade == "observation_tower" -> "observation_tower"
            manMade == "lighthouse" -> "lighthouse"
            manMade in setOf("tower", "water_tower") -> "tower"
            manMade == "windmill" -> "windmill"
            manMade == "watermill" -> "watermill"
            tags.optString("bridge").isNotBlank() -> "bridge"
            else -> null
        }
    }

    private fun worshipSubtype(building: String, tags: JSONObject): String {
        if (building in setOf("church", "cathedral", "chapel", "mosque", "synagogue", "temple")) return building
        return when (tags.optString("religion").lowercase(Locale.ROOT)) {
            "muslim" -> "mosque"
            "jewish" -> "synagogue"
            "buddhist", "hindu" -> "temple"
            else -> "church"
        }
    }

    private fun dwell(subtype: String, kind: StopKind): Int = when (subtype) {
        "museum" -> 50
        "castle", "defensive_castle", "stately", "palace", "manor", "fort" -> 35
        "ruins", "archaeological_site" -> 28
        "restaurant" -> 55
        "cafe" -> 35
        "viewpoint", "observation_tower" -> 15
        "theme_park", "zoo" -> 90
        else -> kind.defaultDwellMinutes
    }

    private fun elementPoint(element: JSONObject): GeoPoint? {
        val lat = element.optDouble("lat", Double.NaN)
        val lon = element.optDouble("lon", Double.NaN)
        if (lat.isFinite() && lon.isFinite()) return GeoPoint(lat, lon)
        val center = element.optJSONObject("center") ?: return null
        val centerLat = center.optDouble("lat", Double.NaN)
        val centerLon = center.optDouble("lon", Double.NaN)
        return if (centerLat.isFinite() && centerLon.isFinite()) GeoPoint(centerLat, centerLon) else null
    }

    private fun splitRoute(route: List<GeoPoint>, maxMeters: Double): List<List<GeoPoint>> {
        val result = mutableListOf<List<GeoPoint>>()
        var current = mutableListOf(route.first())
        var meters = 0.0
        for (i in 1 until route.size) {
            meters += haversineMeters(route[i - 1], route[i])
            current += route[i]
            if (meters >= maxMeters && i < route.lastIndex) {
                result += RouteCoveragePolicy.sampleByDistance(current, 16)
                current = mutableListOf(route[i])
                meters = 0.0
            }
        }
        if (current.size >= 2) result += RouteCoveragePolicy.sampleByDistance(current, 16)
        return result.ifEmpty { listOf(RouteCoveragePolicy.sampleByDistance(route, 16)) }
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
