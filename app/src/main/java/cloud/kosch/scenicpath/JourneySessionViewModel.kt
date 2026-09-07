package cloud.kosch.scenicpath

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

enum class RouteIssueV2 { NONE, START, DESTINATION, BOTH, RETRY }

data class JourneySessionState(
    val start: PlaceSuggestion? = null,
    val destination: PlaceSuggestion? = null,
    val plan: TripPlan = TripPlan(),
    val preferences: ScenicPreferences = ScenicPreferences(maxStops = 6),
    val routes: RoutePlanUi? = null,
    val selectedIndex: Int = 0,
    val loading: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null,
    val issue: RouteIssueV2 = RouteIssueV2.NONE,
    val notice: String? = null,
    val committedPlan: TripPlan? = null,
    val committedPreferences: ScenicPreferences? = null,
) {
    val activeRoute: RouteCandidateUi? get() = routes?.candidates?.getOrNull(selectedIndex)
}

/** Owns one editing session across Activity recreation. Large route geometry never enters a Bundle. */
class JourneySessionViewModel(
    private val planner: suspend (GeoPoint, GeoPoint, TripPlan, ScenicPreferences) -> Result<RoutePlanUi> = ScenicApi::planRoute,
) : ViewModel() {
    var state by mutableStateOf(JourneySessionState())
        private set
    private var requestId = 0L
    private var routeJob: Job? = null
    private val searches = mutableMapOf<String, PlaceSearchState>()

    fun searchState(key: String, initialQuery: String = ""): PlaceSearchState =
        searches.getOrPut(key) { PlaceSearchState(initialQuery) }

    fun edit(plan: TripPlan = state.plan, preferences: ScenicPreferences = state.preferences) {
        if (plan == state.plan && preferences == state.preferences) return
        cancelBuild(showNotice = false)
        state = state.copy(
            plan = plan,
            preferences = preferences,
            dirty = state.routes != null && (plan != state.committedPlan || preferences != state.committedPreferences),
            error = null,
            issue = RouteIssueV2.NONE,
            notice = null,
        )
    }

    fun selectStart(place: PlaceSuggestion?) = changeEndpoints(place, state.destination)
    fun selectDestination(place: PlaceSuggestion) = changeEndpoints(state.start, place)

    private fun changeEndpoints(start: PlaceSuggestion?, destination: PlaceSuggestion?) {
        cancelBuild(showNotice = false)
        state = state.copy(
            start = start, destination = destination, routes = null, selectedIndex = 0,
            dirty = false, error = null, issue = RouteIssueV2.NONE, notice = null,
            committedPlan = null, committedPreferences = null,
        )
    }

    fun selectAlternative(delta: Int) {
        val count = state.routes?.candidates?.size ?: 0
        if (count > 0 && !state.loading) state = state.copy(selectedIndex = (state.selectedIndex + delta + count) % count)
    }

    fun dismissFeedback() { state = state.copy(error = null, issue = RouteIssueV2.NONE, notice = null) }

    fun cancelBuild(showNotice: Boolean = true) {
        requestId++ // Invalidate before cancellation: even an uncooperative provider cannot commit.
        routeJob?.cancel()
        routeJob = null
        val wasLoading = state.loading
        state = state.copy(
            loading = false,
            notice = if (showNotice && wasLoading) {
                if (state.routes != null) "Planning cancelled. Your previous route is still available." else "Planning cancelled. Your choices are kept."
            } else state.notice,
        )
    }

    fun build(liveOrigin: GeoPoint?, appendAlternative: Boolean = false, rerouteFrom: GeoPoint? = null): Boolean {
        // Repeated taps must not create overlapping provider work.
        if (state.loading) return false
        val before = state
        val from = rerouteFrom ?: before.start?.point ?: liveOrigin
        val to = before.destination?.point
        if (from == null || to == null) {
            state = state.copy(
                issue = when { from == null && to == null -> RouteIssueV2.BOTH; from == null -> RouteIssueV2.START; else -> RouteIssueV2.DESTINATION },
                error = when { from == null && to == null -> "Choose a start and destination before planning."; from == null -> "Choose a start place or enable GPS."; else -> "Choose a destination before planning." },
                notice = null,
            )
            return false
        }
        if (appendAlternative && before.dirty) {
            state = state.copy(notice = "Rebuild your changes before adding an alternative.")
            return false
        }
        val currentCount = before.routes?.candidates?.size ?: 0
        if (appendAlternative && currentCount >= 5) return false
        val buildPlan = when {
            rerouteFrom != null -> before.committedPlan ?: before.plan
            appendAlternative -> before.plan.copy(
                requestedAlternatives = (currentCount + 1).coerceIn(2, 5),
                alternativeGeneration = before.plan.alternativeGeneration + 1,
            )
            else -> before.plan
        }
        val buildPreferences = if (rerouteFrom != null) before.committedPreferences ?: before.preferences else before.preferences
        val token = ++requestId
        state = state.copy(loading = true, error = null, issue = RouteIssueV2.NONE, notice = null)
        routeJob = viewModelScope.launch {
            try {
                val result = withTimeout(240_000L) { planner(from, to, buildPlan, buildPreferences).getOrThrow() }
                coroutineContext.ensureActive()
                if (token != requestId) return@launch
                check(result.candidates.isNotEmpty()) { "No route matched your settings. Adjust the time budget and try again." }
                val applied = if (appendAlternative && before.routes != null) {
                    RouteAlternativeMergePolicy.merge(before.routes, result, buildPlan.requestedAlternatives)
                } else result
                ScenicSceneSelectionState.activate(buildPlan.enabledSceneKinds)
                val noNewAlternative = appendAlternative && applied.candidates.size <= currentCount
                state = state.copy(
                    routes = applied,
                    selectedIndex = if (appendAlternative) before.selectedIndex.coerceIn(0, applied.candidates.lastIndex) else 0,
                    plan = buildPlan,
                    preferences = buildPreferences,
                    committedPlan = buildPlan,
                    committedPreferences = buildPreferences,
                    dirty = false,
                    start = if (rerouteFrom != null) null else state.start,
                    notice = if (noNewAlternative) "No distinct alternative found for these settings. Your existing routes are kept." else null,
                )
            } catch (error: TimeoutCancellationException) {
                if (token == requestId) fail("Planning took too long. Please try again or request fewer alternatives.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token == requestId) fail(error.message?.take(260) ?: "Route planning failed. Please try again.")
            } finally {
                if (token == requestId) {
                    state = state.copy(loading = false)
                    routeJob = null
                }
            }
        }
        return true
    }

    private fun fail(message: String) {
        state = state.copy(
            issue = RouteIssueV2.RETRY,
            error = if (state.routes != null) "$message Your previous route is still available." else message,
        )
    }
}
