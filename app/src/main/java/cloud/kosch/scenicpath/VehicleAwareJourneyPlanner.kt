package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Vehicle-aware development router.
 *
 * Every displayed route is generated on the real Valhalla/OSM transport network. Fixed user
 * waypoints and automatic Smart Stops are routed as actual breaks, not visual annotations.
 * Automatic stops are accepted only after the real reroute fits both the global extra-minute
 * budget (including dwell time) and the percentage detour budget.
 */
object VehicleAwareJourneyPlanner {
    private const val VALHALLA_URL = "https://valhalla1.openstreetmap.de"
    private const val CLIENT_ID = "scenic-path-android-dev"

    private data class RawRoute(
        val distanceMeters: Double,
        val durationSeconds: Double,
        val points: List<GeoPoint>,
    )

    private data class RouteAnchor(
        val id: String,
        val point: GeoPoint,
    )

    suspend fun plan(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi = withContext(Dispatchers.IO) {
        val effective = preferences.forCharacter(plan.routeCharacter)
        val vehicle = effective.vehicle

        val baseNoStops = requestRoute(listOf(origin, destination), effective, scenic = false)
        val fixedStops = orderedFixedStops(plan, baseNoStops.points)
        val fixedPoints = fixedStops.mapNotNull { it.point }

        val direct = routeThrough(
            nodes = listOf(origin) + fixedPoints + destination,
            preferences = effective,
            scenic = false,
        )

        val scenicBase = if (plan.routeCharacter == RouteCharacter.DIRECT) {
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

        val discovered = if (
            plan.routeCharacter != RouteCharacter.DIRECT &&
            plan.autoSuggestStops &&
            plan.enabledSceneKinds.isNotEmpty()
        ) {
            RoutePoiDiscoveryCoordinator.discover(
                route = scenicBase.points,
                enabledKinds = plan.enabledSceneKinds,
                maxResults = 96,
                broad = effective.maxExtraMinutes >= 90,
            )
        } else emptyList()

        val fixedHighlights = fixedStops.mapNotNull(::fixedHighlight)
        val fixedIds = fixedHighlights.mapTo(mutableSetOf()) { it.id }
        val filteredDiscoveries = discovered
            .filterNot { it.id in fixedIds }
            .filter { NativeAutoStopPolicy.foodMatches(it, effective) }
            .take(96)

        val fixedDwell = fixedStops.sumOf { it.dwellMinutes }
        val directDriveExtra = max(0.0, (direct.durationSeconds - baseNoStops.durationSeconds) / 60.0)
        val scenicBaseDriveExtra = max(0.0, (scenicBase.durationSeconds - baseNoStops.durationSeconds) / 60.0)

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
            dwellMinutes = fixedDwell,
            totalExtraMinutes = directDriveExtra + fixedDwell,
            dataConfidence = 1.0,
        )

        var scenicCandidate = RouteCandidateUi(
            id = "vehicle-scenic-${vehicle.kind.name.lowercase()}",
            character = plan.routeCharacter.name,
            distanceMeters = scenicBase.distanceMeters,
            durationSeconds = scenicBase.durationSeconds,
            scenicScore = scenicScore(scenicBase.points, filteredDiscoveries, vehicle),
            extraMinutes = scenicBaseDriveExtra,
            points = scenicBase.points,
            provider = "Valhalla / OpenStreetMap · ${vehicle.kind.label}",
            scenePoints = fixedHighlights + filteredDiscoveries,
            strongestSignals = vehicleSignals(vehicle, scenic = true),
            variantLabel = if (vehicle.kind == VehicleKind.BICYCLE) "Scenic cycleways" else if (fixedStops.isEmpty()) "Scenic drive" else "Scenic via waypoints",
            experienceScore = scenicScore(scenicBase.points, filteredDiscoveries, vehicle),
            autoStopIds = emptyList(),
            driveExtraMinutes = scenicBaseDriveExtra,
            dwellMinutes = fixedDwell,
            totalExtraMinutes = scenicBaseDriveExtra + fixedDwell,
            corridorRadiusKm = (4.0 + effective.maxExtraMinutes * 0.15).coerceIn(6.0, 42.0),
            dataConfidence = if (filteredDiscoveries.isEmpty()) 0.65 else 0.9,
        )

        if (filteredDiscoveries.isNotEmpty()) {
            scenicCandidate = includeAutomaticStops(
                origin = origin,
                destination = destination,
                baseNoStops = baseNoStops,
                scenicBase = scenicBase,
                fixedStops = fixedStops,
                discoveries = filteredDiscoveries,
                candidate = scenicCandidate,
                preferences = effective,
                enabledKinds = plan.enabledSceneKinds,
            )
        }

        if (scenicCandidate.scenePoints.isNotEmpty()) {
            ScenicPoiSharedState.publish(scenicCandidate.points, scenicCandidate.scenePoints)
            RoutePoiDiscoveryCoordinator.seed(
                route = scenicCandidate.points,
                enabledKinds = plan.enabledSceneKinds,
                points = scenicCandidate.scenePoints,
            )
        }

        RoutePlanUi(
            candidates = if (plan.routeCharacter == RouteCharacter.DIRECT) {
                listOf(directCandidate)
            } else {
                listOf(scenicCandidate, directCandidate).distinctBy {
                    "${(it.distanceMeters / 250).roundToInt()}:${(it.durationSeconds / 60).roundToInt()}:${it.autoStopIds.sorted()}"
                }
            },
            baselineDurationSeconds = baseNoStops.durationSeconds,
            baselineDistanceMeters = baseNoStops.distanceMeters,
            note = buildString {
                append("${vehicle.kind.emoji} ${vehicle.kind.label} routing · real-network route")
                if (vehicle.hasPhysicalRestrictions) {
                    append(" · ${format1(vehicle.heightMeters)}m H × ${format1(vehicle.widthMeters)}m W × ${format1(vehicle.lengthMeters)}m L · ${format1(vehicle.weightTons)}t")
                }
                if (vehicle.kind == VehicleKind.BICYCLE) append(" · ${vehicle.bicycleType.label}")
                if (fixedStops.isNotEmpty()) append(" · ${fixedStops.size} fixed waypoint${if (fixedStops.size == 1) "" else "s"}")
                if (scenicCandidate.autoStopIds.isNotEmpty()) {
                    append(" · ${scenicCandidate.autoStopIds.size} automatic Smart Stop${if (scenicCandidate.autoStopIds.size == 1) "" else "s"} validated inside budget")
                } else if (filteredDiscoveries.isNotEmpty()) {
                    append(" · ${filteredDiscoveries.size} Smart Stop alternatives available")
                }
            },
        )
    }

    private fun includeAutomaticStops(
        origin: GeoPoint,
        destination: GeoPoint,
        baseNoStops: RawRoute,
        scenicBase: RawRoute,
        fixedStops: List<PlannedStop>,
        discoveries: List<ScenePointUi>,
        candidate: RouteCandidateUi,
        preferences: ScenicPreferences,
        enabledKinds: Set<StopKind>,
    ): RouteCandidateUi {
        var selected = NativeAutoStopPolicy.select(discoveries, preferences, enabledKinds)
        if (selected.isEmpty()) return candidate

        val fixedDwell = fixedStops.sumOf { it.dwellMinutes }
        val maxDurationByPercent = baseNoStops.durationSeconds * (1.0 + preferences.maxExtraPercent.coerceAtLeast(0) / 100.0)

        while (selected.isNotEmpty()) {
            val anchors = buildList {
                fixedStops.forEach { stop -> stop.point?.let { add(RouteAnchor(stop.id, it)) } }
                selected.forEach { add(RouteAnchor(it.id, it.point)) }
            }.distinctBy { it.id }
                .sortedBy { routeProgressIndex(scenicBase.points, it.point) }

            val routed = runCatching {
                routeThrough(
                    nodes = listOf(origin) + anchors.map { it.point } + destination,
                    preferences = preferences,
                    scenic = true,
                )
            }.getOrNull()

            if (routed != null) {
                val driveExtra = max(0.0, (routed.durationSeconds - baseNoStops.durationSeconds) / 60.0)
                val autoDwell = selected.sumOf { it.suggestedDwellMinutes }
                val totalExtra = driveExtra + fixedDwell + autoDwell
                val withinMinutes = totalExtra <= preferences.maxExtraMinutes + 1.0
                val withinPercent = routed.durationSeconds <= maxDurationByPercent + 1.0

                if (withinMinutes && withinPercent) {
                    val includedIds = selected.mapTo(mutableSetOf()) { it.id }
                    val scenePoints = buildList {
                        fixedStops.mapNotNull(::fixedHighlight).forEach(::add)
                        discoveries.forEach { point ->
                            val included = point.id in includedIds
                            add(
                                point.copy(
                                    includedInRoute = included,
                                    personalMatch = NativeAutoStopPolicy.utility(point, preferences).coerceIn(0.0, 100.0),
                                    rationale = if (included) {
                                        if (point.kind == StopKind.FOOD.name) "Top Food · real detour validated inside your budget"
                                        else "Strong Scenic DNA fit · real detour validated inside your budget"
                                    } else point.rationale,
                                    estimatedDetourMinutes = point.distanceFromRouteMeters.coerceAtLeast(0) / 500.0,
                                )
                            )
                        }
                    }.distinctBy { it.id }
                    val score = scenicScore(routed.points, discoveries, preferences.vehicle)
                    val experienceBonus = selected.map { NativeAutoStopPolicy.utility(it, preferences) }.averageOrZero() * 0.08
                    return candidate.copy(
                        distanceMeters = routed.distanceMeters,
                        durationSeconds = routed.durationSeconds,
                        points = routed.points,
                        scenicScore = score,
                        extraMinutes = driveExtra,
                        driveExtraMinutes = driveExtra,
                        dwellMinutes = fixedDwell + autoDwell,
                        totalExtraMinutes = totalExtra,
                        autoStopIds = selected.map { it.id },
                        scenePoints = scenePoints,
                        variantLabel = "Best match",
                        experienceScore = (score + experienceBonus).coerceIn(0.0, 100.0),
                        strongestSignals = (candidate.strongestSignals + listOf("automaticSmartStops", "budgetValidated")).distinct().take(8),
                        dataConfidence = 0.92,
                    )
                }
            }

            val weakest = NativeAutoStopPolicy.weakestRemovable(selected, preferences, enabledKinds) ?: break
            selected = selected.filterNot { it.id == weakest.id }
        }
        return candidate
    }

    private fun orderedFixedStops(plan: TripPlan, baseline: List<GeoPoint>): List<PlannedStop> {
        val stops = plan.stops.filter { it.mustVisit && it.point != null }
        if (!plan.flexibleStopOrder || stops.size < 2) return stops
        return stops.sortedBy { routeProgressIndex(baseline, requireNotNull(it.point)) }
    }

    private fun fixedHighlight(stop: PlannedStop): ScenePointUi? = stop.point?.let { point ->
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
            rationale = "fixed waypoint · ${VehicleSettingsState.profile.kind.label}",
            estimatedDetourMinutes = 0.0,
        )
    }

