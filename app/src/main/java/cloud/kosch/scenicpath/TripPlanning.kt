package cloud.kosch.scenicpath

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
    val enabledSceneKinds: Set<StopKind> = prototypeSelectableSceneKinds,
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
        maxExtraMinutes = 30,
        maxExtraPercent = 25,
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
