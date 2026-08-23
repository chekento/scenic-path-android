package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v0.6 routing core used on physical/debug builds.
 *
 * Unlike the old long-route adapter this planner never creates artificial straight-line
 * intermediate anchors. It asks Valhalla to route on the real transport network first and,
 * for user waypoints, routes each consecutive leg independently. That removes the large
 * zig-zags caused when interpolated geographic anchors snapped to unrelated roads.
 *
 * Vehicle costing is part of the route request. Bicycle, motorcycle, car, motorhome, truck and
 * coach therefore use different networks/costs instead of sharing an automobile polyline.
 */
object VehicleAwareJourneyPlanner {
    private const val VALHALLA_URL = "https://valhalla1.openstreetmap.de"
    private const val CLIENT_ID = "scenic-path-android-dev"

    private data class RawRoute(
        val distanceMeters: Double,
        val durationSeconds: Double,
        val points: List<GeoPoint>,
    )

    suspend fun plan(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi = withContext(Dispatchers.IO) {
        val effective = preferences.forCharacter(plan.routeCharacter)
        val vehicle = effective.vehicle

        // A clean A→B route is the only ordering reference. No synthetic air-line anchor may
        // ever become a mandatory routing point.
        val baseNoStops = requestRoute(listOf(origin, destination), effective, scenic = false)
        val fixedStops = orderedFixedStops(plan, baseNoStops.points)
        val fixedPoints = fixedStops.mapNotNull { it.point }

        val direct = routeThrough(
            nodes = listOf(origin) + fixedPoints + destination,
            preferences = effective,
            scenic = false,
        )

        val scenic = if (plan.routeCharacter == RouteCharacter.DIRECT) {
            direct
        } else {
            runCatching {
                routeThrough(
                    nodes = listOf(origin) + fixedPoints + destination,
                    preferences = effective,
                    scenic = true,
                )
            }.getOrElse { direct }
        }

        val discovered = if (plan.autoSuggestStops && plan.enabledSceneKinds.isNotEmpty()) {
            withTimeoutOrNull(10_000) {
                runCatching {
                    FastRoutePoiDiscovery.discover(
                        route = scenic.points,
                        enabledKinds = plan.enabledSceneKinds,
                        maxResults = 96,
                    )
                }.getOrElse { emptyList() }
            }.orEmpty()
        } else emptyList()

        if (discovered.isNotEmpty()) ScenicPoiSharedState.publish(scenic.points, discovered)

        val fixedHighlights = fixedStops.mapNotNull { stop ->
            stop.point?.let { point ->
                ScenePointUi(
                    id = stop.id,
                    name = stop.name,
                    kind = stop.kind.name,
                    subtype = stop.subtype,
                    point = point,
                    relevance = 1.3,
                    suggestionScore = 300.0,
                    distanceFromRouteMeters = 0,
                    suggestedDwellMinutes = stop.dwellMinutes,
                    rating = stop.rating,
                    ratingCount = stop.ratingCount,
                    includedInRoute = true,
                    personalMatch = 100.0,
                    rationale = "fixed waypoint · ${vehicle.labelForRoute()}",
                    estimatedDetourMinutes = 0.0,
                )
            }
        }
        val fixedIds = fixedHighlights.mapTo(mutableSetOf()) { it.id }
        val scenePoints = (fixedHighlights + discovered.filterNot { it.id in fixedIds }).take(96)

        val dwell = fixedStops.sumOf { it.dwellMinutes }
        val directDriveExtra = max(0.0, (direct.durationSeconds - baseNoStops.durationSeconds) / 60.0)
        val scenicDriveExtra = max(0.0, (scenic.durationSeconds - baseNoStops.durationSeconds) / 60.0)

        val directCandidate = RouteCandidateUi(
            id = "vehicle-direct-${vehicle.kind.name.lowercase()}",
            character = RouteCharacter.DIRECT.name,
            distanceMeters = direct.distanceMeters,
            durationSeconds = direct.durationSeconds,
            scenicScore = 0.0,
            extraMinutes = directDriveExtra,
            points = direct.points,
            provider = "Valhalla / OpenStreetMap · ${vehicle.kind.label}",
            scenePoints = fixedHighlights,
            strongestSignals = vehicleSignals(vehicle, scenic = false),
            variantLabel = if (fixedStops.isEmpty()) "Direct" else "Direct via waypoints",
            experienceScore = 0.0,
            driveExtraMinutes = directDriveExtra,
            dwellMinutes = dwell,
            totalExtraMinutes = directDriveExtra + dwell,
            dataConfidence = 1.0,
        )

        val scenicCandidate = RouteCandidateUi(
            id = "vehicle-scenic-${vehicle.kind.name.lowercase()}",
            character = plan.routeCharacter.name,
            distanceMeters = scenic.distanceMeters,
            durationSeconds = scenic.durationSeconds,
            scenicScore = scenicScore(scenic.points, discovered, vehicle),
            extraMinutes = scenicDriveExtra,
            points = scenic.points,
            provider = "Valhalla / OpenStreetMap · ${vehicle.kind.label}",
            scenePoints = scenePoints,
            strongestSignals = vehicleSignals(vehicle, scenic = true),
            variantLabel = if (vehicle.kind == VehicleKind.BICYCLE) "Scenic cycleways" else if (fixedStops.isEmpty()) "Scenic drive" else "Scenic via waypoints",
            experienceScore = scenicScore(scenic.points, discovered, vehicle),
            autoStopIds = emptyList(),
            driveExtraMinutes = scenicDriveExtra,
            dwellMinutes = dwell,
            totalExtraMinutes = scenicDriveExtra + dwell,
            corridorRadiusKm = (4.0 + effective.maxExtraMinutes * 0.15).coerceIn(6.0, 42.0),
            dataConfidence = if (discovered.isEmpty()) 0.65 else 0.9,
        )

        RoutePlanUi(
            candidates = if (plan.routeCharacter == RouteCharacter.DIRECT) {
                listOf(directCandidate)
            } else {
                listOf(scenicCandidate, directCandidate).distinctBy {
                    "${(it.distanceMeters / 250).roundToInt()}:${(it.durationSeconds / 60).roundToInt()}"
                }
            },
            baselineDurationSeconds = baseNoStops.durationSeconds,
            baselineDistanceMeters = baseNoStops.distanceMeters,
            note = buildString {
                append("${vehicle.kind.emoji} ${vehicle.kind.label} routing")
                append(" · real-network route, no synthetic long-route anchors")
                if (vehicle.hasPhysicalRestrictions) {
                    append(" · ${format1(vehicle.heightMeters)}m H × ${format1(vehicle.widthMeters)}m W × ${format1(vehicle.lengthMeters)}m L · ${format1(vehicle.weightTons)}t")
                }
                if (vehicle.kind == VehicleKind.BICYCLE) append(" · cycleways/paths preferred over parallel main roads")
                if (fixedStops.isNotEmpty()) append(" · ${fixedStops.size} fixed waypoint${if (fixedStops.size == 1) "" else "s"} routed pairwise")
                if (discovered.isNotEmpty()) append(" · ${discovered.size} potential Smart Stops")
            },
        )
    }

    private fun orderedFixedStops(plan: TripPlan, baseline: List<GeoPoint>): List<PlannedStop> {
        val stops = plan.stops.filter { it.mustVisit && it.point != null }
        if (!plan.flexibleStopOrder || stops.size < 2) return stops
        return stops.sortedBy { routeProgressIndex(baseline, requireNotNull(it.point)) }
    }

    private fun routeThrough(
        nodes: List<GeoPoint>,
        preferences: ScenicPreferences,
        scenic: Boolean,
    ): RawRoute {
        if (nodes.size <= 2) return requestRoute(nodes, preferences, scenic)
        val legs = nodes.zipWithNext().map { (from, to) ->
            requestRoute(listOf(from, to), preferences, scenic)
        }
        return stitch(legs)
    }

    private fun stitch(legs: List<RawRoute>): RawRoute {
        val points = buildList {
            legs.forEach { leg ->
                if (isEmpty()) addAll(leg.points)
                else if (leg.points.isNotEmpty()) {
                    if (haversineMeters(last(), leg.points.first()) < 20.0) addAll(leg.points.drop(1))
                    else addAll(leg.points)
                }
            }
        }
        if (points.size < 2) error("Vehicle route returned no usable geometry")
        return RawRoute(
            distanceMeters = legs.sumOf { it.distanceMeters },
            durationSeconds = legs.sumOf { it.durationSeconds },
            points = points,
        )
    }

    private fun requestRoute(
        locations: List<GeoPoint>,
        preferences: ScenicPreferences,
        scenic: Boolean,
    ): RawRoute {
        val vehicle = preferences.vehicle
        val costing = costingName(vehicle.kind)
        val options = costingOptions(vehicle, preferences, scenic)
        val body = JSONObject().apply {
            put("locations", JSONArray().apply {
                locations.forEach { point ->
                    put(JSONObject().put("lat", point.lat).put("lon", point.lon).put("type", "break"))
                }
            })
            put("costing", costing)
            put("costing_options", JSONObject().put(costing, options))
            put("directions_options", JSONObject().put("units", "kilometers").put("language", "de-DE"))
        }

        val connection = (URL("$VALHALLA_URL/route").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 16_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
            setRequestProperty("X-Client-Id", CLIENT_ID)
            outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Valhalla $costing HTTP $code ${text.take(180)}")
            val response = JSONObject(text.ifBlank { "{}" })
            val trip = response.optJSONObject("trip") ?: error("Valhalla returned no trip")
            val summary = trip.optJSONObject("summary") ?: error("Valhalla returned no summary")
            val legs = trip.optJSONArray("legs") ?: JSONArray()
            val points = buildList {
                for (index in 0 until legs.length()) {
                    val shape = legs.optJSONObject(index)?.optString("shape").orEmpty()
                    if (shape.isBlank()) continue
                    val decoded = decodePolyline6(shape)
                    if (isNotEmpty() && decoded.isNotEmpty() && haversineMeters(last(), decoded.first()) < 5.0) addAll(decoded.drop(1)) else addAll(decoded)
                }
            }
            if (points.size < 2) error("Valhalla $costing route shape is empty")
            RawRoute(
                distanceMeters = summary.optDouble("length", 0.0) * 1000.0,
                durationSeconds = summary.optDouble("time", 0.0),
                points = points,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun costingName(kind: VehicleKind): String = when (kind) {
        VehicleKind.CAR, VehicleKind.CAMPER -> "auto"
        VehicleKind.MOTORCYCLE -> "motorcycle"
        VehicleKind.TRUCK -> "truck"
        VehicleKind.COACH -> "bus"
        VehicleKind.BICYCLE -> "bicycle"
    }

    private fun costingOptions(
        vehicle: VehicleProfile,
        preferences: ScenicPreferences,
        scenic: Boolean,
    ): JSONObject = when (vehicle.kind) {
        VehicleKind.BICYCLE -> JSONObject().apply {
            put("bicycle_type", vehicle.bicycleType.apiValue)
            // Valhalla: 0 means avoid shared motor-vehicle roads and stay on cycleways/paths.
            put("use_roads", if (scenic) 0.05 else 0.30)
            put("use_hills", (preferences.hilliness / 100.0).coerceIn(0.05, 0.85))
            put("avoid_bad_surfaces", if (vehicle.allowUnpavedBikePaths) 0.35 else 0.92)
            put("use_ferry", 0.35)
        }
        VehicleKind.MOTORCYCLE -> JSONObject().apply {
            put("use_highways", if (preferences.avoidMotorways) 0.0 else if (scenic) 0.18 else 0.8)
            put("use_tolls", if (preferences.avoidTolls) 0.0 else 0.5)
            put("use_trails", if (scenic) (preferences.windingness / 100.0 * 0.55).coerceIn(0.1, 0.55) else 0.0)
            put("use_ferry", 0.35)
            put("top_speed", 140)
        }
        VehicleKind.TRUCK -> JSONObject().apply {
            putHeavyEnvelope(vehicle)
            put("use_highways", if (preferences.avoidMotorways) 0.0 else if (scenic) 0.55 else 0.9)
            put("use_tolls", if (preferences.avoidTolls) 0.0 else 0.5)
            put("use_truck_route", 0.85)
            put("low_class_factor", if (scenic) 1.7 else 2.0)
            put("low_class_penalty", 45)
            put("hgv_no_access_penalty", 43200)
            put("top_speed", 90)
        }
        VehicleKind.COACH -> JSONObject().apply {
            putAutoEnvelope(vehicle)
            put("use_highways", if (preferences.avoidMotorways) 0.0 else if (scenic) 0.45 else 0.85)
            put("use_tolls", if (preferences.avoidTolls) 0.0 else 0.5)
            put("use_ferry", 0.25)
            put("use_distance", if (scenic) 0.08 else 0.0)
            put("top_speed", 100)
        }
        VehicleKind.CAMPER -> JSONObject().apply {
            putAutoEnvelope(vehicle)
            put("use_highways", if (preferences.avoidMotorways) 0.0 else if (scenic) 0.28 else 0.8)
            put("use_tolls", if (preferences.avoidTolls) 0.0 else 0.5)
            put("use_ferry", 0.25)
            put("use_distance", if (scenic) 0.12 else 0.0)
            put("exclude_unpaved", true)
            put("top_speed", 115)
        }
        VehicleKind.CAR -> JSONObject().apply {
            putAutoEnvelope(vehicle)
            put("use_highways", if (preferences.avoidMotorways) 0.0 else if (scenic) 0.15 else 0.9)
            put("use_tolls", if (preferences.avoidTolls) 0.0 else 0.5)
            put("use_ferry", 0.35)
            put("use_distance", if (scenic) 0.15 else 0.0)
            put("exclude_unpaved", true)
        }
    }

    private fun JSONObject.putAutoEnvelope(vehicle: VehicleProfile) {
        put("height", vehicle.heightMeters)
        put("width", vehicle.widthMeters)
        put("length", vehicle.lengthMeters)
        put("weight", vehicle.weightTons)
    }

    private fun JSONObject.putHeavyEnvelope(vehicle: VehicleProfile) {
        putAutoEnvelope(vehicle)
        put("axle_load", vehicle.axleLoadTons)
        put("axle_count", vehicle.axleCount)
    }

    private fun vehicleSignals(vehicle: VehicleProfile, scenic: Boolean): List<String> = buildList {
        add("vehicleAwareRouting")
        add("networkOnlyAnchors")
        if (vehicle.hasPhysicalRestrictions) add("physicalAccessRestrictions")
        if (vehicle.kind == VehicleKind.TRUCK) add("hgvAccess")
        if (vehicle.kind == VehicleKind.BICYCLE) add(if (scenic) "cyclewayPriority" else "bicycleRouting")
        if (vehicle.kind == VehicleKind.MOTORCYCLE && scenic) add("secondaryRoadAdventure")
    }

    private fun VehicleProfile.labelForRoute(): String = when (kind) {
        VehicleKind.BICYCLE -> "cycleway route"
        VehicleKind.TRUCK -> "HGV-safe route"
        VehicleKind.COACH -> "coach-safe route"
        VehicleKind.CAMPER -> "motorhome-safe route"
        VehicleKind.MOTORCYCLE -> "motorcycle route"
        VehicleKind.CAR -> "car route"
    }

    private fun scenicScore(route: List<GeoPoint>, pois: List<ScenePointUi>, vehicle: VehicleProfile): Double {
        if (route.size < 3) return 55.0
        val samples = sample(route, 36)
        var bendEnergy = 0.0
        for (index in 1 until samples.lastIndex) {
            var delta = abs(bearing(samples[index - 1], samples[index]) - bearing(samples[index], samples[index + 1]))
            if (delta > 180) delta = 360 - delta
            bendEnergy += (delta / 90.0).coerceIn(0.0, 1.0)
        }
        val bends = bendEnergy / max(1, samples.size - 2)
        val poi = (pois.take(12).sumOf { it.relevance } / 12.0).coerceIn(0.0, 1.0)
        val bikeBonus = if (vehicle.kind == VehicleKind.BICYCLE) 12.0 else 0.0
        return (50.0 + bends * 20.0 + poi * 18.0 + bikeBonus).coerceIn(0.0, 100.0)
    }

    private fun routeProgressIndex(route: List<GeoPoint>, point: GeoPoint): Int {
        if (route.isEmpty()) return 0
        val step = max(1, route.size / 180)
        var bestIndex = 0
        var bestDistance = Double.POSITIVE_INFINITY
        var index = 0
        while (index < route.size) {
            val distance = haversineMeters(route[index], point)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
            index += step
        }
        return bestIndex
    }

    private fun sample(route: List<GeoPoint>, count: Int): List<GeoPoint> {
        if (route.size <= count) return route
        val step = (route.size - 1).toDouble() / (count - 1).coerceAtLeast(1)
        return (0 until count).map { route[(it * step).roundToInt().coerceIn(0, route.lastIndex)] }
    }

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
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
                    if (index >= encoded.length) return 0
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                return if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            }
            lat += nextDelta()
            lon += nextDelta()
            points += GeoPoint(lat / 1_000_000.0, lon / 1_000_000.0)
        }
        return points
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

    private fun format1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}
