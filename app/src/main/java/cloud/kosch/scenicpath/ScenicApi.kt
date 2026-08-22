package cloud.kosch.scenicpath

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class PlaceSuggestion(
    val id: String,
    val title: String,
    val subtitle: String,
    val point: GeoPoint,
)

data class ScenePointUi(
    val id: String,
    val name: String,
    val kind: String,
    val subtype: String?,
    val point: GeoPoint,
    val relevance: Double,
    val suggestionScore: Double = relevance,
    val distanceFromRouteMeters: Int = 0,
    val suggestedDwellMinutes: Int = 20,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val openNow: Boolean? = null,
    val url: String? = null,
    val attribution: String? = null,
    val includedInRoute: Boolean = false,
    val personalMatch: Double? = null,
    val rationale: String? = null,
    val estimatedDetourMinutes: Double? = null,
)

data class RouteCandidateUi(
    val id: String,
    val character: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val scenicScore: Double,
    val extraMinutes: Double,
    val points: List<GeoPoint>,
    val provider: String,
    val scenePoints: List<ScenePointUi> = emptyList(),
    val strongestSignals: List<String> = emptyList(),
    val isPreviewFallback: Boolean = false,
    val variantLabel: String? = null,
    val experienceScore: Double = scenicScore,
    val autoStopIds: List<String> = emptyList(),
    val driveExtraMinutes: Double = extraMinutes,
    val dwellMinutes: Int = 0,
    val totalExtraMinutes: Double = extraMinutes,
    val corridorRadiusKm: Double = 0.0,
    val dataConfidence: Double = 0.0,
)

data class RoutePlanUi(
    val candidates: List<RouteCandidateUi>,
    val baselineDurationSeconds: Double? = null,
    val baselineDistanceMeters: Double? = null,
    val note: String? = null,
)

object ScenicApi {
    private val baseUrl: String get() = BuildConfig.SCENIC_API_BASE_URL.trimEnd('/')

    suspend fun searchPlaces(
        context: Context,
        query: String,
        bias: GeoPoint? = null,
    ): List<PlaceSuggestion> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()

        val backend = runCatching { searchBackend(query.trim(), bias) }.getOrNull().orEmpty()
        if (backend.isNotEmpty()) return@withContext backend

