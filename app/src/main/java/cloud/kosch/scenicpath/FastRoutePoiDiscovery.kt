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
 * Fast, diverse POI enrichment for an already available route.
 *
 * v0.4.5 changes the contract from "best N overall" to "cover every enabled category
 * whenever OSM has a usable candidate, then fill remaining slots by quality". Each
 * category is emitted separately by Overpass so common peaks/parks cannot consume the
 * whole response before museums, heritage or food are seen.
 */
object FastRoutePoiDiscovery {
    private val overpassEndpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 40,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2) return@withContext emptyList()

        val (photon, targeted) = coroutineScope {
            val photonJob = async(Dispatchers.IO) {
                runCatching {
                    PhotonSceneFallback.discover(route, enabledKinds, maxResults = 24, fast = true)
                }.getOrElse { emptyList() }
            }
            val overpassJob = async(Dispatchers.IO) {
                runCatching {
                    targetedOverpass(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = maxResults,
                        radiusMeters = 12_000,
                        maxSamples = 6,
                    )
                }.getOrElse { emptyList() }
            }
            photonJob.await() to overpassJob.await()
        }

        var result = merge(photon, targeted, enabledKinds, maxResults)

        // One wider, smaller backfill pass only for categories that are still absent.
        // It runs after the route is already visible, so it cannot delay road rendering.
        val missingKinds = enabledKinds
            .filter { kind -> kind.autoDiscoverable && result.none { it.kind == kind.name } }
            .toSet()
        if (missingKinds.isNotEmpty()) {
            val backfill = runCatching {
                targetedOverpass(
                    route = route,
                    enabledKinds = missingKinds,
                    maxResults = maxOf(12, missingKinds.size * 4),
                    radiusMeters = 18_000,
                    maxSamples = 4,
                )
            }.getOrElse { emptyList() }
            result = merge(result, backfill, enabledKinds, maxResults)
        }

        result
    }

    private suspend fun targetedOverpass(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
        radiusMeters: Int,
        maxSamples: Int,
    ): List<ScenePointUi> {
        val routeLength = route.zipWithNext().sumOf { (a, b) -> haversineMeters(a, b) }
        val desiredSamples = when {
            routeLength > 260_000 -> 6
            routeLength > 140_000 -> 5
            routeLength > 60_000 -> 4
            else -> 3
        }
        val samples = routeSamples(route, min(maxSamples, desiredSamples))
        val routeForDistance = routeSamples(route, 140)
        val raw = linkedMapOf<String, JSONObject>()

        for (batch in samples.chunked(3)) {
            val windows = coroutineScope {
                batch.map { sample ->
                    async(Dispatchers.IO) {
                        runCatching { queryWindow(sample, enabledKinds, radiusMeters) }.getOrNull()
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
            if (raw.size >= 220) break
        }

        val points = raw.values.mapNotNull { element ->
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val point = elementPoint(element) ?: return@mapNotNull null
            val rawType = rawType(tags) ?: return@mapNotNull null
            val kind = sceneKindForRawType(rawType)
            if (kind != StopKind.SCENIC && kind !in enabledKinds) return@mapNotNull null

            val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: return@mapNotNull null
            if (distance > radiusMeters * 1.15) return@mapNotNull null

            val name = tags.optString("name").ifBlank { fallbackName(rawType) }
            val relevance = relevance(kind, rawType, tags)
            val website = tags.optString("website")
                .ifBlank { tags.optString("contact:website") }
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val foodBonus = if (kind == StopKind.FOOD) foodMetadataBonus(rawType, tags) else 0.0

            ScenePointUi(
                id = "fast-osm-${element.optString("type")}-${element.optLong("id")}",
                name = name,
                kind = kind.name,
                subtype = rawType,
                point = point,
                relevance = relevance,
                suggestionScore = (relevance * 100.0 + foodBonus - distance / 320.0).coerceAtLeast(1.0),
                distanceFromRouteMeters = distance.roundToInt(),
                suggestedDwellMinutes = dwell(rawType, kind),
                url = website,
                attribution = "© OpenStreetMap contributors",
                rationale = if (kind == StopKind.FOOD) foodRationale(rawType, tags) else null,
            )
        }

        return merge(emptyList(), points, enabledKinds, maxResults)
    }

    /**
     * The query deliberately emits each category separately with its own result cap.
     * This is the important difference from the old union query: 70 nearby peaks can no
     * longer prevent a castle, museum or restaurant from ever reaching the client.
     */
    private fun queryWindow(sample: GeoPoint, enabledKinds: Set<StopKind>, radius: Int): JSONArray {
        val statements = buildList {
            if (StopKind.VIEWPOINT in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism=viewpoint];out center tags 12;")
            }
            if (StopKind.MUSEUM in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism=museum][name];out center tags 12;")
            }
            if (StopKind.ART in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism~\"^(artwork|gallery)$\"][name];out center tags 12;")
                add("nwr(around:$radius,${sample.lat},${sample.lon})[amenity=arts_centre][name];out center tags 8;")
            }
            if (StopKind.MONUMENT in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[historic~\"^(castle|manor|palace|fort|ruins|monument|memorial|archaeological_site)$\"][name];out center tags 16;")
                add("nwr(around:$radius,${sample.lat},${sample.lon})[castle_type][name];out center tags 10;")
            }
            if (StopKind.NATURE in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[natural~\"^(peak|cape|stone)$\"][name];out center tags 10;")
            }
            if (StopKind.WATER in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[natural=beach][name];out center tags 8;")
                add("nwr(around:$radius,${sample.lat},${sample.lon})[waterway=waterfall][name];out center tags 10;")
            }
            if (StopKind.PARK in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[leisure~\"^(park|garden|nature_reserve)$\"][name];out center tags 10;")
            }
            if (StopKind.WORSHIP in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[amenity=place_of_worship][name];out center tags 10;")
            }
            if (StopKind.FOOD in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[amenity=restaurant][name];out center tags 16;")
                add("nwr(around:$radius,${sample.lat},${sample.lon})[amenity=cafe][name];out center tags 8;")
            }
            if (StopKind.ARCHITECTURE in enabledKinds) {
                add("nwr(around:$radius,${sample.lat},${sample.lon})[man_made~\"^(tower|lighthouse)$\"][name];out center tags 10;")
                add("nwr(around:$radius,${sample.lat},${sample.lon})[bridge=yes][name];out center tags 8;")
            }
            add("nwr(around:$radius,${sample.lat},${sample.lon})[tourism=attraction][name];out center tags 8;")
        }
        if (statements.isEmpty()) return JSONArray()

        val query = "[out:json][timeout:9];${statements.joinToString("\n")}"
        val encoded = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        var lastError: Throwable? = null

        for (endpoint in overpassEndpoints) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_500
                readTimeout = 6_500
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

    private fun merge(
        first: List<ScenePointUi>,
        second: List<ScenePointUi>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
    ): List<ScenePointUi> {
        val merged = mutableListOf<ScenePointUi>()
        (second + first).forEach { candidate ->
            val duplicate = merged.any {
                it.name.equals(candidate.name, ignoreCase = true) || haversineMeters(it.point, candidate.point) < 160
            }
            if (!duplicate) merged += candidate
        }

        val grouped = merged
            .groupBy { it.kind }
            .mapValues { (_, values) -> values.sortedByDescending { it.suggestionScore } }
        val result = mutableListOf<ScenePointUi>()

        // Hard category coverage: one good candidate from every enabled category first.
        prototypeSelectableSceneKinds
            .filter { it in enabledKinds }
            .forEach { kind ->
                grouped[kind.name]?.firstOrNull()?.let { candidate ->
                    if (result.size < maxResults && result.none { it.id == candidate.id }) result += candidate
                }
            }

        // Generic scenic attractions are useful, but only after explicit user categories.
        grouped[StopKind.SCENIC.name]?.firstOrNull()?.let { candidate ->
            if (result.size < maxResults && result.none { it.id == candidate.id }) result += candidate
        }

        var round = 1
        while (result.size < maxResults) {
            var added = false
            val kindOrder = prototypeSelectableSceneKinds.filter { it in enabledKinds }.map { it.name } + StopKind.SCENIC.name
            kindOrder.forEach { kindName ->
                grouped[kindName]?.getOrNull(round)?.let { candidate ->
                    if (result.size < maxResults && result.none { it.id == candidate.id }) {
                        result += candidate
                        added = true
                    }
                }
            }
            if (!added) break
            round++
        }

        // Fill any remaining capacity by global score.
        if (result.size < maxResults) {
            merged.sortedByDescending { it.suggestionScore }.forEach { candidate ->
                if (result.size < maxResults && result.none { it.id == candidate.id }) result += candidate
            }
        }
        return result.take(maxResults)
    }

    private fun rawType(tags: JSONObject): String? {
        val historic = tags.optString("historic").lowercase()
        val castleType = tags.optString("castle_type").lowercase()
        val amenity = tags.optString("amenity").lowercase()
        return when {
            tags.optString("tourism") == "viewpoint" -> "viewpoint"
            tags.optString("tourism") == "museum" -> "museum"
            tags.optString("tourism") == "artwork" -> "artwork"
            tags.optString("tourism") == "gallery" -> "gallery"
            amenity == "arts_centre" -> "artwork"
            amenity == "place_of_worship" -> "worship"
            amenity == "restaurant" -> "restaurant"
            amenity == "cafe" -> "cafe"
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
            StopKind.FOOD -> 0.82
            StopKind.PARK -> 0.80
            StopKind.ARCHITECTURE -> 0.78
            StopKind.ART -> 0.74
            StopKind.WORSHIP -> 0.70
            StopKind.SCENIC -> 0.66
            else -> 0.55
        }
        if (rawType in setOf("castle", "defensive_castle", "stately", "palace", "manor")) value += 0.20
        if (rawType in setOf("waterfall", "lighthouse")) value += 0.12
        if (kind == StopKind.FOOD) {
            if (rawType == "restaurant") value += 0.05
            if (tags.optString("website").isNotBlank() || tags.optString("contact:website").isNotBlank()) value += 0.05
            if (tags.optString("opening_hours").isNotBlank()) value += 0.04
            if (tags.optString("cuisine").isNotBlank()) value += 0.03
        }
        if (tags.optString("wikipedia").isNotBlank() || tags.optString("wikidata").isNotBlank()) value += 0.08
        return value.coerceAtMost(1.3)
    }

    private fun foodMetadataBonus(rawType: String, tags: JSONObject): Double {
        var value = if (rawType == "restaurant") 8.0 else 2.0
        if (tags.optString("website").isNotBlank() || tags.optString("contact:website").isNotBlank()) value += 5.0
        if (tags.optString("opening_hours").isNotBlank()) value += 4.0
        if (tags.optString("cuisine").isNotBlank()) value += 3.0
        if (tags.optString("wikidata").isNotBlank() || tags.optString("wikipedia").isNotBlank()) value += 5.0
        return value
    }

    private fun foodRationale(rawType: String, tags: JSONObject): String {
        val details = buildList {
            if (rawType == "restaurant") add("restaurant") else add("cafe")
            tags.optString("cuisine").takeIf { it.isNotBlank() }?.let { add(it.replace(';', ',')) }
            if (tags.optString("opening_hours").isNotBlank()) add("opening hours mapped")
            if (tags.optString("website").isNotBlank() || tags.optString("contact:website").isNotBlank()) add("website available")
        }
        return "Route food candidate · ${details.joinToString(" · ")} · verified ratings require the configured food provider"
    }

    private fun dwell(rawType: String, kind: StopKind): Int = when (rawType) {
        "castle", "defensive_castle", "stately", "palace", "manor", "fort" -> 30
        "ruins" -> 25
        "viewpoint" -> 12
        "waterfall", "beach" -> 25
        "restaurant" -> 55
        "cafe" -> 35
        else -> kind.defaultDwellMinutes
    }

    private fun fallbackName(rawType: String): String = when (rawType) {
        "defensive_castle" -> "Castle"
        "stately" -> "Stately home"
        "palace" -> "Palace"
        "manor" -> "Manor house"
        "restaurant" -> "Restaurant"
        "cafe" -> "Cafe"
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
