package cloud.kosch.scenicpath

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private enum class ExperiencePanelV2 {
    START,
    DESTINATION,
    PLANNER,
    STOPS,
    STOP_KIND,
    STOP_PLACE,
}

private enum class QuickModeV2(val label: String, val description: String) {
    DIRECT("Direct", "Minimal detour. Best when arrival time matters most."),
    BALANCED("Balanced", "A sensible compromise between travel time, scenery and useful stops."),
    SCENIC("Scenic", "Beautiful roads and worthwhile scenery get clear priority."),
    DISCOVER("Discover", "A wider corridor, more time and more Smart Stops for an experience-led trip."),
}

private enum class RouteIssueV2 {
    NONE,
    START,
    DESTINATION,
    BOTH,
    RETRY,
}

/** Coordinated map-first shell. */
@Composable
fun ScenicExperienceRootV2(
    locationPermissionGranted: Boolean,
    requestLocationPermission: () -> Unit,
    vehicleProfile: VehicleProfile = VehicleSettingsState.profile,
    onVehicleSettings: () -> Unit = {},
    externalOverlayVisible: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val location = rememberLocationUiState(locationPermissionGranted)

    var startSelection by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var destinationSelection by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var activePanel by remember { mutableStateOf<ExperiencePanelV2?>(null) }
    var minimizedPanel by remember { mutableStateOf<ExperiencePanelV2?>(null) }
    var pendingStopKind by remember { mutableStateOf(StopKind.CUSTOM) }

    var preferences by remember { mutableStateOf(ScenicPreferences(maxStops = 6, vehicle = vehicleProfile)) }
    var plan by remember { mutableStateOf(TripPlan()) }
    var routePlan by remember { mutableStateOf<RoutePlanUi?>(null) }
    var selectedCandidateIndex by remember { mutableIntStateOf(0) }
    var routeLoading by remember { mutableStateOf(false) }
    var routeDirty by remember { mutableStateOf(false) }
    var navigationActive by remember { mutableStateOf(false) }

    var topExpanded by remember { mutableStateOf(true) }
    var routeBarExpanded by remember { mutableStateOf(false) }
    var recenterToken by remember { mutableIntStateOf(0) }

    var routeError by remember { mutableStateOf<String?>(null) }
    var routeIssue by remember { mutableStateOf(RouteIssueV2.NONE) }

    val origin = startSelection?.point ?: location.point
    val destination = destinationSelection?.point
    val activeRoute = routePlan?.candidates?.getOrNull(selectedCandidateIndex)
    val rootOsdVisible = activePanel == null && !externalOverlayVisible && !navigationActive

    val startLabel = startSelection?.title ?: when {
        location.point != null -> "Current location"
        locationPermissionGranted -> "Waiting for GPS"
        else -> "Choose start or enable GPS"
    }
    val destinationLabel = destinationSelection?.title.orEmpty()

    // Vehicle settings are routing inputs, not decoration. Keep the live planner preferences in
    // lock-step with the persisted vehicle profile so the very next calculation uses the vehicle
    // that the user just selected.
    LaunchedEffect(vehicleProfile) {
        if (preferences.vehicle != vehicleProfile) {
            preferences = preferences.copy(vehicle = vehicleProfile)
            if (routePlan != null) routeDirty = true
        }
    }

    fun openPanel(panel: ExperiencePanelV2) {
        activePanel = panel
        minimizedPanel = null
        topExpanded = false
        routeBarExpanded = false
    }

    fun minimizePanel(panel: ExperiencePanelV2) {
        activePanel = null
        minimizedPanel = panel
    }

    fun clearEndpointRoute() {
        navigationActive = false
        routePlan = null
        selectedCandidateIndex = 0
        routeDirty = false
        routeBarExpanded = false
        routeError = null
        routeIssue = RouteIssueV2.NONE
        topExpanded = true
    }

    fun applyQuickMode(mode: QuickModeV2) {
        val hadRoute = routePlan != null
        when (mode) {
            QuickModeV2.DIRECT -> {
                plan = plan.copy(mode = PlanningMode.QUICK, routeCharacter = RouteCharacter.DIRECT, autoSuggestStops = false)
                preferences = preferences.copy(
                    maxExtraMinutes = 10,
                    maxExtraPercent = 10,
                    maxStops = 3,
                    avoidMotorways = false,
                    windingness = 20,
                    hilliness = 20,
                    vehicle = vehicleProfile,
                )
            }
            QuickModeV2.BALANCED -> {
                plan = plan.copy(mode = PlanningMode.QUICK, routeCharacter = RouteCharacter.BALANCED, autoSuggestStops = true)
                preferences = preferences.copy(
                    maxExtraMinutes = 30,
                    maxExtraPercent = 25,
                    maxStops = 5,
                    avoidMotorways = false,
                    windingness = 50,
                    hilliness = 40,
                    vehicle = vehicleProfile,
                )
            }
            QuickModeV2.SCENIC -> {
                plan = plan.copy(mode = PlanningMode.QUICK, routeCharacter = RouteCharacter.BEAUTIFUL, autoSuggestStops = true)
                preferences = preferences.copy(
                    maxExtraMinutes = 60,
                    maxExtraPercent = 40,
                    maxStops = 6,
                    avoidMotorways = true,
                    windingness = 75,
                    hilliness = 60,
                    vehicle = vehicleProfile,
                )
            }
            QuickModeV2.DISCOVER -> {
                plan = plan.copy(mode = PlanningMode.DAY_TRIP, routeCharacter = RouteCharacter.BEAUTIFUL, autoSuggestStops = true)
                preferences = preferences.copy(
                    maxExtraMinutes = 120,
                    maxExtraPercent = 70,
                    maxStops = 8,
                    avoidMotorways = true,
                    windingness = 80,
                    hilliness = 65,
                    vehicle = vehicleProfile,
                )
            }
        }
        if (hadRoute) routeDirty = true
    }

    fun executeBuildRoute(fromOverride: GeoPoint? = null) {
        val from = fromOverride ?: origin
        val to = destination

        if (from == null || to == null) {
            activePanel = null
            minimizedPanel = null
            topExpanded = true
            routeBarExpanded = false
            routeIssue = when {
                from == null && to == null -> RouteIssueV2.BOTH
                from == null -> RouteIssueV2.START
                else -> RouteIssueV2.DESTINATION
            }
            routeError = when (routeIssue) {
                RouteIssueV2.BOTH -> "Start and destination are missing. Choose both before planning a route."
                RouteIssueV2.START -> "Start is missing. Choose a start place or enable live GPS."
                RouteIssueV2.DESTINATION -> "Destination is missing. Choose where you want to go."
                else -> "Start or destination is missing."
            }
            return
        }

        routeLoading = true
        routeError = null
        routeIssue = RouteIssueV2.NONE
        activePanel = null
        minimizedPanel = null
        routeBarExpanded = false

        val effectivePreferences = preferences.copy(vehicle = vehicleProfile)
        scope.launch {
            ScenicApi.planRoute(from, to, plan, effectivePreferences)
                .onSuccess { result ->
                    routeLoading = false
                    if (result.candidates.isNotEmpty()) {
                        routePlan = result
                        selectedCandidateIndex = 0
                        routeDirty = false
                        preferences = effectivePreferences
                        // A navigation reroute becomes a route from the actual current GPS point,
                        // so the planner must not keep displaying an obsolete manually chosen start.
                        if (fromOverride != null) startSelection = null
                        topExpanded = false
                        routeBarExpanded = false
                    } else {
                        routeIssue = RouteIssueV2.RETRY
                        routeError = if (routePlan != null) {
                            "No replacement route matched this time budget. The previous route stays visible."
                        } else {
                            "No route matched this time budget. Try Balanced, Scenic or a larger budget."
                        }
                    }
                }
                .onFailure { error ->
                    routeLoading = false
                    routeIssue = RouteIssueV2.RETRY
                    routeError = if (routePlan != null) {
                        "${error.message ?: "Route planning failed"}. The previous route stays visible."
                    } else {
                        error.message ?: "Route planning failed"
                    }
                }
        }
    }

    fun buildRoute() = executeBuildRoute()
    fun rerouteFromCurrentLocation(point: GeoPoint) = executeBuildRoute(point)

    fun addAlternative(stop: ScenePointUi) {
        if (plan.stops.any { it.id == stop.id }) return
        val kind = StopKind.entries.firstOrNull { it.name == stop.kind } ?: StopKind.SCENIC
        plan = plan.copy(
            stops = plan.stops + PlannedStop(
                id = stop.id,
                name = stop.name,
                kind = kind,
                dwellMinutes = stop.suggestedDwellMinutes,
                locked = true,
                mustVisit = true,
                point = stop.point,
                rating = stop.rating,
                ratingCount = stop.ratingCount,
                subtype = stop.subtype,
            )
        )
        if (routePlan != null) routeDirty = true
        openPanel(ExperiencePanelV2.PLANNER)
    }

    fun toggleMapStop(stop: ScenePointUi) {
        val exists = plan.stops.any { it.id == stop.id }
        plan = if (exists) {
            plan.copy(stops = plan.stops.filterNot { it.id == stop.id })
        } else {
            val kind = StopKind.entries.firstOrNull { it.name == stop.kind } ?: StopKind.SCENIC
            plan.copy(
                stops = plan.stops + PlannedStop(
                    id = stop.id,
                    name = stop.name,
                    kind = kind,
                    dwellMinutes = stop.suggestedDwellMinutes,
                    locked = true,
                    mustVisit = true,
                    point = stop.point,
                    rating = stop.rating,
                    ratingCount = stop.ratingCount,
                    subtype = stop.subtype,
                )
            )
        }
        if (routePlan != null) routeDirty = true
    }

    Box(Modifier.fillMaxSize()) {
        ScenicMap(
            modifier = Modifier.fillMaxSize(),
            userLocation = location.point,
            routePoints = activeRoute?.points.orEmpty(),
            stops = plan.stops,
            highlights = activeRoute?.scenePoints.orEmpty(),
            routeDirty = routeDirty,
            recenterToken = recenterToken,
            onToggleRouteStop = ::toggleMapStop,
            onRecalculateRoute = ::buildRoute,
            onRerouteFromLocation = ::rerouteFromCurrentLocation,
            onNavigationActiveChange = { navigationActive = it },
            onMapError = {},
        )

        if (rootOsdVisible) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                routeError?.let { message ->
                    RouteErrorBannerV2(
                        message = message,
                        issue = routeIssue,
                        onStart = { openPanel(ExperiencePanelV2.START) },
                        onDestination = { openPanel(ExperiencePanelV2.DESTINATION) },
                        onRetry = ::buildRoute,
                        onDismiss = { routeError = null; routeIssue = RouteIssueV2.NONE },
                    )
                }

                TopRoutePanelV2(
                    expanded = topExpanded,
                    onToggle = {
                        val next = !topExpanded
                        topExpanded = next
                        if (next) routeBarExpanded = false
                    },
                    startLabel = startLabel,
                    destinationLabel = destinationLabel,
                    startSupporting = when {
                        startSelection != null -> startSelection?.subtitle
                        location.accuracyMeters != null -> "GPS ±${location.accuracyMeters.toInt()} m"
                        !locationPermissionGranted -> "Live GPS is off"
                        location.error != null -> location.error
                        else -> "Using live GPS"
                    },
                    destinationSupporting = destinationSelection?.subtitle,
                    plan = plan,
                    preferences = preferences,
                    routeLoading = routeLoading,
                    hasRoute = routePlan != null,
                    routeDirty = routeDirty,
                    vehicleProfile = vehicleProfile,
                    onStart = { openPanel(ExperiencePanelV2.START) },
                    onDestination = { openPanel(ExperiencePanelV2.DESTINATION) },
                    onPlanner = { openPanel(ExperiencePanelV2.PLANNER) },
                    onStops = { openPanel(ExperiencePanelV2.STOPS) },
                    onVehicleSettings = onVehicleSettings,
                    onBuildRoute = ::buildRoute,
                    onQuickMode = ::applyQuickMode,
                    onEnableGps = requestLocationPermission,
                    locationPermissionGranted = locationPermissionGranted,
                )
            }

            activeRoute?.let { route ->
                RouteSummaryBarV2(
                    route = route,
                    expanded = routeBarExpanded,
                    candidateIndex = selectedCandidateIndex,
                    candidateCount = routePlan?.candidates?.size ?: 1,
                    onToggle = {
                        val next = !routeBarExpanded
                        routeBarExpanded = next
                        if (next) topExpanded = false
                    },
                    onPrevious = {
                        val count = routePlan?.candidates?.size ?: 0
                        if (count > 0) selectedCandidateIndex = (selectedCandidateIndex - 1 + count) % count
                    },
                    onNext = {
                        val count = routePlan?.candidates?.size ?: 0
                        if (count > 0) selectedCandidateIndex = (selectedCandidateIndex + 1) % count
                    },
                    onStops = { openPanel(ExperiencePanelV2.STOPS) },
                    onPlanner = { openPanel(ExperiencePanelV2.PLANNER) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 12.dp, end = 78.dp, bottom = 78.dp),
                )
            }

            Column(
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (locationPermissionGranted) {
                    SmallFloatingActionButton(onClick = { recenterToken++ }) {
                        Icon(Icons.Default.MyLocation, "Center map")
                    }
                }
                SmallFloatingActionButton(onClick = { openPanel(ExperiencePanelV2.PLANNER) }) {
                    Icon(Icons.Default.Tune, "Open planner")
                }
                SmallFloatingActionButton(onClick = onVehicleSettings) { Text(vehicleProfile.kind.emoji) }
            }

            minimizedPanel?.let { panel ->
                MinimizedPanelDockV2(
                    panel = panel,
                    onRestore = { openPanel(panel) },
                    onClose = { minimizedPanel = null },
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                )
            }
        }
    }

    when (activePanel) {
        ExperiencePanelV2.START -> PlacePickerSheet(
            title = "Choose start",
            initialQuery = startSelection?.title.orEmpty(),
            bias = location.point,
            onDismiss = { minimizePanel(ExperiencePanelV2.START) },
            onPick = {
                startSelection = it
                activePanel = null
                clearEndpointRoute()
                if (destinationSelection == null) openPanel(ExperiencePanelV2.DESTINATION)
            },
        )
        ExperiencePanelV2.DESTINATION -> PlacePickerSheet(
            title = "Choose destination",
            initialQuery = destinationSelection?.title.orEmpty(),
            bias = origin,
            onDismiss = { minimizePanel(ExperiencePanelV2.DESTINATION) },
            onPick = {
                destinationSelection = it
                activePanel = null
                clearEndpointRoute()
            },
        )
        ExperiencePanelV2.PLANNER -> JourneyPlannerSheet(
            start = startLabel,
            destination = destinationLabel,
            plan = plan,
            preferences = preferences,
            hasRoute = routePlan != null,
            onPlanChange = {
                plan = it
                if (routePlan != null) routeDirty = true
            },
            onPreferencesChange = {
                preferences = it.copy(vehicle = vehicleProfile)
                if (routePlan != null) routeDirty = true
            },
            onRequestSuggestions = { openPanel(ExperiencePanelV2.STOPS) },
            onBuildRoute = ::buildRoute,
            onDismiss = { minimizePanel(ExperiencePanelV2.PLANNER) },
        )
        ExperiencePanelV2.STOPS -> JourneyStopsSheet(
            route = activeRoute,
            manuallyAddedIds = plan.stops.mapTo(mutableSetOf()) { it.id },
            onAddAlternative = ::addAlternative,
            onManualSearch = { openPanel(ExperiencePanelV2.STOP_KIND) },
            onDismiss = { minimizePanel(ExperiencePanelV2.STOPS) },
        )
        ExperiencePanelV2.STOP_KIND -> StopKindDialogV2(
            selected = pendingStopKind,
            onDismiss = { openPanel(ExperiencePanelV2.STOPS) },
            onSelect = { pendingStopKind = it; openPanel(ExperiencePanelV2.STOP_PLACE) },
        )
        ExperiencePanelV2.STOP_PLACE -> PlacePickerSheet(
            title = "Add ${pendingStopKind.label.lowercase()}",
            bias = origin,
            onDismiss = { openPanel(ExperiencePanelV2.STOPS) },
            onPick = { place ->
                plan = plan.copy(
                    stops = plan.stops + PlannedStop(
                        id = "manual-${System.nanoTime()}",
                        name = place.title,
                        kind = pendingStopKind,
                        point = place.point,
                        subtitle = place.subtitle,
                        locked = true,
                    )
                )
                if (routePlan != null) routeDirty = true
                openPanel(ExperiencePanelV2.PLANNER)
            },
        )
        null -> Unit
    }
}

