package cloud.kosch.scenicpath

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Waypoint-aware planner with three hard invariants:
 * 1) flexible POIs are visited in forward corridor order;
 * 2) the detour required to reach fixed POIs counts against the user's global time budget;
 * 3) scenic leg upgrades can only spend what remains of that same budget.
 */
object WaypointAwareJourneyOptimizer {
    suspend fun plan(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi {
        val rawStops = plan.stops.filter { it.mustVisit && it.point != null }
        if (rawStops.isEmpty()) return SegmentedJourneyOptimizer.plan(origin, destination, plan, preferences)

        val baselinePlan = plan.copy(
            routeCharacter = RouteCharacter.DIRECT,
            stops = emptyList(),
            flexibleStopOrder = false,
            autoSuggestStops = false,
        )
        val baseline = pickDirect(
            SegmentedJourneyOptimizer.plan(
                origin,
                destination,
                baselinePlan,
                preferences.forCharacter(RouteCharacter.DIRECT).copy(maxStops = 0),
            )
        )

        val mandatoryStops = if (plan.flexibleStopOrder) {
            rawStops.sortedWith(
                compareBy<PlannedStop> { stop -> routeProgressIndex(baseline.points, requireNotNull(stop.point)) }
                    .thenBy { stop -> distanceToRouteMeters(baseline.points, requireNotNull(stop.point)) }
            )
        } else rawStops

        val nodes = buildList {
            add(origin)
            mandatoryStops.forEach { add(requireNotNull(it.point)) }
            add(destination)
        }
        val legDistances = nodes.zipWithNext().map { (a, b) -> haversineMeters(a, b).coerceAtLeast(1.0) }
        val manualDwell = mandatoryStops.sumOf { it.dwellMinutes }
        val totalBudget = preferences.maxExtraMinutes.coerceAtLeast(0)

        // First pass: direct legs only. These reveal the true mandatory detour caused by the POIs.
        val directLegResults = nodes.zipWithNext().map { (from, to) ->
            val directPlan = plan.copy(
                routeCharacter = RouteCharacter.DIRECT,
                stops = emptyList(),
                flexibleStopOrder = false,
                autoSuggestStops = false,
            )
            SegmentedJourneyOptimizer.plan(
                from,
                to,
                directPlan,
                preferences.forCharacter(RouteCharacter.DIRECT).copy(maxStops = 0),
            )
        }
        val directPieces = directLegResults.map(::pickDirect)
        var direct = stitch(
            id = "waypoint-direct",
            label = "Direct via waypoints",
            pieces = directPieces,
            requestedCharacter = RouteCharacter.DIRECT,
            manualHighlights = mandatoryStops.map(::toHighlight),
            manualDwellMinutes = manualDwell,
            direct = true,
        )
        validateMandatoryStops(direct.points, mandatoryStops)

        val mandatoryDriveExtra = max(0.0, (direct.durationSeconds - baseline.durationSeconds) / 60.0)
        val mandatoryTotalExtra = mandatoryDriveExtra + manualDwell
        direct = direct.copy(
            extraMinutes = mandatoryDriveExtra,
            driveExtraMinutes = mandatoryDriveExtra,
            totalExtraMinutes = mandatoryTotalExtra,
        )

        val scenicDriveBudget = (totalBudget - mandatoryTotalExtra.roundToInt()).coerceAtLeast(0)
        val reordered = mandatoryStops.map { it.id } != rawStops.map { it.id }
        if (plan.routeCharacter == RouteCharacter.DIRECT || scenicDriveBudget <= 0) {
            return RoutePlanUi(
                candidates = listOf(direct),
                baselineDurationSeconds = baseline.durationSeconds,
                baselineDistanceMeters = baseline.distanceMeters,
                note = note(
                    mandatoryStops,
                    plan,
                    totalBudget,
                    mandatoryDriveExtra,
                    scenicMinutes = 0,
                    reordered = reordered,
                    overBudget = mandatoryTotalExtra > totalBudget + 1.0,
                ),
            )
        }

        // Second pass: each leg may offer a scenic upgrade, but all upgrades share only the
        // remaining budget after fixed-POI travel + dwell time has been paid.
        val legBudgets = allocate(scenicDriveBudget, legDistances)
        val legStopQuotas = if (plan.autoSuggestStops) allocate(preferences.maxStops.coerceAtLeast(0), legDistances)
        else List(legDistances.size) { 0 }
        val scenicLegResults = nodes.zipWithNext().mapIndexed { index, (from, to) ->
            val legPlan = plan.copy(
                routeCharacter = RouteCharacter.CUSTOM,
                stops = emptyList(),
                flexibleStopOrder = false,
                autoSuggestStops = plan.autoSuggestStops && legStopQuotas[index] > 0,
            )
            SegmentedJourneyOptimizer.plan(
                from,
                to,
                legPlan,
                preferences.copy(maxExtraMinutes = legBudgets[index], maxStops = legStopQuotas[index]),
            )
        }

        data class Upgrade(val index: Int, val candidate: RouteCandidateUi, val extraMinutes: Double, val value: Double)
        val upgrades = scenicLegResults.mapIndexedNotNull { index, result ->
            val scenic = pickScenic(result, plan.routeCharacter)
            val base = directPieces[index]
            val extra = max(0.0, (scenic.durationSeconds - base.durationSeconds) / 60.0)
            if (extra <= 0.25) null else {
                val gain = max(0.0, scenic.experienceScore - base.experienceScore) + scenic.scenicScore * 0.35
                Upgrade(index, scenic, extra, gain / extra.coerceAtLeast(1.0))
            }
        }.sortedByDescending { it.value }

        val chosenPieces = directPieces.toMutableList()
        var scenicSpent = 0.0
        upgrades.forEach { upgrade ->
            if (scenicSpent + upgrade.extraMinutes <= scenicDriveBudget + 0.5) {
                chosenPieces[upgrade.index] = upgrade.candidate
                scenicSpent += upgrade.extraMinutes
            }
        }

        var scenic = stitch(
            id = "waypoint-scenic",
            label = "Best match via waypoints",
            pieces = chosenPieces,
            requestedCharacter = plan.routeCharacter,
            manualHighlights = mandatoryStops.map(::toHighlight),
            manualDwellMinutes = manualDwell,
            direct = false,
        )
        validateMandatoryStops(scenic.points, mandatoryStops)

        val totalDriveExtra = max(0.0, (scenic.durationSeconds - baseline.durationSeconds) / 60.0)
        val totalExtra = totalDriveExtra + scenic.dwellMinutes
        if (totalExtra > totalBudget + 1.0 && mandatoryTotalExtra <= totalBudget + 1.0) {
            scenic = direct.copy(
                id = "waypoint-budget-safe",
                character = plan.routeCharacter.name,
                variantLabel = "Budget-safe via waypoints",
                strongestSignals = (direct.strongestSignals + "globalBudgetGuard").distinct(),
            )
        } else {
            scenic = scenic.copy(
                extraMinutes = totalDriveExtra,
                driveExtraMinutes = totalDriveExtra,
                totalExtraMinutes = totalExtra,
                strongestSignals = (scenic.strongestSignals + listOf("forwardPoiOrder", "mandatoryDetourBudget", "globalBudgetGuard")).distinct().take(8),
            )
        }

        return RoutePlanUi(
            candidates = listOf(scenic, direct).distinctBy(::candidateKey),
            baselineDurationSeconds = baseline.durationSeconds,
            baselineDistanceMeters = baseline.distanceMeters,
            note = note(
                mandatoryStops,
                plan,
                totalBudget,
                mandatoryDriveExtra,
                scenicMinutes = scenicSpent.roundToInt(),
                reordered = reordered,
                overBudget = mandatoryTotalExtra > totalBudget + 1.0,
            ),
        )
    }

    private fun note(
        stops: List<PlannedStop>,
        plan: TripPlan,
        totalBudget: Int,
        mandatoryDriveMinutes: Double,
        scenicMinutes: Int,
        reordered: Boolean,
        overBudget: Boolean,
    ): String = buildString {
        append("${stops.size} fixed waypoint${if (stops.size == 1) "" else "s"}")
        if (reordered) append(" · automatically ordered along the route")
        append(" · ${mandatoryDriveMinutes.roundToInt()} min mandatory POI detour")
        if (scenicMinutes > 0) append(" · +$scenicMinutes min scenic-road upgrade")
        append(" · ${totalBudget} min global budget")
        if (overBudget) append(" · fixed POI itself exceeds the time budget")
        if (plan.autoSuggestStops) append(" · Smart Stops use only remaining budget")
    }

    private fun pickDirect(result: RoutePlanUi): RouteCandidateUi =
        result.candidates.firstOrNull {
            it.character == RouteCharacter.DIRECT.name || it.id.contains("direct", ignoreCase = true)
        } ?: result.candidates.minByOrNull { it.durationSeconds }
        ?: error("Waypoint leg returned no direct route")

    private fun pickScenic(result: RoutePlanUi, requested: RouteCharacter): RouteCandidateUi {
        if (requested == RouteCharacter.DIRECT) return pickDirect(result)
        return result.candidates.firstOrNull {
            it.character != RouteCharacter.DIRECT.name && !it.id.contains("direct", ignoreCase = true)
        } ?: result.candidates.maxByOrNull { it.experienceScore } ?: pickDirect(result)
    }

    private fun stitch(
        id: String,
        label: String,
        pieces: List<RouteCandidateUi>,
        requestedCharacter: RouteCharacter,
        manualHighlights: List<ScenePointUi>,
        manualDwellMinutes: Int,
        direct: Boolean,
    ): RouteCandidateUi {
        require(pieces.isNotEmpty()) { "Waypoint journey returned no route pieces" }
        val points = buildList {
            pieces.forEach { piece ->
                if (isEmpty()) addAll(piece.points)
                else if (piece.points.isNotEmpty()) {
                    if (last() == piece.points.first()) addAll(piece.points.drop(1)) else addAll(piece.points)
                }
            }
        }
        if (points.size < 2) error("Waypoint journey route shape is empty")

        val distance = pieces.sumOf { it.distanceMeters }
        val duration = pieces.sumOf { it.durationSeconds }
        val weightTotal = pieces.sumOf { it.distanceMeters.coerceAtLeast(1.0) }
        fun weighted(selector: (RouteCandidateUi) -> Double): Double =
            pieces.sumOf { selector(it) * it.distanceMeters.coerceAtLeast(1.0) } / weightTotal
        val manualIds = manualHighlights.mapTo(mutableSetOf()) { it.id }
        val scenePoints = buildList {
            addAll(manualHighlights)
            pieces.flatMap { it.scenePoints }.forEach { point -> if (point.id !in manualIds) add(point) }
        }.distinctBy { it.id }
        val automaticDwell = pieces.sumOf { it.dwellMinutes }

        return RouteCandidateUi(
            id = id,
            character = if (direct) RouteCharacter.DIRECT.name else requestedCharacter.name,
            distanceMeters = distance,
            durationSeconds = duration,
            scenicScore = if (direct) 0.0 else weighted { it.scenicScore },
            extraMinutes = 0.0,
            points = points,
            provider = "Waypoint-aware Journey Optimizer · Valhalla / OpenStreetMap development",
            scenePoints = scenePoints,
            strongestSignals = (listOf("fixedWaypoints") + pieces.flatMap { it.strongestSignals }).distinct().take(8),
            isPreviewFallback = pieces.any { it.isPreviewFallback },
            variantLabel = label,
            experienceScore = if (direct) 0.0 else weighted { it.experienceScore },
            autoStopIds = pieces.flatMap { it.autoStopIds }.distinct(),
            driveExtraMinutes = 0.0,
            dwellMinutes = manualDwellMinutes + automaticDwell,
            totalExtraMinutes = (manualDwellMinutes + automaticDwell).toDouble(),
            corridorRadiusKm = pieces.maxOfOrNull { it.corridorRadiusKm } ?: 0.0,
            dataConfidence = pieces.map { it.dataConfidence }.averageOrZero(),
        )
    }

    private fun toHighlight(stop: PlannedStop): ScenePointUi = ScenePointUi(
        id = stop.id,
        name = stop.name,
        kind = stop.kind.name,
        subtype = stop.subtype,
        point = requireNotNull(stop.point),
        relevance = 1.25,
        suggestionScore = 250.0,
        distanceFromRouteMeters = 0,
        suggestedDwellMinutes = stop.dwellMinutes,
        rating = stop.rating,
        ratingCount = stop.ratingCount,
        includedInRoute = true,
        personalMatch = 100.0,
        rationale = "fixed waypoint · must visit · ordered with route flow",
        estimatedDetourMinutes = 0.0,
    )

    private fun validateMandatoryStops(route: List<GeoPoint>, stops: List<PlannedStop>) {
        stops.forEach { stop ->
            val point = stop.point ?: return@forEach
            if (distanceToRouteMeters(route, point) > 900.0) error("Recalculated route bypassed fixed waypoint: ${stop.name}")
        }
    }

    private fun routeProgressIndex(route: List<GeoPoint>, point: GeoPoint): Int =
        route.indices.minByOrNull { haversineMeters(route[it], point) } ?: Int.MAX_VALUE

    private fun distanceToRouteMeters(route: List<GeoPoint>, point: GeoPoint): Double =
        route.minOfOrNull { haversineMeters(it, point) } ?: Double.POSITIVE_INFINITY

    private fun allocate(total: Int, weights: List<Double>): List<Int> {
        if (weights.isEmpty()) return emptyList()
        if (total <= 0) return List(weights.size) { 0 }
        val sum = weights.sum().takeIf { it > 0.0 } ?: return List(weights.size) { 0 }
        val raw = weights.map { total * (it / sum) }
        val result = raw.map { floor(it).toInt() }.toMutableList()
        var remainder = total - result.sum()
        raw.indices.sortedByDescending { raw[it] - floor(raw[it]) }.forEach { index ->
            if (remainder > 0) { result[index] += 1; remainder-- }
        }
        return result
    }

    private fun candidateKey(candidate: RouteCandidateUi): String =
        "${(candidate.distanceMeters / 250.0).toInt()}:${(candidate.durationSeconds / 60.0).toInt()}:${candidate.autoStopIds.sorted().joinToString(",")}" 

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val earth = 6_371_000.0
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val sinLat = kotlin.math.sin(dLat / 2.0)
        val sinLon = kotlin.math.sin(dLon / 2.0)
        val h = sinLat * sinLat + kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * sinLon * sinLon
        return 2.0 * earth * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
