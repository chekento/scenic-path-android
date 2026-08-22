package cloud.kosch.scenicpath

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Long-distance guard for the public development Valhalla service.
 *
 * The FOSSGIS demo currently rejects paths beyond its configured path-distance cap.
 * Scenic Path must never expose that provider detail as a product limitation, so long
 * A→B journeys are optimized in safe geographic segments and stitched back into one
 * continuous user-facing experience.
 *
 * This is a development transport adapter, not a product-level restriction. A controlled
 * production routing backend can use different limits without changing the UI or planner.
 */
object SegmentedJourneyOptimizer {
    // Start segmenting well before the public service's 200 km path cap. Road distance can
    // be materially longer than great-circle distance, especially with scenic constraints.
    private const val PREEMPTIVE_SEGMENT_THRESHOLD_METERS = 145_000.0
    private const val TARGET_SEGMENT_SPAN_METERS = 105_000.0

    suspend fun plan(
        origin: GeoPoint,
        destination: GeoPoint,
        plan: TripPlan,
        preferences: ScenicPreferences,
    ): RoutePlanUi {
        val straightLine = haversineMeters(origin, destination)
        if (straightLine > PREEMPTIVE_SEGMENT_THRESHOLD_METERS) {
            return planSegmented(origin, destination, plan, preferences, forceMultipleSegments = false)
        }

        return try {
            ScenicJourneyOptimizer.plan(origin, destination, plan, preferences)
        } catch (error: Throwable) {
            if (!isProviderDistanceLimit(error)) throw error
            // Defensive retry: road topology or a scenic profile can exceed the provider
            // cap even when the straight-line distance looked safe.
            planSegmented(origin, destination, plan, preferences, forceMultipleSegments = true)
        }
    }

    private suspend fun planSegmented(
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
        val budgetAllocation = distribute(totalBudget, segmentCount)
        val stopAllocation = distribute(effective.maxStops.coerceAtLeast(0), segmentCount)
        val segmentPlans = mutableListOf<RoutePlanUi>()

        for (index in 0 until segmentCount) {
            // CUSTOM prevents Beautiful/Balanced preset floors from being re-applied to
            // every sub-journey. The original route character is restored when stitching.
            val segmentCharacter = if (plan.routeCharacter == RouteCharacter.DIRECT) {
                RouteCharacter.DIRECT
            } else {
                RouteCharacter.CUSTOM
            }
            val segmentPlan = plan.copy(
                routeCharacter = segmentCharacter,
                stops = emptyList(),
            )
            val segmentPreferences = effective.copy(
                maxExtraMinutes = budgetAllocation[index],
                maxStops = stopAllocation[index],
            )

            val segmentResult = ScenicJourneyOptimizer.plan(
                origin = anchors[index],
                destination = anchors[index + 1],
                plan = segmentPlan,
                preferences = segmentPreferences,
            )
            if (segmentResult.candidates.isEmpty()) {
                error("No route returned for long-route segment ${index + 1}/$segmentCount")
            }
            segmentPlans += segmentResult
        }

        return stitch(segmentPlans, plan.routeCharacter, effective, totalBudget)
    }

