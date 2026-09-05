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
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coverage-first OSM discovery along the actual route corridor.
 *
 * v0.5 no longer asks Overpass for one huge union and then applies one shared output limit.
 * That design allowed a dense feature type (especially water or restaurants) to consume the
 * response before museums, heritage or architecture were returned. Each selector now has
 * its own output quota inside one of four failure-isolated request groups. A museum cannot be
 * crowded out by 300 restaurants and a heritage timeout cannot erase the food result set.
 */
object PrecisionRoutePoiDiscovery {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private data class SelectorSpec(
        val requiredKind: StopKind?,
        val filter: String,
        val normalLimit: Int,
        val deepLimit: Int,
    )

    private data class QueryGroup(
        val id: String,
        val normalRadiusMeters: Int,
        val deepRadiusMeters: Int,
        val selectors: List<SelectorSpec>,
    )

    private val groups = listOf(
        QueryGroup(
            id = "essential",
            normalRadiusMeters = 8_000,
            deepRadiusMeters = 14_000,
            selectors = listOf(
                SelectorSpec(StopKind.FOOD, "[amenity~\"^(restaurant|cafe|biergarten|food_court)$\"][name]", 80, 130),
                SelectorSpec(StopKind.FOOD, "[tourism=hotel][restaurant=yes][name]", 20, 35),
                SelectorSpec(StopKind.MUSEUM, "[tourism=museum][name]", 45, 75),
                SelectorSpec(StopKind.VIEWPOINT, "[tourism=viewpoint][name]", 45, 75),
                SelectorSpec(StopKind.VIEWPOINT, "[man_made=observation_tower][name]", 25, 40),
            ),
        ),
        QueryGroup(
            id = "heritage",
            normalRadiusMeters = 14_000,
            deepRadiusMeters = 24_000,
            selectors = listOf(
                SelectorSpec(
                    StopKind.MONUMENT,
                    "[historic~\"^(castle|manor|palace|fort|ruins|monument|memorial|archaeological_site|battlefield|city_gate)$\"][name]",
                    80,
                    130,
                ),
                SelectorSpec(StopKind.MONUMENT, "[castle_type][name]", 35, 55),
                SelectorSpec(StopKind.MONUMENT, "[heritage][name]", 55, 90),
                SelectorSpec(StopKind.MONUMENT, "[memorial][name]", 35, 55),
            ),
        ),
        QueryGroup(
            id = "culture",
            normalRadiusMeters = 12_000,
            deepRadiusMeters = 22_000,
            selectors = listOf(
                SelectorSpec(StopKind.ART, "[tourism~\"^(artwork|gallery)$\"][name]", 55, 90),
                SelectorSpec(StopKind.ART, "[amenity~\"^(arts_centre|theatre)$\"][name]", 40, 65),
                SelectorSpec(StopKind.ART, "[artwork_type][name]", 45, 70),
                SelectorSpec(StopKind.WORSHIP, "[amenity=place_of_worship][name]", 70, 110),
                SelectorSpec(StopKind.WORSHIP, "[building~\"^(church|cathedral|chapel|mosque|synagogue|temple)$\"][name]", 55, 90),
                SelectorSpec(StopKind.ARCHITECTURE, "[man_made~\"^(tower|lighthouse|water_tower|windmill|watermill)$\"][name]", 50, 80),
                SelectorSpec(StopKind.ARCHITECTURE, "[bridge][name]", 45, 70),
                SelectorSpec(StopKind.ARCHITECTURE, "[historic=aqueduct][name]", 20, 35),
            ),
        ),
        QueryGroup(
            id = "landscape",
            normalRadiusMeters = 10_000,
            deepRadiusMeters = 18_000,
            selectors = listOf(
                SelectorSpec(StopKind.NATURE, "[natural~\"^(peak|cape|stone|rock|cave_entrance|cliff|ridge)$\"][name]", 55, 90),
                SelectorSpec(StopKind.NATURE, "[geological][name]", 35, 55),
                SelectorSpec(StopKind.NATURE, "[landuse=forest][name]", 45, 70),
                SelectorSpec(StopKind.NATURE, "[natural=wood][name]", 45, 70),
                SelectorSpec(StopKind.PARK, "[leisure~\"^(park|garden|nature_reserve)$\"][name]", 60, 95),
                SelectorSpec(StopKind.PARK, "[boundary~\"^(protected_area|national_park)$\"][name]", 40, 65),
                SelectorSpec(StopKind.WATER, "[natural~\"^(water|beach|spring)$\"][name]", 55, 85),
                SelectorSpec(StopKind.WATER, "[waterway~\"^(waterfall|river)$\"][name]", 50, 80),
                // Scenic attractions are intentionally always available as a distinct lane.
                SelectorSpec(null, "[tourism~\"^(attraction|zoo|theme_park|aquarium)$\"][name]", 45, 75),
            ),
        ),
    )

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
        radiusMeters: Int = 15_000,
        maxSamples: Int = 10,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        val deep = radiusMeters >= 24_000 || maxSamples >= 12
        val segments = splitRoute(
            route = route,
            maxSegmentMeters = if (deep) 50_000.0 else 82_000.0,
            maxPointsPerSegment = if (deep) 16 else 13,
        )
        val routeForDistance = sampleRoute(route, if (deep) 560 else 380)
        val collected = mutableListOf<ScenePointUi>()

