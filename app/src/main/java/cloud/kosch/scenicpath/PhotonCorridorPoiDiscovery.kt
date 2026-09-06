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

/** Category-filtered human-interest discovery using the Photon index. */
object PhotonCorridorPoiDiscovery {
    private const val SEARCH_ENDPOINT = "https://photon.komoot.io/api"
    private const val REVERSE_ENDPOINT = "https://photon.komoot.io/reverse"

    private data class Pack(
        val id: String,
        val kinds: Set<StopKind>,
        val categories: List<String>,
        val limit: Int,
    )

    private val packs = listOf(
        Pack(
            "food",
            setOf(StopKind.FOOD),
            listOf(
                "osm.amenity.restaurant", "osm.amenity.cafe",
                "osm.amenity.biergarten", "osm.amenity.food_court",
            ),
            55,
        ),
        Pack(
            "culture",
            setOf(StopKind.MUSEUM, StopKind.ART, StopKind.WORSHIP, StopKind.SCENIC),
            listOf(
                "osm.tourism.museum", "osm.tourism.artwork", "osm.tourism.gallery",
                "osm.amenity.arts_centre", "osm.amenity.theatre", "osm.amenity.place_of_worship",
                "osm.tourism.attraction", "osm.tourism.zoo", "osm.tourism.theme_park",
                "osm.tourism.aquarium",
            ),
            70,
        ),
        Pack(
            "heritage",
            setOf(StopKind.VIEWPOINT, StopKind.MONUMENT, StopKind.ARCHITECTURE),
            listOf(
                "osm.tourism.viewpoint", "osm.historic.castle", "osm.historic.manor",
                "osm.historic.palace", "osm.historic.fort", "osm.historic.ruins",
                "osm.historic.monument", "osm.historic.memorial",
                "osm.historic.archaeological_site", "osm.historic.battlefield",
                "osm.man_made.observation_tower", "osm.man_made.tower",
                "osm.man_made.lighthouse", "osm.man_made.windmill", "osm.man_made.watermill",
                "osm.bridge.yes",
            ),
            70,
        ),
    )

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int = 120,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        val activePacks = packs.filter { pack ->
            pack.kinds.any { it == StopKind.SCENIC || it in enabledKinds }
        }
        if (activePacks.isEmpty()) return@withContext emptyList()

        // Long routes used to be split into ~90 km windows and then truncated with take(6),
        // silently dropping everything after roughly 540 km. Use a route-length-aware window
        // size instead: the complete route is always covered, while very long trips stay
        // bounded to roughly twelve Photon corridor windows.
        val routeForDistance = RouteCoveragePolicy.sampleByDistance(route, 520)
        val windows = splitRoute(route, RouteCoveragePolicy.fastWindowMeters(route))
        val all = coroutineScope {
            windows.flatMapIndexed { windowIndex, segment ->
                activePacks.map { pack ->
                    async(Dispatchers.IO) {
                        withTimeoutOrNull(6_200) {
                            runCatching {
                                queryWindow(windowIndex, segment, routeForDistance, enabledKinds, pack)
                            }.getOrElse { emptyList() }
                        }.orEmpty()
                    }
                }
            }.awaitAll()
        }.flatten()

