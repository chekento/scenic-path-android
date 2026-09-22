package cloud.kosch.scenicpath

import java.util.Locale
import kotlin.math.roundToInt

const val MAX_EXPLORATION_MINUTES: Int = 43_200 // 30 days

fun explorationTimeLabel(minutes: Int): String {
    val value = minutes.coerceIn(0, MAX_EXPLORATION_MINUTES)
    return when {
        value >= 10_080 && value % 10_080 == 0 -> "${value / 10_080}w"
        value >= 1_440 && value % 1_440 == 0 -> "${value / 1_440}d"
        value >= 60 && value % 60 == 0 -> "${value / 60}h"
        value >= 60 -> "${value / 60}h ${value % 60}m"
        else -> "${value}m"
    }
}

/** Accepts values such as `90`, `6h`, `3d` or `2w` for the free exploration field. */
fun parseExplorationTime(raw: String): Int? {
    val match = Regex("^([0-9]+(?:[.,][0-9]+)?)\\s*(m|min|h|d|w)?$")
        .matchEntire(raw.trim().lowercase(Locale.ROOT)) ?: return null
    val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2]) {
        "w" -> 10_080.0
        "d" -> 1_440.0
        "h" -> 60.0
        else -> 1.0
    }
    return (value * multiplier).roundToInt().coerceIn(0, MAX_EXPLORATION_MINUTES)
}

/** Scenic search space grows with time, including multi-day and multi-week itineraries. */
fun explorationCorridorKm(minutes: Int): Double {
    val value = minutes.coerceIn(0, MAX_EXPLORATION_MINUTES)
    return when {
        value >= 10_080 -> (90.0 + (value - 10_080) / 1_440.0 * 6.0).coerceAtMost(240.0)
        value >= 1_440 -> (42.0 + (value - 1_440) / 60.0 * 1.5).coerceAtMost(90.0)
        else -> (4.0 + value * 0.15).coerceIn(6.0, 42.0)
    }
}

enum class PlanningMode(val label: String) {
    QUICK("Quick route"),
    DAY_TRIP("Day trip"),
    ROAD_TRIP("Road trip")
}

enum class RouteCharacter(val label: String) {
    BEAUTIFUL("Beautiful"),
    BALANCED("Balanced"),
    DIRECT("Direct"),
    CUSTOM("Custom")
}

data class PlannedStop(
    val id: String,
    val name: String,
    val kind: StopKind,
    val dwellMinutes: Int = kind.defaultDwellMinutes,
    val locked: Boolean = false,
    val mustVisit: Boolean = true,
    val point: GeoPoint? = null,
    val subtitle: String? = null,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val subtype: String? = null,
)

data class TripPlan(
    val mode: PlanningMode = PlanningMode.QUICK,
    val routeCharacter: RouteCharacter = RouteCharacter.BEAUTIFUL,
    val stops: List<PlannedStop> = emptyList(),
    val departureLabel: String = "Leave now",
    val arrivalDeadlineLabel: String? = null,
    val flexibleStopOrder: Boolean = true,
    val autoSuggestStops: Boolean = true,
    val preserveScenicIntentOnReroute: Boolean = true,
    val enabledSceneKinds: Set<StopKind> = allSelectableSceneKinds,
)

fun ScenicPreferences.forCharacter(character: RouteCharacter): ScenicPreferences = when (character) {
    RouteCharacter.BEAUTIFUL -> copy(
        maxExtraMinutes = maxOf(maxExtraMinutes, 45),
        maxExtraPercent = maxOf(maxExtraPercent, 35),
        avoidMotorways = true,
        windingness = 75,
        hilliness = 60,
    )
    RouteCharacter.BALANCED -> copy(
        // Character presets define a floor, not a replacement for the user's budget.
        maxExtraMinutes = maxOf(maxExtraMinutes, 30),
        maxExtraPercent = maxOf(maxExtraPercent, 25),
        windingness = 50,
        hilliness = 40,
    )
    RouteCharacter.DIRECT -> copy(
        maxExtraMinutes = 10,
        maxExtraPercent = 10,
        windingness = 20,
        hilliness = 20,
    )
    RouteCharacter.CUSTOM -> this
}