@Composable
private fun RouteErrorBannerV2(
    message: String,
    issue: RouteIssueV2,
    onStart: () -> Unit,
    onDestination: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WarningAmber, null)
                Spacer(Modifier.width(8.dp))
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (issue) {
                    RouteIssueV2.BOTH -> {
                        TextButton(onClick = onStart) { Text("Choose start") }
                        TextButton(onClick = onDestination) { Text("Choose destination") }
                    }
                    RouteIssueV2.START -> TextButton(onClick = onStart) { Text("Choose start") }
                    RouteIssueV2.DESTINATION -> TextButton(onClick = onDestination) { Text("Choose destination") }
                    RouteIssueV2.RETRY -> TextButton(onClick = onRetry) { Text("Retry") }
                    RouteIssueV2.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun TopRoutePanelV2(
    expanded: Boolean,
    onToggle: () -> Unit,
    startLabel: String,
    destinationLabel: String,
    startSupporting: String?,
    destinationSupporting: String?,
    plan: TripPlan,
    preferences: ScenicPreferences,
    routeLoading: Boolean,
    hasRoute: Boolean,
    routeDirty: Boolean,
    vehicleProfile: VehicleProfile,
    onStart: () -> Unit,
    onDestination: () -> Unit,
    onPlanner: () -> Unit,
    onStops: () -> Unit,
    onVehicleSettings: () -> Unit,
    onBuildRoute: () -> Unit,
    onQuickMode: (QuickModeV2) -> Unit,
    onEnableGps: () -> Unit,
    locationPermissionGranted: Boolean,
) {
    val activeQuickMode = quickModeForV2(plan, preferences)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(if (expanded) 14.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Landscape, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (expanded) {
                    Text("Scenic Path", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("The beautiful way", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${startLabel.take(18)} → ${destinationLabel.ifBlank { "Destination" }.take(18)}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "${activeQuickMode?.label ?: plan.routeCharacter.label} · ${vehicleProfile.kind.emoji} ${vehicleProfile.kind.label} · +${preferences.maxExtraMinutes} min${if (routeDirty) " · changes pending" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (routeLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            IconButton(onClick = onToggle) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Minimize route controls" else "Expand route controls")
            }
        }

        if (expanded) {
            PlaceFieldV2("Start", startLabel, Icons.Default.MyLocation, startSupporting, onStart)
            PlaceFieldV2("Destination", destinationLabel.ifBlank { "Where do you want to go?" }, Icons.Default.Flag, destinationSupporting, onDestination)

            Text("Route mode", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                QuickModeV2.entries.forEach { mode ->
                    FilterChip(selected = activeQuickMode == mode, onClick = { onQuickMode(mode) }, label = { Text(mode.label) })
                }
            }
            Text(
                activeQuickMode?.description ?: "Custom settings are active. Advanced planner keeps full Scenic DNA and route constraints available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!locationPermissionGranted) {
                    AssistChip(onClick = onEnableGps, label = { Text("Enable GPS") }, leadingIcon = { Icon(Icons.Default.GpsFixed, null, Modifier.size(18.dp)) })
                }
                AssistChip(onClick = onVehicleSettings, label = { Text("${vehicleProfile.kind.emoji} ${vehicleProfile.kind.label}") })
                AssistChip(onClick = onPlanner, label = { Text("Advanced planner") }, leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) })
                AssistChip(onClick = onStops, label = { Text("Smart Stops") }, leadingIcon = { Icon(Icons.Default.AddLocationAlt, null, Modifier.size(18.dp)) })
                AssistChip(onClick = onPlanner, label = { Text("+${preferences.maxExtraMinutes} min") }, leadingIcon = { Icon(Icons.Default.MoreTime, null, Modifier.size(18.dp)) })
            }

            Button(onClick = onBuildRoute, enabled = !routeLoading, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Default.Route, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        routeDirty && hasRoute -> "Rebuild route with changes"
                        hasRoute -> "Recalculate route"
                        else -> "Plan route"
                    }
                )
            }
        }
    }
}