        PrecisionRoutePoiDiscovery.mergeForDisplay(all, emptyList(), maxResults)
    }

    private fun queryWindow(
        windowIndex: Int,
        segment: List<GeoPoint>,
        routeForDistance: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        pack: Pack,
    ): List<ScenePointUi> {
        val box = bbox(segment, 13_000)
        val center = segment[segment.size / 2]
        val includeRaw = pack.categories.joinToString(",")
        val include = URLEncoder.encode(includeRaw, Charsets.UTF_8.name())
        val boxRaw = "${box.west},${box.south},${box.east},${box.north}"

        val searchUrl = buildString {
            append(SEARCH_ENDPOINT)
            append("?bbox=").append(URLEncoder.encode(boxRaw, Charsets.UTF_8.name()))
            append("&lon=").append(center.lon).append("&lat=").append(center.lat)
            append("&zoom=10&location_bias_scale=0.05")
            append("&limit=").append(pack.limit)
            append("&lang=de&dedupe=1&include=").append(include)
        }

        var features = runCatching { fetch(searchUrl) }.getOrElse { JSONArray() }
        if (features.length() == 0) {
            // Some Photon deployments are stricter about textless `/api` queries. Reverse is
            // documented to accept the same include filter, so keep a category-filtered,
            // route-centered fallback instead of reverting to unfiltered natural features.
            val reverseUrl = buildString {
                append(REVERSE_ENDPOINT)
                append("?lon=").append(center.lon).append("&lat=").append(center.lat)
                append("&radius=28&limit=").append(pack.limit)
                append("&lang=de&dedupe=1&include=").append(include)
            }
            features = runCatching { fetch(reverseUrl) }.getOrElse { JSONArray() }
        }

        return parseFeatures(windowIndex, features, routeForDistance, enabledKinds)
    }

    private fun parseFeatures(
        windowIndex: Int,
        features: JSONArray,
        routeForDistance: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
    ): List<ScenePointUi> {
        val result = mutableListOf<ScenePointUi>()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue

            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val rawType = photonRawType(
                properties.optString("osm_key").lowercase(Locale.ROOT),
                properties.optString("osm_value").lowercase(Locale.ROOT),
            ) ?: continue
            val kind = sceneKindForRawType(rawType)
            if (kind != StopKind.SCENIC && kind !in enabledKinds) continue

            val point = GeoPoint(lat, lon)
            val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: continue
            if (distance > 16_500) continue

            val name = properties.optString("name").trim().ifBlank {
                rawType.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.ROOT) }
            }
            if (name.isBlank()) continue
            val osmType = properties.optString("osm_type").ifBlank { "X" }
            val osmId = properties.optLong("osm_id", -1L)
            val id = if (osmId >= 0) "photon-corridor-$osmType-$osmId" else {
                "photon-corridor-$windowIndex-${point.lat}-${point.lon}-${name.hashCode()}"
            }
            val relevance = relevance(kind, rawType)
            result += ScenePointUi(
                id = id,
                name = name,
                kind = kind.name,
                subtype = rawType,
                point = point,
                relevance = relevance,
                suggestionScore = (relevance * 100.0 + specialBonus(rawType) - distance / 650.0).coerceAtLeast(1.0),
                distanceFromRouteMeters = distance.roundToInt(),
                suggestedDwellMinutes = dwell(kind, rawType),
                attribution = "© OpenStreetMap contributors · Photon",
                rationale = when (kind) {
                    StopKind.FOOD -> "category-filtered route restaurant/café"
                    StopKind.MUSEUM -> "category-filtered museum"
                    StopKind.MONUMENT -> "category-filtered heritage highlight"
                    StopKind.ART -> "category-filtered art/culture"
                    StopKind.WORSHIP -> "category-filtered worship candidate"
                    StopKind.ARCHITECTURE -> "category-filtered architecture"
                    StopKind.VIEWPOINT -> "category-filtered viewpoint"
                    else -> "category-filtered Scenic highlight"
                },
            )
        }
        return result
    }

    private fun fetch(url: String): JSONArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_200
            readTimeout = 4_800
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Photon corridor HTTP $code")
            return JSONObject(text.ifBlank { "{}" }).optJSONArray("features") ?: JSONArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun photonRawType(key: String, value: String): String? = when {
        key == "tourism" && value == "museum" -> "museum"
        key == "tourism" && value == "viewpoint" -> "viewpoint"
        key == "tourism" && value == "artwork" -> "artwork"
        key == "tourism" && value == "gallery" -> "gallery"
        key == "tourism" && value == "zoo" -> "zoo"
        key == "tourism" && value == "theme_park" -> "theme_park"
        key == "tourism" && value in setOf("attraction", "aquarium") -> "attraction"
        key == "amenity" && value == "restaurant" -> "restaurant"
        key == "amenity" && value == "cafe" -> "cafe"
        key == "amenity" && value in setOf("biergarten", "food_court") -> "restaurant"
        key == "amenity" && value in setOf("arts_centre", "theatre") -> "artwork"
        key == "amenity" && value == "place_of_worship" -> "worship"
        key == "historic" && value == "castle" -> "castle"
        key == "historic" && value in setOf("manor", "palace", "fort", "ruins", "monument", "memorial", "archaeological_site", "battlefield") -> value
        key == "man_made" && value == "observation_tower" -> "observation_tower"
        key == "man_made" && value == "lighthouse" -> "lighthouse"
        key == "man_made" && value in setOf("tower", "water_tower") -> "tower"
        key == "man_made" && value == "windmill" -> "windmill"
        key == "man_made" && value == "watermill" -> "watermill"
        key == "bridge" -> "bridge"
        else -> null
    }

    private fun relevance(kind: StopKind, rawType: String): Double {
        var value = when (kind) {
            StopKind.VIEWPOINT -> 1.00
            StopKind.MONUMENT -> 0.99
            StopKind.MUSEUM -> 0.98
            StopKind.FOOD -> 0.96
            StopKind.ARCHITECTURE -> 0.91
            StopKind.ART -> 0.89
            StopKind.WORSHIP -> 0.86
            StopKind.SCENIC -> 0.84
            else -> 0.80
        }
        if (rawType in setOf("castle", "palace", "manor")) value += 0.14
        return value.coerceAtMost(1.20)
    }

    private fun specialBonus(rawType: String): Double = when (rawType) {
        "restaurant" -> 14.0
        "museum" -> 13.0
        "castle", "palace", "manor" -> 16.0
        "viewpoint", "observation_tower" -> 12.0
        "ruins", "archaeological_site" -> 10.0
        else -> 5.0
    }

    private fun dwell(kind: StopKind, rawType: String): Int = when (rawType) {
        "restaurant" -> 55
        "cafe" -> 35
        "museum" -> 50
        "castle", "palace", "manor", "fort" -> 35
        "ruins", "archaeological_site" -> 28
        "viewpoint", "observation_tower" -> 15
        "zoo", "theme_park" -> 90
        else -> kind.defaultDwellMinutes
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

    private fun splitRoute(route: List<GeoPoint>, maxMeters: Double): List<List<GeoPoint>> {
        if (route.size < 2) return emptyList()
        val result = mutableListOf<List<GeoPoint>>()
        var current = mutableListOf(route.first())
        var meters = 0.0
        for (index in 1 until route.size) {
            meters += haversineMeters(route[index - 1], route[index])
            current += route[index]
            if (meters >= maxMeters && index < route.lastIndex) {
                result += RouteCoveragePolicy.sampleByDistance(current, 18)
                current = mutableListOf(route[index])
                meters = 0.0
            }
        }
        if (current.size >= 2) result += RouteCoveragePolicy.sampleByDistance(current, 18)
        return result.ifEmpty { listOf(RouteCoveragePolicy.sampleByDistance(route, 18)) }
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
