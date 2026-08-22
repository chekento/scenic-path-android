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
 * Resilient route-wide OSM POI discovery.
 *
 * Categories are deliberately isolated into independent request families. A timeout in a
 * broad nature request can therefore never erase restaurants, museums, heritage or art.
 */
object PrecisionRoutePoiDiscovery {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private data class QueryFamily(
        val id: String,
        val kinds: Set<StopKind>,
        val radiusMeters: Int,
        val outputLimit: Int,
        val attractions: Boolean = false,
    )

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
        radiusMeters: Int = 15_000,
        maxSamples: Int = 10,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty()) return@withContext emptyList()

        val deep = radiusMeters >= 24_000 || maxSamples >= 12
        val length = route.zipWithNext().sumOf { (a, b) -> haversineMeters(a, b) }
        val desiredSamples = when {
            length > 300_000 -> 12
            length > 180_000 -> 11
            length > 100_000 -> 10
            length > 50_000 -> 8
            length > 25_000 -> 7
            else -> 5
        } + if (deep) 2 else 0
        val samples = routeSamples(route, min(maxSamples, desiredSamples.coerceAtLeast(4)))
        val routeForDistance = routeSamples(route, if (deep) 360 else 260)

        val foodRadius = min(radiusMeters, if (deep) 18_000 else 10_000)
        val cultureRadius = min(radiusMeters, if (deep) 25_000 else 15_000)
        val natureRadius = min(radiusMeters, if (deep) 22_000 else 14_000)
        val outputLimit = if (deep) 850 else 520

        val families = buildList {
            if (StopKind.FOOD in enabledKinds) add(QueryFamily("food", setOf(StopKind.FOOD), foodRadius, outputLimit))
            setOf(StopKind.VIEWPOINT, StopKind.MUSEUM, StopKind.MONUMENT).intersect(enabledKinds)
                .takeIf { it.isNotEmpty() }
                ?.let { add(QueryFamily("heritage", it, cultureRadius, outputLimit)) }
            setOf(StopKind.ART, StopKind.WORSHIP, StopKind.ARCHITECTURE).intersect(enabledKinds)
                .takeIf { it.isNotEmpty() }
                ?.let { add(QueryFamily("culture", it, cultureRadius, outputLimit)) }
            setOf(StopKind.NATURE, StopKind.PARK, StopKind.WATER).intersect(enabledKinds)
                .takeIf { it.isNotEmpty() }
                ?.let { add(QueryFamily("nature", it, natureRadius, outputLimit)) }
            add(QueryFamily("attractions", emptySet(), cultureRadius, if (deep) 500 else 300, attractions = true))
        }

        val collected = mutableListOf<ScenePointUi>()
        for (batch in families.chunked(2)) {
            val resultSets = coroutineScope {
                batch.map { family ->
                    async(Dispatchers.IO) {
                        runCatching { queryFamily(family, samples, routeForDistance, deep) }
                            .getOrElse { emptyList() }
                    }
                }.awaitAll()
            }
            resultSets.forEach(collected::addAll)
        }

        balanceAndDedupe(collected, maxResults)
    }

    /** Merge independently discovered sets without deleting different POIs merely because
     * they happen to be close to one another. */
    internal fun mergeForDisplay(
        first: List<ScenePointUi>,
        second: List<ScenePointUi>,
        maxResults: Int,
    ): List<ScenePointUi> = balanceAndDedupe(first + second, maxResults)

    private fun balanceAndDedupe(input: List<ScenePointUi>, maxResults: Int): List<ScenePointUi> {
        val deduped = mutableListOf<ScenePointUi>()
        input.sortedByDescending { it.suggestionScore }.forEach { candidate ->
            val duplicate = deduped.any { existing ->
                existing.id == candidate.id ||
                    (existing.kind == candidate.kind &&
                        existing.name.equals(candidate.name, ignoreCase = true) &&
                        haversineMeters(existing.point, candidate.point) < 350) ||
                    (existing.kind == candidate.kind &&
                        existing.subtype == candidate.subtype &&
                        haversineMeters(existing.point, candidate.point) < 45)
            }
            if (!duplicate) deduped += candidate
        }

        val byLane = deduped.groupBy { scenicCategoryLaneFor(it).id }
            .mapValues { (_, values) -> values.sortedByDescending { it.suggestionScore } }
        val result = mutableListOf<ScenePointUi>()
        var round = 0
        while (result.size < maxResults && round < 12) {
            var added = false
            scenicCategoryLanes.forEach { lane ->
                byLane[lane.id]?.getOrNull(round)?.let { candidate ->
                    if (result.size < maxResults && result.none { it.id == candidate.id }) {
                        result += candidate
                        added = true
                    }
                }
            }
            if (!added) break
            round++
        }
        deduped.forEach { candidate ->
            if (result.size < maxResults && result.none { it.id == candidate.id }) result += candidate
        }
        return result.take(maxResults)
    }

    private fun queryFamily(
        family: QueryFamily,
        samples: List<GeoPoint>,
        routeForDistance: List<GeoPoint>,
        deep: Boolean,
    ): List<ScenePointUi> {
        val selectors = buildList {
            samples.forEach { sample ->
                family.kinds.forEach { addAll(selectorsFor(it, sample, family.radiusMeters)) }
                if (family.attractions) {
                    add(selector(sample, family.radiusMeters, "[tourism~\"^(attraction|zoo|theme_park)$\"][name]"))
                }
            }
        }
        if (selectors.isEmpty()) return emptyList()

        val query = buildString {
            append("[out:json][timeout:${if (deep) 28 else 20}];(")
            selectors.forEach(::append)
            append(");out center ${family.outputLimit};")
        }
        val elements = execute(query, deep)
        val points = mutableListOf<ScenePointUi>()

        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val point = elementPoint(element) ?: continue
            val rawType = rawType(tags) ?: continue
            val kind = sceneKindForRawType(rawType)
            if (kind != StopKind.SCENIC && kind !in family.kinds) continue

            val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: continue
            if (distance > family.radiusMeters * 1.22) continue
            val name = preferredName(tags).ifBlank { fallbackName(rawType) }
            if (name.isBlank()) continue
            val relevance = relevance(kind, rawType, tags)
            val website = tags.optString("website")
                .ifBlank { tags.optString("contact:website") }
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }

            points += ScenePointUi(
                id = "precision-osm-${element.optString("type")}-${element.optLong("id")}",
                name = name,
                kind = kind.name,
                subtype = rawType,
                point = point,
                relevance = relevance,
                suggestionScore = (relevance * 100.0 + metadataBonus(kind, rawType, tags) - distance / 430.0).coerceAtLeast(1.0),
                distanceFromRouteMeters = distance.roundToInt(),
                suggestedDwellMinutes = dwell(rawType, kind),
                url = website,
                attribution = "© OpenStreetMap contributors",
                rationale = rationale(kind, rawType, tags),
            )
        }
        return points
    }

    private fun selectorsFor(kind: StopKind, sample: GeoPoint, radius: Int): List<String> = when (kind) {
        StopKind.VIEWPOINT -> listOf(
            selector(sample, radius, "[tourism=viewpoint][name]"),
            selector(sample, radius, "[man_made=observation_tower][name]"),
        )
        StopKind.MUSEUM -> listOf(selector(sample, radius, "[tourism=museum][name]"))
        StopKind.MONUMENT -> listOf(
            selector(sample, radius, "[historic][name]"),
            selector(sample, radius, "[heritage][name]"),
            selector(sample, radius, "[memorial][name]"),
        )
        StopKind.ART -> listOf(
            selector(sample, radius, "[tourism~\"^(artwork|gallery)$\"][name]"),
            selector(sample, radius, "[amenity=arts_centre][name]"),
            selector(sample, radius, "[artwork_type][name]"),
        )
        StopKind.WORSHIP -> listOf(
            selector(sample, radius, "[amenity=place_of_worship][name]"),
            selector(sample, radius, "[building~\"^(church|cathedral|chapel|mosque|synagogue|temple)$\"][name]"),
        )
        StopKind.ARCHITECTURE -> listOf(
            selector(sample, radius, "[man_made~\"^(tower|lighthouse|water_tower|windmill|watermill)$\"][name]"),
            selector(sample, radius, "[bridge][name]"),
            selector(sample, radius, "[historic=aqueduct][name]"),
        )
        StopKind.NATURE -> listOf(
            selector(sample, radius, "[natural~\"^(peak|cape|stone|rock|cave_entrance|wood)$\"][name]"),
            selector(sample, radius, "[geological][name]"),
            selector(sample, radius, "[landuse=forest][name]"),
        )
        StopKind.PARK -> listOf(
            selector(sample, radius, "[leisure~\"^(park|garden|nature_reserve)$\"][name]"),
            selector(sample, radius, "[boundary~\"^(protected_area|national_park)$\"][name]"),
        )
        StopKind.WATER -> listOf(
            selector(sample, radius, "[natural~\"^(water|beach|spring)$\"][name]"),
            selector(sample, radius, "[waterway~\"^(waterfall|river)$\"][name]"),
        )
        StopKind.FOOD -> listOf(
            selector(sample, radius, "[amenity=restaurant][name]"),
            selector(sample, radius, "[amenity=cafe][name]"),
        )
        else -> emptyList()
    }

    private fun selector(sample: GeoPoint, radius: Int, filter: String): String =
        "nwr(around:$radius,${sample.lat},${sample.lon})$filter;"

    private fun execute(query: String, deep: Boolean): JSONArray {
        val encoded = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        var lastError: Throwable? = null

        for (endpoint in endpoints) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3_500
                    readTimeout = if (deep) 24_000 else 17_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
                }
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(encoded) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("Overpass HTTP $code")
                return JSONObject(text.ifBlank { "{}" }).optJSONArray("elements") ?: JSONArray()
            } catch (error: Throwable) {
                lastError = error
            } finally {
                connection?.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("Precision POI discovery unavailable")
    }

    private fun rawType(tags: JSONObject): String? {
        val tourism = tags.optString("tourism").lowercase(Locale.ROOT)
        val amenity = tags.optString("amenity").lowercase(Locale.ROOT)
        val historic = tags.optString("historic").lowercase(Locale.ROOT)
        val castleType = tags.optString("castle_type").lowercase(Locale.ROOT)
        val natural = tags.optString("natural").lowercase(Locale.ROOT)
        val leisure = tags.optString("leisure").lowercase(Locale.ROOT)
        val boundary = tags.optString("boundary").lowercase(Locale.ROOT)
        val waterway = tags.optString("waterway").lowercase(Locale.ROOT)
        val manMade = tags.optString("man_made").lowercase(Locale.ROOT)
        val building = tags.optString("building").lowercase(Locale.ROOT)
        val landuse = tags.optString("landuse").lowercase(Locale.ROOT)

        return when {
            tourism == "viewpoint" -> "viewpoint"
            tourism == "museum" -> "museum"
            tourism == "artwork" -> "artwork"
            tourism == "gallery" -> "gallery"
            tourism == "zoo" -> "zoo"
            tourism == "theme_park" -> "theme_park"
            tourism == "attraction" -> "attraction"
            amenity == "arts_centre" || tags.optString("artwork_type").isNotBlank() -> "artwork"
            amenity == "restaurant" -> "restaurant"
            amenity == "cafe" -> "cafe"
            amenity == "place_of_worship" -> worshipSubtype(building, tags)
            building in setOf("church", "cathedral", "chapel", "mosque", "synagogue", "temple") -> worshipSubtype(building, tags)
            historic == "castle" && castleType == "defensive" -> "defensive_castle"
            historic == "castle" && castleType == "stately" -> "stately"
            historic == "castle" && castleType == "palace" -> "palace"
            historic == "castle" && castleType in setOf("manor", "manor_house") -> "manor"
            historic == "castle" -> "castle"
            historic in setOf("manor", "manor_house") -> "manor"
            historic == "palace" -> "palace"
            historic == "aqueduct" -> "aqueduct"
            historic.isNotBlank() -> historic
            tags.optString("heritage").isNotBlank() || tags.optString("memorial").isNotBlank() -> "historic"
            manMade == "observation_tower" -> "observation_tower"
            manMade == "lighthouse" -> "lighthouse"
            manMade == "water_tower" || manMade == "tower" -> "tower"
            manMade == "windmill" -> "windmill"
            manMade == "watermill" -> "watermill"
            tags.optString("bridge").isNotBlank() -> "bridge"
            waterway == "waterfall" -> "waterfall"
            waterway == "river" -> "river"
            natural == "water" -> "lake"
            natural == "beach" -> "beach"
            natural == "spring" -> "spring"
            natural == "cave_entrance" -> "cave"
            natural == "wood" || landuse == "forest" -> "forest"
            natural in setOf("peak", "cape", "stone", "rock") -> natural
            tags.optString("geological").isNotBlank() -> "geological"
            leisure == "nature_reserve" || boundary in setOf("protected_area", "national_park") -> "nature_reserve"
            leisure in setOf("park", "garden") -> leisure
            else -> null
        }
    }

    private fun worshipSubtype(building: String, tags: JSONObject): String {
        if (building in setOf("church", "cathedral", "chapel", "mosque", "synagogue", "temple")) return building
        return when (tags.optString("religion").lowercase(Locale.ROOT)) {
            "christian" -> "church"
            "muslim" -> "mosque"
            "jewish" -> "synagogue"
            "hindu", "buddhist" -> "temple"
            else -> "worship"
        }
    }

    private fun preferredName(tags: JSONObject): String = tags.optString("name")
        .ifBlank { tags.optString("name:de") }
        .ifBlank { tags.optString("name:en") }

    private fun relevance(kind: StopKind, rawType: String, tags: JSONObject): Double {
        var value = when (rawType) {
            "viewpoint" -> 1.10
            "castle", "defensive_castle", "palace", "stately", "manor" -> 1.12
            "archaeological_site", "ruins", "fort", "battlefield" -> 1.04
            "waterfall", "lighthouse", "observation_tower" -> 1.05
            "museum" -> 1.00
            "zoo", "theme_park" -> 0.96
            "restaurant" -> 0.94
            "artwork", "gallery" -> 0.90
            "cave", "geological" -> 0.93
            "peak" -> 0.92
            "nature_reserve" -> 0.90
            "cafe" -> 0.84
            "church", "cathedral", "mosque", "synagogue", "temple" -> 0.88
            "bridge", "aqueduct", "tower", "windmill", "watermill" -> 0.86
            "beach", "lake", "river", "spring" -> 0.84
            "park", "garden", "forest" -> 0.78
            "attraction" -> 0.82
            else -> if (kind == StopKind.MONUMENT) 0.90 else 0.76
        }
        if (tags.optString("wikidata").isNotBlank() || tags.optString("wikipedia").isNotBlank()) value += 0.13
        if (tags.optString("heritage").isNotBlank()) value += 0.08
        return value.coerceAtMost(1.30)
    }

    private fun metadataBonus(kind: StopKind, rawType: String, tags: JSONObject): Double {
        var value = 0.0
        if (tags.optString("wikidata").isNotBlank() || tags.optString("wikipedia").isNotBlank()) value += 14.0
        if (tags.optString("heritage").isNotBlank()) value += 8.0
        if (tags.optString("website").isNotBlank() || tags.optString("contact:website").isNotBlank()) value += 5.0
        if (tags.optString("opening_hours").isNotBlank()) value += 4.0
        if (kind == StopKind.FOOD) {
            if (rawType == "restaurant") value += 12.0
            if (tags.optString("cuisine").isNotBlank()) value += 6.0
            if (tags.optString("outdoor_seating") == "yes") value += 2.0
        }
        return value
    }

    private fun rationale(kind: StopKind, rawType: String, tags: JSONObject): String? {
        if (kind == StopKind.FOOD) {
            val details = buildList {
                add(if (rawType == "restaurant") "restaurant" else "café")
                tags.optString("cuisine").takeIf { it.isNotBlank() }?.let { add(it.replace(';', ',')) }
                if (tags.optString("opening_hours").isNotBlank()) add("opening hours mapped")
                if (tags.optString("website").isNotBlank() || tags.optString("contact:website").isNotBlank()) add("website available")
            }
            return "OSM route-food candidate · ${details.joinToString(" · ")} · verified consumer ratings require the configured food provider"
        }
        return buildList {
            if (rawType in setOf("castle", "defensive_castle", "palace", "stately", "manor")) add("major heritage")
            if (tags.optString("wikidata").isNotBlank() || tags.optString("wikipedia").isNotBlank()) add("reference data available")
            if (tags.optString("heritage").isNotBlank()) add("heritage tagged")
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun dwell(rawType: String, kind: StopKind): Int = when (rawType) {
        "castle", "defensive_castle", "palace", "stately", "manor", "fort" -> 35
        "museum" -> 55
        "ruins", "archaeological_site" -> 28
        "viewpoint", "observation_tower" -> 15
        "waterfall", "beach", "lake", "cave", "geological" -> 25
        "zoo", "theme_park" -> 90
        "restaurant" -> 55
        "cafe" -> 35
        else -> kind.defaultDwellMinutes
    }

    private fun fallbackName(rawType: String): String = rawType.replace('_', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

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
