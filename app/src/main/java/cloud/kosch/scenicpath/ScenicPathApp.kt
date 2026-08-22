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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ScenicPathApp(locationPermissionGranted: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val location = rememberLocationUiState(locationPermissionGranted)

    var startSelection by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var destinationSelection by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showDestinationPicker by remember { mutableStateOf(false) }
    var showStopPicker by remember { mutableStateOf(false) }
    var showStopKindPicker by remember { mutableStateOf(false) }
    var pendingStopKind by remember { mutableStateOf(StopKind.CUSTOM) }
    var showScenicDNA by remember { mutableStateOf(false) }
    var showPlanner by remember { mutableStateOf(false) }
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
    val startLabel = startSelection?.title ?: if (location.point != null) "Current location" else "Waiting for GPS"
    val destinationLabel = destinationSelection?.title.orEmpty()
    val activeRoute = routePlan?.candidates?.getOrNull(selectedCandidateIndex)

    fun buildRoute() {
        val from = origin
        val to = destination
        if (from == null) {
            routeError = "Current location is not ready. Choose a start place or wait for GPS."
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
                    if (result.candidates.isEmpty()) routeError = "No route matched the current detour budget."
                }
                .onFailure { error ->
                    routeLoading = false
                    routeError = error.message ?: "Route planning failed"
                }
        }
    }

    Box(Modifier.fillMaxSize()) {
        ScenicMap(
            modifier = Modifier.fillMaxSize(),
            userLocation = location.point,
            routePoints = activeRoute?.points.orEmpty(),
            stops = plan.stops,
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
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Landscape, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Scenic Path", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("The beautiful way", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (routeLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                IconButton(onClick = { showScenicDNA = true }) {
                    Icon(Icons.Default.Tune, "Scenic DNA")
                }
            }

            PlaceField(
                label = "Start",
                value = startLabel,
                icon = Icons.Default.MyLocation,
                supporting = when {
                    startSelection != null -> startSelection?.subtitle
                    location.accuracyMeters != null -> "GPS ±${location.accuracyMeters!!.toInt()} m"
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
                AssistChip(
                    onClick = { showPlanner = true },
                    label = { Text(plan.routeCharacter.label) },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { showPlanner = true },
                    label = { Text(if (plan.stops.isEmpty()) "No fixed stops" else "${plan.stops.size} stops") },
                    leadingIcon = { Icon(Icons.Default.LocationOn, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { showPlanner = true },
                    label = { Text("+${preferences.maxExtraMinutes} min") },
                    leadingIcon = { Icon(Icons.Default.MoreTime, null, Modifier.size(18.dp)) },
                )
                if (startSelection != null && location.point != null) {
                    AssistChip(
                        onClick = { startSelection = null },
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
            onPlanChange = { plan = it },
            onPreferencesChange = { preferences = it },
            onRequestAddStop = {
                showPlanner = false
                showStopKindPicker = true
            },
            onBuildRoute = ::buildRoute,
            onDismiss = { showPlanner = false },
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
                routePlan = null
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
                routePlan = null
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
            title = "Add ${pendingStopKind.label.lowercase()} stop",
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
                routePlan = null
            },
        )
    }

    if (showScenicDNA) {
        ScenicSettingsSheet(
            preferences = preferences,
            onChange = {
                preferences = it
                plan = plan.copy(routeCharacter = RouteCharacter.CUSTOM)
                routePlan = null
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
    onRebuild: () -> Unit,
) {
    Card(
        modifier = Modifier.widthIn(max = 360.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (route.isPreviewFallback) "Route preview" else "Scenic route",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${formatDistance(route.distanceMeters)} · ${formatDuration(route.durationSeconds)}",
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What kind of stop?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StopKind.entries.forEach { kind ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (kind == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(kind) },
                    ) {
                        Text(kind.label, Modifier.padding(12.dp), fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

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
            WeightSlider("Mountains", preferences.weights.mountains) { v -> onChange(preferences.copy(weights = preferences.weights.copy(mountains = v))) }
            WeightSlider("Viewpoints", preferences.weights.viewpoints) { v -> onChange(preferences.copy(weights = preferences.weights.copy(viewpoints = v))) }
            WeightSlider("Culture & sights", preferences.weights.culture) { v -> onChange(preferences.copy(weights = preferences.weights.copy(culture = v))) }
            WeightSlider("Museums & art", preferences.weights.museums) { v -> onChange(preferences.copy(weights = preferences.weights.copy(museums = v))) }
            WeightSlider("Architecture", preferences.weights.architecture) { v -> onChange(preferences.copy(weights = preferences.weights.copy(architecture = v))) }
            WeightSlider("Parks & gardens", preferences.weights.parks) { v -> onChange(preferences.copy(weights = preferences.weights.copy(parks = v))) }
            WeightSlider("Top-rated food", preferences.weights.food) { v -> onChange(preferences.copy(weights = preferences.weights.copy(food = v))) }

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
