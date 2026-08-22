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
 * Route-wide POI enrichment for an already available road route.
 *
 * The contract is coverage-first: every enabled category gets a reserved slot whenever
 * OSM has a usable candidate in the searched corridor. Common nature/water features may
 * only consume the remaining capacity after that coverage pass.
 *
 * Public Overpass/Photon endpoints are development infrastructure only.
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
                    PhotonSceneFallback.discover(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = 26,
                        fast = true,
                        includeTargetedBackfill = false,
                    )
                }.getOrElse { emptyList() }
            }
            val targetedJob = async(Dispatchers.IO) {
                runCatching {
                    discoverTargetedOnly(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = maxResults,
                        radiusMeters = 15_000,
                        maxSamples = 10,
                        allowBackfill = true,
                    )
                }.getOrElse { emptyList() }
            }
            photonJob.await() to targetedJob.await()
        }

        mergeResults(photon, targeted, enabledKinds, maxResults)
    }

    /**
     * Targeted OSM pass used both by the post-route enrichment and by the long-route
     * planner's cheap missing-category rescue. Keeping this separate avoids recursion with
     * Photon while giving both consumers the same taxonomy and ranking rules.
     */
    internal suspend fun discoverTargetedOnly(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
        radiusMeters: Int = 15_000,
        maxSamples: Int = 10,
        allowBackfill: Boolean = true,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty()) return@withContext emptyList()

        var result = targetedOverpass(
            route = route,
            enabledKinds = enabledKinds,
            maxResults = maxResults,
            radiusMeters = radiusMeters,
            maxSamples = maxSamples,
        )

        if (allowBackfill) {
            val missing = enabledKinds
                .filter { it.autoDiscoverable && result.none { point -> point.kind == it.name } }
                .toSet()
            if (missing.isNotEmpty()) {
                val backfill = runCatching {
                    targetedOverpass(
                        route = route,
                        enabledKinds = missing,
                        maxResults = maxOf(16, missing.size * 4),
                        radiusMeters = max(radiusMeters + 8_000, 24_000),
                        maxSamples = min(7, maxSamples),
                    )
                }.getOrElse { emptyList() }
                result = mergeResults(result, backfill, enabledKinds, maxResults)
            }
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
            routeLength > 300_000 -> 10
            routeLength > 180_000 -> 9
            routeLength > 110_000 -> 8
            routeLength > 60_000 -> 6
            routeLength > 25_000 -> 5
            else -> 4
        }
        val samples = routeSamples(route, min(maxSamples, desiredSamples))
        val routeForDistance = routeSamples(route, 180)
        val raw = linkedMapOf<String, JSONObject>()

        // Keep public development load bounded while still covering the whole route.
        for (batch in samples.chunked(2)) {
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
            if (raw.size >= 320) break
        }

        val points = raw.values.mapNotNull { element ->
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val point = elementPoint(element) ?: return@mapNotNull null
            val rawType = rawType(tags) ?: return@mapNotNull null
            val kind = sceneKindForRawType(rawType)
            if (kind != StopKind.SCENIC && kind !in enabledKinds) return@mapNotNull null

            val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: return@mapNotNull null
            if (distance > radiusMeters * 1.18) return@mapNotNull null

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
                suggestionScore = (relevance * 100.0 + foodBonus - distance / 360.0).coerceAtLeast(1.0),
                distanceFromRouteMeters = distance.roundToInt(),
                suggestedDwellMinutes = dwell(rawType, kind),
                url = website,
                attribution = "© OpenStreetMap contributors",
                rationale = if (kind == StopKind.FOOD) foodRationale(rawType, tags) else heritageRationale(kind, rawType, tags),
            )
        }

        return mergeResults(emptyList(), points, enabledKinds, maxResults)
    }

    /**
     * Each category has its own capped selector. `out center` deliberately uses body
     * verbosity: nodes retain lat/lon, while ways/relations additionally get a center.
     * This matters for restaurants, monuments, churches and museums which are often nodes.
     */
    private fun queryWindow(sample: GeoPoint, enabledKinds: Set<StopKind>, radius: Int): JSONArray {
        val lat = sample.lat
        val lon = sample.lon
        val statements = buildList {
            if (StopKind.VIEWPOINT in enabledKinds) {
                add("nwr(around:$radius,$lat,$lon)[tourism=viewpoint];out center 14;")
            }
            if (StopKind.MUSEUM in enabledKinds) {
                add("nwr(around:$radius,$lat,$lon)[tourism=museum][name];out center 14;")
            }
            if (StopKind.ART in enabledKinds) {
                add("nwr(around:$radius,$lat,$lon)[tourism~\"^(artwork|gallery)$\"][name];out center 12;")
                add("nwr(around:$radius,$lat,$lon)[amenity=arts_centre][name];out center 10;")
                add("nwr(around:$radius,$lat,$lon)[artwork_type][name];out center 10;")
            }
            if (StopKind.MONUMENT in enabledKinds) {
                add("nwr(around:$radius,$lat,$lon)[historic~\"^(castle|manor|palace|fort|ruins|monument|memorial|archaeological_site)$\"][name];out center 20;")
                add("nwr(around:$radius,$lat,$lon)[castle_type][name];out center 12;")
                add("nwr(around:$radius,$lat,$lon)[heritage][name];out center 12;")
                add("nwr(around:$radius,$lat,$lon)[memorial][name];out center 10;")
            }
            if (StopKind.NATURE in enabledKinds) {
                add("nwr(around:$radius,$lat,$lon)[natural~\"^(peak|cape|stone|rock)$\"][name];out center 12;")
            }
            if (StopKind.WATER in enabledKinds) {
                add("nwr(around:$radius,$lat,$lon)[natural=beach][name];out center 8;")
                add("nwr(around:$radius,$lat,$lon)[waterway=waterfall][name];out center 10;")
                add("nwr(around:$radius,$lat,$lon)[natural=water][name];out center 12;")
                add("nwr(around:$radius,$lat,$lon)[waterway=river][name];out center 10;")
            }
            if (StopKind.PARK in enabledKinds) {
                add("nwr(around:$radius,$lat,$lon)[leisure~\"^(park|garden|nature_reserve)$\"][name];out center 12;")
                add("nwr(around:$radius,$lat,$lon)[boundary=protected_area][name];out center 8;")
            }
            if (StopKind.WORSHIP in enabledKinds) {
                add("nwr(around:$radius,$lat,$lon)[amenity=place_of_worship][name];out center 14;")
                add("nwr(around:$radius,$lat,$lon)[building~\"^(church|cathedral|chapel|mosque|synagogue|temple)$\"][name];out center 10;")
            }
            if (StopKind.FOOD in enabledKinds) {
                add("nwr(around:$radius,$lat,$lon)[amenity=restaurant][name];out center 22;")
                add("nwr(around:$radius,$lat,$lon)[amenity=cafe][name];out center 12;")
            }
            if (StopKind.ARCHITECTURE in enabledKinds) {
                add("nwr(around:$radius,$lat,$lon)[man_made~\"^(tower|lighthouse|water_tower)$\"][name];out center 12;")
                add("nwr(around:$radius,$lat,$lon)[bridge=yes][name];out center 10;")
                add("nwr(around:$radius,$lat,$lon)[historic=aqueduct][name];out center 8;")
            }
            add("nwr(around:$radius,$lat,$lon)[tourism=attraction][name];out center 10;")
        }
        if (statements.isEmpty()) return JSONArray()

        val query = "[out:json][timeout:11];${statements.joinToString("\n")}"
        val encoded = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        var lastError: Throwable? = null

        for (endpoint in overpassEndpoints) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_800
                readTimeout = 7_500
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
        throw lastError ?: IllegalStateException("Route POI discovery unavailable")
    }

    internal fun mergeResults(
        first: List<ScenePointUi>,
        second: List<ScenePointUi>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
    ): List<ScenePointUi> {
        val merged = mutableListOf<ScenePointUi>()
        (second + first).forEach { candidate ->
            val duplicate = merged.any {
                it.id == candidate.id ||
                    it.name.equals(candidate.name, ignoreCase = true) ||
                    haversineMeters(it.point, candidate.point) < 140
            }
            if (!duplicate) merged += candidate
        }

        val grouped = merged
            .groupBy { it.kind }
            .mapValues { (_, values) -> values.sortedByDescending { it.suggestionScore } }
        val result = mutableListOf<ScenePointUi>()

        // One reserved candidate per enabled category before any category gets a second.
        prototypeSelectableSceneKinds
            .filter { it in enabledKinds }
            .forEach { kind ->
                grouped[kind.name]?.firstOrNull()?.let { candidate ->
                    if (result.size < maxResults && result.none { it.id == candidate.id }) result += candidate
                }
            }

        grouped[StopKind.SCENIC.name]?.firstOrNull()?.let { candidate ->
            if (result.size < maxResults && result.none { it.id == candidate.id }) result += candidate
        }

        var round = 1
        val kindOrder = prototypeSelectableSceneKinds.filter { it in enabledKinds }.map { it.name } + StopKind.SCENIC.name
        while (result.size < maxResults) {
            var added = false
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

        if (result.size < maxResults) {
            merged.sortedByDescending { it.suggestionScore }.forEach { candidate ->
                if (result.size < maxResults && result.none { it.id == candidate.id }) result += candidate
            }
        }
        return result.take(maxResults)
    }

    private fun rawType(tags: JSONObject): String? {
        val tourism = tags.optString("tourism").lowercase()
        val historic = tags.optString("historic").lowercase()
        val castleType = tags.optString("castle_type").lowercase()
        val amenity = tags.optString("amenity").lowercase()
        val natural = tags.optString("natural").lowercase()
        val leisure = tags.optString("leisure").lowercase()
        val waterway = tags.optString("waterway").lowercase()
        val manMade = tags.optString("man_made").lowercase()
        val building = tags.optString("building").lowercase()

        return when {
            tourism == "viewpoint" -> "viewpoint"
            tourism == "museum" -> "museum"
            tourism == "artwork" -> "artwork"
            tourism == "gallery" -> "gallery"
            amenity == "arts_centre" || tags.optString("artwork_type").isNotBlank() -> "artwork"
            amenity == "place_of_worship" -> "worship"
            building in setOf("church", "cathedral", "chapel", "mosque", "synagogue", "temple") -> "worship"
            amenity == "restaurant" -> "restaurant"
            amenity == "cafe" -> "cafe"
            historic == "castle" && castleType == "defensive" -> "defensive_castle"
            historic == "castle" && castleType == "stately" -> "stately"
            historic == "castle" && castleType == "palace" -> "palace"
            historic == "castle" && castleType == "manor" -> "manor"
            historic == "castle" -> "castle"
            historic in setOf("manor", "manor_house") -> "manor"
            historic == "palace" -> "palace"
            historic == "archaeological_site" -> "archaeological_site"
            historic == "aqueduct" -> "tower"
            historic.isNotBlank() -> historic
            tags.optString("heritage").isNotBlank() || tags.optString("memorial").isNotBlank() -> "historic"
            waterway == "waterfall" -> "waterfall"
            waterway == "river" -> "river"
            natural == "water" -> "lake"
            natural == "beach" -> "beach"
            natural in setOf("peak", "cape", "stone", "rock") -> natural
            leisure == "nature_reserve" || tags.optString("boundary") == "protected_area" -> "nature_reserve"
            leisure.isNotBlank() -> leisure
            manMade == "water_tower" -> "tower"
            manMade.isNotBlank() -> manMade
            tags.optString("bridge") == "yes" -> "bridge"
            tourism == "attraction" -> "attraction"
            else -> null
        }
    }

    private fun relevance(kind: StopKind, rawType: String, tags: JSONObject): Double {
        var value = when (kind) {
            StopKind.VIEWPOINT -> 1.00
            StopKind.MONUMENT -> 0.97
            StopKind.WATER -> 0.92
            StopKind.NATURE -> 0.88
            StopKind.MUSEUM -> 0.90
            StopKind.FOOD -> 0.84
            StopKind.PARK -> 0.80
            StopKind.ARCHITECTURE -> 0.80
            StopKind.ART -> 0.77
            StopKind.WORSHIP -> 0.73
            StopKind.SCENIC -> 0.66
            else -> 0.55
        }
        if (rawType in setOf("castle", "defensive_castle", "stately", "palace", "manor")) value += 0.22
        if (rawType in setOf("fort", "ruins", "archaeological_site")) value += 0.12
        if (rawType in setOf("waterfall", "lighthouse")) value += 0.12
        if (kind == StopKind.FOOD) {
            if (rawType == "restaurant") value += 0.05
            if (tags.optString("website").isNotBlank() || tags.optString("contact:website").isNotBlank()) value += 0.05
            if (tags.optString("opening_hours").isNotBlank()) value += 0.04
            if (tags.optString("cuisine").isNotBlank()) value += 0.03
        }
        if (tags.optString("wikipedia").isNotBlank() || tags.optString("wikidata").isNotBlank()) value += 0.10
        return value.coerceAtMost(1.3)
    }

    private fun foodMetadataBonus(rawType: String, tags: JSONObject): Double {
        var value = if (rawType == "restaurant") 10.0 else 3.0
        if (tags.optString("website").isNotBlank() || tags.optString("contact:website").isNotBlank()) value += 6.0
        if (tags.optString("opening_hours").isNotBlank()) value += 5.0
        if (tags.optString("cuisine").isNotBlank()) value += 4.0
        if (tags.optString("phone").isNotBlank() || tags.optString("contact:phone").isNotBlank()) value += 2.0
        if (tags.optString("outdoor_seating") == "yes") value += 2.0
        if (tags.optString("wikidata").isNotBlank() || tags.optString("wikipedia").isNotBlank()) value += 5.0
        return value
    }

    private fun foodRationale(rawType: String, tags: JSONObject): String {
        val details = buildList {
            add(if (rawType == "restaurant") "restaurant" else "cafe")
            tags.optString("cuisine").takeIf { it.isNotBlank() }?.let { add(it.replace(';', ',')) }
            if (tags.optString("opening_hours").isNotBlank()) add("opening hours mapped")
            if (tags.optString("website").isNotBlank() || tags.optString("contact:website").isNotBlank()) add("website available")
        }
        return "Best available route-food candidate from OSM metadata · ${details.joinToString(" · ")} · verified ratings require the configured food provider"
    }

    private fun heritageRationale(kind: StopKind, rawType: String, tags: JSONObject): String? {
        if (kind != StopKind.MONUMENT && kind != StopKind.MUSEUM && kind != StopKind.ARCHITECTURE) return null
        val details = buildList {
            if (rawType in setOf("castle", "defensive_castle", "stately", "palace", "manor")) add("high-value heritage")
            if (tags.optString("wikipedia").isNotBlank() || tags.optString("wikidata").isNotBlank()) add("reference data available")
            if (tags.optString("heritage").isNotBlank()) add("heritage tagged")
        }
        return details.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun dwell(rawType: String, kind: StopKind): Int = when (rawType) {
        "castle", "defensive_castle", "stately", "palace", "manor", "fort" -> 30
        "ruins", "archaeological_site" -> 25
        "viewpoint" -> 12
        "waterfall", "beach", "lake" -> 25
        "restaurant" -> 55
        "cafe" -> 35
        else -> kind.defaultDwellMinutes
    }

    private fun fallbackName(rawType: String): String = when (rawType) {
        "defensive_castle" -> "Castle"
        "stately" -> "Stately home"
        "palace" -> "Palace"
        "manor" -> "Manor house"
        "archaeological_site" -> "Archaeological site"
        "nature_reserve" -> "Nature reserve"
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
