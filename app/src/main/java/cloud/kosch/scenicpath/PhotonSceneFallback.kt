package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
 * Development fallback when public Overpass discovery is unavailable or sparse.
 * Photon is OSM-backed. Production must use controlled/self-hosted infrastructure.
 *
 * Fast mode deliberately does not depend on a specific Photon installation having the
 * exact category index configured. It requests nearby `other` features and filters the
 * returned principal OSM tags locally. Normal mode can still use category filtering.
 */
object PhotonSceneFallback {
    private const val ENDPOINT = "https://photon.komoot.io/reverse"

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int = 18,
        fast: Boolean = true,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2) return@withContext emptyList()
        val categories = categoriesFor(enabledKinds)
        if (categories.isEmpty()) return@withContext emptyList()

        val length = routeLengthMeters(route)
        val normalCount = when {
            length > 180_000 -> 5
            length > 70_000 -> 4
            else -> 3
        }
        val sampleCount = if (fast) min(4, normalCount) else normalCount
        val samples = routeSamples(route, sampleCount)
        val routeForDistance = routeSamples(route, 100)
        val found = linkedMapOf<String, ScenePointUi>()

        // Two lookups at a time keeps the public development service load modest while
        // avoiding a route-length × timeout chain on the user's phone.
        for (batch in samples.chunked(2)) {
            val resultSets = coroutineScope {
                batch.map { sample ->
                    async(Dispatchers.IO) {
                        runCatching { query(sample, categories, fast) }.getOrNull()
                    }
                }.awaitAll()
            }

            for (features in resultSets) {
                features ?: continue
                for (i in 0 until features.length()) {
                    val feature = features.optJSONObject(i) ?: continue
                    val geometry = feature.optJSONObject("geometry") ?: continue
                    val coords = geometry.optJSONArray("coordinates") ?: continue
                    if (coords.length() < 2) continue
                    val lon = coords.optDouble(0, Double.NaN)
                    val lat = coords.optDouble(1, Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite()) continue

                    val properties = feature.optJSONObject("properties") ?: JSONObject()
                    val osmKey = properties.optString("osm_key")
                    val osmValue = properties.optString("osm_value")
                    val rawType = photonRawType(osmKey, osmValue) ?: continue
                    val kind = sceneKindForRawType(rawType)
                    if (kind == StopKind.FOOD) continue
                    if (kind != StopKind.SCENIC && kind !in enabledKinds) continue

                    val point = GeoPoint(lat, lon)
                    val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: continue
                    if (distance > 12_000) continue

                    val name = properties.optString("name").ifBlank {
                        rawType.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    }
                    val osmType = properties.optString("osm_type").ifBlank { "X" }
                    val osmId = properties.optLong("osm_id", -1L)
                    val id = if (osmId >= 0) "photon-$osmType-$osmId" else "photon-${lat}-${lon}-${name.hashCode()}"
                    val relevance = relevance(kind, rawType)
                    val score = (relevance * 100.0 - distance / 240.0).coerceAtLeast(1.0)
                    val candidate = ScenePointUi(
                        id = id,
                        name = name,
                        kind = kind.name,
                        subtype = rawType,
                        point = point,
                        relevance = relevance,
                        suggestionScore = score,
                        distanceFromRouteMeters = distance.roundToInt(),
                        suggestedDwellMinutes = dwellMinutes(kind, rawType),
                        attribution = "© OpenStreetMap contributors · Photon",
                    )
                    found.putIfAbsent(id, candidate)
                }
            }
        }

        found.values
            .distinctBy { "${it.name.lowercase(Locale.ROOT)}:${it.kind}" }
            .sortedByDescending { it.suggestionScore }
            .take(maxResults)
    }

    private fun query(
        sample: GeoPoint,
        categories: List<String>,
        fast: Boolean,
    ): org.json.JSONArray {
        val url = if (fast) {
            // `other` keeps houses/streets from crowding out POIs while avoiding a hard
            // dependency on installation-specific category indexes.
            "$ENDPOINT?lon=${sample.lon}&lat=${sample.lat}&radius=20&limit=50&lang=de&layer=other"
        } else {
            val include = URLEncoder.encode(categories.joinToString(","), Charsets.UTF_8.name())
            "$ENDPOINT?lon=${sample.lon}&lat=${sample.lat}&radius=20&limit=20&lang=de&include=$include"
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = if (fast) 2_000 else 3_500
            readTimeout = if (fast) 3_500 else 6_500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Photon HTTP $code")
            return JSONObject(text.ifBlank { "{}" }).optJSONArray("features") ?: org.json.JSONArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun categoriesFor(enabledKinds: Set<StopKind>): List<String> = buildList {
        if (StopKind.VIEWPOINT in enabledKinds) add("osm.tourism.viewpoint")
        if (StopKind.MUSEUM in enabledKinds) add("osm.tourism.museum")
        if (StopKind.ART in enabledKinds) {
            add("osm.tourism.artwork")
            add("osm.tourism.gallery")
            add("osm.amenity.arts_centre")
        }
        if (StopKind.MONUMENT in enabledKinds) {
            add("osm.historic.castle")
            add("osm.historic.manor")
            add("osm.historic.palace")
            add("osm.historic.fort")
            add("osm.historic.ruins")
            add("osm.historic.monument")
            add("osm.historic.memorial")
        }
        if (StopKind.NATURE in enabledKinds) {
            add("osm.natural.peak")
            add("osm.natural.cape")
            add("osm.natural.stone")
        }
        if (StopKind.WATER in enabledKinds) {
            add("osm.natural.beach")
            add("osm.waterway.waterfall")
        }
        if (StopKind.PARK in enabledKinds) {
            add("osm.leisure.park")
            add("osm.leisure.garden")
            add("osm.leisure.nature_reserve")
        }
        if (StopKind.WORSHIP in enabledKinds) add("osm.amenity.place_of_worship")
        if (StopKind.ARCHITECTURE in enabledKinds) {
            add("osm.man_made.lighthouse")
            add("osm.man_made.tower")
            add("osm.bridge.yes")
        }
        add("osm.tourism.attraction")
    }

    private fun photonRawType(key: String, value: String): String? = when {
        key == "tourism" && value in setOf("viewpoint", "museum", "artwork", "gallery", "attraction") -> value
        key == "amenity" && value == "arts_centre" -> "artwork"
        key == "amenity" && value == "place_of_worship" -> "worship"
        key == "historic" && value in setOf("castle", "manor", "palace", "fort", "ruins", "monument", "memorial") -> value
        key == "natural" && value in setOf("peak", "cape", "stone", "beach") -> value
        key == "waterway" && value == "waterfall" -> "waterfall"
        key == "leisure" && value in setOf("park", "garden", "nature_reserve") -> value
        key == "man_made" && value in setOf("lighthouse", "tower") -> value
        key == "bridge" && value == "yes" -> "bridge"
        else -> null
    }

    private fun relevance(kind: StopKind, rawType: String): Double {
        var score = when (kind) {
            StopKind.VIEWPOINT -> 1.0
            StopKind.MONUMENT -> 0.96
            StopKind.WATER -> 0.94
            StopKind.NATURE -> 0.90
            StopKind.MUSEUM -> 0.84
            StopKind.PARK -> 0.78
            StopKind.ARCHITECTURE -> 0.76
            StopKind.ART -> 0.72
            StopKind.WORSHIP -> 0.68
            StopKind.SCENIC -> 0.65
            else -> 0.55
        }
        if (rawType in setOf("castle", "manor", "palace", "waterfall", "lighthouse")) score += 0.12
        return score.coerceAtMost(1.2)
    }

    private fun dwellMinutes(kind: StopKind, rawType: String): Int = when (rawType) {
        "castle", "manor", "palace", "fort", "ruins" -> 30
        else -> kind.defaultDwellMinutes
    }

    private fun routeSamples(route: List<GeoPoint>, maxSamples: Int): List<GeoPoint> {
        if (route.size <= maxSamples) return route
        val step = (route.size - 1).toDouble() / max(1, maxSamples - 1)
        return (0 until maxSamples).map { index ->
            route[(index * step).roundToInt().coerceIn(0, route.lastIndex)]
        }
    }

    private fun routeLengthMeters(route: List<GeoPoint>): Double =
        route.zipWithNext().sumOf { (a, b) -> haversineMeters(a, b) }

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