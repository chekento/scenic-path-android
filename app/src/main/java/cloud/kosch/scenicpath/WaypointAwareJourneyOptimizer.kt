package cloud.kosch.scenicpath

import kotlin.math.floor
import kotlin.math.max

/**
 * Hard-constraint wrapper for user-added waypoints.
 *
 * A manual waypoint is not a suggestion. Once the user adds it to the itinerary, every
 * recalculated candidate must physically route through that coordinate. The long-distance
 * segmented optimizer historically rebuilt A→B first and only considered automatically
 * discovered POIs afterwards, so manually added points could remain visible in the plan while
 * the blue polyline bypassed them.
 *
 * This wrapper turns the ordered manual stop list into routing breaks:
 *
 *   origin → stop 1 → stop 2 → … → destination
 *
 * Every leg still uses the existing Journey Optimizer (including its long-distance guard), so
 * we keep scenic-road behavior, Smart Stops and provider fallbacks. The global extra-time and
 * automatic-stop budgets are distributed across the legs instead of being multiplied per leg.
 */
object WaypointAwareJourneyOptimizer {
    suspend fun plan(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi {
        val mandatoryStops = plan.stops.filter { it.mustVisit && it.point != null }
        if (mandatoryStops.isEmpty()) {
            return SegmentedJourneyOptimizer.plan(origin, destination, plan, preferences)
        }

        val nodes = buildList {
            add(origin)
            mandatoryStops.forEach { add(requireNotNull(it.point)) }
            add(destination)
        }
        val legDistances = nodes.zipWithNext().map { (a, b) -> haversineMeters(a, b).coerceAtLeast(1.0) }
        val legBudgets = allocate(preferences.maxExtraMinutes.coerceAtLeast(0), legDistances)
        val legStopQuotas = if (plan.autoSuggestStops) {
            allocate(preferences.maxStops.coerceAtLeast(0), legDistances)
        } else {
            List(legDistances.size) { 0 }
        }

        val legResults = nodes.zipWithNext().mapIndexed { index, (from, to) ->
            val legCharacter = if (plan.routeCharacter == RouteCharacter.DIRECT) {
                RouteCharacter.DIRECT
            } else {
                // Preferences already carry the requested Scenic DNA/constraints. CUSTOM avoids
                // re-applying a preset floor independently to every leg and inflating the budget.
                RouteCharacter.CUSTOM
            }
            val legPlan = plan.copy(
                routeCharacter = legCharacter,
                stops = emptyList(),
                flexibleStopOrder = false,
                autoSuggestStops = plan.autoSuggestStops && legStopQuotas[index] > 0,
            )
            val legPreferences = preferences.copy(
                maxExtraMinutes = legBudgets[index],
                maxStops = legStopQuotas[index],
            )
            SegmentedJourneyOptimizer.plan(from, to, legPlan, legPreferences)
        }

        val directPieces = legResults.map(::pickDirect)
        val scenicPieces = legResults.map { pickScenic(it, plan.routeCharacter) }
        val manualHighlights = mandatoryStops.map(::toHighlight)

        val direct = stitch(
            id = "waypoint-direct",
            label = "Direct via waypoints",
            pieces = directPieces,
            requestedCharacter = RouteCharacter.DIRECT,
            manualHighlights = manualHighlights,
            manualDwellMinutes = mandatoryStops.sumOf { it.dwellMinutes },
            direct = true,
        )
        val scenicBase = stitch(
            id = "waypoint-scenic",
            label = if (plan.routeCharacter == RouteCharacter.DIRECT) "Direct via waypoints" else "Best match via waypoints",
            pieces = scenicPieces,
            requestedCharacter = plan.routeCharacter,
            manualHighlights = manualHighlights,
            manualDwellMinutes = mandatoryStops.sumOf { it.dwellMinutes },
            direct = plan.routeCharacter == RouteCharacter.DIRECT,
        )

        val driveExtra = max(0.0, (scenicBase.durationSeconds - direct.durationSeconds) / 60.0)
        val scenic = scenicBase.copy(
            extraMinutes = driveExtra,
            driveExtraMinutes = driveExtra,
            totalExtraMinutes = driveExtra + scenicBase.dwellMinutes,
        )

        val orderedCandidates = if (plan.routeCharacter == RouteCharacter.DIRECT) {
            listOf(direct, scenic).distinctBy(::candidateKey)
        } else {
            listOf(scenic, direct).distinctBy(::candidateKey)
        }

        return RoutePlanUi(
            candidates = orderedCandidates,
            baselineDurationSeconds = direct.durationSeconds,
            baselineDistanceMeters = direct.distanceMeters,
            note = buildString {
                append("${mandatoryStops.size} fixed waypoint")
                if (mandatoryStops.size != 1) append("s")
                append(" enforced as routing breaks")
                append(" · shared +${preferences.maxExtraMinutes} min scenic budget")
                if (plan.autoSuggestStops) append(" · Smart Stops distributed across ${legResults.size} route leg${if (legResults.size == 1) "" else "s"}")
            },
        )
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
        } ?: result.candidates.maxByOrNull { it.experienceScore }
        ?: pickDirect(result)
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
                if (isEmpty()) {
                    addAll(piece.points)
                } else if (piece.points.isNotEmpty()) {
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
            // Manual points go first so deduplication always retains the included/glowing copy.
            addAll(manualHighlights)
            pieces.flatMap { it.scenePoints }.forEach { point ->
                if (point.id !in manualIds) add(point)
            }
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
            strongestSignals = (listOf("fixedWaypoints") + pieces.flatMap { it.strongestSignals }).distinct().take(7),
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
        rationale = "fixed waypoint · must visit",
        estimatedDetourMinutes = 0.0,
    )

    /** Integer proportional allocation whose parts always sum to [total]. */
    private fun allocate(total: Int, weights: List<Double>): List<Int> {
        if (weights.isEmpty()) return emptyList()
        if (total <= 0) return List(weights.size) { 0 }
        val sum = weights.sum().takeIf { it > 0.0 } ?: return List(weights.size) { 0 }
        val raw = weights.map { total * (it / sum) }
        val result = raw.map { floor(it).toInt() }.toMutableList()
        var remainder = total - result.sum()
        raw.indices
            .sortedByDescending { raw[it] - floor(raw[it]) }
            .forEach { index ->
                if (remainder > 0) {
                    result[index] += 1
                    remainder--
                }
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
