package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
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
 * Development-only OSM scene discovery with endpoint failover.
 *
 * The production app should point the same concept at controlled/cached infrastructure.
 */
object OsmSceneDiscovery {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 24,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2) return@withContext emptyList()

        val lengthMeters = routeLengthMeters(route)
        val sampleCount = when {
            lengthMeters > 250_000 -> 9
            lengthMeters > 120_000 -> 8
            lengthMeters > 50_000 -> 7
            else -> 6
        }
        val samples = routeSamples(route, sampleCount)
        val routeForDistance = routeSamples(route, 100)
        val collected = linkedMapOf<String, JSONObject>()

        for (sample in samples) {
            val elements = queryOneWindow(sample, enabledKinds)
            for (index in 0 until elements.length()) {
                val element = elements.optJSONObject(index) ?: continue
                val type = element.optString("type")
                val id = element.optLong("id", -1L)
                if (type.isBlank() || id < 0) continue
                collected.putIfAbsent("$type:$id", element)
            }
            if (collected.size >= 80) break
        }

        val parsed = buildList {
            collected.values.forEach { element ->
                val tags = element.optJSONObject("tags") ?: JSONObject()
                val point = elementPoint(element) ?: return@forEach
                val rawType = rawSceneType(tags) ?: return@forEach
                val kind = sceneKindForRawType(rawType)
                if (kind == StopKind.FOOD) return@forEach
                if (kind != StopKind.SCENIC && kind !in enabledKinds) return@forEach

                val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: return@forEach
                if (distance > 10_000) return@forEach

                val relevance = sceneRelevance(kind, rawType, tags)
                val name = tags.optString("name").ifBlank { fallbackName(rawType) }
                val website = tags.optString("website")
                    .ifBlank { tags.optString("contact:website") }
                    .takeIf { it.startsWith("http://") || it.startsWith("https://") }

                add(
                    ScenePointUi(
                        id = "osm-${element.optString("type")}-${element.optLong("id")}",
                        name = name,
                        kind = kind.name,
                        subtype = rawType,
                        point = point,
                        relevance = relevance,
                        suggestionScore = (relevance * 100.0 - distance / 220.0).coerceAtLeast(1.0),
                        distanceFromRouteMeters = distance.roundToInt(),
                        suggestedDwellMinutes = kind.defaultDwellMinutes,
                        url = website,
                        attribution = "© OpenStreetMap contributors",
                    )
                )
            }
        }

        val selected = mutableListOf<ScenePointUi>()
        parsed
            .distinctBy { "${it.name.lowercase(Locale.ROOT)}:${it.kind}" }
            .sortedByDescending { it.suggestionScore }
            .forEach { candidate ->
                val duplicateCluster = selected.any {
                    it.kind == candidate.kind && haversineMeters(it.point, candidate.point) < 1_000
                }
                if (!duplicateCluster) selected += candidate
            }

        selected.take(maxResults)
    }

    private fun queryOneWindow(sample: GeoPoint, enabledKinds: Set<StopKind>): JSONArray {
        val radius = 9_000
        val clauses = buildList {
            if (StopKind.VIEWPOINT in enabledKinds) add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism=viewpoint];")
            if (StopKind.MUSEUM in enabledKinds) add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism=museum];")
            if (StopKind.ART in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism~\"^(artwork|gallery)$\"];")
                add("nwr(around:$radius,${sample.lat},${sample.lon})[amenity=arts_centre];")
            }
            if (StopKind.MONUMENT in enabledKinds || StopKind.WORSHIP in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[historic];")
            }
            if (StopKind.NATURE in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[natural~\"^(peak|cape|stone)$\"];")
            }
            if (StopKind.WATER in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[natural~\"^(beach|waterfall)$\"];")
                add("nwr(around:$radius,${sample.lat},${sample.lon})[waterway=waterfall];")
            }
            if (StopKind.PARK in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[leisure~\"^(park|garden|nature_reserve)$\"];")
            }
            if (StopKind.WORSHIP in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[amenity=place_of_worship][historic];")
            }
            if (StopKind.ARCHITECTURE in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[man_made~\"^(tower|lighthouse)$\"];")
                add("nwr(around:$radius,${sample.lat},${sample.lon})[bridge=yes][name];")
            }
            // Generic attractions remain useful as a fallback scene category.
            add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism=attraction];")
        }

        if (clauses.isEmpty()) return JSONArray()
        val query = "[out:json][timeout:10];(${clauses.joinToString("\n")});out center tags 80;"
        val encoded = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        var lastError: Throwable? = null

        for (endpoint in endpoints) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4_500
                readTimeout = 11_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
                outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(encoded) }
            }
            try {
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
        throw lastError ?: IllegalStateException("OSM scene discovery unavailable")
    }

    private fun rawSceneType(tags: JSONObject): String? = when {
        tags.optString("amenity") == "place_of_worship" -> "worship"
        tags.optString("amenity") == "arts_centre" -> "artwork"
        tags.optString("tourism") == "viewpoint" -> "viewpoint"
        tags.optString("tourism") == "museum" -> "museum"
        tags.optString("tourism") == "artwork" -> "artwork"
        tags.optString("tourism") == "gallery" -> "gallery"
        tags.optString("tourism") == "attraction" -> "attraction"
        tags.optString("waterway") == "waterfall" -> "waterfall"
        tags.optString("historic").isNotBlank() -> tags.optString("historic")
        tags.optString("natural").isNotBlank() -> tags.optString("natural")
        tags.optString("leisure").isNotBlank() -> tags.optString("leisure")
        tags.optString("man_made").isNotBlank() -> tags.optString("man_made")
        tags.optString("bridge") == "yes" -> "bridge"
        else -> null
    }

    private fun fallbackName(rawType: String): String = rawType
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }

    private fun sceneRelevance(kind: StopKind, rawType: String, tags: JSONObject): Double {
        var score = when (kind) {
            StopKind.VIEWPOINT -> 1.00
            StopKind.WATER -> 0.95
            StopKind.NATURE -> 0.92
            StopKind.MONUMENT -> 0.89
            StopKind.MUSEUM -> 0.84
            StopKind.PARK -> 0.80
            StopKind.ARCHITECTURE -> 0.78
            StopKind.ART -> 0.75
            StopKind.WORSHIP -> 0.70
            StopKind.SCENIC -> 0.68
            else -> 0.55
        }
        if (rawType == "castle" || rawType == "waterfall" || rawType == "lighthouse") score += 0.10
        if (tags.optString("wikipedia").isNotBlank() || tags.optString("wikidata").isNotBlank()) score += 0.10
        return score.coerceIn(0.0, 1.2)
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

    private fun elementPoint(element: JSONObject): GeoPoint? {
        val lat = element.optDouble("lat", Double.NaN)
        val lon = element.optDouble("lon", Double.NaN)
        if (lat.isFinite() && lon.isFinite()) return GeoPoint(lat, lon)
        val center = element.optJSONObject("center") ?: return null
        val centerLat = center.optDouble("lat", Double.NaN)
        val centerLon = center.optDouble("lon", Double.NaN)
        return if (centerLat.isFinite() && centerLon.isFinite()) GeoPoint(centerLat, centerLon) else null
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
