package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Route-support-focused OSM discovery around planned day ends and e-bike range anchors.
 * Sleep/charge logistics remain separate from scenic scoring, while each overnight area also gets
 * a deliberately wide Scenic neighborhood search for evening/next-morning exploration.
 */
object JourneySupportDiscovery {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    suspend fun discover(
        route: RouteCandidateUi,
        vehicle: VehicleProfile,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        val overnight = JourneyStagePolicy.overnightBreaks(
            route.points,
            route.durationSeconds,
            vehicle,
            dwellMinutes = route.dwellMinutes,
        )
        val charging = JourneyStagePolicy.eBikeChargeAnchors(route.points, route.distanceMeters, vehicle)
        if (overnight.isEmpty() && charging.isEmpty()) return@withContext emptyList()

        coroutineScope {
            val overnightJobs = overnight.map { stage ->
                async(Dispatchers.IO) {
                    val support = withTimeoutOrNull(9_000) {
                        runCatching { discoverOvernight(stage, vehicle) }.getOrElse { emptyList() }
                    }.orEmpty().ifEmpty { listOf(overnightFallback(stage, vehicle)) }
                    val scenic = withTimeoutOrNull(10_500) {
                        runCatching { discoverScenicAroundOvernight(stage, enabledKinds) }.getOrElse { emptyList() }
                    }.orEmpty()
                    (support + scenic).distinctBy { it.id }
                }
            }
            val chargeJobs = charging.mapIndexed { index, point ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(7_500) {
                        runCatching { discoverCharging(index + 1, point) }.getOrElse { emptyList() }
                    }.orEmpty().ifEmpty { listOf(chargingFallback(index + 1, point, vehicle)) }
                }
            }
            (overnightJobs + chargeJobs).awaitAll().flatten().distinctBy { it.id }
        }
    }

    private suspend fun discoverScenicAroundOvernight(
        stage: JourneyStageBreak,
        enabledKinds: Set<StopKind>,
    ): List<ScenePointUi> {
        if (enabledKinds.isEmpty()) return emptyList()
        val tinyOffset = GeoPoint(stage.point.lat + 0.0015, stage.point.lon + 0.0015)
        val points = PrecisionRoutePoiDiscovery.discover(
            route = listOf(stage.point, tinyOffset),
            enabledKinds = enabledKinds,
            maxResults = 70,
            radiusMeters = 30_000,
            maxSamples = 2,
        )
        return points
            .filter { haversineMeters(stage.point, it.point) <= 32_000.0 }
            .take(48)
            .map { point ->
                point.copy(
                    rationale = listOfNotNull(
                        "Near day ${stage.day} overnight area",
                        point.rationale,
                    ).joinToString(" · "),
                )
            }
    }

    private fun discoverOvernight(stage: JourneyStageBreak, vehicle: VehicleProfile): List<ScenePointUi> {
        val radius = when (vehicle.kind) {
            VehicleKind.TRUCK, VehicleKind.COACH -> 24_000
            VehicleKind.CAMPER -> 22_000
            else -> 20_000
        }
        val statements = when (vehicle.kind) {
            VehicleKind.CAMPER -> listOf(
                "nwr(around:$radius,${stage.point.lat},${stage.point.lon})[tourism~\"^(camp_site|caravan_site)$\"][name];out center 36;",
                "nwr(around:$radius,${stage.point.lat},${stage.point.lon})[amenity=parking][motorhome=yes];out center 36;",
            )
            VehicleKind.TRUCK -> listOf(
                "nwr(around:$radius,${stage.point.lat},${stage.point.lon})[amenity=parking][hgv=yes];out center 40;",
                "nwr(around:$radius,${stage.point.lat},${stage.point.lon})[highway~\"^(services|rest_area)$\"];out center 40;",
            )
            VehicleKind.COACH -> listOf(
                "nwr(around:$radius,${stage.point.lat},${stage.point.lon})[tourism~\"^(hotel|motel|guest_house|hostel)$\"][name];out center 40;",
                "nwr(around:$radius,${stage.point.lat},${stage.point.lon})[amenity=parking][bus=yes];out center 32;",
            )
            VehicleKind.CAR, VehicleKind.MOTORCYCLE -> listOf(
                "nwr(around:$radius,${stage.point.lat},${stage.point.lon})[tourism~\"^(hotel|motel|guest_house|hostel|camp_site)$\"][name];out center 50;",
            )
            VehicleKind.BICYCLE -> listOf(
                "nwr(around:$radius,${stage.point.lat},${stage.point.lon})[tourism~\"^(hotel|guest_house|hostel|camp_site)$\"][name];out center 50;",
            )
        }
        val elements = execute("[out:json][timeout:11];${statements.joinToString("")}", stage.day)
        return parseOvernight(elements, stage, vehicle).take(10)
    }

    private fun parseOvernight(
        elements: JSONArray,
        stage: JourneyStageBreak,
        vehicle: VehicleProfile,
    ): List<ScenePointUi> {
        val points = mutableListOf<ScenePointUi>()
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val point = elementPoint(element) ?: continue
            val distance = haversineMeters(stage.point, point)
            val tourism = tags.optString("tourism").lowercase(Locale.ROOT)
            val highway = tags.optString("highway").lowercase(Locale.ROOT)
            val amenity = tags.optString("amenity").lowercase(Locale.ROOT)
            val subtype = when {
                tourism in setOf("hotel", "motel", "guest_house", "hostel") -> "overnight_hotel"
                tourism in setOf("camp_site", "caravan_site") -> "overnight_camp"
                vehicle.kind == VehicleKind.TRUCK || highway in setOf("services", "rest_area") -> "overnight_truck"
                amenity == "parking" -> "overnight_parking"
                else -> "overnight_option"
            }
            val fallbackLabel = when (subtype) {
                "overnight_camp" -> "Camping / caravan site"
                "overnight_truck" -> "Truck rest / parking"
                "overnight_parking" -> "Suitable parking"
                else -> "Overnight option"
            }
            val name = tags.optString("name").trim().ifBlank { fallbackLabel }
            points += ScenePointUi(
                id = "journey-night-${stage.day}-${element.optString("type")}-${element.optLong("id")}",
                name = name,
                kind = StopKind.SCENIC.name,
                subtype = subtype,
                point = point,
                relevance = 1.10,
                suggestionScore = (170.0 - distance / 300.0).coerceAtLeast(30.0),
                distanceFromRouteMeters = distance.roundToInt(),
                suggestedDwellMinutes = 8 * 60,
                url = tags.optString("website").ifBlank { tags.optString("contact:website") }
                    .takeIf { it.startsWith("http://") || it.startsWith("https://") },
                attribution = "© OpenStreetMap contributors",
                rationale = "Day ${stage.day} overnight option · near replanned daily travel end",
            )
        }
        return points.distinctBy { it.id }.sortedByDescending { it.suggestionScore }
    }

    private fun discoverCharging(anchorIndex: Int, anchor: GeoPoint): List<ScenePointUi> {
        val radius = 7_500
        val query = buildString {
            append("[out:json][timeout:9];")
            append("nwr(around:$radius,${anchor.lat},${anchor.lon})[amenity=charging_station];out center 40;")
            append("nwr(around:$radius,${anchor.lat},${anchor.lon})[service:bicycle:charging=yes];out center 32;")
        }
        val elements = execute(query, 100 + anchorIndex)
        val result = mutableListOf<ScenePointUi>()
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val point = elementPoint(element) ?: continue
            val distance = haversineMeters(anchor, point)
            val bikeSpecific = tags.optString("bicycle") == "yes" ||
                tags.optString("service:bicycle:charging") == "yes" ||
                tags.keys().asSequence().any { key -> key.startsWith("socket:") }
            if (!bikeSpecific) continue
            val name = tags.optString("name").trim().ifBlank { "E-bike charging" }
            result += ScenePointUi(
                id = "journey-ebike-$anchorIndex-${element.optString("type")}-${element.optLong("id")}",
                name = name,
                kind = StopKind.SCENIC.name,
                subtype = "ebike_charging",
                point = point,
                relevance = 1.20,
                suggestionScore = (175.0 - distance / 180.0).coerceAtLeast(30.0),
                distanceFromRouteMeters = distance.roundToInt(),
                suggestedDwellMinutes = 60,
                url = tags.optString("website").ifBlank { tags.optString("contact:website") }
                    .takeIf { it.startsWith("http://") || it.startsWith("https://") },
                attribution = "© OpenStreetMap contributors",
                rationale = "Bicycle/plug-compatible charging option · near battery-range anchor $anchorIndex",
            )
        }
        return result.distinctBy { it.id }.sortedByDescending { it.suggestionScore }.take(7)
    }

    private fun overnightFallback(stage: JourneyStageBreak, vehicle: VehicleProfile) = ScenePointUi(
        id = "journey-night-search-${stage.day}",
        name = when (vehicle.kind) {
            VehicleKind.CAMPER -> "Day ${stage.day}: overnight parking search area"
            VehicleKind.TRUCK -> "Day ${stage.day}: truck rest search area"
            else -> "Day ${stage.day}: overnight search area"
        },
        kind = StopKind.SCENIC.name,
        subtype = "overnight_search",
        point = stage.point,
        relevance = 0.90,
        suggestionScore = 110.0,
        suggestedDwellMinutes = 8 * 60,
        attribution = "Journey planning anchor",
        rationale = "Daily itinerary limit reached here · search nearby overnight options and Scenic POIs",
    )

    private fun chargingFallback(anchorIndex: Int, point: GeoPoint, vehicle: VehicleProfile) = ScenePointUi(
        id = "journey-ebike-search-$anchorIndex",
        name = "E-bike charge search area $anchorIndex",
        kind = StopKind.SCENIC.name,
        subtype = "ebike_charge_search",
        point = point,
        relevance = 0.95,
        suggestionScore = 120.0,
        suggestedDwellMinutes = 60,
        attribution = "Journey planning anchor",
        rationale = "Configured ${vehicle.eBikeRangeKm.roundToInt()} km range minus ${vehicle.eBikeReservePercent}% reserve reaches this area",
    )

    private fun execute(query: String, seed: Int): JSONArray {
        val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        var lastError: Throwable? = null
        for (attempt in endpoints.indices) {
            val endpoint = endpoints[Math.floorMod(seed + attempt, endpoints.size)]
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_200
                readTimeout = 5_500
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} journey-support")
            }
            try {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
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
        throw lastError ?: IllegalStateException("Journey support discovery unavailable")
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
