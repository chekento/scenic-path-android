package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.*

/**
 * Physical-device development fallback while the Scenic Path backend is not yet public.
 *
 * Uses the FOSSGIS Valhalla demo service for OSM-native routing and a deliberately small
 * Overpass query for scene-point discovery. Public services are development-only; the
 * production app will use Scenic Path controlled/contracted endpoints.
 */
object OsmScenicRoutingFallback {
    private const val VALHALLA_URL = "https://valhalla.openstreetmap.de"
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"

    suspend fun plan(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi = withContext(Dispatchers.IO) {
        val effective = preferences.forCharacter(plan.routeCharacter)
        val fixedStops = plan.stops.mapNotNull { it.point }

        val baseline = requestValhalla(
            locations = listOf(origin) + fixedStops + destination,
            avoidMotorways = false,
            avoidTolls = effective.avoidTolls,
        )

        val initialScenic = requestValhalla(
            locations = listOf(origin) + fixedStops + destination,
            avoidMotorways = effective.avoidMotorways,
            avoidTolls = effective.avoidTolls,
        )

        val discovered = if (plan.autoSuggestStops) {
            runCatching {
                discoverScenePoints(
                    route = initialScenic.points,
                    enabledKinds = plan.enabledSceneKinds,
                )
            }.getOrElse { emptyList() }
        } else emptyList()

        val autoStops = selectAutoStops(discovered, plan, effective)
        val scenicWithStops = if (autoStops.isNotEmpty()) {
            runCatching {
                requestValhalla(
                    locations = listOf(origin) + fixedStops + autoStops.map { it.point } + destination,
                    avoidMotorways = effective.avoidMotorways,
                    avoidTolls = effective.avoidTolls,
                )
            }.getOrNull()
        } else null

        val selectedScenic = scenicWithStops
            ?.takeIf { candidate ->
                val travelExtraMinutes = max(0.0, (candidate.durationSeconds - baseline.durationSeconds) / 60.0)
                val dwellMinutes = autoStops.sumOf { it.suggestedDwellMinutes }
                travelExtraMinutes + dwellMinutes <= effective.maxExtraMinutes + 1.0
            }
            ?: initialScenic

        val extraMinutes = max(0.0, (selectedScenic.durationSeconds - baseline.durationSeconds) / 60.0)
        val scenicScore = debugScenicScore(
            avoidMotorways = effective.avoidMotorways,
            route = selectedScenic.points,
            scenePoints = discovered,
        )
        val signals = buildList {
            if (effective.avoidMotorways) add("motorwayAvoidance")
            if (autoStops.isNotEmpty() && scenicWithStops != null) add("autoHighlights")
            if (discovered.any { it.kind == StopKind.VIEWPOINT.name }) add("viewpoints")
            if (discovered.any { it.kind == StopKind.WATER.name }) add("water")
            if (discovered.any { it.kind == StopKind.NATURE.name || it.kind == StopKind.PARK.name }) add("forest")
            if (discovered.any { it.kind == StopKind.MONUMENT.name }) add("monuments")
        }

        val scenicCandidate = RouteCandidateUi(
            id = "osm-scenic-device",
            character = plan.routeCharacter.name,
            distanceMeters = selectedScenic.distanceMeters,
            durationSeconds = selectedScenic.durationSeconds,
            scenicScore = scenicScore,
            extraMinutes = extraMinutes,
            points = selectedScenic.points,
            provider = "Valhalla · OpenStreetMap development",
            scenePoints = discovered.take(maxOf(6, plan.preferencesDisplayLimit())),
            strongestSignals = signals.take(4),
            isPreviewFallback = false,
        )

        val directCandidate = RouteCandidateUi(
            id = "osm-direct-device",
            character = RouteCharacter.DIRECT.name,
            distanceMeters = baseline.distanceMeters,
            durationSeconds = baseline.durationSeconds,
            scenicScore = 0.0,
            extraMinutes = 0.0,
            points = baseline.points,
            provider = "Valhalla · OpenStreetMap development",
            isPreviewFallback = false,
        )

        val candidates = when (plan.routeCharacter) {
            RouteCharacter.DIRECT -> listOf(directCandidate, scenicCandidate).distinctBy { routeKey(it) }
            else -> listOf(scenicCandidate, directCandidate).distinctBy { routeKey(it) }
        }

        RoutePlanUi(
            candidates = candidates,
            baselineDurationSeconds = baseline.durationSeconds,
            baselineDistanceMeters = baseline.distanceMeters,
            note = buildString {
                append("OSM development route via Valhalla")
                if (effective.avoidMotorways) append(" · motorway avoidance active")
                if (autoStops.isNotEmpty() && scenicWithStops != null) {
                    append(" · ${autoStops.size} scenic waypoint")
                    if (autoStops.size != 1) append("s")
                    append(" included")
                } else if (discovered.isNotEmpty()) {
                    append(" · ${discovered.size} optional highlights found")
                }
            },
        )
    }

    private data class RawRoute(
        val distanceMeters: Double,
        val durationSeconds: Double,
        val points: List<GeoPoint>,
    )

    private fun requestValhalla(
        locations: List<GeoPoint>,
        avoidMotorways: Boolean,
        avoidTolls: Boolean,
    ): RawRoute {
        val body = JSONObject().apply {
            put("locations", JSONArray().apply {
                locations.forEachIndexed { index, point ->
                    put(JSONObject().apply {
                        put("lat", point.lat)
                        put("lon", point.lon)
                        put("type", if (index == 0 || index == locations.lastIndex) "break" else "break")
                    })
                }
            })
            put("costing", "auto")
            put("costing_options", JSONObject().put("auto", JSONObject().apply {
                put("use_highways", if (avoidMotorways) 0.0 else 1.0)
                put("use_tolls", if (avoidTolls) 0.0 else 0.5)
                put("use_ferry", 0.35)
            }))
            put("directions_options", JSONObject().put("units", "kilometers").put("language", "de-DE"))
        }

        val connection = (URL("$VALHALLA_URL/route").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
            outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        }

        try {
            val text = connection.readTextOrThrow()
            val response = JSONObject(text)
            val trip = response.optJSONObject("trip") ?: error("Valhalla returned no trip")
            val summary = trip.optJSONObject("summary") ?: error("Valhalla returned no summary")
            val legs = trip.optJSONArray("legs") ?: JSONArray()
            val points = buildList {
                for (index in 0 until legs.length()) {
                    val encoded = legs.optJSONObject(index)?.optString("shape").orEmpty()
                    if (encoded.isBlank()) continue
                    val decoded = decodePolyline6(encoded)
                    if (isNotEmpty() && decoded.isNotEmpty() && last() == decoded.first()) {
                        addAll(decoded.drop(1))
                    } else addAll(decoded)
                }
            }
            if (points.size < 2) error("Valhalla route shape is empty")
            return RawRoute(
                distanceMeters = summary.optDouble("length", 0.0) * 1000.0,
                durationSeconds = summary.optDouble("time", 0.0),
                points = points,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun discoverScenePoints(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
    ): List<ScenePointUi> {
        if (route.size < 2) return emptyList()
        val samples = routeSamples(route, 4)
        val aroundBlocks = samples.joinToString("\n") { p ->
            """
            nwr(around:7000,${p.lat},${p.lon})[tourism=viewpoint];
            nwr(around:7000,${p.lat},${p.lon})[tourism=museum];
            nwr(around:7000,${p.lat},${p.lon})[tourism=artwork];
            nwr(around:7000,${p.lat},${p.lon})[tourism=attraction];
            nwr(around:7000,${p.lat},${p.lon})[historic];
            nwr(around:7000,${p.lat},${p.lon})[natural~"^(peak|cape|waterfall|beach)$"];
            nwr(around:7000,${p.lat},${p.lon})[leisure~"^(park|garden)$"];
            nwr(around:7000,${p.lat},${p.lon})[amenity=place_of_worship][historic];
            nwr(around:7000,${p.lat},${p.lon})[man_made~"^(tower|lighthouse)$"];
            nwr(around:7000,${p.lat},${p.lon})[bridge=yes][name];
            """.trimIndent()
        }
        val query = "[out:json][timeout:12];(\n$aroundBlocks\n);out center tags 100;"
        val encoded = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        val connection = (URL(OVERPASS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
            outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(encoded) }
        }

        try {
            val response = JSONObject(connection.readTextOrThrow())
            val elements = response.optJSONArray("elements") ?: JSONArray()
            val routeForDistance = routeSamples(route, 70)
            val parsed = buildList {
                for (index in 0 until elements.length()) {
                    val element = elements.optJSONObject(index) ?: continue
                    val tags = element.optJSONObject("tags") ?: JSONObject()
                    val point = elementPoint(element) ?: continue
                    val rawType = rawSceneType(tags) ?: continue
                    val kind = sceneKindForRawType(rawType)
                    if (kind == StopKind.FOOD || (kind != StopKind.SCENIC && kind !in enabledKinds)) continue
                    val name = tags.optString("name").ifBlank { fallbackName(rawType) }
                    val distance = routeForDistance.minOfOrNull { haversineMeters(point, it) } ?: continue
                    if (distance > 8_000) continue
                    val relevance = sceneRelevance(kind, rawType, tags)
                    add(
                        ScenePointUi(
                            id = "osm-${element.optString("type")}-${element.optLong("id")}",
                            name = name,
                            kind = kind.name,
                            subtype = rawType,
                            point = point,
                            relevance = relevance,
                            suggestionScore = (relevance * 100.0 - distance / 180.0).coerceAtLeast(1.0),
                            distanceFromRouteMeters = distance.roundToInt(),
                            suggestedDwellMinutes = kind.defaultDwellMinutes,
                            url = tags.optString("website").takeIf { it.isNotBlank() },
                            attribution = "© OpenStreetMap contributors",
                        )
                    )
                }
            }

            val selected = mutableListOf<ScenePointUi>()
            parsed
                .distinctBy { it.name.lowercase(Locale.ROOT) }
                .sortedByDescending { it.suggestionScore }
                .forEach { candidate ->
                    val tooClose = selected.any {
                        it.kind == candidate.kind && haversineMeters(it.point, candidate.point) < 1_200
                    }
                    if (!tooClose) selected += candidate
                }
            return selected.take(18)
        } finally {
            connection.disconnect()
        }
    }

    private fun selectAutoStops(
        suggestions: List<ScenePointUi>,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): List<ScenePointUi> {
        if (!plan.autoSuggestStops || suggestions.isEmpty()) return emptyList()
        val maximum = when {
            preferences.maxExtraMinutes >= 150 -> min(4, preferences.maxStops)
            preferences.maxExtraMinutes >= 90 -> min(3, preferences.maxStops)
            preferences.maxExtraMinutes >= 45 -> min(2, preferences.maxStops)
            preferences.maxExtraMinutes >= 20 -> min(1, preferences.maxStops)
            else -> 0
        }
        if (maximum <= 0) return emptyList()

        var remaining = preferences.maxExtraMinutes
        val picked = mutableListOf<ScenePointUi>()
        suggestions.forEach { candidate ->
            if (picked.size >= maximum) return@forEach
            val dwell = candidate.suggestedDwellMinutes
            if (dwell + 10 > remaining) return@forEach
            val diverse = picked.none { it.kind == candidate.kind } || picked.size >= maximum - 1
            if (!diverse) return@forEach
            picked += candidate
            remaining -= dwell
        }
        return picked
    }

    private fun rawSceneType(tags: JSONObject): String? = when {
        tags.optString("amenity") == "place_of_worship" -> "worship"
        tags.optString("tourism") == "viewpoint" -> "viewpoint"
        tags.optString("tourism") == "museum" -> "museum"
        tags.optString("tourism") == "artwork" -> "artwork"
        tags.optString("tourism") == "attraction" -> "attraction"
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
            StopKind.WATER -> 0.94
            StopKind.NATURE -> 0.91
            StopKind.MONUMENT -> 0.88
            StopKind.MUSEUM -> 0.82
            StopKind.PARK -> 0.78
            StopKind.ARCHITECTURE -> 0.76
            StopKind.ART -> 0.73
            StopKind.WORSHIP -> 0.68
            StopKind.SCENIC -> 0.66
            else -> 0.55
        }
        if (rawType == "castle" || rawType == "waterfall") score += 0.10
        if (tags.optString("wikipedia").isNotBlank() || tags.optString("wikidata").isNotBlank()) score += 0.10
        return score.coerceIn(0.0, 1.2)
    }

    private fun debugScenicScore(
        avoidMotorways: Boolean,
        route: List<GeoPoint>,
        scenePoints: List<ScenePointUi>,
    ): Double {
        val bendScore = if (route.size >= 4) {
            val samples = routeSamples(route, 25)
            var turns = 0.0
            for (i in 1 until samples.lastIndex) {
                val a = bearing(samples[i - 1], samples[i])
                val b = bearing(samples[i], samples[i + 1])
                var delta = abs(a - b)
                if (delta > 180) delta = 360 - delta
                turns += min(delta, 90.0) / 90.0
            }
            (turns / max(1, samples.size - 2)).coerceIn(0.0, 1.0)
        } else 0.0
        val poiScore = (scenePoints.take(6).sumOf { it.relevance } / 6.0).coerceIn(0.0, 1.0)
        return (35 + bendScore * 30 + poiScore * 25 + if (avoidMotorways) 10 else 0).coerceIn(0.0, 100.0)
    }

    private fun routeSamples(route: List<GeoPoint>, maxSamples: Int): List<GeoPoint> {
        if (route.size <= maxSamples) return route
        val step = (route.size - 1).toDouble() / (maxSamples - 1).coerceAtLeast(1)
        return (0 until maxSamples).map { index -> route[(index * step).roundToInt().coerceIn(0, route.lastIndex)] }
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

    private fun decodePolyline6(encoded: String): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            fun nextDelta(): Int {
                var result = 0
                var shift = 0
                var b: Int
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && index < encoded.length)
                return if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            }
            lat += nextDelta()
            lon += nextDelta()
            points += GeoPoint(lat / 1_000_000.0, lon / 1_000_000.0)
        }
        return points
    }

    private fun HttpURLConnection.readTextOrThrow(): String {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("HTTP $code ${text.take(180)}")
        return text
    }

    private fun routeKey(route: RouteCandidateUi): String =
        "${(route.distanceMeters / 250).roundToInt()}:${(route.durationSeconds / 60).roundToInt()}"

    private fun TripPlan.preferencesDisplayLimit(): Int = maxOf(6, maxStopsSafe())
    private fun TripPlan.maxStopsSafe(): Int = 6

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earth * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}
