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
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v0.4 development planner: optimize the complete experience, not just one route.
 *
 * Pipeline:
 * 1. Build several road corridors with different Valhalla costing profiles.
 * 2. Expand the POI search space with the user's available-time budget.
 * 3. Score POIs against Scenic DNA.
 * 4. Use a Valhalla time/distance matrix and beam search (orienteering-style)
 *    to find high-value stop combinations inside the total time budget.
 * 5. Route and validate several deliberately different journey variants.
 *
 * Public Valhalla/Photon/Overpass infrastructure is development-only.
 */
object ScenicJourneyOptimizer {
    private const val VALHALLA_URL = "https://valhalla1.openstreetmap.de"
    private const val CLIENT_ID = "scenic-path-android-dev"

    private enum class RoadProfile(
        val label: String,
        val useHighways: Double,
        val shortest: Boolean,
    ) {
        FLOW("Scenic flow", 0.0, false),
        BALANCED("Balanced roads", 0.08, false),
        EXPLORER("Explorer roads", 0.0, true),
        DIRECT("Direct", 1.0, false),
    }

    private enum class JourneyStrategy(
        val label: String,
        val diversityBonus: Double,
        val minutePenalty: Double,
        val maxStopsBias: Int,
    ) {
        BEST_MATCH("Best match", 8.0, 0.22, 0),
        HIGHLIGHT_HUNTER("Highlight hunter", 15.0, 0.15, 1),
        SCENIC_DRIVE("Scenic drive", 5.0, 0.28, -1),
    }

    private data class RawRoute(
        val distanceMeters: Double,
        val durationSeconds: Double,
        val points: List<GeoPoint>,
    )

    private data class MatrixData(
        val durations: List<List<Double?>>,
        val distances: List<List<Double?>>,
    ) {
        fun seconds(from: Int, to: Int): Double? = durations.getOrNull(from)?.getOrNull(to)
        fun meters(from: Int, to: Int): Double? = distances.getOrNull(from)?.getOrNull(to)
    }

    private data class PersonalizedPoi(
        val poi: ScenePointUi,
        val match: Double,
        val baseUtility: Double,
        val rationale: String,
    )

    private data class SearchState(
        val order: List<Int>,
        val lastLocationIndex: Int,
        val drivingSeconds: Double,
        val dwellMinutes: Int,
        val utility: Double,
        val kinds: Map<String, Int>,
    )

    private data class JourneySelection(
        val poiOrder: List<PersonalizedPoi>,
        val estimatedDrivingSeconds: Double,
        val estimatedTotalSeconds: Double,
        val objective: Double,
    )