@Composable
private fun PlaceFieldV2(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    supporting: String?,
    onClick: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontWeight = FontWeight.Medium, maxLines = 1)
                if (!supporting.isNullOrBlank()) {
                    Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun RouteSummaryBarV2(
    route: RouteCandidateUi,
    expanded: Boolean,
    candidateIndex: Int,
    candidateCount: Int,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStops: () -> Unit,
    onPlanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val included = route.scenePoints.filter { it.includedInRoute || it.id in route.autoStopIds }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (route.character == RouteCharacter.DIRECT.name) Icons.Default.NearMe else Icons.Default.Explore, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(route.variantLabel ?: route.character.lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        "${formatDistanceV2(route.distanceMeters)} · ${formatDurationV2(route.durationSeconds)} · +${route.totalExtraMinutes.roundToInt()}m total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (candidateCount > 1) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ChevronLeft, "Previous route") }
                    Text("${candidateIndex + 1}/$candidateCount", style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ChevronRight, "Next route") }
                }
                IconButton(onClick = onToggle, modifier = Modifier.size(38.dp)) {
                    Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (expanded) "Minimize route details" else "Show route details")
                }
            }
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricV2("Drive detour", "+${route.driveExtraMinutes.roundToInt()}m", Modifier.weight(1f))
                        MetricV2("Visits", "${route.dwellMinutes}m", Modifier.weight(1f))
                        MetricV2("Score", route.experienceScore.roundToInt().toString(), Modifier.weight(1f))
                    }
                    if (included.isNotEmpty()) {
                        Text("Included stops", fontWeight = FontWeight.SemiBold)
                        included.take(4).forEachIndexed { index, stop ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text((index + 1).toString(), modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(stop.name, Modifier.weight(1f), maxLines = 1)
                                Text("${stop.suggestedDwellMinutes}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onStops, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.AddLocationAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stops")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onPlanner, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Adjust")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricV2(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MinimizedPanelDockV2(panel: ExperiencePanelV2, onRestore: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val (label, icon) = when (panel) {
        ExperiencePanelV2.START -> "Start search" to Icons.Default.MyLocation
        ExperiencePanelV2.DESTINATION -> "Destination" to Icons.Default.Flag
        ExperiencePanelV2.PLANNER -> "Planner" to Icons.Default.Tune
        ExperiencePanelV2.STOPS -> "Smart Stops" to Icons.Default.AddLocationAlt
        ExperiencePanelV2.STOP_KIND -> "Stop type" to Icons.Default.Category
        ExperiencePanelV2.STOP_PLACE -> "Add stop" to Icons.Default.Place
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Row(Modifier.padding(start = 10.dp, end = 2.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onRestore) { Text(label) }
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Close, "Close minimized $label") }
        }
    }
}

