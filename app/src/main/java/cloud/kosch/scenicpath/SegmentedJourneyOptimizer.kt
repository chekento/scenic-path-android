package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Long-distance adapter for the public development routing stack.
 *
 * v0.4.2 deliberately does NOT run the full matrix Journey Optimizer once per segment.
 * That multiplied expensive public Valhalla/Overpass calls and could take minutes.
 *
 * Long trips now use a two-phase strategy:
 * 1. build safe road segments without POI discovery and stitch one continuous base trip;
 * 2. discover POIs once across the stitched journey, choose a small global stop set, and
 *    reroute only the segments that actually contain an accepted stop.
 *
 * The user still sees one route and one shared extra-time budget.
 */
object SegmentedJourneyOptimizer {
    private const val PREEMPTIVE_SEGMENT_THRESHOLD_METERS = 145_000.0
    private const val TARGET_SEGMENT_SPAN_METERS = 105_000.0
    private const val SEGMENT_BATCH_SIZE = 2

    suspend fun plan(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi {
        val straightLine = haversineMeters(origin, destination)
        if (straightLine > PREEMPTIVE_SEGMENT_THRESHOLD_METERS) {
            return planLongJourney(origin, destination, plan, preferences, forceMultipleSegments = false)
        }

        return try {
            ScenicJourneyOptimizer.plan(origin, destination, plan, preferences)
        } catch (error: Throwable) {
            if (!isProviderDistanceLimit(error)) throw error
            planLongJourney(origin, destination, plan, preferences, forceMultipleSegments = true)
        }
    }

    private suspend fun planLongJourney(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
        forceMultipleSegments: Boolean,
    ): RoutePlanUi {
        val effective = preferences.forCharacter(plan.routeCharacter)
        val anchors = buildAnchors(origin, destination, forceMultipleSegments)
        val segmentCount = anchors.size - 1
        if (segmentCount <= 1) return ScenicJourneyOptimizer.plan(origin, destination, plan, effective)

        val totalBudget = if (plan.routeCharacter == RouteCharacter.DIRECT) 10 else effective.maxExtraMinutes

        // Phase 1: roads only. No Overpass, no Photon, no matrix, no auto-stop trial loop.
        // At most two independent segments are planned concurrently so the public demo is
        // not flooded on very long trips.
        val baseSegments = mutableListOf<RoutePlanUi>()
        for (batch in (0 until segmentCount).toList().chunked(SEGMENT_BATCH_SIZE)) {
            val results = coroutineScope {
                batch.map { index ->
                    async(Dispatchers.IO) {
                        planRoadsOnlySegment(
                            origin = anchors[index],
                            destination = anchors[index + 1],
                            originalPlan = plan,
                            preferences = effective,
                        )
                    }
                }.awaitAll()
            }
            baseSegments += results
        }

        val directPieces = baseSegments.map(::pickDirect)
        val scenicPieces = baseSegments.map(::pickScenic)
        val direct = stitchCandidate("Direct", directPieces, plan.routeCharacter)
        var scenic = stitchCandidate("Scenic drive", scenicPieces, plan.routeCharacter)

        if (plan.routeCharacter == RouteCharacter.DIRECT || !plan.autoSuggestStops) {
            return guaranteedPlan(
                scenic = scenic,
                direct = direct,
                totalBudget = totalBudget,
                segmentCount = segmentCount,
                noteSuffix = "roads-only long-route mode",
            )
        }

        // Phase 2: one capped diverse discovery pass over the complete stitched journey.
        // If targeted OSM enrichment is slow, fall back to the quicker Photon pool. The
        // already-valid road route is never sacrificed for POI discovery.
        val diverseDiscoveries = withTimeoutOrNull(12_000) {
            runCatching {
                FastRoutePoiDiscovery.discover(
                    route = scenic.points,
                    enabledKinds = plan.enabledSceneKinds,
                    maxResults = 36,
                )
            }.getOrElse { emptyList() }
        }.orEmpty()
        val discoveries = diverseDiscoveries.ifEmpty {
            runCatching {
                PhotonSceneFallback.discover(
                    route = scenic.points,
                    enabledKinds = plan.enabledSceneKinds,
                    maxResults = 28,
                )
            }.getOrElse { emptyList() }
        }

        if (discoveries.isEmpty()) {
            return guaranteedPlan(
                scenic = scenic,
                direct = direct,
                totalBudget = totalBudget,
                segmentCount = segmentCount,
                noteSuffix = "fast global discovery returned no POIs",
            )
        }

        var selected = chooseGlobalStops(discoveries, effective, totalBudget)
        var includedIds = emptySet<String>()

        // Exact validation. If the real detour is more expensive than the estimate,
        // remove the weakest stop and retry. At every point we still have a complete base
        // scenic route and Direct fallback available.
        while (selected.isNotEmpty()) {
            val rerouted = rerouteSelectedStops(
                anchors = anchors,
                baseScenicPieces = scenicPieces,
                selected = selected,
                originalPlan = plan,
                preferences = effective,
            )
            val candidate = stitchCandidate("Best match", rerouted.pieces, plan.routeCharacter)
            val driveExtra = max(0.0, (candidate.durationSeconds - direct.durationSeconds) / 60.0)
            val dwell = selected.filter { it.id in rerouted.includedIds }.sumOf { it.suggestedDwellMinutes }
            val totalExtra = driveExtra + dwell

            if (rerouted.includedIds.isNotEmpty() && totalExtra <= totalBudget + 1.0) {
                includedIds = rerouted.includedIds
                val included = discoveries
                    .filter { it.id in includedIds }
                    .sortedBy { routeProgressIndex(candidate.points, it.point) }
                    .map { poi ->
                        poi.copy(
                            includedInRoute = true,
                            personalMatch = personalMatch(poi, effective),
                            rationale = rationale(poi, effective),
                            estimatedDetourMinutes = poi.distanceFromRouteMeters / 500.0,
                        )
                    }
                val alternatives = discoveries
                    .filterNot { it.id in includedIds }
                    .sortedByDescending { stopUtility(it, effective) }
                    .take(max(0, 18 - included.size))
                    .map { poi ->
                        poi.copy(
                            includedInRoute = false,
                            personalMatch = personalMatch(poi, effective),
                            rationale = rationale(poi, effective),
                            estimatedDetourMinutes = poi.distanceFromRouteMeters / 500.0,
                        )
                    }

                scenic = candidate.copy(
                    scenePoints = included + alternatives,
                    autoStopIds = included.map { it.id },
                    dwellMinutes = dwell,
                    driveExtraMinutes = driveExtra,
                    extraMinutes = driveExtra,
                    totalExtraMinutes = totalExtra,
                    variantLabel = "Best match",
                    experienceScore = longExperienceScore(candidate, included, effective, totalBudget, totalExtra),
                    strongestSignals = buildList {
                        add("fastLongRouteOptimizer")
                        add("globalPoiPool")
                        if (effective.avoidMotorways) add("motorwayAvoidance")
                        if (included.any { it.kind == StopKind.MONUMENT.name }) add("heritage")
                        if (included.any { it.kind == StopKind.VIEWPOINT.name }) add("viewpoints")
                        if (included.any { it.kind == StopKind.WATER.name }) add("water")
                        if (included.any { it.kind == StopKind.FOOD.name }) add("topFood")
                    }.take(6),
                )
                break
            }

            selected = dropWeakestPreservingFood(selected, effective)
        }

        if (includedIds.isEmpty()) {
            val alternatives = discoveries
                .sortedByDescending { stopUtility(it, effective) }
                .take(18)
                .map { poi ->
                    poi.copy(
                        includedInRoute = false,
                        personalMatch = personalMatch(poi, effective),
                        rationale = rationale(poi, effective),
                        estimatedDetourMinutes = poi.distanceFromRouteMeters / 500.0,
                    )
                }
            scenic = scenic.copy(scenePoints = alternatives, variantLabel = "Scenic drive")
        }

        return RoutePlanUi(
            candidates = listOf(scenic, direct).distinctBy(::routeKey),
            baselineDurationSeconds = direct.durationSeconds,
            baselineDistanceMeters = direct.distanceMeters,
            note = buildString {
                append("Fast long-route planner · $segmentCount safe road segments")
                append(" · one global POI search")
                append(" · shared +$totalBudget min budget")
                if (includedIds.isNotEmpty()) append(" · ${includedIds.size} Smart Stop${if (includedIds.size == 1) "" else "s"} included")
                else append(" · ${discoveries.size} automatic suggestions")
                if (includedIds.any { id -> discoveries.any { it.id == id && it.kind == StopKind.FOOD.name } }) append(" · Top Food included")
                if (effective.avoidMotorways) append(" · motorway avoidance preserved")
            },
        )
    }

    private suspend fun planRoadsOnlySegment(
        origin: GeoPoint,
        destination: GeoPoint,
        originalPlan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi {
        val segmentCharacter = if (originalPlan.routeCharacter == RouteCharacter.DIRECT) {
            RouteCharacter.DIRECT
        } else {
            RouteCharacter.CUSTOM
        }
        val roadsOnlyPlan = originalPlan.copy(
            routeCharacter = segmentCharacter,
            autoSuggestStops = false,
            stops = emptyList(),
        )
        val roadsOnlyPreferences = preferences.copy(
            maxExtraMinutes = 0,
            maxStops = 0,
        )

        return runCatching {
            OsmScenicRoutingFallback.plan(origin, destination, roadsOnlyPlan, roadsOnlyPreferences)
        }.getOrElse {
            // Last transport fallback: one direct request path through the v0.4 optimizer.
            // It guarantees that a slow POI provider cannot turn a valid A→B journey into
            // an empty result.
            ScenicJourneyOptimizer.plan(
                origin,
                destination,
                roadsOnlyPlan.copy(routeCharacter = RouteCharacter.DIRECT),
                roadsOnlyPreferences,
            )
        }.also {
            if (it.candidates.isEmpty()) error("Road segment returned no route")
        }
    }

    private data class RerouteResult(
        val pieces: List<RouteCandidateUi>,
        val includedIds: Set<String>,
    )

    private suspend fun rerouteSelectedStops(
        anchors: List<GeoPoint>,
        baseScenicPieces: List<RouteCandidateUi>,
        selected: List<ScenePointUi>,
        originalPlan: TripPlan,
        preferences: ScenicPreferences,
    ): RerouteResult {
        val assignments = mutableMapOf<Int, MutableList<ScenePointUi>>()
        selected.forEach { poi ->
            val segment = baseScenicPieces.indices.minByOrNull { index ->
                nearestDistanceMeters(baseScenicPieces[index].points, poi.point)
            } ?: 0
            assignments.getOrPut(segment) { mutableListOf() } += poi
        }

        val pieces = baseScenicPieces.toMutableList()
        val included = mutableSetOf<String>()

        for (batch in assignments.keys.sorted().chunked(SEGMENT_BATCH_SIZE)) {
            val results = coroutineScope {
                batch.map { index ->
                    async(Dispatchers.IO) {
                        val stops = assignments[index].orEmpty()
                            .sortedBy { routeProgressIndex(baseScenicPieces[index].points, it.point) }
                        val planned = stops.map { poi ->
                            PlannedStop(
                                id = poi.id,
                                name = poi.name,
                                kind = StopKind.entries.firstOrNull { it.name == poi.kind } ?: StopKind.SCENIC,
                                dwellMinutes = poi.suggestedDwellMinutes,
                                locked = true,
                                mustVisit = true,
                                point = poi.point,
                                rating = poi.rating,
                                ratingCount = poi.ratingCount,
                                subtype = poi.subtype,
                            )
                        }
                        val reroutePlan = originalPlan.copy(
                            routeCharacter = RouteCharacter.CUSTOM,
                            autoSuggestStops = false,
                            flexibleStopOrder = false,
                            stops = planned,
                        )
                        val result = runCatching {
                            OsmScenicRoutingFallback.plan(
                                origin = anchors[index],
                                destination = anchors[index + 1],
                                plan = reroutePlan,
                                preferences = preferences.copy(maxStops = planned.size),
                            )
                        }.getOrNull()
                        Triple(index, result?.let(::pickScenic), if (result != null) stops.map { it.id }.toSet() else emptySet())
                    }
                }.awaitAll()
            }
            results.forEach { (index, route, ids) ->
                if (route != null) {
                    pieces[index] = route
                    included += ids
                }
            }
        }

        return RerouteResult(pieces, included)
    }

    private fun chooseGlobalStops(
        discoveries: List<ScenePointUi>,
        preferences: ScenicPreferences,
        budgetMinutes: Int,
    ): List<ScenePointUi> {
        val maxStops = when {
            budgetMinutes >= 210 -> min(3, preferences.maxStops)
            budgetMinutes >= 100 -> min(2, preferences.maxStops)
            budgetMinutes >= 30 -> min(1, preferences.maxStops)
            else -> 0
        }
        if (maxStops <= 0) return emptyList()

        val usableBudget = budgetMinutes * 0.84 // keep reserve for road-level detour error
        var used = 0.0
        val selected = mutableListOf<ScenePointUi>()
        val ranked = discoveries.sortedByDescending { stopUtility(it, preferences) }

        fun tryAdd(candidate: ScenePointUi): Boolean {
            if (selected.size >= maxStops) return false
            if (candidate.distanceFromRouteMeters > 12_000) return false
            val estimatedDetour = candidate.distanceFromRouteMeters / 500.0
            val estimatedCost = candidate.suggestedDwellMinutes + estimatedDetour
            if (used + estimatedCost > usableBudget) return false
            selected += candidate
            used += estimatedCost
            return true
        }

        // Top Food is an explicit product category. With enough budget, reserve one slot
        // for the strongest route-adjacent restaurant instead of letting common nature or
        // heritage POIs crowd food out completely.
        if (budgetMinutes >= 60) {
            discoveries
                .filter { it.kind == StopKind.FOOD.name }
                .maxByOrNull { topFoodUtility(it, preferences) }
                ?.let(::tryAdd)
        }

        for (candidate in ranked) {
            if (selected.size >= maxStops) break
            if (selected.any { it.id == candidate.id }) continue
            if (selected.any { it.kind == candidate.kind } && ranked.any { it.kind != candidate.kind }) continue
            tryAdd(candidate)
        }
        return selected
    }

    private fun dropWeakestPreservingFood(
        selected: List<ScenePointUi>,
        preferences: ScenicPreferences,
    ): List<ScenePointUi> {
        if (selected.size <= 1) return emptyList()
        val nonFood = selected.filterNot { it.kind == StopKind.FOOD.name }
        val removable = (if (nonFood.isNotEmpty()) nonFood else selected)
            .minByOrNull { stopUtility(it, preferences) }
            ?: return selected.dropLast(1)
        return selected.filterNot { it.id == removable.id }
    }

    private fun topFoodUtility(point: ScenePointUi, preferences: ScenicPreferences): Double {
        val rating = point.rating
        val reviews = point.ratingCount ?: 0
        val verified = if (rating != null) rating * 18.0 + ln((reviews + 1).toDouble()) * 4.0 else 0.0
        val restaurantBonus = if (point.subtype.equals("restaurant", ignoreCase = true)) 10.0 else 2.0
        return stopUtility(point, preferences) + verified + restaurantBonus
    }

    private fun stopUtility(point: ScenePointUi, preferences: ScenicPreferences): Double {
        val match = personalMatch(point, preferences)
        val special = when (point.subtype.orEmpty().lowercase()) {
            "castle", "defensive_castle", "stately", "palace", "manor" -> 24.0
            "fort", "ruins" -> 16.0
            "waterfall", "viewpoint", "lighthouse" -> 12.0
            "restaurant" -> 8.0
            "cafe" -> 3.0
            else -> 0.0
        }
        val detourPenalty = point.distanceFromRouteMeters / 420.0
        val dwellPenalty = point.suggestedDwellMinutes * 0.18
        return match + special - detourPenalty - dwellPenalty
    }

    private fun personalMatch(point: ScenePointUi, preferences: ScenicPreferences): Double {
        val kind = StopKind.entries.firstOrNull { it.name == point.kind } ?: StopKind.SCENIC
        val dna = when (kind) {
            StopKind.VIEWPOINT -> preferences.weights.viewpoints.toDouble()
            StopKind.MUSEUM -> preferences.weights.museums.toDouble()
            StopKind.NATURE -> ((preferences.weights.forest + preferences.weights.mountains) / 2f).toDouble()
            StopKind.MONUMENT -> ((preferences.weights.monuments + preferences.weights.culture) / 2f).toDouble()
            StopKind.PARK -> preferences.weights.parks.toDouble()
            StopKind.ART -> preferences.weights.art.toDouble()
            StopKind.WORSHIP -> preferences.weights.worship.toDouble()
            StopKind.WATER -> preferences.weights.water.toDouble()
            StopKind.FOOD -> preferences.weights.food.toDouble()
            StopKind.ARCHITECTURE -> preferences.weights.architecture.toDouble()
            StopKind.SCENIC -> preferences.weights.scenicHighlights.toDouble()
            StopKind.CUSTOM -> 1.0
        }.coerceIn(0.0, 1.0)
        val intrinsic = (point.relevance / 1.2).coerceIn(0.0, 1.0)
        return (intrinsic * 0.55 + dna * 0.45) * 100.0
    }

    private fun rationale(point: ScenePointUi, preferences: ScenicPreferences): String {
        val reasons = buildList {
            if (personalMatch(point, preferences) >= 80) add("strong Scenic DNA match")
            if (point.subtype.orEmpty().lowercase() in setOf("castle", "defensive_castle", "stately", "palace", "manor", "fort", "ruins")) add("heritage highlight")
            if (point.kind == StopKind.VIEWPOINT.name) add("viewpoint")
            if (point.kind == StopKind.WATER.name) add("water experience")
            if (point.kind == StopKind.NATURE.name || point.kind == StopKind.PARK.name) add("nature")
            if (point.kind == StopKind.FOOD.name) {
                if (point.rating != null) add("verified Top Food") else add("best available route-food candidate")
            }
            if (point.distanceFromRouteMeters <= 2500) add("small detour")
        }
        return reasons.ifEmpty { listOf("good fit for this journey") }.joinToString(" · ")
    }

    private fun longExperienceScore(
        route: RouteCandidateUi,
        included: List<ScenePointUi>,
        preferences: ScenicPreferences,
        budgetMinutes: Int,
        totalExtraMinutes: Double,
    ): Double {
        val match = included.map { personalMatch(it, preferences) }.averageOrZero()
        val budgetEfficiency = 1.0 - (totalExtraMinutes / max(1.0, budgetMinutes.toDouble())).coerceIn(0.0, 1.0)
        return (route.scenicScore * 0.48 + match * 0.44 + budgetEfficiency * 8.0).coerceIn(0.0, 100.0)
    }

    private fun guaranteedPlan(
        scenic: RouteCandidateUi,
        direct: RouteCandidateUi,
        totalBudget: Int,
        segmentCount: Int,
        noteSuffix: String,
    ): RoutePlanUi = RoutePlanUi(
        candidates = listOf(scenic, direct).distinctBy(::routeKey).ifEmpty { listOf(direct) },
        baselineDurationSeconds = direct.durationSeconds,
        baselineDistanceMeters = direct.distanceMeters,
        note = "Fast long-route planner · $segmentCount safe segments · shared +$totalBudget min budget · $noteSuffix",
    )

    private fun pickDirect(plan: RoutePlanUi): RouteCandidateUi =
        plan.candidates.firstOrNull { it.character == RouteCharacter.DIRECT.name || it.id.contains("direct", true) }
            ?: plan.candidates.minByOrNull { it.durationSeconds }
            ?: error("Segment has no direct candidate")

    private fun pickScenic(plan: RoutePlanUi): RouteCandidateUi =
        plan.candidates.firstOrNull { it.character != RouteCharacter.DIRECT.name && !it.id.contains("direct", true) }
            ?: plan.candidates.maxByOrNull { it.scenicScore }
            ?: pickDirect(plan)

    private fun stitchCandidate(
        label: String,
        pieces: List<RouteCandidateUi>,
        requestedCharacter: RouteCharacter,
    ): RouteCandidateUi {
        val points = buildList {
            pieces.forEach { piece ->
                if (isEmpty()) addAll(piece.points)
                else if (piece.points.isNotEmpty()) {
                    if (last() == piece.points.first()) addAll(piece.points.drop(1)) else addAll(piece.points)
                }
            }
        }
        val distance = pieces.sumOf { it.distanceMeters }
        val duration = pieces.sumOf { it.durationSeconds }
        val weightTotal = pieces.sumOf { it.distanceMeters.coerceAtLeast(1.0) }
        fun weighted(selector: (RouteCandidateUi) -> Double): Double =
            pieces.sumOf { selector(it) * it.distanceMeters.coerceAtLeast(1.0) } / weightTotal
        val isDirect = label.equals("Direct", true)

        return RouteCandidateUi(
            id = "long-${label.lowercase().replace(' ', '-')}",
            character = if (isDirect) RouteCharacter.DIRECT.name else requestedCharacter.name,
            distanceMeters = distance,
            durationSeconds = duration,
            scenicScore = if (isDirect) 0.0 else weighted { it.scenicScore },
            extraMinutes = 0.0,
            points = points,
            provider = "Fast segmented Journey Optimizer · Valhalla / OpenStreetMap development",
            scenePoints = pieces.flatMap { it.scenePoints }.distinctBy { it.id },
            strongestSignals = pieces.flatMap { it.strongestSignals }.distinct().take(6),
            isPreviewFallback = pieces.any { it.isPreviewFallback },
            variantLabel = label,
            experienceScore = if (isDirect) 0.0 else weighted { it.experienceScore },
            autoStopIds = pieces.flatMap { it.autoStopIds }.distinct(),
            driveExtraMinutes = 0.0,
            dwellMinutes = pieces.sumOf { it.dwellMinutes },
            totalExtraMinutes = 0.0,
            corridorRadiusKm = pieces.maxOfOrNull { it.corridorRadiusKm } ?: 0.0,
            dataConfidence = pieces.map { it.dataConfidence }.averageOrZero() / 100.0.coerceAtLeast(1.0),
        )
    }

    private fun buildAnchors(
        origin: GeoPoint,
        destination: GeoPoint,
        forceMultipleSegments: Boolean,
    ): List<GeoPoint> {
        val distance = haversineMeters(origin, destination)
        val calculated = ceil(distance / TARGET_SEGMENT_SPAN_METERS).toInt().coerceAtLeast(1)
        val segments = if (forceMultipleSegments) max(2, calculated) else calculated
        return (0..segments).map { index ->
            when (index) {
                0 -> origin
                segments -> destination
                else -> interpolate(origin, destination, index.toDouble() / segments.toDouble())
            }
        }
    }

    private fun routeProgressIndex(route: List<GeoPoint>, point: GeoPoint): Int {
        if (route.isEmpty()) return 0
        val step = max(1, route.size / 120)
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

    private fun nearestDistanceMeters(route: List<GeoPoint>, point: GeoPoint): Double {
        if (route.isEmpty()) return Double.POSITIVE_INFINITY
        val step = max(1, route.size / 100)
        var best = Double.POSITIVE_INFINITY
        var index = 0
        while (index < route.size) {
            best = min(best, haversineMeters(route[index], point))
            index += step
        }
        return best
    }

    private fun isProviderDistanceLimit(error: Throwable): Boolean {
        val text = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return "max distance" in text ||
            "distance limit" in text ||
            "error_code\":154" in text ||
            "path distance exceeds" in text
    }

    private fun routeKey(route: RouteCandidateUi): String =
        "${(route.distanceMeters / 300).roundToInt()}:${(route.durationSeconds / 90).roundToInt()}:${route.autoStopIds.sorted().joinToString(",")}" 

    private fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint = GeoPoint(
        lat = a.lat + (b.lat - a.lat) * fraction,
        lon = a.lon + (b.lon - a.lon) * fraction,
    )

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
}
