package cloud.kosch.scenicpath

import kotlin.math.ln

/** Pure selection policy shared by the physical/debug vehicle router and unit tests. */
object NativeAutoStopPolicy {
    fun limit(maxExtraMinutes: Int, configuredMaxStops: Int): Int = when {
        maxExtraMinutes >= 420 -> minOf(7, configuredMaxStops.coerceAtLeast(0))
        maxExtraMinutes >= 300 -> minOf(6, configuredMaxStops.coerceAtLeast(0))
        maxExtraMinutes >= 210 -> minOf(5, configuredMaxStops.coerceAtLeast(0))
        maxExtraMinutes >= 150 -> minOf(4, configuredMaxStops.coerceAtLeast(0))
        maxExtraMinutes >= 100 -> minOf(3, configuredMaxStops.coerceAtLeast(0))
        maxExtraMinutes >= 60 -> minOf(2, configuredMaxStops.coerceAtLeast(0))
        maxExtraMinutes >= 30 -> minOf(1, configuredMaxStops.coerceAtLeast(0))
        else -> 0
    }

    fun distanceLimitMeters(maxExtraMinutes: Int): Int = when {
        maxExtraMinutes < 45 -> 6_000
        maxExtraMinutes < 90 -> 12_000
        maxExtraMinutes < 150 -> 20_000
        maxExtraMinutes < 210 -> 30_000
        maxExtraMinutes < 300 -> 42_000
        maxExtraMinutes < 420 -> 58_000
        else -> 70_000
    }

    fun foodMatches(point: ScenePointUi, preferences: ScenicPreferences): Boolean {
        if (point.kind != StopKind.FOOD.name) return true
        val rating = point.rating ?: return preferences.minimumFoodRating <= 0.0
        val reviews = point.ratingCount ?: return preferences.minimumFoodReviewCount <= 0
        if (rating < preferences.minimumFoodRating) return false
        if (reviews < preferences.minimumFoodReviewCount) return false
        if (preferences.onlyOpenFood && point.openNow != true) return false
        return true
    }

    fun select(
        points: List<ScenePointUi>,
        preferences: ScenicPreferences,
        enabledKinds: Set<StopKind>,
    ): List<ScenePointUi> {
        val maxStops = limit(preferences.maxExtraMinutes, preferences.maxStops)
        if (maxStops <= 0 || enabledKinds.isEmpty()) return emptyList()
        val enabledNames = enabledKinds.mapTo(mutableSetOf()) { it.name }
        val maxDistance = distanceLimitMeters(preferences.maxExtraMinutes)
        val eligible = points
            .filter { it.kind in enabledNames }
            .filter { it.distanceFromRouteMeters <= maxDistance }
            .filter { foodMatches(it, preferences) }
            .sortedByDescending { utility(it, preferences) }
        if (eligible.isEmpty()) return emptyList()

        val selected = mutableListOf<ScenePointUi>()
        fun add(point: ScenePointUi?) {
            if (point != null && selected.size < maxStops && selected.none { it.id == point.id }) selected += point
        }

        if (preferences.maxExtraMinutes >= 60 && StopKind.FOOD in enabledKinds) {
            add(eligible.filter { it.kind == StopKind.FOOD.name }.maxByOrNull { utility(it, preferences) })
        }
        for (point in eligible) {
            if (selected.size >= maxStops) break
            val unusedKindExists = eligible.any { candidate -> selected.none { it.kind == candidate.kind } }
            if (unusedKindExists && selected.any { it.kind == point.kind }) continue
            add(point)
        }
        return selected
    }

    fun weakestRemovable(
        points: List<ScenePointUi>,
        preferences: ScenicPreferences,
        enabledKinds: Set<StopKind>,
    ): ScenePointUi? {
        if (points.isEmpty()) return null
        val preserveFood = preferences.maxExtraMinutes >= 60 && StopKind.FOOD in enabledKinds
        val nonFood = points.filterNot { it.kind == StopKind.FOOD.name }
        val pool = if (preserveFood && nonFood.isNotEmpty()) nonFood else points
        return pool.minByOrNull { utility(it, preferences) }
    }

    fun utility(point: ScenePointUi, preferences: ScenicPreferences): Double {
        val dna = when (point.kind) {
            StopKind.VIEWPOINT.name -> preferences.weights.viewpoints
            StopKind.MUSEUM.name -> preferences.weights.museums
            StopKind.NATURE.name -> (preferences.weights.forest + preferences.weights.mountains) / 2f
            StopKind.MONUMENT.name -> (preferences.weights.monuments + preferences.weights.culture) / 2f
            StopKind.PARK.name -> preferences.weights.parks
            StopKind.ART.name -> preferences.weights.art
            StopKind.WORSHIP.name -> preferences.weights.worship
            StopKind.WATER.name -> preferences.weights.water
            StopKind.FOOD.name -> preferences.weights.food
            StopKind.ARCHITECTURE.name -> preferences.weights.architecture
            else -> preferences.weights.scenicHighlights
        }.toDouble()
        val ratingBonus = point.rating?.let { rating ->
            rating * 9.0 + ln(((point.ratingCount ?: 0) + 10).toDouble()) * 2.5
        } ?: 0.0
        val dwellPenalty = when {
            preferences.maxExtraMinutes >= 300 -> 0.025
            preferences.maxExtraMinutes >= 210 -> 0.045
            preferences.maxExtraMinutes >= 120 -> 0.08
            preferences.maxExtraMinutes >= 60 -> 0.13
            else -> 0.20
        }
        val longVisitBonus = if (preferences.maxExtraMinutes >= 180) {
            point.suggestedDwellMinutes.coerceIn(30, 180) * 0.055
        } else 0.0
        return point.relevance * 55.0 + point.suggestionScore * 18.0 + dna * 38.0 + ratingBonus + longVisitBonus -
            point.distanceFromRouteMeters.coerceAtLeast(0) / 430.0 - point.suggestedDwellMinutes.coerceAtLeast(0) * dwellPenalty
    }
}
