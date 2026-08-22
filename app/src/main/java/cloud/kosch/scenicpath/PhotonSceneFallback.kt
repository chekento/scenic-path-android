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
 * Fast OSM-backed route discovery used by the long-route planner.
 *
 * Photon remains the cheap first pass. When enabled, a small targeted Overpass rescue is
 * run only for categories Photon missed. That keeps long-route generation bounded while
 * still allowing castles, museums, monuments, worship, architecture and FOOD to enter the
 * actual auto-stop candidate pool.
 */
object PhotonSceneFallback {
    private const val ENDPOINT = "https://photon.komoot.io/reverse"

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int = 18,
        fast: Boolean = true,
        includeTargetedBackfill: Boolean = true,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2) return@withContext emptyList()
        val categories = categoriesFor(enabledKinds)
        if (categories.isEmpty()) return@withContext emptyList()

        val length = routeLengthMeters(route)
        val normalCount = when {
            length > 180_000 -> 6
            length > 70_000 -> 5
            else -> 3
        }
        val sampleCount = if (fast) min(6, normalCount) else normalCount
        val samples = routeSamples(route, sampleCount)
        val routeForDistance = routeSamples(route, 120)
        val found = linkedMapOf<String, ScenePointUi>()

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
                    val rawType = photonRawType(
                        properties.optString("osm_key"),
                        properties.optString("osm_value"),
                    ) ?: continue
                    val kind = sceneKindForRawType(rawType)
                    if (kind != StopKind.SCENIC && kind !in enabledKinds) continue

                    val point = GeoPoint(lat, lon)
                    val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: continue
                    if (distance > 13_000) continue

                    val name = properties.optString("name").ifBlank {
                        rawType.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    }
                    val osmType = properties.optString("osm_type").ifBlank { "X" }
                    val osmId = properties.optLong("osm_id", -1L)
                    val id = if (osmId >= 0) "photon-$osmType-$osmId" else "photon-${lat}-${lon}-${name.hashCode()}"
                    val relevance = relevance(kind, rawType)
                    val restaurantBonus = if (rawType == "restaurant") 9.0 else 0.0
                    val score = (relevance * 100.0 + restaurantBonus - distance / 280.0).coerceAtLeast(1.0)

                    found.putIfAbsent(
                        id,
                        ScenePointUi(
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
                            rationale = if (kind == StopKind.FOOD) {
                                "Route food candidate · verified ratings require the configured food provider"
                            } else null,
                        )
                    )
                }
            }
        }

        val photon = coverageSelect(found.values.toList(), enabledKinds, maxResults)
        if (!includeTargetedBackfill) return@withContext photon

        // Rescue only categories that Photon missed. Three broad route anchors are enough
        // for this planning-time pass; the post-route UI enrichment performs the deeper
        // 8-10 anchor search after the road is already visible.
        val missingKinds = enabledKinds
            .filter { kind -> kind.autoDiscoverable && photon.none { it.kind == kind.name } }
            .toSet()
        if (missingKinds.isEmpty()) return@withContext photon

        val rescue = runCatching {
            FastRoutePoiDiscovery.discoverTargetedOnly(
                route = route,
                enabledKinds = missingKinds,
                maxResults = maxOf(12, missingKinds.size * 3),
                radiusMeters = 22_000,
                maxSamples = 3,
                allowBackfill = false,
            )
        }.getOrElse { emptyList() }

        FastRoutePoiDiscovery.mergeResults(photon, rescue, enabledKinds, maxResults)
    }

    private fun coverageSelect(
        values: List<ScenePointUi>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
    ): List<ScenePointUi> {
        val deduped = values
            .distinctBy { "${it.name.lowercase(Locale.ROOT)}:${it.kind}" }
        val grouped = deduped
            .groupBy { it.kind }
            .mapValues { (_, value) -> value.sortedByDescending { it.suggestionScore } }
        val selected = mutableListOf<ScenePointUi>()

        prototypeSelectableSceneKinds.filter { it in enabledKinds }.forEach { kind ->
            grouped[kind.name]?.firstOrNull()?.let { candidate ->
                if (selected.size < maxResults) selected += candidate
            }
        }
        deduped.sortedByDescending { it.suggestionScore }.forEach { candidate ->
            if (selected.size < maxResults && selected.none { it.id == candidate.id }) selected += candidate
        }
        return selected.take(maxResults)
    }

    private fun query(
        sample: GeoPoint,
        categories: List<String>,
        fast: Boolean,
    ): org.json.JSONArray {
        val url = if (fast) {
            "$ENDPOINT?lon=${sample.lon}&lat=${sample.lat}&radius=20&limit=60&lang=de&layer=other"
        } else {
            val include = URLEncoder.encode(categories.joinToString(","), Charsets.UTF_8.name())
            "$ENDPOINT?lon=${sample.lon}&lat=${sample.lat}&radius=20&limit=30&lang=de&include=$include"
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
            add("osm.historic.archaeological_site")
        }
        if (StopKind.NATURE in enabledKinds) {
            add("osm.natural.peak")
            add("osm.natural.cape")
            add("osm.natural.stone")
        }
        if (StopKind.WATER in enabledKinds) {
            add("osm.natural.beach")
            add("osm.natural.water")
            add("osm.waterway.waterfall")
            add("osm.waterway.river")
        }
        if (StopKind.PARK in enabledKinds) {
            add("osm.leisure.park")
            add("osm.leisure.garden")
            add("osm.leisure.nature_reserve")
        }
        if (StopKind.WORSHIP in enabledKinds) add("osm.amenity.place_of_worship")
        if (StopKind.FOOD in enabledKinds) {
            add("osm.amenity.restaurant")
            add("osm.amenity.cafe")
        }
        if (StopKind.ARCHITECTURE in enabledKinds) {
            add("osm.man_made.lighthouse")
            add("osm.man_made.tower")
            add("osm.man_made.water_tower")
            add("osm.bridge.yes")
        }
        add("osm.tourism.attraction")
    }

    private fun photonRawType(key: String, value: String): String? = when {
        key == "tourism" && value in setOf("viewpoint", "museum", "artwork", "gallery", "attraction") -> value
        key == "amenity" && value == "arts_centre" -> "artwork"
        key == "amenity" && value == "place_of_worship" -> "worship"
        key == "amenity" && value == "restaurant" -> "restaurant"
        key == "amenity" && value == "cafe" -> "cafe"
        key == "historic" && value in setOf("castle", "manor", "palace", "fort", "ruins", "monument", "memorial", "archaeological_site") -> value
        key == "natural" && value in setOf("peak", "cape", "stone", "rock", "beach") -> value
        key == "natural" && value == "water" -> "lake"
        key == "waterway" && value == "waterfall" -> "waterfall"
        key == "waterway" && value == "river" -> "river"
        key == "leisure" && value in setOf("park", "garden", "nature_reserve") -> value
        key == "man_made" && value in setOf("lighthouse", "tower") -> value
        key == "man_made" && value == "water_tower" -> "tower"
        key == "bridge" && value == "yes" -> "bridge"
        else -> null
    }

    private fun relevance(kind: StopKind, rawType: String): Double {
        var score = when (kind) {
            StopKind.VIEWPOINT -> 1.0
            StopKind.MONUMENT -> 0.98
            StopKind.WATER -> 0.92
            StopKind.NATURE -> 0.88
            StopKind.MUSEUM -> 0.90
            StopKind.FOOD -> 0.86
            StopKind.PARK -> 0.78
            StopKind.ARCHITECTURE -> 0.80
            StopKind.ART -> 0.76
            StopKind.WORSHIP -> 0.72
            StopKind.SCENIC -> 0.65
            else -> 0.55
        }
        if (rawType in setOf("castle", "manor", "palace")) score += 0.14
        if (rawType == "restaurant") score += 0.07
        return score.coerceAtMost(1.2)
    }

    private fun dwellMinutes(kind: StopKind, rawType: String): Int = when (rawType) {
        "castle", "manor", "palace", "fort", "ruins" -> 30
        "restaurant" -> 55
        "cafe" -> 35
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
