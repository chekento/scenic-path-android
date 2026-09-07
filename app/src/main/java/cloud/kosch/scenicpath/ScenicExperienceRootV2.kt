package cloud.kosch.scenicpath

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.roundToInt

private enum class ExperiencePanelV2 { START, DESTINATION, PLANNER, STOPS, STOP_KIND, STOP_PLACE }

/** One session, one modal, and measured screen regions instead of competing fixed overlays. */
@Composable
fun ScenicExperienceRootV2(
    locationPermissionGranted: Boolean,
    requestLocationPermission: () -> Unit,
    vehicleProfile: VehicleProfile = VehicleSettingsState.profile,
    onVehicleSettings: () -> Unit = {},
    externalOverlayVisible: Boolean = false,
    onGpsEnabledChange: (Boolean) -> Unit = {},
    session: JourneySessionViewModel = viewModel(),
) {
    val configuration = LocalConfiguration.current
    val location = rememberLocationUiState(locationPermissionGranted)
    val state = session.state
    val plan = state.plan
    val preferences = state.preferences
    val origin = state.start?.point ?: location.point
    val destination = state.destination?.point
    val activeRoute = state.activeRoute
    var activePanel by rememberSaveable { mutableStateOf<ExperiencePanelV2?>(null) }
    var minimizedPanel by rememberSaveable { mutableStateOf<ExperiencePanelV2?>(null) }
    var pendingStopKind by rememberSaveable { mutableStateOf(StopKind.CUSTOM) }
    var topExpanded by rememberSaveable { mutableStateOf(true) }
    var routeBarExpanded by rememberSaveable { mutableStateOf(false) }
    var mapOverlayActive by remember { mutableStateOf(false) }
    var recenterToken by remember { mutableIntStateOf(0) }
    var mapRetryToken by remember { mutableIntStateOf(0) }
    var mapError by remember { mutableStateOf<String?>(null) }
    val rootOsdVisible = activePanel == null && !externalOverlayVisible && !mapOverlayActive
    val isRoundTripSelection = origin != null && destination != null &&
        plan.mode == PlanningMode.DAY_TRIP && RoundTripPolicy.haversineMeters(origin, destination) <= 350.0
    val startLabel = state.start?.title ?: when {
        location.point != null -> "Current location"
        locationPermissionGranted -> "Waiting for GPS"
        else -> "Choose start or enable GPS"
    }
    val destinationLabel = state.destination?.title.orEmpty()

    LaunchedEffect(vehicleProfile) {
        session.edit(preferences = session.state.preferences.copy(vehicle = vehicleProfile))
    }
    LaunchedEffect(state.routes) {
        if (state.routes != null) { topExpanded = false; routeBarExpanded = false }
    }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            activePanel = null
            topExpanded = false
            routeBarExpanded = false
        }
    }
    BackHandler(enabled = activePanel == null && minimizedPanel != null && !mapOverlayActive) {
        minimizedPanel = null
    }

    fun openPanel(panel: ExperiencePanelV2) {
        activePanel = panel
        minimizedPanel = null
        topExpanded = false
        routeBarExpanded = false
    }
    fun minimizePanel(panel: ExperiencePanelV2) { activePanel = null; minimizedPanel = panel }
    fun buildRoute() {
        activePanel = null
        minimizedPanel = null
        topExpanded = false
        routeBarExpanded = false
        session.build(location.point)
    }
    fun toggleMapStop(stop: ScenePointUi) {
        val current = session.state.plan
        val nextStops = if (current.stops.any { it.id == stop.id }) current.stops.filterNot { it.id == stop.id }
        else current.stops + PlannedStop(
            id = stop.id, name = stop.name,
            kind = StopKind.entries.firstOrNull { it.name == stop.kind } ?: StopKind.SCENIC,
            dwellMinutes = stop.suggestedDwellMinutes, locked = true, mustVisit = true,
            point = stop.point, rating = stop.rating, ratingCount = stop.ratingCount, subtype = stop.subtype,
        )
        session.edit(plan = current.copy(stops = nextStops))
    }
    fun makeRoundTrip() {
        if (origin == null) { session.build(location.point); return }
        val (roundPlan, roundPreferences) = RoutePresetPolicy.apply(QuickModeV2.DISCOVER, plan, preferences)
        session.edit(roundPlan, roundPreferences)
        // Pin both ends: subsequent GPS movement must not move the start of the chosen loop.
        val start = state.start ?: PlaceSuggestion("round-trip-start", startLabel, "Round trip starting point", origin)
        session.selectStart(start)
        session.selectDestination(start.copy(id = "round-trip-return", subtitle = "Return to starting point"))
        topExpanded = true
    }

    val mapContent: @Composable () -> Unit = {
        ScenicMap(
            modifier = Modifier.fillMaxSize(),
            userLocation = location.point,
            locationState = location,
            routePoints = activeRoute?.points.orEmpty(),
            stops = plan.stops,
            navigationStops = state.committedPlan?.stops.orEmpty(),
            highlights = activeRoute?.scenePoints.orEmpty(),
            routeDirty = state.dirty,
            routeLoading = state.loading,
            controlsVisible = activePanel == null && !externalOverlayVisible,
            recenterToken = recenterToken,
            onToggleRouteStop = ::toggleMapStop,
            onRecalculateRoute = ::buildRoute,
            onRerouteFromLocation = { session.build(location.point, rerouteFrom = it) },
            onNavigationActiveChange = { mapOverlayActive = it },
            onMapError = { mapError = it.takeIf(String::isNotBlank) },
            mapRetryToken = mapRetryToken,
        )
    }
    val retainedMap = remember { movableContentOf<@Composable () -> Unit> { content -> content() } }
    val topControls: @Composable () -> Unit = {
        TopRoutePanelV2(
            expanded = topExpanded,
            onToggle = { topExpanded = !topExpanded; if (topExpanded) routeBarExpanded = false },
            startLabel = startLabel, destinationLabel = destinationLabel,
            startSupporting = when {
                state.start != null -> state.start.subtitle
                location.error != null -> location.error
                location.accuracyMeters != null -> "GPS ±${location.accuracyMeters.toInt()} m"
                else -> "Current position is optional"
            },
            destinationSupporting = state.destination?.subtitle,
            plan = plan, preferences = preferences, routeLoading = state.loading,
            hasRoute = state.routes != null, routeDirty = state.dirty, vehicleProfile = vehicleProfile,
            roundTripActive = isRoundTripSelection, canRoundTrip = origin != null,
            onRoundTrip = ::makeRoundTrip,
            onStart = { openPanel(ExperiencePanelV2.START) },
            onDestination = { openPanel(ExperiencePanelV2.DESTINATION) },
            onPlanner = { openPanel(ExperiencePanelV2.PLANNER) },
            onStops = { openPanel(ExperiencePanelV2.STOPS) },
            onVehicleSettings = onVehicleSettings, onBuildRoute = ::buildRoute,
            onQuickMode = { mode ->
                val (nextPlan, nextPreferences) = RoutePresetPolicy.apply(mode, plan, preferences)
                session.edit(nextPlan, nextPreferences)
            },
            onEnableGps = requestLocationPermission, locationPermissionGranted = locationPermissionGranted,
        )
    }
    val bottomControls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            minimizedPanel?.let { panel ->
                MinimizedPanelDockV2(panel, { openPanel(panel) }, { minimizedPanel = null }, Modifier.fillMaxWidth())
            }
            activeRoute?.let { route ->
                RouteSummaryBarV2(
                    route = route, expanded = routeBarExpanded, candidateIndex = state.selectedIndex,
                    candidateCount = state.routes?.candidates?.size ?: 1,
                    onToggle = { routeBarExpanded = !routeBarExpanded; if (routeBarExpanded) topExpanded = false },
                    onPrevious = { session.selectAlternative(-1) }, onNext = { session.selectAlternative(1) },
                    onAddRoute = { session.build(location.point, appendAlternative = true) },
                    canAddRoute = !state.dirty && (state.routes?.candidates?.size ?: 0) < 5,
                    addingRoute = state.loading,
                    onStops = { openPanel(ExperiencePanelV2.STOPS) }, onPlanner = { openPanel(ExperiencePanelV2.PLANNER) },
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconToggleButton(checked = locationPermissionGranted, onCheckedChange = onGpsEnabledChange) {
                    Icon(if (locationPermissionGranted) Icons.Default.GpsFixed else Icons.Default.GpsOff, if (locationPermissionGranted) "Turn GPS off" else "Enable GPS")
                }
                IconButton(onClick = { if (state.start != null) session.selectStart(null); requestLocationPermission(); recenterToken++ }) {
                    Icon(Icons.Default.MyLocation, "Use current location as start")
                }
                IconButton(onClick = { recenterToken++ }, enabled = location.point != null) { Icon(Icons.Default.CenterFocusStrong, "Center map") }
                IconButton(onClick = { openPanel(ExperiencePanelV2.PLANNER) }) { Icon(Icons.Default.Tune, "Open planner") }
                IconButton(onClick = onVehicleSettings) { Icon(Icons.Default.DirectionsCar, "Vehicle settings: ${vehicleProfile.kind.label}") }
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Feedback remains above navigation, POI details and collapsed panels.
        if (!externalOverlayVisible) Column(Modifier.fillMaxWidth().heightIn(max = configuration.screenHeightDp.dp * 0.28f).verticalScroll(rememberScrollState())) {
            state.error?.let { message ->
                RouteErrorBannerV2(message, state.issue,
                    { openPanel(ExperiencePanelV2.START) }, { openPanel(ExperiencePanelV2.DESTINATION) },
                    ::buildRoute, session::dismissFeedback)
            }
            if (state.loading) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Planning your route…", Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { session.cancelBuild() }) { Text("Cancel") }
                    }
                }
            }
            (state.notice ?: mapError)?.let { message ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                        if (mapError != null) TextButton(onClick = { mapRetryToken++; mapError = null }) { Text("Retry map") }
                        IconButton(onClick = { session.dismissFeedback(); mapError = null }) { Icon(Icons.Default.Close, "Dismiss message") }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val contentHeight = maxHeight
            val wide = maxWidth >= 840.dp || maxWidth > maxHeight * 1.25f
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    if (rootOsdVisible) Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        topControls()
                        bottomControls()
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { retainedMap(mapContent) }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (rootOsdVisible) Box(Modifier.heightIn(max = contentHeight * 0.43f)) { topControls() }
                    Box(Modifier.weight(1f).fillMaxWidth()) { retainedMap(mapContent) }
                    if (rootOsdVisible) Column(Modifier.heightIn(max = contentHeight * 0.34f).verticalScroll(rememberScrollState())) { bottomControls() }
                }
            }
        }
    }

    when (activePanel) {
        ExperiencePanelV2.START -> PlacePickerSheet(
            title = "Choose start", bias = location.point,
            searchState = session.searchState("start", state.start?.title.orEmpty()),
            onDismiss = { minimizePanel(ExperiencePanelV2.START) },
            onPick = { session.selectStart(it); activePanel = null; topExpanded = true; if (state.destination == null) openPanel(ExperiencePanelV2.DESTINATION) },
        )
        ExperiencePanelV2.DESTINATION -> PlacePickerSheet(
            title = "Choose destination", bias = origin,
            searchState = session.searchState("destination", destinationLabel),
            onDismiss = { minimizePanel(ExperiencePanelV2.DESTINATION) },
            onPick = { session.selectDestination(it); activePanel = null; topExpanded = true },
        )
        ExperiencePanelV2.PLANNER -> JourneyPlannerSheet(
            start = startLabel, destination = destinationLabel, plan = plan, preferences = preferences,
            hasRoute = state.routes != null,
            onPlanChange = { session.edit(plan = it) },
            onPreferencesChange = { session.edit(preferences = it.copy(vehicle = vehicleProfile)) },
            onRequestSuggestions = { openPanel(ExperiencePanelV2.STOPS) }, onBuildRoute = ::buildRoute,
            onDismiss = { minimizePanel(ExperiencePanelV2.PLANNER) },
        )
        ExperiencePanelV2.STOPS -> JourneyStopsSheet(
            route = activeRoute, manuallyAddedIds = plan.stops.mapTo(mutableSetOf()) { it.id },
            onAddAlternative = { if (plan.stops.none { stop -> stop.id == it.id }) toggleMapStop(it); openPanel(ExperiencePanelV2.PLANNER) },
            onManualSearch = { openPanel(ExperiencePanelV2.STOP_KIND) }, onDismiss = { minimizePanel(ExperiencePanelV2.STOPS) },
        )
        ExperiencePanelV2.STOP_KIND -> StopKindDialogV2(pendingStopKind,
            { openPanel(ExperiencePanelV2.STOPS) }, { pendingStopKind = it; openPanel(ExperiencePanelV2.STOP_PLACE) })
        ExperiencePanelV2.STOP_PLACE -> PlacePickerSheet(
            title = "Add ${pendingStopKind.label.lowercase()}", bias = origin,
            searchState = session.searchState("stop-${pendingStopKind.name}"),
            onDismiss = { minimizePanel(ExperiencePanelV2.STOP_PLACE) },
            onPick = { place ->
                session.edit(plan = plan.copy(stops = plan.stops + PlannedStop(
                    id = "manual-${System.nanoTime()}", name = place.title, kind = pendingStopKind,
                    point = place.point, subtitle = place.subtitle, locked = true)))
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Assertive },
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
    roundTripActive: Boolean,
    canRoundTrip: Boolean,
    onRoundTrip: () -> Unit,
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
            .fillMaxWidth().heightIn(max = 560.dp)
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
                    Text(
                        if (roundTripActive) "Round trip · $startLabel" else "${startLabel.take(18)} → ${destinationLabel.ifBlank { "Destination" }.take(18)}",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${activeQuickMode?.label ?: plan.routeCharacter.label} · ${vehicleProfile.kind.emoji} ${vehicleProfile.kind.label} · ${if (plan.mode == PlanningMode.DAY_TRIP) "${preferences.maxExtraMinutes} min budget" else "+${preferences.maxExtraMinutes} min"}${if (routeDirty) " · changes pending" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (routeLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            IconButton(onClick = onToggle) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Minimize route controls" else "Expand route controls")
            }
        }

        if (expanded) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaceFieldV2("Start", startLabel, Icons.Default.MyLocation, startSupporting, onStart)
            PlaceFieldV2(
                if (roundTripActive) "Return point" else "Destination",
                destinationLabel.ifBlank { "Where do you want to go?" },
                if (roundTripActive) Icons.Default.Loop else Icons.Default.Flag,
                destinationSupporting,
                onDestination,
            )

            Text("${plan.mode.label} · route priority", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                QuickModeV2.entries.forEach { mode ->
                    FilterChip(selected = activeQuickMode == mode, onClick = { onQuickMode(mode) }, label = { Text(mode.label) })
                }
            }
            Text(
                if (roundTripActive) {
                    "Round-trip mode uses the selected day budget as a target: Scenic Path tries to fill it with a beautiful loop and worthwhile stops, then returns to the start."
                } else activeQuickMode?.description
                    ?: "Custom settings are active. Advanced planner keeps full Scenic DNA and route constraints available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!locationPermissionGranted) {
                    AssistChip(onClick = onEnableGps, label = { Text("Enable GPS") }, leadingIcon = { Icon(Icons.Default.GpsFixed, null, Modifier.size(18.dp)) })
                }
                AssistChip(
                    onClick = onRoundTrip,
                    enabled = canRoundTrip,
                    label = { Text(if (roundTripActive) "Round trip active" else "Round trip from start") },
                    leadingIcon = { Icon(Icons.Default.Loop, null, Modifier.size(18.dp)) },
                )
                AssistChip(onClick = onVehicleSettings, label = { Text("${vehicleProfile.kind.emoji} ${vehicleProfile.kind.label}") })
                AssistChip(onClick = onPlanner, label = { Text("Advanced planner") }, leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) })
                AssistChip(onClick = onStops, label = { Text("Smart Stops") }, leadingIcon = { Icon(Icons.Default.AddLocationAlt, null, Modifier.size(18.dp)) })
                AssistChip(
                    onClick = onPlanner,
                    label = { Text(if (plan.mode == PlanningMode.DAY_TRIP) "${preferences.maxExtraMinutes} min budget" else "+${preferences.maxExtraMinutes} min") },
                    leadingIcon = { Icon(Icons.Default.MoreTime, null, Modifier.size(18.dp)) },
                )
            }

            }

            Button(onClick = onBuildRoute, enabled = !routeLoading, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Icon(if (roundTripActive) Icons.Default.Loop else Icons.Default.Route, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        routeDirty && hasRoute -> "Rebuild route with changes"
                        roundTripActive && hasRoute -> "Recalculate round trip"
                        roundTripActive -> "Create scenic round trip"
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
    onAddRoute: () -> Unit,
    canAddRoute: Boolean,
    addingRoute: Boolean,
    onStops: () -> Unit,
    onPlanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val included = route.scenePoints.filter { it.includedInRoute || it.id in route.autoStopIds }
    val budgetUsed = route.budgetUsedMinutes
    val budget = route.budgetMinutes
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (route.isRoundTrip) Icons.Default.Loop else if (route.character == RouteCharacter.DIRECT.name) Icons.Default.NearMe else Icons.Default.Explore, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(route.variantLabel ?: route.character.lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        if (route.isRoundTrip && budgetUsed != null && budget != null) {
                            "${formatDistanceV2(route.distanceMeters)} · ${formatDurationV2(route.durationSeconds)} drive · ${budgetUsed.roundToInt()}/$budget min day"
                        } else if (budgetUsed != null && budget != null) {
                            "${formatDistanceV2(route.distanceMeters)} · ${formatDurationV2(route.durationSeconds)} · ${budgetUsed.roundToInt()}/$budget min exploration"
                        } else {
                            "${formatDistanceV2(route.distanceMeters)} · ${formatDurationV2(route.durationSeconds)} · +${route.totalExtraMinutes.roundToInt()}m total"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                    Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (expanded) "Minimize route details" else "Show route details")
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = candidateCount > 1 && !addingRoute) { Icon(Icons.Default.ChevronLeft, "Previous route") }
                Text("Route ${candidateIndex + 1} of $candidateCount", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onNext, enabled = candidateCount > 1 && !addingRoute) { Icon(Icons.Default.ChevronRight, "Next route") }
                IconButton(onClick = onAddRoute, enabled = canAddRoute && !addingRoute) {
                    Icon(Icons.Default.AddCircleOutline, "Find another alternative route")
                }
            }
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (route.isRoundTrip) {
                            MetricV2("Driving", "${(route.durationSeconds / 60).roundToInt()}m", Modifier.weight(1f))
                            MetricV2("Visits", "${route.dwellMinutes}m", Modifier.weight(1f))
                            val percent = if (budgetUsed != null && budget != null && budget > 0) (budgetUsed / budget * 100).roundToInt() else 0
                            MetricV2("Budget used", "$percent%", Modifier.weight(1f))
                        } else {
                            MetricV2("Drive detour", "+${route.driveExtraMinutes.roundToInt()}m", Modifier.weight(1f))
                            MetricV2("Visits", "${route.dwellMinutes}m", Modifier.weight(1f))
                            MetricV2("Score", route.experienceScore.roundToInt().toString(), Modifier.weight(1f))
                        }
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
                    if (candidateIndex == 1) {
                        Text(
                            "Alternative 2 is deliberately selected for a different road corridor and, where available, different automatic waypoints.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onStops, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.AddLocationAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stops")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onPlanner, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Adjust")
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
            TextButton(onClick = onRestore, modifier = Modifier.weight(1f)) { Text("Resume $label") }
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close minimized $label") }
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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

private fun quickModeForV2(plan: TripPlan, preferences: ScenicPreferences): QuickModeV2? = when (plan.routeCharacter) {
    RouteCharacter.DIRECT -> QuickModeV2.DIRECT
    RouteCharacter.BALANCED -> QuickModeV2.BALANCED
    RouteCharacter.BEAUTIFUL -> if (plan.mode == PlanningMode.DAY_TRIP && preferences.maxExtraMinutes >= 120) QuickModeV2.DISCOVER else QuickModeV2.SCENIC
    RouteCharacter.CUSTOM -> null
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
