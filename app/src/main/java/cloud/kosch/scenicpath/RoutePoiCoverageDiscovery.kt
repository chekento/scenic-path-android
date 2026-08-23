package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fast category-first POI coverage for long journeys.
 *
 * The continuous `around` corridor remains the deepest scan, but public Overpass instances
 * can need long enough on a 250-300 km route that the app only has Photon/nature results when
 * the map first appears. Official Overpass guidance recommends bounding boxes where possible
 * because they are cheaper. This scanner therefore breaks the real route into bounded boxes,
 * requests explicit quotas for the categories that Photon tends to miss, and then applies an
 * exact distance-to-route filter locally.
 *
 * It is deliberately complementary to PrecisionRoutePoiDiscovery: this pass is about getting
 * museums, food, heritage, art, worship, architecture and viewpoints onto the map quickly;
 * the precision pass can enrich the result later with the full 23-lane taxonomy.
 */
object RoutePoiCoverageDiscovery {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private data class Selector(
        val kind: StopKind?,
        val filter: String,
        val limit: Int,
    )

    private val selectors = listOf(
        Selector(StopKind.FOOD, "[amenity~\"^(restaurant|cafe|biergarten|food_court)$\"][name]", 46),
        Selector(StopKind.MUSEUM, "[tourism=museum][name]", 34),
        Selector(StopKind.VIEWPOINT, "[tourism=viewpoint][name]", 34),
        Selector(StopKind.VIEWPOINT, "[man_made=observation_tower][name]", 18),
        Selector(StopKind.MONUMENT, "[historic~\"^(castle|manor|palace|fort|ruins|monument|memorial|archaeological_site|battlefield|city_gate)$\"][name]", 42),
        Selector(StopKind.MONUMENT, "[heritage][name]", 28),
        Selector(StopKind.ART, "[tourism~\"^(artwork|gallery)$\"][name]", 30),
        Selector(StopKind.ART, "[amenity~\"^(arts_centre|theatre)$\"][name]", 24),
        Selector(StopKind.WORSHIP, "[amenity=place_of_worship][name]", 40),
        Selector(StopKind.ARCHITECTURE, "[man_made~\"^(tower|lighthouse|water_tower|windmill|watermill)$\"][name]", 30),
        Selector(StopKind.ARCHITECTURE, "[bridge][name]", 24),
        Selector(null, "[tourism~\"^(attraction|zoo|theme_park|aquarium)$\"][name]", 28),
    )

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int = 96,
        corridorMeters: Int = 12_000,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        val activeSelectors = selectors.filter { it.kind == null || it.kind in enabledKinds }
        if (activeSelectors.isEmpty()) return@withContext emptyList()

        val windows = splitRoute(route, maxSegmentMeters = 58_000.0)
        val routeForDistance = sampleRoute(route, 520)
        val collected = mutableListOf<ScenePointUi>()

        for (batch in windows.chunked(2)) {
            val results = coroutineScope {
                batch.map { segment ->
                    async(Dispatchers.IO) {
                        runCatching {
                            queryWindow(
                                segment = segment,
                                routeForDistance = routeForDistance,
                                selectors = activeSelectors,
                                corridorMeters = corridorMeters,
                            )
                        }.getOrElse { emptyList() }
                    }
                }.awaitAll()
            }
            results.forEach(collected::addAll)
        }

