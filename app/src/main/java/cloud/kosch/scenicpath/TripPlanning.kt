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
    /** Total number of route variants requested from the planner. */
    val requestedAlternatives: Int = 2,
    /**
     * Incremented only when the user presses + Route. Existing alternatives remain stable while
     * the next planning pass deliberately explores a new set of corridor/loop seeds.
     */
    val alternativeGeneration: Int = 0,
)

/**
 * RouteCharacter is a routing-priority preset while editing. Once constraints are committed for
 * execution, deeper planners must treat them as authoritative and never silently overwrite them.
 */
fun ScenicPreferences.forCharacter(character: RouteCharacter): ScenicPreferences {
    if (constraintsCommitted) return this
    return when (character) {
        RouteCharacter.BEAUTIFUL -> copy(
            maxExtraMinutes = maxOf(maxExtraMinutes, 45),
            maxExtraPercent = maxOf(maxExtraPercent, 35),
            avoidMotorways = true,
            windingness = 75,
            hilliness = 60,
        )
        RouteCharacter.BALANCED -> copy(
            maxExtraMinutes = maxOf(maxExtraMinutes, 30),
            maxExtraPercent = maxOf(maxExtraPercent, 25),
            avoidMotorways = false,
            windingness = 50,
            hilliness = 40,
        )
        RouteCharacter.DIRECT -> copy(
            avoidMotorways = false,
            windingness = 20,
            hilliness = 20,
        )
        RouteCharacter.CUSTOM -> this
    }
}

fun ScenicPreferences.forPlan(plan: TripPlan): ScenicPreferences {
    val characterized = forCharacter(plan.routeCharacter)
    return if (plan.mode == PlanningMode.DAY_TRIP) {
        characterized.copy(maxExtraMinutes = maxOf(30, characterized.maxExtraMinutes))
    } else characterized
}
