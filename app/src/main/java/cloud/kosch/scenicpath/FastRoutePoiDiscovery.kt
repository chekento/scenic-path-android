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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fast, diverse POI enrichment for an already available route.
 *
 * This deliberately runs after road routing. Photon provides a quick first pool while a
 * small targeted Overpass pass prevents nature features from crowding out culture/history.
 * Public endpoints are development infrastructure only.
 */
object FastRoutePoiDiscovery {
    private val overpassEndpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 36,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2) return@withContext emptyList()

        val (photon, targeted) = coroutineScope {
            val photonJob = async(Dispatchers.IO) {
                runCatching {
                    PhotonSceneFallback.discover(route, enabledKinds, maxResults = 24, fast = true)
                }.getOrElse { emptyList() }
            }
            val overpassJob = async(Dispatchers.IO) {
                runCatching { targetedOverpass(route, enabledKinds, maxResults) }
                    .getOrElse { emptyList() }
            }
            photonJob.await() to overpassJob.await()
        }

        merge(photon, targeted, maxResults)
    }

    private suspend fun targetedOverpass(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
    ): List<ScenePointUi> {
        val routeLength = route.zipWithNext().sumOf { (a, b) -> haversineMeters(a, b) }
        val sampleCount = when {
            routeLength > 260_000 -> 6
            routeLength > 140_000 -> 5
            routeLength > 60_000 -> 4
            else -> 3
        }
        val samples = routeSamples(route, sampleCount)
        val routeForDistance = routeSamples(route, 120)
        val raw = linkedMapOf<String, JSONObject>()

        for (batch in samples.chunked(3)) {
            val windows = coroutineScope {
                batch.map { sample ->
                    async(Dispatchers.IO) {
                        runCatching { queryWindow(sample, enabledKinds) }.getOrNull()
                    }
                }.awaitAll()
            }
            windows.filterNotNull().forEach { elements ->
                for (index in 0 until elements.length()) {
                    val element = elements.optJSONObject(index) ?: continue
                    val type = element.optString("type")
                    val id = element.optLong("id", -1)
                    if (type.isBlank() || id < 0) continue
                    raw.putIfAbsent("$type:$id", element)
                }
            }
            if (raw.size >= 140) break
        }

        val points = raw.values.mapNotNull { element ->
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val point = elementPoint(element) ?: return@mapNotNull null
            val rawType = rawType(tags) ?: return@mapNotNull null
            val kind = sceneKindForRawType(rawType)
            if (kind == StopKind.FOOD) return@mapNotNull null
            if (kind != StopKind.SCENIC && kind !in enabledKinds) return@mapNotNull null

            val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: return@mapNotNull null
            if (distance > 14_000) return@mapNotNull null

            val name = tags.optString("name").ifBlank { fallbackName(rawType) }
            val relevance = relevance(kind, rawType, tags)
            val website = tags.optString("website")
                .ifBlank { tags.optString("contact:website") }
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }

            ScenePointUi(
                id = "fast-osm-${element.optString("type")}-${element.optLong("id")}",
                name = name,
                kind = kind.name,
                subtype = rawType,
                point = point,
                relevance = relevance,
                suggestionScore = (relevance * 100.0 - distance / 300.0).coerceAtLeast(1.0),
                distanceFromRouteMeters = distance.roundToInt(),
                suggestedDwellMinutes = dwell(rawType, kind),
                url = website,
                attribution = "© OpenStreetMap contributors",
            )
        }

        return points
            .distinctBy { "${it.name.lowercase(Locale.ROOT)}:${it.kind}" }
            .sortedByDescending { it.suggestionScore }
            .take(maxResults)
    }

    private fun queryWindow(sample: GeoPoint, enabledKinds: Set<StopKind>): JSONArray {
        val radius = 9_000
        val clauses = buildList {
            if (StopKind.VIEWPOINT in enabledKinds) add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism=viewpoint];")
            if (StopKind.MUSEUM in enabledKinds) add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism=museum];")
            if (StopKind.ART in enabledKinds) add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism~\"^(artwork|gallery)$\"];")
            if (StopKind.MONUMENT in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[historic~\"^(castle|manor|palace|fort|ruins|monument|memorial|archaeological_site)$\"];")
                add("nwr(around:$radius,${sample.lat},${sample.lon})[castle_type];")
            }
            if (StopKind.NATURE in enabledKinds) add("nwr(around:$radius,${sample.lat},${sample.lon})[natural~\"^(peak|cape|stone)$\"];")
            if (StopKind.WATER in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[natural=beach];")
                add("nwr(around:$radius,${sample.lat},${sample.lon})[waterway=waterfall];")
            }
            if (StopKind.PARK in enabledKinds) add("nwr(around:$radius,${sample.lat},${sample.lon})[leisure~\"^(park|garden|nature_reserve)$\"];")
            if (StopKind.WORSHIP in enabledKinds) add("nwr(around:$radius,${sample.lat},${sample.lon})[amenity=place_of_worship][name];")
            if (StopKind.ARCHITECTURE in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[man_made~\"^(tower|lighthouse)$\"];")
                add("nwr(around:$radius,${sample.lat},${sample.lon})[bridge=yes][name];")
            }
            add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism=attraction][name];")
        }
        if (clauses.isEmpty()) return JSONArray()

        val query = "[out:json][timeout:7];(${clauses.joinToString("\n")});out center tags 70;"
        val encoded = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        var lastError: Throwable? = null

        for (endpoint in overpassEndpoints) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_500
                readTimeout = 5_500
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
        throw lastError ?: IllegalStateException("Fast route POI discovery unavailable")
    }

    private fun merge(first: List<ScenePointUi>, second: List<ScenePointUi>, maxResults: Int): List<ScenePointUi> {
        val merged = mutableListOf<ScenePointUi>()
        (second + first).forEach { candidate ->
            val duplicate = merged.any {
                it.name.equals(candidate.name, ignoreCase = true) || haversineMeters(it.point, candidate.point) < 160
            }
            if (!duplicate) merged += candidate
        }

        // Keep category diversity instead of allowing peaks/parks to fill every slot.
        val result = mutableListOf<ScenePointUi>()
        val grouped = merged.groupBy { it.kind }
        var round = 0
        while (result.size < maxResults) {
            var added = false
            grouped.values.forEach { group ->
                group.sortedByDescending { it.suggestionScore }.getOrNull(round)?.let {
                    if (result.size < maxResults) result += it
                    added = true
                }
            }
            if (!added) break
            round++
        }
        return result
    }

    private fun rawType(tags: JSONObject): String? {
        val historic = tags.optString("historic").lowercase()
        val castleType = tags.optString("castle_type").lowercase()
        return when {
            tags.optString("tourism") == "viewpoint" -> "viewpoint"
            tags.optString("tourism") == "museum" -> "museum"
            tags.optString("tourism") == "artwork" -> "artwork"
            tags.optString("tourism") == "gallery" -> "gallery"
            tags.optString("amenity") == "place_of_worship" -> "worship"
            historic == "castle" && castleType == "defensive" -> "defensive_castle"
            historic == "castle" && castleType == "stately" -> "stately"
            historic == "castle" && castleType == "palace" -> "palace"
            historic == "castle" && castleType == "manor" -> "manor"
            historic == "castle" -> "castle"
            historic in setOf("manor", "manor_house") -> "manor"
            historic == "palace" -> "palace"
            historic.isNotBlank() -> historic
            tags.optString("waterway") == "waterfall" -> "waterfall"
            tags.optString("natural").isNotBlank() -> tags.optString("natural")
            tags.optString("leisure").isNotBlank() -> tags.optString("leisure")
            tags.optString("man_made").isNotBlank() -> tags.optString("man_made")
            tags.optString("bridge") == "yes" -> "bridge"
            tags.optString("tourism") == "attraction" -> "attraction"
            else -> null
        }
    }

    private fun relevance(kind: StopKind, rawType: String, tags: JSONObject): Double {
        var value = when (kind) {
            StopKind.VIEWPOINT -> 1.00
            StopKind.MONUMENT -> 0.96
            StopKind.WATER -> 0.94
            StopKind.NATURE -> 0.88
            StopKind.MUSEUM -> 0.86
            StopKind.PARK -> 0.80
            StopKind.ARCHITECTURE -> 0.78
            StopKind.ART -> 0.74
            StopKind.WORSHIP -> 0.70
            StopKind.SCENIC -> 0.66
            else -> 0.55
        }
        if (rawType in setOf("castle", "defensive_castle", "stately", "palace", "manor")) value += 0.20
        if (rawType in setOf("waterfall", "lighthouse")) value += 0.12
        if (tags.optString("wikipedia").isNotBlank() || tags.optString("wikidata").isNotBlank()) value += 0.08
        return value.coerceAtMost(1.3)
    }

    private fun dwell(rawType: String, kind: StopKind): Int = when (rawType) {
        "castle", "defensive_castle", "stately", "palace", "manor", "fort" -> 30
        "ruins" -> 25
        "viewpoint" -> 12
        "waterfall", "beach" -> 25
        else -> kind.defaultDwellMinutes
    }

    private fun fallbackName(rawType: String): String = when (rawType) {
        "defensive_castle" -> "Castle"
        "stately" -> "Stately home"
        "palace" -> "Palace"
        "manor" -> "Manor house"
        else -> rawType.replace('_', ' ').replaceFirstChar { it.uppercase() }
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

    private fun routeSamples(route: List<GeoPoint>, maxSamples: Int): List<GeoPoint> {
        if (route.size <= maxSamples) return route
        val step = (route.size - 1).toDouble() / max(1, maxSamples - 1)
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
