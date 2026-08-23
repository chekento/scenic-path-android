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
 * Reliable human-interest POI discovery using Photon's indexed category filters.
 *
 * The public Photon service has already proved reachable on the physical test device, while
 * large public Overpass queries are not reliable enough to define the first visible marker
 * population. This pass therefore uses Photon's `/api` endpoint with `bbox` + `include`.
 * According to Photon v1, a text query is optional when include/exclude category filters are
 * present, and comma-separated include categories are OR-ed. That lets Scenic Path search a
 * complete route window for specific kinds instead of reverse-geocoding whatever large
 * natural feature happens to be nearest an anchor.
 */
object PhotonCorridorPoiDiscovery {
    private const val ENDPOINT = "https://photon.komoot.io/api"

    private data class Pack(
        val id: String,
        val kinds: Set<StopKind>,
        val categories: List<String>,
        val limit: Int,
    )

    private val packs = listOf(
        Pack(
            id = "food",
            kinds = setOf(StopKind.FOOD),
            categories = listOf(
                "osm.amenity.restaurant",
                "osm.amenity.cafe",
                "osm.amenity.biergarten",
                "osm.amenity.food_court",
            ),
            limit = 55,
        ),
        Pack(
            id = "culture",
            kinds = setOf(StopKind.MUSEUM, StopKind.ART, StopKind.WORSHIP, StopKind.SCENIC),
            categories = listOf(
                "osm.tourism.museum",
                "osm.tourism.artwork",
                "osm.tourism.gallery",
                "osm.amenity.arts_centre",
                "osm.amenity.theatre",
                "osm.amenity.place_of_worship",
                "osm.tourism.attraction",
                "osm.tourism.zoo",
                "osm.tourism.theme_park",
                "osm.tourism.aquarium",
            ),
            limit = 70,
        ),
        Pack(
            id = "heritage",
            kinds = setOf(StopKind.VIEWPOINT, StopKind.MONUMENT, StopKind.ARCHITECTURE),
            categories = listOf(
                "osm.tourism.viewpoint",
                "osm.historic.castle",
                "osm.historic.manor",
                "osm.historic.palace",
                "osm.historic.fort",
                "osm.historic.ruins",
                "osm.historic.monument",
                "osm.historic.memorial",
                "osm.historic.archaeological_site",
                "osm.historic.battlefield",
                "osm.man_made.observation_tower",
                "osm.man_made.tower",
                "osm.man_made.lighthouse",
                "osm.man_made.windmill",
                "osm.man_made.watermill",
                "osm.bridge.yes",
            ),
            limit = 70,
        ),
    )

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int = 120,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        val activePacks = packs.mapNotNull { pack ->
            val enabled = pack.kinds.intersect(enabledKinds + StopKind.SCENIC)
            if (enabled.isEmpty()) null else pack
        }
        if (activePacks.isEmpty()) return@withContext emptyList()

        val routeForDistance = sampleRoute(route, 520)
        // Four windows for a ~300 km journey keeps the public development endpoint load
        // bounded while still covering the whole road corridor.
        val windows = splitRoute(route, maxMeters = 90_000.0).take(6)

        val all = coroutineScope {
            windows.flatMapIndexed { windowIndex, segment ->
                activePacks.map { pack ->
                    async(Dispatchers.IO) {
                        withTimeoutOrNull(5_200) {
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
        val include = URLEncoder.encode(pack.categories.joinToString(","), Charsets.UTF_8.name())
        val bbox = "${box.west},${box.south},${box.east},${box.north}"
        val url = buildString {
            append(ENDPOINT)
            append("?bbox=").append(URLEncoder.encode(bbox, Charsets.UTF_8.name()))
            append("&lon=").append(center.lon)
            append("&lat=").append(center.lat)
            append("&zoom=10&location_bias_scale=0.05")
            append("&limit=").append(pack.limit)
            append("&lang=de&dedupe=1&include=").append(include)
        }

        val features = fetch(url)
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
            if (distance > 15_500) continue

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
            val score = (relevance * 100.0 + specialBonus(rawType) - distance / 650.0).coerceAtLeast(1.0)

            result += ScenePointUi(
                id = id,
                name = name,
                kind = kind.name,
                subtype = rawType,
                point = point,
                relevance = relevance,
                suggestionScore = score,
                distanceFromRouteMeters = distance.roundToInt(),
                suggestedDwellMinutes = dwell(kind, rawType),
                attribution = "© OpenStreetMap contributors · Photon",
                rationale = when (kind) {
                    StopKind.FOOD -> "category-filtered route restaurant/café"
                    StopKind.MUSEUM -> "category-filtered museum"
                    StopKind.MONUMENT -> "category-filtered heritage highlight"
                    StopKind.ART -> "category-filtered art/culture"
                    StopKind.WORSHIP -> "category-filtered historic worship candidate"
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
            readTimeout = 4_400
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
                result += sampleRoute(current, 18)
                current = mutableListOf(route[index])
                meters = 0.0
            }
        }
        if (current.size >= 2) result += sampleRoute(current, 18)
        return result.ifEmpty { listOf(sampleRoute(route, 18)) }
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
