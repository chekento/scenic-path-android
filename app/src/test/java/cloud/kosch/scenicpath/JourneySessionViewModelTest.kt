package cloud.kosch.scenicpath

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JourneySessionViewModelTest {
    private val from = GeoPoint(53.67, 10.24)
    private val to = GeoPoint(52.0, 8.9)
    private val destination = PlaceSuggestion("destination", "Detmold", "Germany", to)
    private val sessions = mutableListOf<JourneySessionViewModel>()

    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun teardown() { sessions.forEach { it.cancelBuild(false) }; Dispatchers.resetMain() }
    private fun session(planner: suspend (GeoPoint, GeoPoint, TripPlan, ScenicPreferences) -> Result<RoutePlanUi>) =
        JourneySessionViewModel(planner).also { sessions += it }
    private fun result(id: String = "route") = RoutePlanUi(listOf(RouteCandidateUi(
        id = id, character = "BEAUTIFUL", distanceMeters = 240_000.0, durationSeconds = 12_000.0,
        scenicScore = 70.0, extraMinutes = 15.0, points = listOf(from, to), provider = "test",
    )))

    @Test fun missingEndpointsNeverCallProvider() = runTest {
        var calls = 0
        val vm = session { _, _, _, _ -> calls++; Result.success(result()) }
        assertFalse(vm.build(null))
        assertEquals(RouteIssueV2.BOTH, vm.state.issue)
        vm.selectDestination(destination)
        assertFalse(vm.build(null))
        assertEquals(RouteIssueV2.START, vm.state.issue)
        assertEquals(0, calls)
    }

    @Test fun duplicateBuildTapsUseOneRequest() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val vm = session { _, _, _, _ -> calls++; release.await(); Result.success(result()) }
        vm.selectDestination(destination)
        assertTrue(vm.build(from))
        assertFalse(vm.build(from))
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.loading)
        assertEquals("route", vm.state.activeRoute?.id)
    }

    @Test fun cancelledOldRequestCannotOverwriteNewEndpointOrResult() = runTest {
        val oldRelease = CompletableDeferred<Unit>()
        var calls = 0
        val vm = session { _, _, _, _ ->
            val call = ++calls
            if (call == 1) withContext(NonCancellable) { oldRelease.await() }
            Result.success(result(if (call == 1) "old" else "new"))
        }
        vm.selectDestination(destination)
        vm.build(from)
        runCurrent()
        vm.selectDestination(destination.copy(id = "new", point = GeoPoint(51.0, 9.0)))
        assertFalse(vm.state.loading)
        assertNull(vm.state.routes)
        vm.build(from)
        runCurrent()
        assertEquals("new", vm.state.activeRoute?.id)
        oldRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals("new", vm.state.destination?.id)
        assertEquals("new", vm.state.activeRoute?.id)
        assertNull(vm.state.error)
    }

    @Test fun editingDuringBuildCancelsAndKeepsTheNewDraft() = runTest {
        val release = CompletableDeferred<Unit>()
        val vm = session { _, _, _, _ -> withContext(NonCancellable) { release.await() }; Result.success(result()) }
        vm.selectDestination(destination)
        vm.build(from)
        runCurrent()
        vm.edit(preferences = vm.state.preferences.copy(maxExtraMinutes = 180))
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(180, vm.state.preferences.maxExtraMinutes)
        assertFalse(vm.state.loading)
        assertNull(vm.state.routes)
    }

    @Test fun failedReplacementKeepsCommittedRouteAndDraftSeparate() = runTest {
        var calls = 0
        val vm = session { _, _, _, _ -> if (++calls == 1) Result.success(result()) else Result.failure(IllegalStateException("Provider unavailable")) }
        vm.selectDestination(destination)
        vm.build(from)
        advanceUntilIdle()
        val previous = vm.state.routes
        val committed = vm.state.committedPreferences
        vm.edit(preferences = vm.state.preferences.copy(maxExtraMinutes = 180))
        vm.build(from)
        advanceUntilIdle()
        assertSame(previous, vm.state.routes)
        assertEquals(committed, vm.state.committedPreferences)
        assertEquals(180, vm.state.preferences.maxExtraMinutes)
        assertTrue(vm.state.dirty)
        assertEquals(RouteIssueV2.RETRY, vm.state.issue)
        assertFalse(vm.state.loading)
    }

    @Test fun noNewAlternativeDoesNotExhaustTheFiveRouteLimit() = runTest {
        val generations = mutableListOf<Int>()
        val vm = session { _, _, plan, _ -> generations += plan.alternativeGeneration; Result.success(result()) }
        vm.selectDestination(destination)
        vm.build(from)
        advanceUntilIdle()
        repeat(6) {
            assertTrue(vm.build(from, appendAlternative = true))
            advanceUntilIdle()
            assertEquals(1, vm.state.routes?.candidates?.size)
            assertTrue(vm.state.notice.orEmpty().contains("No distinct alternative"))
        }
        assertEquals((0..6).toList(), generations)
    }

    @Test fun explicitCancelRetainsChoicesWithoutDisplayingAnError() = runTest {
        val vm = session { _, _, _, _ -> CompletableDeferred<Result<RoutePlanUi>>().await() }
        vm.selectDestination(destination)
        vm.build(from)
        runCurrent()
        vm.cancelBuild()
        advanceUntilIdle()
        assertFalse(vm.state.loading)
        assertEquals(destination, vm.state.destination)
        assertNull(vm.state.error)
        assertTrue(vm.state.notice.orEmpty().contains("cancelled"))
    }

    @Test fun searchDraftSurvivesPanelMinimizationAndKeepsStartSeparateFromDestination() {
        val vm = session { _, _, _, _ -> Result.success(result()) }
        vm.searchState("start").query = "Hamburger Straße 12"
        vm.searchState("destination").query = "Detmold"
        assertEquals("Hamburger Straße 12", vm.searchState("start", "different initial query").query)
        assertEquals("Detmold", vm.searchState("destination").query)
    }
}
