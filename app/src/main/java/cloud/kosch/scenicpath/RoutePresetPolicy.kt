package cloud.kosch.scenicpath

enum class QuickModeV2(val label: String, val description: String) {
    DIRECT("Direct", "Prioritise travel time; automatic scenic stops are off."),
    BALANCED("Balanced", "Balance travel time, scenery and worthwhile stops."),
    SCENIC("Scenic", "Prioritise beautiful roads and scenery."),
    DISCOVER("Discover", "Set up a day trip with more exploration time and Smart Stops."),
}

object RoutePresetPolicy {
    fun apply(mode: QuickModeV2, plan: TripPlan, preferences: ScenicPreferences): Pair<TripPlan, ScenicPreferences> {
        val character = when (mode) {
            QuickModeV2.DIRECT -> RouteCharacter.DIRECT
            QuickModeV2.BALANCED -> RouteCharacter.BALANCED
            else -> RouteCharacter.BEAUTIFUL
        }
        val nextPlan = plan.copy(
            // A routing priority must never turn a day/road trip into a point-to-point journey.
            mode = if (mode == QuickModeV2.DISCOVER) PlanningMode.DAY_TRIP else plan.mode,
            routeCharacter = character,
            autoSuggestStops = mode != QuickModeV2.DIRECT,
            requestedAlternatives = if (mode == QuickModeV2.DIRECT && plan.mode == PlanningMode.QUICK) 1 else maxOf(2, plan.requestedAlternatives),
        )
        val minutes = when (mode) { QuickModeV2.DIRECT -> 10; QuickModeV2.BALANCED -> 30; QuickModeV2.SCENIC -> 60; QuickModeV2.DISCOVER -> maxOf(120, preferences.maxExtraMinutes) }
        val nextPreferences = preferences.copy(constraintsCommitted = false).forCharacter(character).copy(
            maxExtraMinutes = if (mode != QuickModeV2.DISCOVER && plan.mode != PlanningMode.QUICK) preferences.maxExtraMinutes else minutes,
            maxExtraPercent = if (plan.mode != PlanningMode.QUICK) preferences.maxExtraPercent else when (mode) { QuickModeV2.DIRECT -> 10; QuickModeV2.BALANCED -> 25; QuickModeV2.SCENIC -> 40; QuickModeV2.DISCOVER -> 70 },
            maxStops = if (plan.mode != PlanningMode.QUICK) preferences.maxStops else when (mode) { QuickModeV2.DIRECT -> 3; QuickModeV2.BALANCED -> 5; QuickModeV2.SCENIC -> 6; QuickModeV2.DISCOVER -> 8 },
        )
        return nextPlan to nextPreferences
    }
}