    private fun routeThrough(nodes: List<GeoPoint>, preferences: ScenicPreferences, scenic: Boolean): RawRoute {
        if (nodes.size <= 2) return requestRoute(nodes, preferences, scenic)
        return stitch(nodes.zipWithNext().map { (from, to) -> requestRoute(listOf(from, to), preferences, scenic) })
    }

    private fun stitch(legs: List<RawRoute>): RawRoute {
        val points = buildList {
            legs.forEach { leg ->
                if (isEmpty()) addAll(leg.points)
                else if (leg.points.isNotEmpty()) {
                    if (haversineMeters(last(), leg.points.first()) < 20.0) addAll(leg.points.drop(1)) else addAll(leg.points)
                }
            }
        }
        if (points.size < 2) error("Vehicle route returned no usable geometry")
        return RawRoute(legs.sumOf { it.distanceMeters }, legs.sumOf { it.durationSeconds }, points)
    }

    private fun requestRoute(locations: List<GeoPoint>, preferences: ScenicPreferences, scenic: Boolean): RawRoute {
        val vehicle = preferences.vehicle
        val costing = costingName(vehicle.kind)
        val options = costingOptions(vehicle, preferences, scenic)
        val body = JSONObject().apply {
            put("locations", JSONArray().apply {
                locations.forEach { point -> put(JSONObject().put("lat", point.lat).put("lon", point.lon).put("type", "break")) }
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
            val trip = JSONObject(text.ifBlank { "{}" }).optJSONObject("trip") ?: error("Valhalla returned no trip")
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
            val distanceMeters = summary.optDouble("length", 0.0) * 1000.0
            RawRoute(
                distanceMeters = distanceMeters,
                durationSeconds = RouteTimeSanity.normalizeDurationSeconds(
                    distanceMeters = distanceMeters,
                    providerDurationSeconds = summary.optDouble("time", 0.0),
                    vehicle = vehicle,
                ),
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

    private fun costingOptions(vehicle: VehicleProfile, preferences: ScenicPreferences, scenic: Boolean): JSONObject {
        val tuning = NativeRouteConstraintPolicy.tuning(vehicle, preferences, scenic)
        return when (vehicle.kind) {
            VehicleKind.BICYCLE -> JSONObject().apply {
                put("bicycle_type", vehicle.bicycleType.apiValue)
                tuning.useRoads?.let { put("use_roads", it) }
                put("avoid_bad_surfaces", if (vehicle.allowUnpavedBikePaths) 0.35 else 0.92)
                put("use_ferry", 0.35)
            }
            VehicleKind.MOTORCYCLE -> JSONObject().apply {
                putTuning(tuning)
                put("use_ferry", 0.35)
                put("top_speed", 140)
            }
            VehicleKind.TRUCK -> JSONObject().apply {
                putHeavyEnvelope(vehicle)
                putTuning(tuning)
                put("use_truck_route", 0.85)
                put("low_class_factor", if (scenic) 1.7 else 2.0)
                put("low_class_penalty", 45)
                put("hgv_no_access_penalty", 43200)
                put("top_speed", 90)
            }
            VehicleKind.COACH -> JSONObject().apply {
                putAutoEnvelope(vehicle)
                putTuning(tuning)
                put("use_ferry", 0.25)
                put("top_speed", 100)
            }
            VehicleKind.CAMPER -> JSONObject().apply {
                putAutoEnvelope(vehicle)
                putTuning(tuning)
                put("use_ferry", 0.25)
                put("exclude_unpaved", true)
                put("top_speed", 115)
            }
            VehicleKind.CAR -> JSONObject().apply {
                putAutoEnvelope(vehicle)
                putTuning(tuning)
                put("use_ferry", 0.35)
                put("exclude_unpaved", true)
            }
        }
    }

    private fun JSONObject.putTuning(tuning: NativeRouteTuning) {
        tuning.useHighways?.let { put("use_highways", it) }
        tuning.useTolls?.let { put("use_tolls", it) }
        tuning.useHills?.let { put("use_hills", it) }
        tuning.useDistance?.let { put("use_distance", it) }
        tuning.useTrails?.let { put("use_trails", it) }
        tuning.useRoads?.let { put("use_roads", it) }
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

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
    private fun format1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}