    private fun stitch(
        segmentPlans: List<RoutePlanUi>,
        requestedCharacter: RouteCharacter,
        preferences: ScenicPreferences,
        totalBudgetMinutes: Int,
    ): RoutePlanUi {
        val orderedLabels = listOf("Best match", "Highlight hunter", "Scenic drive", "Direct")
        val labels = orderedLabels.filter { label ->
            segmentPlans.all { segment -> segment.candidates.any { it.variantLabel.equals(label, ignoreCase = true) } }
        }.ifEmpty { listOf("Direct") }

        val candidates = labels.mapNotNull { label ->
            val pieces = segmentPlans.mapNotNull { segment ->
                segment.candidates.firstOrNull { it.variantLabel.equals(label, ignoreCase = true) }
            }
            if (pieces.size != segmentPlans.size) return@mapNotNull null
            stitchCandidate(label, pieces, requestedCharacter, preferences)
        }

        val baselineDuration = segmentPlans.sumOf { plan ->
            plan.baselineDurationSeconds
                ?: plan.candidates.firstOrNull { it.variantLabel.equals("Direct", true) }?.durationSeconds
                ?: 0.0
        }
        val baselineDistance = segmentPlans.sumOf { plan ->
            plan.baselineDistanceMeters
                ?: plan.candidates.firstOrNull { it.variantLabel.equals("Direct", true) }?.distanceMeters
                ?: 0.0
        }

        return RoutePlanUi(
            candidates = candidates,
            baselineDurationSeconds = baselineDuration,
            baselineDistanceMeters = baselineDistance,
            note = buildString {
                append("Long-route Journey Optimizer · ${segmentPlans.size} safe routing segments")
                append(" · stitched into one continuous journey")
                append(" · shared +$totalBudgetMinutes min exploration budget")
                if (preferences.avoidMotorways) append(" · motorway avoidance preserved per segment")
            },
        )
    }

    private fun stitchCandidate(
        label: String,
        pieces: List<RouteCandidateUi>,
        requestedCharacter: RouteCharacter,
        preferences: ScenicPreferences,
    ): RouteCandidateUi {
        val points = buildList {
            pieces.forEach { piece ->
                if (isEmpty()) {
                    addAll(piece.points)
                } else if (piece.points.isNotEmpty()) {
                    if (last() == piece.points.first()) addAll(piece.points.drop(1)) else addAll(piece.points)
                }
            }
        }
        val scenePoints = pieces
            .flatMap { it.scenePoints }
            .distinctBy { it.id }
        val autoStopIds = pieces
            .flatMap { it.autoStopIds }
            .distinct()
        val distance = pieces.sumOf { it.distanceMeters }
        val duration = pieces.sumOf { it.durationSeconds }
        val driveExtra = pieces.sumOf { it.driveExtraMinutes }
        val dwell = pieces.sumOf { it.dwellMinutes }
        val totalExtra = pieces.sumOf { it.totalExtraMinutes }
        val weightTotal = pieces.sumOf { it.distanceMeters.coerceAtLeast(1.0) }
        fun weighted(selector: (RouteCandidateUi) -> Double): Double =
            pieces.sumOf { selector(it) * it.distanceMeters.coerceAtLeast(1.0) } / weightTotal

        val isDirect = label.equals("Direct", ignoreCase = true)
        return RouteCandidateUi(
            id = "segmented-${label.lowercase().replace(' ', '-')}",
            character = if (isDirect) RouteCharacter.DIRECT.name else requestedCharacter.name,
            distanceMeters = distance,
            durationSeconds = duration,
            scenicScore = if (isDirect) 0.0 else weighted { it.scenicScore },
            extraMinutes = if (isDirect) 0.0 else driveExtra,
            points = points,
            provider = "Segmented Journey Optimizer · Valhalla / OpenStreetMap development",
            scenePoints = scenePoints,
            strongestSignals = pieces.flatMap { it.strongestSignals }.distinct().take(6),
            isPreviewFallback = pieces.any { it.isPreviewFallback },
            variantLabel = label,
            experienceScore = if (isDirect) 0.0 else weighted { it.experienceScore },
            autoStopIds = autoStopIds,
            driveExtraMinutes = if (isDirect) 0.0 else driveExtra,
            dwellMinutes = if (isDirect) 0 else dwell,
            totalExtraMinutes = if (isDirect) 0.0 else totalExtra,
            corridorRadiusKm = pieces.maxOfOrNull { it.corridorRadiusKm } ?: 0.0,
            dataConfidence = if (pieces.isEmpty()) 0.0 else pieces.map { it.dataConfidence }.average(),
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

    private fun distribute(total: Int, parts: Int): List<Int> {
        if (parts <= 0) return emptyList()
        val safeTotal = total.coerceAtLeast(0)
        val base = safeTotal / parts
        val remainder = safeTotal % parts
        return List(parts) { index -> base + if (index < remainder) 1 else 0 }
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
}
