package cloud.kosch.scenicpath

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RoutePresetPolicyTest {
    @Test fun changingPriorityPreservesDayAndRoadTripBudgetsAndStops() {
        for (scope in listOf(PlanningMode.DAY_TRIP, PlanningMode.ROAD_TRIP)) {
            for (mode in listOf(QuickModeV2.DIRECT, QuickModeV2.BALANCED, QuickModeV2.SCENIC)) {
                val original = TripPlan(mode = scope, stops = listOf(PlannedStop("stop", "Museum", StopKind.CUSTOM)))
                val prefs = ScenicPreferences(maxExtraMinutes = 240, maxStops = 10, avoidTolls = true)
                val (plan, changed) = RoutePresetPolicy.apply(mode, original, prefs)
                assertEquals(scope, plan.mode)
                assertEquals(original.stops, plan.stops)
                assertEquals(240, changed.maxExtraMinutes)
                assertEquals(10, changed.maxStops)
                assertTrue(changed.avoidTolls)
            }
        }
    }
    @Test fun discoverDoesNotReduceAnAlreadyLargerBudget() {
        val (plan, preferences) = RoutePresetPolicy.apply(QuickModeV2.DISCOVER, TripPlan(), ScenicPreferences(maxExtraMinutes = 360))
        assertEquals(PlanningMode.DAY_TRIP, plan.mode)
        assertEquals(360, preferences.maxExtraMinutes)
    }
    @Test fun directDoesNotAddAutomaticScenicStops() {
        val (plan, _) = RoutePresetPolicy.apply(QuickModeV2.DIRECT, TripPlan(), ScenicPreferences())
        assertEquals(RouteCharacter.DIRECT, plan.routeCharacter)
        assertFalse(plan.autoSuggestStops)
    }
    @Test fun providerCancellationIsNotTreatedAsAnOrdinaryFailure() {
        val cancelled = CancellationException("cancelled by user")
        try {
            runCatchingCancellable<Unit> { throw cancelled }
            fail("Cancellation must be rethrown")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertTrue(runCatchingCancellable<Unit> { error("Provider outage") }.isFailure)
    }
    @Test fun duplicateProviderIdsCannotCrashLazySearchResults() {
        val first = PlaceSuggestion("same", "Street", "One", GeoPoint(53.0, 10.0))
        val other = first.copy(title = "Different name", point = GeoPoint(54.0, 11.0))
        assertEquals(listOf(first), mergePlaceSuggestions(listOf(first), listOf(other), listOf(first)))
    }
}
