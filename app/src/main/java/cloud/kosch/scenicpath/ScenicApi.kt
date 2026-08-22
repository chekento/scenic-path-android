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

data class RouteCandidateUi(
    val id: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val scenicScore: Double,
    val extraMinutes: Double,
    val points: List<GeoPoint>,
    val provider: String,
    val isPreviewFallback: Boolean = false,
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

        // Device Geocoder is a usability fallback for development/offline-backend testing.
        // Production search remains provider-backed through the Scenic Path backend.
        searchDeviceGeocoder(context, query.trim())
    }

    suspend fun planRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): Result<RoutePlanUi> = withContext(Dispatchers.IO) {
        runCatching { planBackend(origin, destination, plan, preferences) }
            .recoverCatching { backendError ->
                if (!BuildConfig.DEBUG) throw backendError
                debugOsrmFallback(origin, destination, plan)
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
        return connection.useJson { response -> parsePlan(response) }
    }

    private fun parsePlan(response: JSONObject): RoutePlanUi {
        val baseline = response.optJSONObject("baseline")
        val candidatesJson = response.optJSONArray("candidates") ?: JSONArray()
        val candidates = buildList {
            for (index in 0 until candidatesJson.length()) {
                val item = candidatesJson.optJSONObject(index) ?: continue
                val pointsJson = item.optJSONArray("points") ?: JSONArray()
                val points = buildList {
                    for (pointIndex in 0 until pointsJson.length()) {
                        val p = pointsJson.optJSONObject(pointIndex) ?: continue
                        val lat = p.optDouble("lat", Double.NaN)
                        val lon = p.optDouble("lon", Double.NaN)
                        if (lat.isFinite() && lon.isFinite()) add(GeoPoint(lat, lon))
                    }
                }
                add(
                    RouteCandidateUi(
                        id = item.optString("id", "route-$index"),
                        distanceMeters = item.optDouble("distanceMeters", 0.0),
                        durationSeconds = item.optDouble("durationSeconds", 0.0),
                        scenicScore = item.optDouble("scenicScore", 0.0),
                        extraMinutes = item.optDouble("extraMinutes", 0.0),
                        points = points,
                        provider = item.optString("provider", "Scenic Path"),
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

    /** Debug-only route preview. Never used in release builds. */
    private fun debugOsrmFallback(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
    ): RoutePlanUi {
        val coordinates = buildList {
            add(origin)
            plan.stops.mapNotNullTo(this) { it.point }
            add(destination)
        }.joinToString(";") { "${it.lon},${it.lat}" }

        val url = "https://router.project-osrm.org/route/v1/driving/$coordinates?overview=full&geometries=geojson&steps=false"
        val connection = open(url, "GET", timeoutMs = 7_000)
        return connection.useJson { response ->
            val route = response.optJSONArray("routes")?.optJSONObject(0)
                ?: error("No debug preview route returned")
            val coords = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: JSONArray()
            val points = buildList {
                for (i in 0 until coords.length()) {
                    val pair = coords.optJSONArray(i) ?: continue
                    if (pair.length() >= 2) add(GeoPoint(pair.optDouble(1), pair.optDouble(0)))
                }
            }
            RoutePlanUi(
                candidates = listOf(
                    RouteCandidateUi(
                        id = "debug-preview",
                        distanceMeters = route.optDouble("distance", 0.0),
                        durationSeconds = route.optDouble("duration", 0.0),
                        scenicScore = 0.0,
                        extraMinutes = 0.0,
                        points = points,
                        provider = "OSRM debug preview",
                        isPreviewFallback = true,
                    )
                ),
                note = "Debug preview only — ScenicScore requires the Scenic Path backend.",
            )
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
