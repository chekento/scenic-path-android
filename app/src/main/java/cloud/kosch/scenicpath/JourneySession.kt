package cloud.kosch.scenicpath

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class LatestRequest {
    private var version = 0L
    fun advance(): Long = ++version
    fun accepts(token: Long): Boolean = token == version
}

/** Retains the committed journey and drafts during rotation; owns exactly one route request. */
class JourneySession : ViewModel() {
    val startSelection = mutableStateOf<PlaceSuggestion?>(null)
    val destinationSelection = mutableStateOf<PlaceSuggestion?>(null)
    val preferences = mutableStateOf(ScenicPreferences(maxStops = 6))
    val plan = mutableStateOf(TripPlan())
    val routePlan = mutableStateOf<RoutePlanUi?>(null)
    val selectedCandidateIndex = mutableIntStateOf(0)
    val routeLoading = mutableStateOf(false)
    val poiLoading = mutableStateOf(false)
    val poiCount = mutableIntStateOf(0)
    val routeError = mutableStateOf<String?>(null)
    val topExpanded = mutableStateOf(true)
    val routeDirty = mutableStateOf(false)
    val startQuery = mutableStateOf("")
    val destinationQuery = mutableStateOf("")
    val stopQuery = mutableStateOf("")
    private val requests = LatestRequest()
    private var routeJob: Job? = null

    fun invalidate() {
        requests.advance()
        routeJob?.cancel()
        routeJob = null
        routeLoading.value = false
    }

    fun draftChanged() {
        invalidate()
        routeDirty.value = routePlan.value != null
    }

    /** Map discovery owns the long-running POI phase after a road route is committed. */
    fun updatePoiSearchState(loading: Boolean, count: Int = poiCount.intValue) {
        poiLoading.value = loading
        poiCount.intValue = count.coerceAtLeast(0)
    }

    fun buildRoute(origin: GeoPoint?, destination: GeoPoint?) {
        invalidate()
        if (origin == null || destination == null) {
            routeError.value = "Choose a start and destination, or enable GPS."
            return
        }
        val token = requests.advance()
        val committedPlan = plan.value
        val committedPreferences = preferences.value.copy(vehicle = VehicleSettingsState.profile)
        routeLoading.value = true
        routeError.value = null
        routeJob = viewModelScope.launch {
            try {
                val result = withTimeout(180_000) {
                    ScenicApi.planRoute(origin, destination, committedPlan, committedPreferences).getOrThrow()
                }
                if (!requests.accepts(token)) return@launch
                check(result.candidates.isNotEmpty()) { "No complete route was returned." }
                routePlan.value = result
                selectedCandidateIndex.intValue = 0
                routeDirty.value = false
                topExpanded.value = false
            } catch (_: TimeoutCancellationException) {
                if (requests.accepts(token)) routeError.value = "The route service took too long. Please try again or add a waypoint."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requests.accepts(token)) {
                    routeError.value = friendlyRouteError(error) +
                        if (routePlan.value != null) " Your previous route is still shown." else ""
                    topExpanded.value = routePlan.value == null
                }
            } finally {
                if (requests.accepts(token)) routeLoading.value = false
            }
        }
    }
}

internal fun friendlyRouteError(error: Throwable): String = when {
    LongDistanceRouting.isDistanceLimit(error) -> "A route section exceeds the service limit. Add a waypoint and try again."
    error is java.net.UnknownHostException -> "The route service cannot be reached. Check your connection and try again."
    error is java.net.SocketTimeoutException -> "The route service did not answer in time. Please try again."
    error is RoutingHttpException && error.status == 429 -> "The route service is busy. Wait a moment and try again."
    error is RoutingHttpException -> "The route service could not plan this journey. Please retry or choose a nearby start or destination."
    else -> error.message?.take(220) ?: "Journey planning failed. Please try again."
}