        searchDeviceGeocoder(context, query.trim())
    }

    suspend fun planRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): Result<RoutePlanUi> = withContext(Dispatchers.IO) {
        val effectivePreferences = preferences.forCharacter(plan.routeCharacter)
        runCatching { planBackend(origin, destination, plan, effectivePreferences) }
            .recoverCatching { backendError ->
                if (!BuildConfig.DEBUG) throw backendError
                ScenicJourneyOptimizer.plan(origin, destination, plan, effectivePreferences)
            }
    }

    private fun searchBackend(query: String, bias: GeoPoint?): List<PlaceSuggestion> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val biasQuery = bias?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: ""
        val connection = open("$baseUrl/v1/search?q=$encoded$biasQuery", "GET")
        return connection.useJson { body ->
            val results = body.optJSONArray("results") ?: JSONArray()
            buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val position = item.optJSONObject("position") ?: continue
                    val lat = position.optDouble("lat", Double.NaN)
                    val lon = position.optDouble("lon", Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite()) continue
                    add(
                        PlaceSuggestion(
                            id = item.optString("id", "$lat,$lon"),
                            title = item.optString("title", query),
                            subtitle = item.optString("subtitle", ""),
                            point = GeoPoint(lat, lon),
                        )
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun searchDeviceGeocoder(context: Context, query: String): List<PlaceSuggestion> {
        if (!Geocoder.isPresent()) return emptyList()
        return runCatching {
            Geocoder(context, Locale.getDefault())
                .getFromLocationName(query, 8)
                .orEmpty()
                .mapIndexed { index, address ->
                    val title = listOfNotNull(address.featureName, address.locality)
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { query }
                    val subtitle = (0..address.maxAddressLineIndex)
                        .mapNotNull { address.getAddressLine(it) }
                        .joinToString(" · ")
                    PlaceSuggestion(
                        id = "device-$index-${address.latitude}-${address.longitude}",
                        title = title,
                        subtitle = subtitle,
                        point = GeoPoint(address.latitude, address.longitude),
                    )
                }
                .distinctBy { "%.5f,%.5f".format(Locale.US, it.point.lat, it.point.lon) }
        }.getOrElse { emptyList() }
    }

    private fun planBackend(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi {
        val body = JSONObject().apply {
            put("origin", origin.toJson())
            put("destination", destination.toJson())
            put("mode", plan.mode.name)
            put("routeCharacter", plan.routeCharacter.name)
            put("autoSuggestStops", plan.autoSuggestStops)
            put("preserveScenicIntentOnReroute", plan.preserveScenicIntentOnReroute)
            put("flexibleStopOrder", plan.flexibleStopOrder)
            put("enabledSceneKinds", JSONArray(plan.enabledSceneKinds.map { it.name }))
            put("preferences", preferences.toJson())
            put("stops", JSONArray().apply {
                plan.stops.forEach { stop ->
                    stop.point?.let { point ->
                        put(JSONObject().apply {
                            put("id", stop.id)
                            put("name", stop.name)
                            put("kind", stop.kind.name)
                            stop.subtype?.let { put("subtype", it) }
                            put("locked", stop.locked)
                            put("mustVisit", stop.mustVisit)
                            put("dwellMinutes", stop.dwellMinutes)
                            put("position", point.toJson())
                        })
                    }
                }
            })
        }

        val connection = open("$baseUrl/v1/plan", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        }
        return connection.useJson(::parsePlan)
    }

    private fun parsePlan(response: JSONObject): RoutePlanUi {
        val baseline = response.optJSONObject("baseline")
        val candidatesJson = response.optJSONArray("candidates") ?: JSONArray()
        val candidates = buildList {
            for (index in 0 until candidatesJson.length()) {
                val item = candidatesJson.optJSONObject(index) ?: continue
                val points = parsePoints(item.optJSONArray("points"))
                val autoStopIds = buildList {
                    val ids = item.optJSONArray("autoStopIds") ?: JSONArray()
                    for (idIndex in 0 until ids.length()) {
                        ids.optString(idIndex).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                val scenePoints = buildList {
                    val highlights = item.optJSONArray("scenePoints") ?: JSONArray()
                    for (highlightIndex in 0 until highlights.length()) {
                        val highlight = highlights.optJSONObject(highlightIndex) ?: continue
                        val pointObject = highlight.optJSONObject("point") ?: continue
                        val lat = pointObject.optDouble("lat", Double.NaN)
                        val lon = pointObject.optDouble("lon", Double.NaN)
                        if (!lat.isFinite() || !lon.isFinite()) continue
                        val id = highlight.optString("id", "highlight-$highlightIndex")
                        add(
                            ScenePointUi(
                                id = id,
                                name = highlight.optString("name", "Scenic highlight"),
                                kind = highlight.optString("kind", "SCENIC"),
                                subtype = highlight.optString("subtype").takeIf { it.isNotBlank() },
                                point = GeoPoint(lat, lon),
                                relevance = highlight.optDouble("relevance", 0.5),
                                suggestionScore = highlight.optDouble("suggestionScore", highlight.optDouble("relevance", 0.5)),
                                distanceFromRouteMeters = highlight.optInt("distanceFromRouteMeters", 0),
                                suggestedDwellMinutes = highlight.optInt("suggestedDwellMinutes", 20),
                                rating = highlight.optDoubleOrNull("rating"),
                                ratingCount = highlight.optIntOrNull("ratingCount"),
                                openNow = if (highlight.has("openNow") && !highlight.isNull("openNow")) highlight.optBoolean("openNow") else null,
                                url = highlight.optString("url").takeIf { it.isNotBlank() },
                                attribution = highlight.optString("attribution").takeIf { it.isNotBlank() },
                                includedInRoute = highlight.optBoolean("includedInRoute", id in autoStopIds),
                                personalMatch = highlight.optDoubleOrNull("personalMatch"),
                                rationale = highlight.optString("rationale").takeIf { it.isNotBlank() },
                                estimatedDetourMinutes = highlight.optDoubleOrNull("estimatedDetourMinutes"),
                            )
                        )
                    }
                }
                val strongestSignals = buildList {
                    val directSignals = item.optJSONArray("strongestSignals")
                    if (directSignals != null) {
                        for (signalIndex in 0 until directSignals.length()) {
                            directSignals.optString(signalIndex).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    } else {
                        val signals = item.optJSONObject("corridor")
                            ?.optJSONObject("diagnostics")
                            ?.optJSONArray("strongestSignals") ?: JSONArray()
                        for (signalIndex in 0 until signals.length()) {
                            signals.optString(signalIndex).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                val scenicScore = item.optDouble("scenicScore", 0.0)
                val extraMinutes = item.optDouble("extraMinutes", 0.0)
                add(
                    RouteCandidateUi(
                        id = item.optString("id", "route-$index"),
                        character = item.optString("character", "BEAUTIFUL"),
                        distanceMeters = item.optDouble("distanceMeters", 0.0),
                        durationSeconds = item.optDouble("durationSeconds", 0.0),
                        scenicScore = scenicScore,
                        extraMinutes = extraMinutes,
                        points = points,
                        provider = item.optString("provider", "Scenic Path"),
                        scenePoints = scenePoints,
                        strongestSignals = strongestSignals,
                        variantLabel = item.optString("variantLabel").takeIf { it.isNotBlank() },
                        experienceScore = item.optDouble("experienceScore", scenicScore),
                        autoStopIds = autoStopIds,
                        driveExtraMinutes = item.optDouble("driveExtraMinutes", extraMinutes),
                        dwellMinutes = item.optInt("dwellMinutes", 0),
                        totalExtraMinutes = item.optDouble("totalExtraMinutes", extraMinutes),
                        corridorRadiusKm = item.optDouble("corridorRadiusKm", 0.0),
                        dataConfidence = item.optDouble("dataConfidence", 0.0),
                    )
                )
            }
        }
        return RoutePlanUi(
            candidates = candidates,
            baselineDurationSeconds = baseline?.optDouble("durationSeconds"),
            baselineDistanceMeters = baseline?.optDouble("distanceMeters"),
            note = response.optString("note").takeIf { it.isNotBlank() },
        )
    }

    private fun parsePoints(pointsJson: JSONArray?): List<GeoPoint> = buildList {
        val source = pointsJson ?: JSONArray()
        for (pointIndex in 0 until source.length()) {
            val p = source.optJSONObject(pointIndex) ?: continue
            val lat = p.optDouble("lat", Double.NaN)
            val lon = p.optDouble("lon", Double.NaN)
            if (lat.isFinite() && lon.isFinite()) add(GeoPoint(lat, lon))
        }
    }

    private fun open(url: String, method: String, timeoutMs: Int = 4_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME}")
        }

    private inline fun <T> HttpURLConnection.useJson(block: (JSONObject) -> T): T {
        try {
            val code = responseCode
            val stream = if (code in 200..299) inputStream else errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                error(message?.takeIf { it.isNotBlank() } ?: "HTTP $code")
            }
            return block(JSONObject(text.ifBlank { "{}" }))
        } finally {
            disconnect()
        }
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key, Double.NaN).takeIf { it.isFinite() } else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun GeoPoint.toJson() = JSONObject().apply {
    put("lat", lat)
    put("lon", lon)
}

private fun ScenicPreferences.toJson() = JSONObject().apply {
    put("maxExtraMinutes", maxExtraMinutes)
    put("maxExtraPercent", maxExtraPercent)
    put("maxStops", maxStops)
    put("minimumFoodRating", minimumFoodRating)
    put("minimumFoodReviewCount", minimumFoodReviewCount)
    put("onlyOpenFood", onlyOpenFood)
    put("avoidMotorways", avoidMotorways)
    put("avoidTolls", avoidTolls)
    put("windingness", windingness)
    put("hilliness", hilliness)
    put("weights", JSONObject().apply {
        put("beautifulRoads", weights.beautifulRoads.toDouble())
        put("forest", weights.forest.toDouble())
        put("water", weights.water.toDouble())
        put("mountains", weights.mountains.toDouble())
        put("viewpoints", weights.viewpoints.toDouble())
        put("culture", weights.culture.toDouble())
        put("monuments", weights.monuments.toDouble())
        put("museums", weights.museums.toDouble())
        put("art", weights.art.toDouble())
        put("worship", weights.worship.toDouble())
        put("architecture", weights.architecture.toDouble())
        put("parks", weights.parks.toDouble())
        put("food", weights.food.toDouble())
        put("scenicHighlights", weights.scenicHighlights.toDouble())
    })
}