        for (segment in segments) {
            val activeGroups = groups.mapNotNull { group ->
                val activeSelectors = group.selectors.filter { selector ->
                    selector.requiredKind == null || selector.requiredKind in enabledKinds
                }
                if (activeSelectors.isEmpty()) null else group to activeSelectors
            }

            // Four groups at most: essential, heritage, culture, landscape. Failures are
            // isolated and endpoint selection is rotated per query to avoid a single public
            // instance becoming the sole point of failure.
            val resultSets = coroutineScope {
                activeGroups.map { (group, selectors) ->
                    async(Dispatchers.IO) {
                        runCatching {
                            querySegment(
                                group = group,
                                selectors = selectors,
                                segment = segment,
                                routeForDistance = routeForDistance,
                                enabledKinds = enabledKinds,
                                deep = deep,
                                requestedRadiusMeters = radiusMeters,
                            )
                        }.getOrElse { emptyList() }
                    }
                }.awaitAll()
            }
            resultSets.forEach(collected::addAll)
        }

        balanceAndDedupe(collected, maxResults)
    }

    /**
     * Display merge also applies the currently committed broad filter set. This prevents old
     * locally cached map results from re-introducing a category the user has disabled.
     */
    internal fun mergeForDisplay(
        first: List<ScenePointUi>,
        second: List<ScenePointUi>,
        maxResults: Int,
    ): List<ScenePointUi> {
        val active = ScenicSceneSelectionState.activeKinds
        val filtered = (first + second).filter { point ->
            val kind = StopKind.entries.firstOrNull { it.name == point.kind } ?: StopKind.SCENIC
            kind == StopKind.SCENIC || kind in active
        }
        return balanceAndDedupe(filtered, maxResults)
    }

    private fun querySegment(
        group: QueryGroup,
        selectors: List<SelectorSpec>,
        segment: List<GeoPoint>,
        routeForDistance: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        deep: Boolean,
        requestedRadiusMeters: Int,
    ): List<ScenePointUi> {
        if (segment.size < 2 || selectors.isEmpty()) return emptyList()

        val familyRadius = if (deep) group.deepRadiusMeters else group.normalRadiusMeters
        val radius = min(requestedRadiusMeters.coerceAtLeast(4_000), familyRadius)
        val line = segment.joinToString(",") { "${it.lat},${it.lon}" }

        // Each selector gets an independent `out` limit. Dense restaurant/water data can no
        // longer consume the museum or viewpoint quota in the same response.
        val query = buildString {
            append("[out:json][timeout:${if (deep) 24 else 15}];")
            selectors.forEach { selector ->
                val limit = if (deep) selector.deepLimit else selector.normalLimit
                append("nwr(around:$radius,$line)")
                append(selector.filter)
                append(";out center $limit;")
            }
        }
        val elements = execute(query, deep)
        val points = mutableListOf<ScenePointUi>()

        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val point = elementPoint(element) ?: continue
            val rawType = rawType(tags) ?: continue
            val kind = sceneKindForRawType(rawType)
            if (kind != StopKind.SCENIC && kind !in enabledKinds) continue

            val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: continue
            if (distance > radius * 1.30) continue

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
                suggestionScore = (
                    relevance * 100.0 + metadataBonus(kind, rawType, tags) -
                        distance / if (kind == StopKind.FOOD) 520.0 else 460.0
                    ).coerceAtLeast(1.0),
                distanceFromRouteMeters = distance.roundToInt(),
                suggestedDwellMinutes = dwell(rawType, kind),
                url = website,
                attribution = "© OpenStreetMap contributors",
                rationale = rationale(kind, rawType, tags),
            )
        }
        return points
    }

    private fun balanceAndDedupe(input: List<ScenePointUi>, maxResults: Int): List<ScenePointUi> {
        if (input.isEmpty() || maxResults <= 0) return emptyList()

        val deduped = mutableListOf<ScenePointUi>()
        input.sortedByDescending { it.suggestionScore }.forEach { candidate ->
            val duplicate = deduped.any { existing -> samePlace(existing, candidate) }
            if (!duplicate) deduped += candidate
        }

        val byLane = deduped
            .groupBy { scenicCategoryLaneFor(it).id }
            .mapValues { (_, values) -> values.sortedByDescending { it.suggestionScore } }
        val result = mutableListOf<ScenePointUi>()
        val laneCounts = mutableMapOf<String, Int>()

        var round = 0
        while (result.size < maxResults && round < 20) {
            var added = false
            scenicCategoryLanes.forEach { lane ->
                val cap = laneCap(lane.id, maxResults)
                if ((laneCounts[lane.id] ?: 0) >= cap) return@forEach
                byLane[lane.id]?.getOrNull(round)?.let { candidate ->
                    if (result.size < maxResults && result.none { it.id == candidate.id }) {
                        result += candidate
                        laneCounts[lane.id] = (laneCounts[lane.id] ?: 0) + 1
                        added = true
                    }
                }
            }
            if (!added) break
            round++
        }

        // Spare capacity still respects per-lane caps. This is what prevents a long named
        // river or a dense park region from turning the map into one-symbol wallpaper.
        deduped.forEach { candidate ->
            if (result.size >= maxResults) return@forEach
            val lane = scenicCategoryLaneFor(candidate)
            val cap = laneCap(lane.id, maxResults)
            if ((laneCounts[lane.id] ?: 0) < cap && result.none { it.id == candidate.id }) {
                result += candidate
                laneCounts[lane.id] = (laneCounts[lane.id] ?: 0) + 1
            }
        }
        return result.take(maxResults)
    }

    private fun laneCap(laneId: String, maxResults: Int): Int {
        if (maxResults <= 40) return 4
        val base = if (maxResults <= 140) 9 else 15
        return when (laneId) {
            "lakes-rivers" -> min(9, base)
            "forests-woodland" -> min(9, base)
            "parks-gardens" -> min(11, base)
            "peaks-landmarks" -> min(12, base)
            "restaurants-cafes" -> if (maxResults > 140) 20 else base
            else -> base
        }
    }

    private fun samePlace(a: ScenePointUi, b: ScenePointUi): Boolean {
        if (a.id == b.id) return true
        if (a.kind != b.kind) return false
        val sameName = a.name.trim().equals(b.name.trim(), ignoreCase = true)
        val distance = haversineMeters(a.point, b.point)
        if (sameName) {
            // Rivers are represented by many OSM ways. For POI purposes one named river is
            // one scenic location, not a row of identical droplet markers.
            if (a.subtype == "river" && b.subtype == "river") return true
            val repeatedArea = setOf("lake", "forest", "protected_area", "national_park", "nature_reserve")
            if (a.subtype in repeatedArea && b.subtype in repeatedArea && distance < 4_000) return true
            if (distance < 350) return true
        }
        return a.subtype == b.subtype && distance < 30
    }

    private fun execute(query: String, deep: Boolean): JSONArray {
        val encodedBody = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        var lastError: Throwable? = null
        val start = Math.floorMod(query.hashCode(), endpoints.size)
        val orderedEndpoints = endpoints.indices.map { endpoints[(start + it) % endpoints.size] }

        for (endpoint in orderedEndpoints) {
            try {
                return executePost(endpoint, encodedBody, deep)
            } catch (error: Throwable) {
                lastError = error
            }
            if (encodedQuery.length < 6_500) {
                try {
                    return executeGet(endpoint, encodedQuery, deep)
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }
        throw lastError ?: IllegalStateException("Precision POI discovery unavailable")
    }

    private fun executePost(endpoint: String, encodedBody: String, deep: Boolean): JSONArray {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = if (deep) 3_500 else 2_500
            readTimeout = if (deep) 15_000 else 9_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
        }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(encodedBody) }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun executeGet(endpoint: String, encodedQuery: String, deep: Boolean): JSONArray {
        val connection = (URL("$endpoint?data=$encodedQuery").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = if (deep) 3_500 else 2_500
            readTimeout = if (deep) 15_000 else 9_000
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
        val natural = tags.optString("natural").lowercase(Locale.ROOT)
        val leisure = tags.optString("leisure").lowercase(Locale.ROOT)
        val boundary = tags.optString("boundary").lowercase(Locale.ROOT)
        val waterway = tags.optString("waterway").lowercase(Locale.ROOT)
        val manMade = tags.optString("man_made").lowercase(Locale.ROOT)
        val building = tags.optString("building").lowercase(Locale.ROOT)
        val landuse = tags.optString("landuse").lowercase(Locale.ROOT)
        val geological = tags.optString("geological").lowercase(Locale.ROOT)

        return when {
            tourism == "viewpoint" -> "viewpoint"
            tourism == "museum" -> "museum"
            tourism == "artwork" -> "artwork"
            tourism == "gallery" -> "gallery"
            tourism == "zoo" -> "zoo"
            tourism == "theme_park" -> "theme_park"
            tourism == "aquarium" -> "attraction"
            tourism == "attraction" -> "attraction"

            amenity in setOf("arts_centre", "theatre") || tags.optString("artwork_type").isNotBlank() -> "artwork"
            amenity == "restaurant" -> "restaurant"
            amenity == "cafe" -> "cafe"
            amenity in setOf("biergarten", "food_court") -> "restaurant"
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
            historic == "archaeological_site" -> "archaeological_site"
            historic == "battlefield" -> "battlefield"
            historic == "ruins" -> "ruins"
            historic == "memorial" -> "memorial"
            historic == "monument" -> "monument"
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
            natural == "wood" -> "forest"
            natural in setOf("cliff", "ridge") -> "natural_landmark"
            natural in setOf("peak", "cape", "stone", "rock") -> natural
            geological.isNotBlank() -> "geological"
            landuse == "forest" -> "forest"

            leisure == "nature_reserve" -> "nature_reserve"
            leisure == "park" -> "park"
            leisure == "garden" -> "garden"
            boundary == "protected_area" -> "protected_area"
            boundary == "national_park" -> "national_park"
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

    private fun preferredName(tags: JSONObject): String =
        tags.optString("name:de").ifBlank { tags.optString("name") }

    private fun relevance(kind: StopKind, rawType: String, tags: JSONObject): Double {
        var value = when (kind) {
            StopKind.VIEWPOINT -> 1.00
            StopKind.MONUMENT -> 0.98
            StopKind.MUSEUM -> 0.96
            StopKind.FOOD -> 0.92
            StopKind.ARCHITECTURE -> 0.88
            StopKind.ART -> 0.86
            StopKind.WORSHIP -> 0.84
            StopKind.WATER -> 0.82
            StopKind.NATURE -> 0.80
            StopKind.PARK -> 0.78
            StopKind.SCENIC -> 0.83
            else -> 0.60
        }
        if (rawType in setOf("castle", "defensive_castle", "stately", "palace", "manor")) value += 0.20
        if (rawType in setOf("ruins", "archaeological_site", "battlefield")) value += 0.13
        if (rawType in setOf("waterfall", "lighthouse", "observation_tower")) value += 0.12
        if (rawType == "restaurant") value += 0.07
        if (tags.optString("wikipedia").isNotBlank() || tags.optString("wikidata").isNotBlank()) value += 0.12
        if (tags.optString("heritage").isNotBlank()) value += 0.08
        return value.coerceAtMost(1.35)
    }

    private fun metadataBonus(kind: StopKind, rawType: String, tags: JSONObject): Double {
        var bonus = 0.0
        if (tags.optString("wikipedia").isNotBlank()) bonus += 11.0
        if (tags.optString("wikidata").isNotBlank()) bonus += 9.0
        if (tags.optString("website").isNotBlank() || tags.optString("contact:website").isNotBlank()) bonus += 5.0
        if (tags.optString("heritage").isNotBlank()) bonus += 5.0
        if (kind == StopKind.FOOD) {
            if (rawType == "restaurant") bonus += 10.0
            if (tags.optString("cuisine").isNotBlank()) bonus += 6.0
            if (tags.optString("opening_hours").isNotBlank()) bonus += 5.0
            if (tags.optString("outdoor_seating") == "yes") bonus += 2.0
        }
        return bonus
    }

    private fun rationale(kind: StopKind, rawType: String, tags: JSONObject): String? = when (kind) {
        StopKind.FOOD -> buildList {
            add(if (rawType == "restaurant") "restaurant" else "café")
            tags.optString("cuisine").takeIf { it.isNotBlank() }?.let { add(it.replace(';', ',')) }
            if (tags.optString("opening_hours").isNotBlank()) add("opening hours mapped")
            if (tags.optString("website").isNotBlank() || tags.optString("contact:website").isNotBlank()) add("website available")
        }.joinToString(" · ")
        StopKind.MONUMENT, StopKind.MUSEUM, StopKind.ARCHITECTURE, StopKind.ART, StopKind.WORSHIP -> buildList {
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
        "waterfall", "beach", "lake" -> 25
        else -> kind.defaultDwellMinutes
    }

    private fun fallbackName(rawType: String): String = rawType
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase(Locale.ROOT) }

    private fun elementPoint(element: JSONObject): GeoPoint? {
        val lat = element.optDouble("lat", Double.NaN)
        val lon = element.optDouble("lon", Double.NaN)
        if (lat.isFinite() && lon.isFinite()) return GeoPoint(lat, lon)
        val center = element.optJSONObject("center") ?: return null
        val centerLat = center.optDouble("lat", Double.NaN)
        val centerLon = center.optDouble("lon", Double.NaN)
        return if (centerLat.isFinite() && centerLon.isFinite()) GeoPoint(centerLat, centerLon) else null
    }

    private fun splitRoute(
        route: List<GeoPoint>,
        maxSegmentMeters: Double,
        maxPointsPerSegment: Int,
    ): List<List<GeoPoint>> {
        if (route.size < 2) return emptyList()
        val segments = mutableListOf<List<GeoPoint>>()
        var current = mutableListOf(route.first())
        var accumulated = 0.0

        for (index in 1 until route.size) {
            val previous = route[index - 1]
            val point = route[index]
            accumulated += haversineMeters(previous, point)
            current += point
            if (accumulated >= maxSegmentMeters && index < route.lastIndex) {
                segments += simplifyByIndex(current, maxPointsPerSegment)
                current = mutableListOf(point)
                accumulated = 0.0
            }
        }
        if (current.size >= 2) segments += simplifyByIndex(current, maxPointsPerSegment)
        return segments.ifEmpty { listOf(simplifyByIndex(route, maxPointsPerSegment)) }
    }

    private fun simplifyByIndex(points: List<GeoPoint>, maxPoints: Int): List<GeoPoint> {
        if (points.size <= maxPoints) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1).coerceAtLeast(1)
        return (0 until maxPoints)
            .map { index -> points[(index * step).roundToInt().coerceIn(0, points.lastIndex)] }
            .distinct()
            .let { simplified -> if (simplified.lastOrNull() == points.last()) simplified else simplified + points.last() }
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