        PrecisionRoutePoiDiscovery.mergeForDisplay(
            first = collected,
            second = emptyList(),
            maxResults = maxResults,
        )
    }

    private fun queryWindow(
        segment: List<GeoPoint>,
        routeForDistance: List<GeoPoint>,
        selectors: List<Selector>,
        corridorMeters: Int,
    ): List<ScenePointUi> {
        val bbox = boundingBox(segment, corridorMeters)
        val box = "${bbox.south},${bbox.west},${bbox.north},${bbox.east}"
        val query = buildString {
            append("[out:json][timeout:12];")
            selectors.forEach { selector ->
                append("nwr($box)")
                append(selector.filter)
                append(";out center ${selector.limit};")
            }
        }
        val elements = execute(query)
        val result = mutableListOf<ScenePointUi>()

        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val point = elementPoint(element) ?: continue
            val rawType = rawType(tags) ?: continue
            val kind = sceneKindForRawType(rawType)
            if (kind != StopKind.SCENIC && selectors.none { it.kind == kind }) continue

            val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: continue
            if (distance > corridorMeters * 1.12) continue

            val name = tags.optString("name:de").ifBlank { tags.optString("name") }.trim()
            if (name.isBlank()) continue
            val website = tags.optString("website")
                .ifBlank { tags.optString("contact:website") }
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val relevance = relevance(kind, rawType, tags)

            result += ScenePointUi(
                id = "coverage-osm-${element.optString("type")}-${element.optLong("id")}",
                name = name,
                kind = kind.name,
                subtype = rawType,
                point = point,
                relevance = relevance,
                suggestionScore = (
                    relevance * 100.0 + metadataBonus(kind, tags) - distance / 520.0
                    ).coerceAtLeast(1.0),
                distanceFromRouteMeters = distance.roundToInt(),
                suggestedDwellMinutes = dwell(rawType, kind),
                url = website,
                attribution = "© OpenStreetMap contributors",
                rationale = rationale(kind, rawType, tags),
            )
        }
        return result
    }

    private data class BBox(val south: Double, val west: Double, val north: Double, val east: Double)

    private fun boundingBox(points: List<GeoPoint>, bufferMeters: Int): BBox {
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        val midLat = (minLat + maxLat) / 2.0
        val latPad = bufferMeters / 111_320.0
        val lonPad = bufferMeters / (111_320.0 * cos(Math.toRadians(midLat)).coerceAtLeast(0.25))
        return BBox(
            south = (minLat - latPad).coerceAtLeast(-90.0),
            west = (minLon - lonPad).coerceAtLeast(-180.0),
            north = (maxLat + latPad).coerceAtMost(90.0),
            east = (maxLon + lonPad).coerceAtMost(180.0),
        )
    }

    private fun execute(query: String): JSONArray {
        val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val start = Math.floorMod(query.hashCode(), endpoints.size)
        var lastError: Throwable? = null

        for (offset in endpoints.indices) {
            val endpoint = endpoints[(start + offset) % endpoints.size]
            try {
                return post(endpoint, body)
            } catch (error: Throwable) {
                lastError = error
            }
            if (encoded.length < 6_500) {
                try {
                    return get(endpoint, encoded)
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }
        throw lastError ?: IllegalStateException("POI coverage service unavailable")
    }

    private fun post(endpoint: String, body: String): JSONArray {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2_500
            readTimeout = 8_500
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
        }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun get(endpoint: String, encodedQuery: String): JSONArray {
        val connection = (URL("$endpoint?data=$encodedQuery").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_500
            readTimeout = 8_500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
        }
        return try {
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readJson(connection: HttpURLConnection): JSONArray {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("Overpass HTTP $code")
        return JSONObject(text.ifBlank { "{}" }).optJSONArray("elements") ?: JSONArray()
    }

    private fun rawType(tags: JSONObject): String? {
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
            historic.isNotBlank() -> "historic"
            tags.optString("heritage").isNotBlank() -> "historic"

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

    private fun relevance(kind: StopKind, rawType: String, tags: JSONObject): Double {
        var value = when (kind) {
            StopKind.VIEWPOINT -> 1.00
            StopKind.MONUMENT -> 0.98
            StopKind.MUSEUM -> 0.97
            StopKind.FOOD -> 0.92
            StopKind.ARCHITECTURE -> 0.89
            StopKind.ART -> 0.87
            StopKind.WORSHIP -> 0.84
            StopKind.SCENIC -> 0.83
            else -> 0.72
        }
        if (rawType in setOf("castle", "defensive_castle", "stately", "palace", "manor")) value += 0.18
        if (tags.optString("wikipedia").isNotBlank() || tags.optString("wikidata").isNotBlank()) value += 0.10
        if (tags.optString("heritage").isNotBlank()) value += 0.07
        return value.coerceAtMost(1.30)
    }

    private fun metadataBonus(kind: StopKind, tags: JSONObject): Double {
        var bonus = 0.0
        if (tags.optString("wikipedia").isNotBlank()) bonus += 10.0
        if (tags.optString("wikidata").isNotBlank()) bonus += 8.0
        if (tags.optString("website").isNotBlank() || tags.optString("contact:website").isNotBlank()) bonus += 4.0
        if (tags.optString("heritage").isNotBlank()) bonus += 5.0
        if (kind == StopKind.FOOD && tags.optString("cuisine").isNotBlank()) bonus += 6.0
        return bonus
    }

    private fun rationale(kind: StopKind, rawType: String, tags: JSONObject): String? = when (kind) {
        StopKind.FOOD -> buildList {
            add(if (rawType == "restaurant") "restaurant" else "café")
            tags.optString("cuisine").takeIf { it.isNotBlank() }?.let { add(it.replace(';', ',')) }
            if (tags.optString("opening_hours").isNotBlank()) add("opening hours mapped")
        }.joinToString(" · ")
        StopKind.MUSEUM, StopKind.MONUMENT, StopKind.ART, StopKind.WORSHIP, StopKind.ARCHITECTURE -> buildList {
            if (tags.optString("wikipedia").isNotBlank() || tags.optString("wikidata").isNotBlank()) add("reference data available")
            if (tags.optString("heritage").isNotBlank()) add("heritage tagged")
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        else -> null
    }

    private fun dwell(rawType: String, kind: StopKind): Int = when (rawType) {
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

    private fun splitRoute(route: List<GeoPoint>, maxSegmentMeters: Double): List<List<GeoPoint>> {
        if (route.size < 2) return emptyList()
        val segments = mutableListOf<List<GeoPoint>>()
        var current = mutableListOf(route.first())
        var distance = 0.0
        for (index in 1 until route.size) {
            val point = route[index]
            distance += haversineMeters(route[index - 1], point)
            current += point
            if (distance >= maxSegmentMeters && index < route.lastIndex) {
                segments += sampleRoute(current, 18)
                current = mutableListOf(point)
                distance = 0.0
            }
        }
        if (current.size >= 2) segments += sampleRoute(current, 18)
        return segments.ifEmpty { listOf(sampleRoute(route, 18)) }
    }

    private fun sampleRoute(route: List<GeoPoint>, maxSamples: Int): List<GeoPoint> {
        if (route.size <= maxSamples) return route
        val step = (route.size - 1).toDouble() / (maxSamples - 1).coerceAtLeast(1)
        return (0 until maxSamples).map { index ->
            route[(index * step).roundToInt().coerceIn(0, route.lastIndex)]
        }
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