@Composable
private fun StopKindDialogV2(selected: StopKind, onDismiss: () -> Unit, onSelect: (StopKind) -> Unit) {
    val kinds = allSelectableSceneKinds + StopKind.CUSTOM
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual stop type") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                kinds.forEach { kind ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (kind == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(kind) },
                    ) {
                        Text("${kind.emoji} ${kind.label}", Modifier.padding(11.dp), fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun quickModeForV2(plan: TripPlan, preferences: ScenicPreferences): QuickModeV2? = when {
    plan.routeCharacter == RouteCharacter.DIRECT && !plan.autoSuggestStops && preferences.maxExtraMinutes <= 15 -> QuickModeV2.DIRECT
    plan.routeCharacter == RouteCharacter.BALANCED && plan.mode == PlanningMode.QUICK && preferences.maxExtraMinutes in 20..45 -> QuickModeV2.BALANCED
    plan.routeCharacter == RouteCharacter.BEAUTIFUL && plan.mode == PlanningMode.DAY_TRIP && preferences.maxExtraMinutes >= 90 -> QuickModeV2.DISCOVER
    plan.routeCharacter == RouteCharacter.BEAUTIFUL && plan.mode == PlanningMode.QUICK && preferences.maxExtraMinutes in 45..89 -> QuickModeV2.SCENIC
    else -> null
}

private fun formatDistanceV2(meters: Double): String = if (meters >= 1000) {
    String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
} else {
    "${meters.roundToInt()} m"
}

private fun formatDurationV2(seconds: Double): String {
    val totalMinutes = (seconds / 60.0).roundToInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
