package cloud.kosch.scenicpath

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ScenicPathApp(
    locationPermissionGranted: Boolean,
    requestLocationPermission: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val location = rememberLocationUiState(locationPermissionGranted)

    var startSelection by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var destinationSelection by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showDestinationPicker by remember { mutableStateOf(false) }
    var showStopIdeas by remember { mutableStateOf(false) }
    var showStopPicker by remember { mutableStateOf(false) }
    var showStopKindPicker by remember { mutableStateOf(false) }
    var pendingStopKind by remember { mutableStateOf(StopKind.CUSTOM) }
    var showScenicDNA by remember { mutableStateOf(false) }
    var showPlanner by remember { mutableStateOf(false) }
    var topPanelExpanded by remember { mutableStateOf(true) }
    var preferences by remember { mutableStateOf(ScenicPreferences()) }
    var plan by remember { mutableStateOf(TripPlan()) }
    var recenterToken by remember { mutableIntStateOf(0) }
    var routePlan by remember { mutableStateOf<RoutePlanUi?>(null) }
    var selectedCandidateIndex by remember { mutableIntStateOf(0) }
    var routeLoading by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var mapError by remember { mutableStateOf<String?>(null) }

    val origin = startSelection?.point ?: location.point
    val destination = destinationSelection?.point
    val startLabel = startSelection?.title ?: when {
        location.point != null -> "Current location"
        locationPermissionGranted -> "Waiting for GPS"
        else -> "Choose start or enable GPS"
    }
    val destinationLabel = destinationSelection?.title.orEmpty()
    val activeRoute = routePlan?.candidates?.getOrNull(selectedCandidateIndex)

    fun invalidateRoute() {
        routePlan = null
        selectedCandidateIndex = 0
    }

    fun buildRoute() {
        val from = origin
        val to = destination
        if (from == null) {
            routeError = "Choose a start place or enable live GPS first."
            return
        }
        if (to == null) {
            routeError = "Choose a destination first."
            return
        }
        routeLoading = true
        routeError = null
        showPlanner = false
        scope.launch {
            ScenicApi.planRoute(from, to, plan, preferences)
                .onSuccess { result ->
                    routePlan = result
                    selectedCandidateIndex = 0
                    routeLoading = false
                    topPanelExpanded = false
                    if (result.candidates.isEmpty()) routeError = "No route matched the current detour budget."
                }
                .onFailure { error ->
                    routeLoading = false
                    routeError = error.message ?: "Route planning failed"
                }
        }
    }

    fun addSuggestedStop(highlight: ScenePointUi) {
        if (plan.stops.any { it.id == highlight.id }) return
        val kind = StopKind.entries.firstOrNull { it.name == highlight.kind } ?: StopKind.SCENIC
        plan = plan.copy(
            stops = plan.stops + PlannedStop(
                id = highlight.id,
                name = highlight.name,
                kind = kind,
                dwellMinutes = highlight.suggestedDwellMinutes,
                locked = true,
                mustVisit = true,
                point = highlight.point,
                rating = highlight.rating,
                ratingCount = highlight.ratingCount,
                subtype = highlight.subtype,
            )
        )
        invalidateRoute()
        showStopIdeas = false
        showPlanner = true
    }

    Box(Modifier.fillMaxSize()) {
        ScenicMap(
            modifier = Modifier.fillMaxSize(),
            userLocation = location.point,
            routePoints = activeRoute?.points.orEmpty(),
            stops = plan.stops,
            highlights = activeRoute?.scenePoints.orEmpty(),
            recenterToken = recenterToken,
            onMapError = { mapError = it },
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .padding(if (topPanelExpanded) 14.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Landscape, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                if (topPanelExpanded) {
                    Column(Modifier.weight(1f)) {
                        Text("Scenic Path", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("The beautiful way", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "$startLabel  →  ${destinationLabel.ifBlank { "Choose destination" }}",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            "${plan.routeCharacter.label} · +${preferences.maxExtraMinutes} min" +
                                if (plan.autoSuggestStops) " · Smart stops" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (routeLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                if (topPanelExpanded) {
                    IconButton(onClick = { showScenicDNA = true }) {
                        Icon(Icons.Default.Tune, "Scenic DNA")
                    }
                }
                IconButton(onClick = { topPanelExpanded = !topPanelExpanded }) {
                    Icon(
                        if (topPanelExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (topPanelExpanded) "Minimize trip panel" else "Expand trip panel",
                    )
                }
            }

            if (topPanelExpanded) {
                PlaceField(
                    label = "Start",
                    value = startLabel,
                    icon = Icons.Default.MyLocation,
                    supporting = when {
                        startSelection != null -> startSelection?.subtitle
                        location.accuracyMeters != null -> "GPS ±${location.accuracyMeters!!.toInt()} m"
                        !locationPermissionGranted -> "Live GPS is off"
                        location.error != null -> location.error
                        else -> "Using live GPS"
                    },
                    onClick = { showStartPicker = true },
                )
                PlaceField(
                    label = "Destination",
                    value = destinationLabel.ifBlank { "Where do you want to go?" },
                    icon = Icons.Default.Flag,
                    supporting = destinationSelection?.subtitle,
                    onClick = { showDestinationPicker = true },
                )

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!locationPermissionGranted) {
                        AssistChip(
                            onClick = requestLocationPermission,
                            label = { Text("Enable live GPS") },
                            leadingIcon = { Icon(Icons.Default.GpsFixed, null, Modifier.size(18.dp)) },
                        )
                    }
                    AssistChip(
                        onClick = { showPlanner = true },
                        label = { Text(plan.routeCharacter.label) },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) },
                    )
                    AssistChip(
                        onClick = {
                            if (activeRoute != null) showStopIdeas = true else showPlanner = true
                        },
                        label = { Text(if (activeRoute?.scenePoints.isNullOrEmpty()) "Stop ideas" else "${activeRoute!!.scenePoints.size} ideas") },
                        leadingIcon = { Icon(Icons.Default.Explore, null, Modifier.size(18.dp)) },
                    )
                    AssistChip(
                        onClick = { showPlanner = true },
                        label = { Text("+${preferences.maxExtraMinutes} min") },
                        leadingIcon = { Icon(Icons.Default.MoreTime, null, Modifier.size(18.dp)) },
                    )
                    if (startSelection != null && location.point != null) {
                        AssistChip(
                            onClick = {
                                startSelection = null
                                invalidateRoute()
                            },
                            label = { Text("Use GPS start") },
                            leadingIcon = { Icon(Icons.Default.GpsFixed, null, Modifier.size(18.dp)) },
                        )
                    }
                }

                Button(
                    onClick = { showPlanner = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = destination != null && !routeLoading,
                ) {
                    Icon(Icons.Default.Route, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (routePlan == null) "Plan the beautiful route" else "Edit journey plan")
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (routeError != null) {
                ErrorCard(routeError!!, onDismiss = { routeError = null })
            }
            activeRoute?.let { route ->
                RouteResultCard(
                    route = route,
                    candidateIndex = selectedCandidateIndex,
                    candidateCount = routePlan?.candidates?.size ?: 1,
                    note = routePlan?.note,
                    onPrevious = {
                        val count = routePlan?.candidates?.size ?: 0
                        if (count > 0) selectedCandidateIndex = (selectedCandidateIndex - 1 + count) % count
                    },
                    onNext = {
                        val count = routePlan?.candidates?.size ?: 0
                        if (count > 0) selectedCandidateIndex = (selectedCandidateIndex + 1) % count
                    },
                    onAddHighlight = ::addSuggestedStop,
                    onShowSuggestions = { showStopIdeas = true },
                    onRebuild = { showPlanner = true },
                )
            }
            if (locationPermissionGranted) {
                SmallFloatingActionButton(onClick = { recenterToken++ }) {
                    Icon(Icons.Default.MyLocation, "Center on my location")
                }
            }
            FloatingActionButton(onClick = { showPlanner = true }) {
                Icon(Icons.Default.EditRoad, "Open route planner")
            }
        }

        if (mapError != null && routeError == null) {
            AssistChip(
                onClick = { mapError = null },
                label = { Text("Map fallback active") },
                leadingIcon = { Icon(Icons.Default.WarningAmber, null) },
                modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
            )
        }
    }

    if (showPlanner) {
        RoutePlannerSheet(
            start = startLabel,
            destination = destinationLabel,
            plan = plan,
            preferences = preferences,
            onPlanChange = { updated ->
                if (updated != plan) {
                    plan = updated
                    invalidateRoute()
                }
            },
            onPreferencesChange = { updated ->
                if (updated != preferences) {
                    preferences = updated
                    invalidateRoute()
                }
            },
            onRequestAddStop = {
                showPlanner = false
                showStopIdeas = true
            },
            onBuildRoute = ::buildRoute,
            onDismiss = { showPlanner = false },
        )
    }

    if (showStopIdeas) {
        StopIdeasSheet(
            routePoints = activeRoute?.points.orEmpty(),
            initialSuggestions = activeRoute?.scenePoints.orEmpty(),
            enabledKinds = plan.enabledSceneKinds,
            alreadyAddedIds = plan.stops.mapTo(mutableSetOf()) { it.id },
            onAddSuggestion = ::addSuggestedStop,
            onManualSearch = {
                showStopIdeas = false
                showStopKindPicker = true
            },
            onDismiss = {
                showStopIdeas = false
                if (routePlan == null) showPlanner = true
            },
        )
    }

    if (showStartPicker) {
        PlacePickerSheet(
            title = "Choose start",
            initialQuery = startSelection?.title.orEmpty(),
            bias = location.point,
            onDismiss = { showStartPicker = false },
            onPick = {
                startSelection = it
                showStartPicker = false
                invalidateRoute()
            },
        )
    }

    if (showDestinationPicker) {
        PlacePickerSheet(
            title = "Choose destination",
            initialQuery = destinationSelection?.title.orEmpty(),
            bias = origin,
            onDismiss = { showDestinationPicker = false },
            onPick = {
                destinationSelection = it
                showDestinationPicker = false
                invalidateRoute()
            },
        )
    }

    if (showStopKindPicker) {
        StopKindDialog(
            selected = pendingStopKind,
            onDismiss = {
                showStopKindPicker = false
                showPlanner = true
            },
            onSelect = { kind ->
                pendingStopKind = kind
                showStopKindPicker = false
                showStopPicker = true
            },
        )
    }

    if (showStopPicker) {
        PlacePickerSheet(
            title = "Manual ${pendingStopKind.label.lowercase()} stop",
            bias = origin,
            onDismiss = {
                showStopPicker = false
                showPlanner = true
            },
            onPick = { place ->
                plan = plan.copy(
                    stops = plan.stops + PlannedStop(
                        id = "place-${System.nanoTime()}",
                        name = place.title,
                        kind = pendingStopKind,
                        point = place.point,
                        subtitle = place.subtitle,
                    )
                )
                showStopPicker = false
                showPlanner = true
                invalidateRoute()
            },
        )
    }

    if (showScenicDNA) {
        ScenicSettingsSheet(
            preferences = preferences,
            onChange = {
                preferences = it
                plan = plan.copy(routeCharacter = RouteCharacter.CUSTOM)
                invalidateRoute()
            },
            onDismiss = { showScenicDNA = false },
        )
    }
}

@Composable
private fun PlaceField(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    supporting: String?,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
private fun RouteResultCard(
    route: RouteCandidateUi,
    candidateIndex: Int,
    candidateCount: Int,
    note: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAddHighlight: (ScenePointUi) -> Unit,
    onShowSuggestions: () -> Unit,
    onRebuild: () -> Unit,
) {
    Card(
        modifier = Modifier.widthIn(max = 390.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (route.isPreviewFallback) "Route preview" else routeCharacterLabel(route.character),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${formatDistance(route.distanceMeters)} · ${formatDuration(route.durationSeconds)}" +
                            if (route.extraMinutes > 0.5) " · +${route.extraMinutes.toInt()} min" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!route.isPreviewFallback) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("${route.scenicScore.toInt()} scenic") },
                    )
                }
            }

            if (route.strongestSignals.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    route.strongestSignals.take(3).forEach { signal ->
                        AssistChip(onClick = {}, label = { Text(signalLabel(signal)) })
                    }
                }
            }

            if (route.scenePoints.isNotEmpty()) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Suggested stops", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onShowSuggestions) { Text("Show all") }
                }
                route.scenePoints.take(3).forEach { highlight ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sceneEmoji(highlight.kind), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(highlight.name, fontWeight = FontWeight.Medium, maxLines = 1)
                            val details = buildList {
                                if (highlight.distanceFromRouteMeters > 0) add("${highlight.distanceFromRouteMeters} m from route")
                                highlight.rating?.let { rating ->
                                    val reviews = highlight.ratingCount?.let { " · $it reviews" }.orEmpty()
                                    add(String.format(Locale.getDefault(), "%.1f★%s", rating, reviews))
                                }
                                add("${highlight.suggestedDwellMinutes} min")
                            }.joinToString(" · ")
                            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        IconButton(onClick = { onAddHighlight(highlight) }) {
                            Icon(Icons.Default.AddCircleOutline, "Add ${highlight.name} to journey")
                        }
                    }
                }
                if (route.scenePoints.size > 3) {
                    Text(
                        "+${route.scenePoints.size - 3} more automatic suggestions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (!route.isPreviewFallback) {
                OutlinedButton(onClick = onShowSuggestions, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Find scenic stop suggestions")
                }
            }

            if (route.isPreviewFallback) {
                Text(
                    "Debug fallback: geometry works, but ScenicScore requires the Scenic Path backend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (!note.isNullOrBlank()) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (candidateCount > 1) {
                    IconButton(onClick = onPrevious) { Icon(Icons.Default.ChevronLeft, "Previous route") }
                    Text("${candidateIndex + 1}/$candidateCount")
                    IconButton(onClick = onNext) { Icon(Icons.Default.ChevronRight, "Next route") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRebuild) { Text("Edit") }
                Button(onClick = { /* turn-by-turn foreground service is next */ }) {
                    Icon(Icons.Default.Navigation, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Preview")
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}

@Composable
private fun StopKindDialog(
    selected: StopKind,
    onDismiss: () -> Unit,
    onSelect: (StopKind) -> Unit,
) {
    val manualKinds = prototypeSelectableSceneKinds + StopKind.CUSTOM
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual stop type") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                manualKinds.forEach { kind ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (kind == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(kind) },
                    ) {
                        Text("${kind.emoji}  ${kind.label}", Modifier.padding(12.dp), fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun routeCharacterLabel(character: String): String = when (character.uppercase()) {
    "BEAUTIFUL" -> "Beautiful route"
    "BALANCED" -> "Balanced route"
    "DIRECT" -> "Direct route"
    "CUSTOM" -> "Custom scenic route"
    else -> "Scenic route"
}

private fun signalLabel(signal: String): String = when (signal) {
    "beautifulRoads" -> "Scenic roads"
    "forest" -> "Forest"
    "water" -> "Water"
    "mountains" -> "Relief"
    "viewpoints" -> "Views"
    "culture" -> "Culture"
    "monuments" -> "History"
    "museums" -> "Museums"
    "art" -> "Art"
    "worship" -> "Historic worship"
    "architecture" -> "Architecture"
    "parks" -> "Parks"
    "food" -> "Top food"
    "scenicHighlights" -> "Highlights"
    "autoHighlights" -> "Auto stops"
    "motorwayAvoidance" -> "No motorway"
    else -> signal.replaceFirstChar { it.uppercase() }
}

private fun sceneEmoji(kind: String): String = StopKind.entries.firstOrNull { it.name == kind }?.emoji ?: "⭐"

private fun formatDistance(meters: Double): String = if (meters >= 1000) {
    String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
} else "${meters.toInt()} m"

private fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).toInt()
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "${hours}h ${rest}m" else "${minutes}m"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScenicSettingsSheet(
    preferences: ScenicPreferences,
    onChange: (ScenicPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scenic DNA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Fine-tune what beautiful means to you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }

            WeightSlider("Beautiful roads", preferences.weights.beautifulRoads) { v -> onChange(preferences.copy(weights = preferences.weights.copy(beautifulRoads = v))) }
            WeightSlider("Forests", preferences.weights.forest) { v -> onChange(preferences.copy(weights = preferences.weights.copy(forest = v))) }
            WeightSlider("Lakes & rivers", preferences.weights.water) { v -> onChange(preferences.copy(weights = preferences.weights.copy(water = v))) }
            WeightSlider("Mountains & relief", preferences.weights.mountains) { v -> onChange(preferences.copy(weights = preferences.weights.copy(mountains = v))) }
            WeightSlider("Viewpoints", preferences.weights.viewpoints) { v -> onChange(preferences.copy(weights = preferences.weights.copy(viewpoints = v))) }
            WeightSlider("Culture overall", preferences.weights.culture) { v -> onChange(preferences.copy(weights = preferences.weights.copy(culture = v))) }
            WeightSlider("Monuments & history", preferences.weights.monuments) { v -> onChange(preferences.copy(weights = preferences.weights.copy(monuments = v))) }
            WeightSlider("Museums", preferences.weights.museums) { v -> onChange(preferences.copy(weights = preferences.weights.copy(museums = v))) }
            WeightSlider("Art", preferences.weights.art) { v -> onChange(preferences.copy(weights = preferences.weights.copy(art = v))) }
            WeightSlider("Historic worship", preferences.weights.worship) { v -> onChange(preferences.copy(weights = preferences.weights.copy(worship = v))) }
            WeightSlider("Architecture", preferences.weights.architecture) { v -> onChange(preferences.copy(weights = preferences.weights.copy(architecture = v))) }
            WeightSlider("Parks & gardens", preferences.weights.parks) { v -> onChange(preferences.copy(weights = preferences.weights.copy(parks = v))) }
            WeightSlider("Top-rated food", preferences.weights.food) { v -> onChange(preferences.copy(weights = preferences.weights.copy(food = v))) }
            WeightSlider("Scenic highlights", preferences.weights.scenicHighlights) { v -> onChange(preferences.copy(weights = preferences.weights.copy(scenicHighlights = v))) }

            HorizontalDivider()
            Text("Road feel", fontWeight = FontWeight.SemiBold)
            Text("Winding ${preferences.windingness}%")
            Slider(
                value = preferences.windingness.toFloat(),
                onValueChange = { onChange(preferences.copy(windingness = it.toInt())) },
                valueRange = 0f..100f,
            )
            Text("Hilly ${preferences.hilliness}%")
            Slider(
                value = preferences.hilliness.toFloat(),
                onValueChange = { onChange(preferences.copy(hilliness = it.toInt())) },
                valueRange = 0f..100f,
            )

            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Use this Scenic DNA") }
        }
    }
}

@Composable
private fun WeightSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text("${(value * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
    }
}