    suspend fun plan(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi = withContext(Dispatchers.IO) {
        // Manual/fixed-stop trips keep the stable planner for now. The new optimizer is
        // designed around automatic discovery; production will later optimize between
        // fixed anchors rather than treating them as ordinary candidates.
        if (plan.stops.any { it.point != null }) {
            return@withContext OsmScenicRoutingFallback.plan(origin, destination, plan, preferences)
        }

        val effective = preferences.forCharacter(plan.routeCharacter)
        val budgetMinutes = if (plan.routeCharacter == RouteCharacter.DIRECT) 10 else effective.maxExtraMinutes
        val baseline = requestRoute(
            locations = listOf(origin, destination),
            preferences = effective,
            profile = RoadProfile.DIRECT,
            enforceMotorwayBan = false,
        )

        if (plan.routeCharacter == RouteCharacter.DIRECT || !plan.autoSuggestStops) {
            return@withContext RoutePlanUi(
                candidates = listOf(directCandidate(baseline)),
                baselineDurationSeconds = baseline.durationSeconds,
                baselineDistanceMeters = baseline.distanceMeters,
                note = "Direct route · Smart Stops disabled",
            )
        }

        val flow = requestRoute(
            locations = listOf(origin, destination),
            preferences = effective,
            profile = RoadProfile.FLOW,
            enforceMotorwayBan = effective.avoidMotorways,
        )
        val corridorRoutes = mutableListOf(flow)

        if (budgetMinutes >= 45) {
            runCatching {
                requestRoute(
                    locations = listOf(origin, destination),
                    preferences = effective,
                    profile = RoadProfile.BALANCED,
                    enforceMotorwayBan = effective.avoidMotorways,
                )
            }.getOrNull()?.let(corridorRoutes::add)
        }
        if (budgetMinutes >= 75) {
            runCatching {
                requestRoute(
                    locations = listOf(origin, destination),
                    preferences = effective,
                    profile = RoadProfile.EXPLORER,
                    enforceMotorwayBan = effective.avoidMotorways,
                )
            }.getOrNull()?.let(corridorRoutes::add)
        }

        val corridorRadiusKm = corridorRadiusKm(budgetMinutes)
        val discoveries = discoverSearchSpace(
            corridorRoutes = corridorRoutes,
            enabledKinds = plan.enabledSceneKinds,
            budgetMinutes = budgetMinutes,
            corridorRadiusKm = corridorRadiusKm,
        )

        if (discoveries.isEmpty()) {
            val scenicCandidate = routeCandidate(
                id = "journey-scenic-flow",
                variantLabel = "Scenic drive",
                route = flow,
                baseline = baseline,
                scenePool = emptyList(),
                included = emptyList(),
                experienceScore = debugScenicScore(flow.points, emptyList(), effective),
                corridorRadiusKm = corridorRadiusKm,
                preferences = effective,
                noteSignal = "expandedSearchSpace",
            )
            return@withContext RoutePlanUi(
                candidates = listOf(scenicCandidate, directCandidate(baseline)),
                baselineDurationSeconds = baseline.durationSeconds,
                baselineDistanceMeters = baseline.distanceMeters,
                note = "Expanded ${corridorRadiusKm.roundToInt()} km scenic search space · no scene candidates returned by development discovery services",
            )
        }

        val personalized = discoveries
            .map { personalize(it, effective) }
            .sortedByDescending { it.baseUtility }

        val matrixCandidates = personalized.take(matrixCandidateLimit(budgetMinutes))
        val locations = buildList {
            add(origin)
            matrixCandidates.forEach { add(it.poi.point) }
            add(destination)
        }

        val matrix = runCatching { requestMatrix(locations, effective) }.getOrNull()
        if (matrix == null) {
            // The matrix is the quantum-jump path, but the user must still get a route if
            // a public demo endpoint is temporarily unavailable.
            return@withContext OsmScenicRoutingFallback.plan(origin, destination, plan, effective)
        }

        val maxStops = automaticStopLimit(budgetMinutes, effective.maxStops)
        val strategies = buildList {
            add(JourneyStrategy.BEST_MATCH)
            if (budgetMinutes >= 45) add(JourneyStrategy.HIGHLIGHT_HUNTER)
            if (budgetMinutes >= 75) add(JourneyStrategy.SCENIC_DRIVE)
        }

        val journeys = mutableListOf<RouteCandidateUi>()
        val seenStopSets = mutableSetOf<String>()

        for (strategy in strategies) {
            val targetStops = (maxStops + strategy.maxStopsBias).coerceIn(1, maxStops.coerceAtLeast(1))
            val selection = optimizeStops(
                candidates = matrixCandidates,
                matrix = matrix,
                baselineSeconds = baseline.durationSeconds,
                budgetMinutes = budgetMinutes,
                maxStops = targetStops,
                strategy = strategy,
            ) ?: continue

            val stopKey = selection.poiOrder.map { it.poi.id }.sorted().joinToString("|")
            if (!seenStopSets.add(stopKey) && journeys.isNotEmpty()) continue

            val profile = when (strategy) {
                JourneyStrategy.BEST_MATCH -> RoadProfile.FLOW
                JourneyStrategy.HIGHLIGHT_HUNTER -> if (budgetMinutes >= 90) RoadProfile.EXPLORER else RoadProfile.BALANCED
                JourneyStrategy.SCENIC_DRIVE -> RoadProfile.EXPLORER
            }

            val validated = routeSelectionWithinBudget(
                origin = origin,
                destination = destination,
                selection = selection,
                baseline = baseline,
                budgetMinutes = budgetMinutes,
                preferences = effective,
                profile = profile,
            ) ?: continue

            val includedIds = validated.second.mapTo(mutableSetOf()) { it.poi.id }
            val includedPoints = validated.second.map { personalizedPoi ->
                personalizedPoi.poi.copy(
                    includedInRoute = true,
                    personalMatch = personalizedPoi.match,
                    rationale = personalizedPoi.rationale,
                )
            }
            val optional = personalized
                .filterNot { it.poi.id in includedIds }
                .take(18 - includedPoints.size)
                .map { personalizedPoi ->
                    personalizedPoi.poi.copy(
                        includedInRoute = false,
                        personalMatch = personalizedPoi.match,
                        rationale = personalizedPoi.rationale,
                    )
                }
            val route = validated.first
            val dwellMinutes = includedPoints.sumOf { it.suggestedDwellMinutes }
            val driveExtra = max(0.0, (route.durationSeconds - baseline.durationSeconds) / 60.0)
            val totalExtra = driveExtra + dwellMinutes
            val scenicScore = debugScenicScore(route.points, includedPoints, effective)
            val avgMatch = includedPoints.mapNotNull { it.personalMatch }.averageOrZero()
            val experience = (
                scenicScore * 0.52 +
                    avgMatch * 0.42 +
                    (1.0 - (totalExtra / max(1.0, budgetMinutes.toDouble())).coerceIn(0.0, 1.0)) * 6.0
                ).coerceIn(0.0, 100.0)

            journeys += RouteCandidateUi(
                id = "journey-${strategy.name.lowercase()}",
                character = plan.routeCharacter.name,
                distanceMeters = route.distanceMeters,
                durationSeconds = route.durationSeconds,
                scenicScore = scenicScore,
                extraMinutes = driveExtra,
                points = route.points,
                provider = "Valhalla · OpenStreetMap development",
                scenePoints = includedPoints + optional,
                strongestSignals = strongestSignals(includedPoints, effective, strategy),
                isPreviewFallback = false,
                variantLabel = strategy.label,
                experienceScore = experience,
                autoStopIds = includedPoints.map { it.id },
                driveExtraMinutes = driveExtra,
                dwellMinutes = dwellMinutes,
                totalExtraMinutes = totalExtra,
                corridorRadiusKm = corridorRadiusKm,
                dataConfidence = dataConfidence(discoveries.size, matrixCandidates.size),
            )
        }

        if (journeys.isEmpty()) {
            return@withContext OsmScenicRoutingFallback.plan(origin, destination, plan, effective)
        }

        val sortedJourneys = journeys
            .distinctBy { routeKey(it) }
            .sortedByDescending { it.experienceScore }
            .take(3)

        RoutePlanUi(
            candidates = sortedJourneys + directCandidate(baseline),
            baselineDurationSeconds = baseline.durationSeconds,
            baselineDistanceMeters = baseline.distanceMeters,
            note = buildString {
                append("Journey Optimizer · ${corridorRadiusKm.roundToInt()} km search space")
                append(" · ${discoveries.size} scenic candidates")
                append(" · Valhalla time-distance matrix")
                append(" · up to $maxStops automatic stops inside +$budgetMinutes min")
            },
        )
    }

    private suspend fun discoverSearchSpace(
        corridorRoutes: List<RawRoute>,
        enabledKinds: Set<StopKind>,
        budgetMinutes: Int,
        corridorRadiusKm: Double,
    ): List<ScenePointUi> {
        val pooled = linkedMapOf<String, ScenePointUi>()

        corridorRoutes.take(3).forEachIndexed { index, route ->
            val central = runCatching {
                if (index == 0) {
                    OsmSceneDiscovery.discover(route.points, enabledKinds, maxResults = 28)
                } else {
                    PhotonSceneFallback.discover(route.points, enabledKinds, maxResults = 24)
                }
            }.getOrElse { emptyList() }
            central.forEach { mergePoi(pooled, it) }
        }

        // Time budget expands the *space*, not just the allowed stop count. Lateral
        // virtual corridors intentionally search away from the already chosen road.
        if (budgetMinutes >= 45 && corridorRoutes.isNotEmpty()) {
            val base = corridorRoutes.first().points
            val offsetKm = (corridorRadiusKm * 0.62).coerceAtLeast(4.0)
            listOf(-offsetKm, offsetKm).forEach { lateralKm ->
                val virtual = offsetPolyline(base, lateralKm)
                val wide = runCatching {
                    PhotonSceneFallback.discover(virtual, enabledKinds, maxResults = 24)
                }.getOrElse { emptyList() }
                wide.forEach { mergePoi(pooled, it) }
            }
        }

        return pooled.values
            .distinctBy { canonicalPoiKey(it) }
            .sortedByDescending { it.relevance * 100.0 + heritageBonus(it) }
            .take(45)
    }

    private fun mergePoi(pool: MutableMap<String, ScenePointUi>, candidate: ScenePointUi) {
        val key = canonicalPoiKey(candidate)
        val existing = pool[key]
        if (existing == null || candidate.relevance > existing.relevance) pool[key] = candidate
    }

    private fun canonicalPoiKey(point: ScenePointUi): String =
        "${point.name.trim().lowercase()}:${(point.point.lat * 5000).roundToInt()}:${(point.point.lon * 5000).roundToInt()}"

    private fun personalize(point: ScenePointUi, preferences: ScenicPreferences): PersonalizedPoi {
        val kind = StopKind.entries.firstOrNull { it.name == point.kind } ?: StopKind.SCENIC
        val dna = dnaWeight(kind, preferences.weights)
        val intrinsic = (point.relevance / 1.3).coerceIn(0.0, 1.0)
        val special = (heritageBonus(point) / 28.0).coerceIn(0.0, 1.0)
        val match = (intrinsic * 0.48 + dna * 0.42 + special * 0.10).coerceIn(0.0, 1.0) * 100.0
        val dwellPenalty = point.suggestedDwellMinutes * 0.12
        val utility = match + heritageBonus(point) - dwellPenalty
        val reasons = buildList {
            if (dna >= 0.85) add("strong Scenic DNA match")
            if (heritageBonus(point) >= 18) add("standout heritage")
            if (point.kind == StopKind.VIEWPOINT.name) add("viewpoint")
            if (point.kind == StopKind.WATER.name) add("water experience")
            if (point.kind == StopKind.NATURE.name || point.kind == StopKind.PARK.name) add("nature")
            if (point.relevance >= 1.0) add("high intrinsic relevance")
        }
        return PersonalizedPoi(
            poi = point,
            match = match,
            baseUtility = utility,
            rationale = reasons.ifEmpty { listOf("good fit for this journey") }.joinToString(" · "),
        )
    }

    private fun optimizeStops(
        candidates: List<PersonalizedPoi>,
        matrix: MatrixData,
        baselineSeconds: Double,
        budgetMinutes: Int,
        maxStops: Int,
        strategy: JourneyStrategy,
    ): JourneySelection? {
        if (candidates.isEmpty() || maxStops <= 0) return null
        val destinationIndex = candidates.size + 1
        val absoluteLimit = baselineSeconds + budgetMinutes * 60.0
        val beamWidth = when {
            candidates.size >= 18 -> 110
            candidates.size >= 14 -> 90
            else -> 70
        }

        var beam = listOf(
            SearchState(
                order = emptyList(),
                lastLocationIndex = 0,
                drivingSeconds = 0.0,
                dwellMinutes = 0,
                utility = 0.0,
                kinds = emptyMap(),
            )
        )
        val feasible = mutableListOf<JourneySelection>()

        repeat(maxStops) {
            val expanded = mutableListOf<SearchState>()
            for (state in beam) {
                for (candidateIndex in candidates.indices) {
                    if (candidateIndex in state.order) continue
                    val locationIndex = candidateIndex + 1
                    val leg = matrix.seconds(state.lastLocationIndex, locationIndex) ?: continue
                    val finish = matrix.seconds(locationIndex, destinationIndex) ?: continue
                    val poi = candidates[candidateIndex]
                    val newDwell = state.dwellMinutes + poi.poi.suggestedDwellMinutes
                    val newDriving = state.drivingSeconds + leg
                    val lowerBound = newDriving + finish + newDwell * 60.0
                    if (lowerBound > absoluteLimit + 30.0) continue

                    val alreadyKind = state.kinds[poi.poi.kind] ?: 0
                    val diversity = if (alreadyKind == 0) strategy.diversityBonus else -alreadyKind * 5.0
                    val strategyBoost = strategyBoost(poi, strategy)
                    val newState = SearchState(
                        order = state.order + candidateIndex,
                        lastLocationIndex = locationIndex,
                        drivingSeconds = newDriving,
                        dwellMinutes = newDwell,
                        utility = state.utility + poi.baseUtility + diversity + strategyBoost,
                        kinds = state.kinds + (poi.poi.kind to (alreadyKind + 1)),
                    )
                    expanded += newState

                    val total = lowerBound
                    val estimatedExtraMinutes = max(0.0, (total - baselineSeconds) / 60.0)
                    feasible += JourneySelection(
                        poiOrder = newState.order.map { candidates[it] },
                        estimatedDrivingSeconds = newDriving + finish,
                        estimatedTotalSeconds = total,
                        objective = newState.utility - estimatedExtraMinutes * strategy.minutePenalty,
                    )
                }
            }

            if (expanded.isEmpty()) return@repeat
            beam = expanded
                .sortedByDescending { partialObjective(it, baselineSeconds, strategy) }
                .take(beamWidth)
        }

        return feasible
            .filter { it.poiOrder.isNotEmpty() }
            .maxByOrNull { selection ->
                selection.objective + selection.poiOrder.size * when (strategy) {
                    JourneyStrategy.HIGHLIGHT_HUNTER -> 4.5
                    JourneyStrategy.BEST_MATCH -> 2.0
                    JourneyStrategy.SCENIC_DRIVE -> 0.5
                }
            }
    }

    private fun partialObjective(state: SearchState, baselineSeconds: Double, strategy: JourneyStrategy): Double {
        val usedMinutes = max(0.0, (state.drivingSeconds + state.dwellMinutes * 60.0 - baselineSeconds) / 60.0)
        return state.utility - usedMinutes * strategy.minutePenalty
    }

    private fun strategyBoost(poi: PersonalizedPoi, strategy: JourneyStrategy): Double = when (strategy) {
        JourneyStrategy.BEST_MATCH -> poi.match * 0.08
        JourneyStrategy.HIGHLIGHT_HUNTER -> {
            heritageBonus(poi.poi) * 0.9 + when (poi.poi.kind) {
                StopKind.VIEWPOINT.name, StopKind.WATER.name, StopKind.NATURE.name -> 8.0
                StopKind.MONUMENT.name, StopKind.MUSEUM.name -> 10.0
                else -> 0.0
            }
        }
        JourneyStrategy.SCENIC_DRIVE -> when (poi.poi.kind) {
            StopKind.VIEWPOINT.name -> 14.0
            StopKind.WATER.name -> 11.0
            StopKind.NATURE.name, StopKind.PARK.name -> 9.0
            else -> -poi.poi.suggestedDwellMinutes * 0.18
        }
    }

    private fun routeSelectionWithinBudget(
        origin: GeoPoint,
        destination: GeoPoint,
        selection: JourneySelection,
        baseline: RawRoute,
        budgetMinutes: Int,
        preferences: ScenicPreferences,
        profile: RoadProfile,
    ): Pair<RawRoute, List<PersonalizedPoi>>? {
        var selected = selection.poiOrder
        while (selected.isNotEmpty()) {
            val route = runCatching {
                requestRoute(
                    locations = listOf(origin) + selected.map { it.poi.point } + destination,
                    preferences = preferences,
                    profile = profile,
                    enforceMotorwayBan = preferences.avoidMotorways,
                )
            }.getOrNull()
            if (route != null) {
                val driveExtra = max(0.0, (route.durationSeconds - baseline.durationSeconds) / 60.0)
                val dwell = selected.sumOf { it.poi.suggestedDwellMinutes }
                if (driveExtra + dwell <= budgetMinutes + 1.0) return route to selected
            }
            // Remove the least useful stop and retry instead of discarding the whole trip.
            val remove = selected.indices.minByOrNull { selected[it].baseUtility } ?: selected.lastIndex
            selected = selected.filterIndexed { index, _ -> index != remove }
        }
        return null
    }

    private fun requestMatrix(locations: List<GeoPoint>, preferences: ScenicPreferences): MatrixData {
        val body = JSONObject().apply {
            put("sources", locationsJson(locations))
            put("targets", locationsJson(locations))
            put("costing", "auto")
            put("verbose", false)
            put("costing_options", JSONObject().put("auto", autoCosting(preferences, RoadProfile.FLOW)))
        }
        val connection = openValhalla("/sources_to_targets", 14_000).apply {
            doOutput = true
            outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        }
        try {
            val response = JSONObject(connection.readTextOrThrow())
            val compact = response.optJSONObject("sources_to_targets")
            if (compact != null) {
                val durations = parseDoubleMatrix(compact.optJSONArray("durations"), locations.size)
                val distances = parseDoubleMatrix(compact.optJSONArray("distances"), locations.size)
                if (durations.isNotEmpty()) return MatrixData(durations, distances)
            }

            val verbose = response.optJSONArray("sources_to_targets") ?: error("Valhalla matrix returned no table")
            val times = MutableList(locations.size) { MutableList<Double?>(locations.size) { null } }
            val distances = MutableList(locations.size) { MutableList<Double?>(locations.size) { null } }
            for (row in 0 until verbose.length()) {
                val rowArray = verbose.optJSONArray(row) ?: continue
                for (col in 0 until rowArray.length()) {
                    val cell = rowArray.optJSONObject(col) ?: continue
                    val from = cell.optInt("from_index", row)
                    val to = cell.optInt("to_index", col)
                    if (from !in times.indices || to !in times.indices) continue
                    if (!cell.isNull("time")) times[from][to] = cell.optDouble("time")
                    if (!cell.isNull("distance")) distances[from][to] = cell.optDouble("distance") * 1000.0
                }
            }
            return MatrixData(times, distances)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseDoubleMatrix(array: JSONArray?, size: Int): List<List<Double?>> {
        val source = array ?: return emptyList()
        return (0 until min(size, source.length())).map { row ->
            val rowArray = source.optJSONArray(row) ?: JSONArray()
            (0 until size).map { col ->
                if (col >= rowArray.length() || rowArray.isNull(col)) null else rowArray.optDouble(col, Double.NaN).takeIf { it.isFinite() }
            }
        }
    }

    private fun requestRoute(
        locations: List<GeoPoint>,
        preferences: ScenicPreferences,
        profile: RoadProfile,
        enforceMotorwayBan: Boolean,
    ): RawRoute {
        val body = JSONObject().apply {
            put("locations", locationsJson(locations, breaks = true))
            put("costing", "auto")
            put("costing_options", JSONObject().put("auto", autoCosting(preferences, profile)))
            put("directions_options", JSONObject().put("units", "kilometers").put("language", "de-DE"))
        }
        val connection = openValhalla("/route", 12_000).apply {
            doOutput = true
            outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        }
        try {
            val response = JSONObject(connection.readTextOrThrow())
            val trip = response.optJSONObject("trip") ?: error("Valhalla returned no trip")
            val summary = trip.optJSONObject("summary") ?: error("Valhalla returned no summary")
            val legs = trip.optJSONArray("legs") ?: JSONArray()
            val points = buildList {
                for (index in 0 until legs.length()) {
                    val encoded = legs.optJSONObject(index)?.optString("shape").orEmpty()
                    if (encoded.isBlank()) continue
                    val decoded = decodePolyline6(encoded)
                    if (isNotEmpty() && decoded.isNotEmpty() && last() == decoded.first()) addAll(decoded.drop(1)) else addAll(decoded)
                }
            }
            if (points.size < 2) error("Valhalla route shape is empty")
            if (enforceMotorwayBan) validateMotorwayFree(points)
            return RawRoute(
                distanceMeters = summary.optDouble("length", 0.0) * 1000.0,
                durationSeconds = summary.optDouble("time", 0.0),
                points = points,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun autoCosting(preferences: ScenicPreferences, profile: RoadProfile) = JSONObject().apply {
        put("use_highways", if (preferences.avoidMotorways) 0.0 else profile.useHighways)
        put("use_tolls", if (preferences.avoidTolls) 0.0 else 0.5)
        put("use_ferry", 0.35)
        put("use_tracks", 0.0)
        put("exclude_unpaved", true)
        put("shortest", profile.shortest)
    }

    private fun locationsJson(locations: List<GeoPoint>, breaks: Boolean = false) = JSONArray().apply {
        locations.forEach { point ->
            put(JSONObject().apply {
                put("lat", point.lat)
                put("lon", point.lon)
                if (breaks) put("type", "break")
            })
        }
    }

    private fun validateMotorwayFree(points: List<GeoPoint>) {
        val shape = routeSamples(points, 120)
        val body = JSONObject().apply {
            put("shape", locationsJson(shape))
            put("costing", "auto")
            put("shape_match", "walk_or_snap")
            put("filters", JSONObject().apply {
                put("action", "include")
                put("attributes", JSONArray(listOf("edge.road_class", "edge.length")))
            })
        }
        val connection = openValhalla("/trace_attributes", 12_000).apply {
            doOutput = true
            outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        }
        try {
            val response = JSONObject(connection.readTextOrThrow())
            val edges = response.optJSONArray("edges") ?: error("Could not validate road classes")
            var motorwayLengthKm = 0.0
            for (index in 0 until edges.length()) {
                val edge = edges.optJSONObject(index) ?: continue
                if (edge.optString("road_class") == "motorway") motorwayLengthKm += edge.optDouble("length", 0.0)
            }
            if (motorwayLengthKm > 0.001) error("No motorway-free route found for the selected constraints")
        } finally {
            connection.disconnect()
        }
    }

    private fun routeCandidate(
        id: String,
        variantLabel: String,
        route: RawRoute,
        baseline: RawRoute,
        scenePool: List<ScenePointUi>,
        included: List<ScenePointUi>,
        experienceScore: Double,
        corridorRadiusKm: Double,
        preferences: ScenicPreferences,
        noteSignal: String,
    ): RouteCandidateUi {
        val includedIds = included.mapTo(mutableSetOf()) { it.id }
        val dwell = included.sumOf { it.suggestedDwellMinutes }
        val driveExtra = max(0.0, (route.durationSeconds - baseline.durationSeconds) / 60.0)
        return RouteCandidateUi(
            id = id,
            character = RouteCharacter.BEAUTIFUL.name,
            distanceMeters = route.distanceMeters,
            durationSeconds = route.durationSeconds,
            scenicScore = debugScenicScore(route.points, included, preferences),
            extraMinutes = driveExtra,
            points = route.points,
            provider = "Valhalla · OpenStreetMap development",
            scenePoints = included + scenePool.filterNot { it.id in includedIds }.take(18 - included.size),
            strongestSignals = listOf(noteSignal),
            variantLabel = variantLabel,
            experienceScore = experienceScore,
            autoStopIds = included.map { it.id },
            driveExtraMinutes = driveExtra,
            dwellMinutes = dwell,
            totalExtraMinutes = driveExtra + dwell,
            corridorRadiusKm = corridorRadiusKm,
            dataConfidence = dataConfidence(scenePool.size, scenePool.size),
        )
    }

    private fun directCandidate(route: RawRoute) = RouteCandidateUi(
        id = "journey-direct",
        character = RouteCharacter.DIRECT.name,
        distanceMeters = route.distanceMeters,
        durationSeconds = route.durationSeconds,
        scenicScore = 0.0,
        extraMinutes = 0.0,
        points = route.points,
        provider = "Valhalla · OpenStreetMap development",
        variantLabel = "Direct",
        experienceScore = 0.0,
        driveExtraMinutes = 0.0,
        dwellMinutes = 0,
        totalExtraMinutes = 0.0,
        corridorRadiusKm = 0.0,
        dataConfidence = 1.0,
    )

    private fun strongestSignals(
        included: List<ScenePointUi>,
        preferences: ScenicPreferences,
        strategy: JourneyStrategy,
    ): List<String> = buildList {
        add("journeyOptimizer")
        if (preferences.avoidMotorways) add("motorwayAvoidance")
        if (included.any { it.kind == StopKind.MONUMENT.name }) add("monuments")
        if (included.any { it.kind == StopKind.VIEWPOINT.name }) add("viewpoints")
        if (included.any { it.kind == StopKind.WATER.name }) add("water")
        if (strategy == JourneyStrategy.HIGHLIGHT_HUNTER) add("diverseHighlights")
        if (strategy == JourneyStrategy.SCENIC_DRIVE) add("scenicRoadFreedom")
    }.distinct().take(5)

    private fun dnaWeight(kind: StopKind, w: ScenicWeights): Double = when (kind) {
        StopKind.VIEWPOINT -> w.viewpoints.toDouble()
        StopKind.MUSEUM -> w.museums.toDouble()
        StopKind.NATURE -> ((w.forest + w.mountains) / 2f).toDouble()
        StopKind.MONUMENT -> ((w.monuments + w.culture) / 2f).toDouble()
        StopKind.PARK -> w.parks.toDouble()
        StopKind.ART -> w.art.toDouble()
        StopKind.WORSHIP -> w.worship.toDouble()
        StopKind.WATER -> w.water.toDouble()
        StopKind.FOOD -> w.food.toDouble()
        StopKind.ARCHITECTURE -> w.architecture.toDouble()
        StopKind.SCENIC -> w.scenicHighlights.toDouble()
        StopKind.CUSTOM -> 1.0
    }.coerceIn(0.0, 1.0)

    private fun heritageBonus(point: ScenePointUi): Double = when (point.subtype.orEmpty().lowercase()) {
        "castle", "defensive_castle", "stately", "palace", "manor" -> 26.0
        "fort", "ruins" -> 18.0
        "waterfall", "viewpoint", "lighthouse" -> 14.0
        else -> 0.0
    }

    private fun automaticStopLimit(budgetMinutes: Int, configuredMax: Int): Int {
        val budgetLimit = when {
            budgetMinutes >= 240 -> 6
            budgetMinutes >= 180 -> 5
            budgetMinutes >= 120 -> 4
            budgetMinutes >= 75 -> 3
            budgetMinutes >= 40 -> 2
            budgetMinutes >= 20 -> 1
            else -> 0
        }
        return min(configuredMax.coerceAtLeast(1), budgetLimit)
    }

    private fun matrixCandidateLimit(budgetMinutes: Int): Int = when {
        budgetMinutes >= 180 -> 20
        budgetMinutes >= 90 -> 17
        budgetMinutes >= 45 -> 14
        else -> 11
    }

    private fun corridorRadiusKm(budgetMinutes: Int): Double =
        (4.0 + budgetMinutes * 0.15).coerceIn(6.0, 42.0)

    private fun offsetPolyline(points: List<GeoPoint>, lateralKm: Double): List<GeoPoint> {
        if (points.size < 2 || abs(lateralKm) < 0.1) return points
        val samples = routeSamples(points, 18)
        val heading = bearing(samples.first(), samples.last())
        val lateralBearing = heading + if (lateralKm >= 0) 90.0 else -90.0
        return samples.map { destinationPoint(it, abs(lateralKm) * 1000.0, lateralBearing) }
    }

    private fun destinationPoint(start: GeoPoint, distanceMeters: Double, bearingDegrees: Double): GeoPoint {
        val earth = 6_371_000.0
        val angular = distanceMeters / earth
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(start.lat)
        val lon1 = Math.toRadians(start.lon)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2),
        )
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun debugScenicScore(route: List<GeoPoint>, stops: List<ScenePointUi>, preferences: ScenicPreferences): Double {
        val samples = routeSamples(route, 35)
        var turnEnergy = 0.0
        for (i in 1 until samples.lastIndex) {
            var delta = abs(bearing(samples[i - 1], samples[i]) - bearing(samples[i], samples[i + 1]))
            if (delta > 180) delta = 360 - delta
            if (delta in 10.0..100.0) turnEnergy += (delta / 100.0).coerceAtMost(1.0)
        }
        val bend = (turnEnergy / max(1, samples.size - 2)).coerceIn(0.0, 1.0)
        val stopQuality = if (stops.isEmpty()) 0.35 else stops.map { (it.relevance / 1.3).coerceIn(0.0, 1.0) }.average()
        val dna = if (stops.isEmpty()) 0.5 else stops.map {
            dnaWeight(StopKind.entries.firstOrNull { kind -> kind.name == it.kind } ?: StopKind.SCENIC, preferences.weights)
        }.average()
        return (38.0 + bend * 24.0 + stopQuality * 22.0 + dna * 16.0).coerceIn(0.0, 100.0)
    }

    private fun dataConfidence(discovered: Int, matrixCandidates: Int): Double {
        val discovery = (discovered / 20.0).coerceIn(0.0, 1.0)
        val matrix = (matrixCandidates / 14.0).coerceIn(0.0, 1.0)
        return (0.45 + discovery * 0.30 + matrix * 0.25).coerceIn(0.0, 1.0)
    }

    private fun routeSamples(route: List<GeoPoint>, maxSamples: Int): List<GeoPoint> {
        if (route.size <= maxSamples) return route
        val step = (route.size - 1).toDouble() / (maxSamples - 1).coerceAtLeast(1)
        return (0 until maxSamples).map { index -> route[(index * step).roundToInt().coerceIn(0, route.lastIndex)] }
    }

    private fun routeKey(route: RouteCandidateUi): String =
        "${(route.distanceMeters / 300).roundToInt()}:${(route.durationSeconds / 90).roundToInt()}:${route.autoStopIds.sorted().joinToString(",")}" 

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
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

    private fun openValhalla(path: String, timeoutMs: Int): HttpURLConnection =
        (URL("$VALHALLA_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
            setRequestProperty("X-Client-Id", CLIENT_ID)
        }

    private fun HttpURLConnection.readTextOrThrow(): String {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("HTTP $code ${text.take(180)}")
        return text
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
