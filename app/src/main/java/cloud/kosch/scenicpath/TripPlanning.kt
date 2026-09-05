package cloud.kosch.scenicpath

enum class PlanningMode(val label: String) {
    QUICK("Point-to-point"),
    DAY_TRIP("Day trip"),
    ROAD_TRIP("Road trip")
}

enum class RouteCharacter(val label: String) {
    BEAUTIFUL("Scenic"),
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
    /**
     * Total number of route variants requested from the planner. Two are shown by default so
     * Alternative 2 can deliberately use another corridor/stop set. The UI can grow this up to
     * five with the + Route action without changing the user's scenic preferences.
     */
    val requestedAlternatives: Int = 2,
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
        // A character preset is a coherent baseline. Explicit exceptions remain available through
        // the Advanced constraints / Custom character instead of leaking in from the previous mode.
        maxExtraMinutes = maxOf(maxExtraMinutes, 30),
        maxExtraPercent = maxOf(maxExtraPercent, 25),
        avoidMotorways = false,
        windingness = 50,
        hilliness = 40,
    )
    RouteCharacter.DIRECT -> copy(
        maxExtraMinutes = 10,
        maxExtraPercent = 10,
        avoidMotorways = false,
        windingness = 20,
        hilliness = 20,
    )
    RouteCharacter.CUSTOM -> this
}
