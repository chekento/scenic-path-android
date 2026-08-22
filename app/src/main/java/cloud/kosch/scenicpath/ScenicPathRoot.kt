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

/**
 * Current app shell. Keeps the map visually dominant and treats route details as a
 * collapsible bottom bar rather than a persistent floating panel.
 */
@Composable
fun ScenicPathRoot(
    locationPermissionGranted: Boolean,
    requestLocationPermission: () -> Unit,
) {
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
    val startLabel = startSelection?.title ?: when {
        location.point != null -> "Current location"
        locationPermissionGranted -> "Waiting for GPS"
        else -> "Choose start or enable GPS"
    }
    val destinationLabel = destinationSelection?.title.orEmpty()
    val activeRoute = routePlan?.candidates?.getOrNull(selectedCandidateIndex)

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
        routePlan = null
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
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
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
                IconButton(onClick = { showScenicDNA = true }) { Icon(Icons.Default.Tune, "Scenic DNA") }
            }

            RootPlaceField(
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
            RootPlaceField(
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
                    onClick = { showPlanner = true },
                    label = { Text(if (plan.stops.isEmpty()) "Smart stops" else "${plan.stops.size} fixed stops") },
                    leadingIcon = { Icon(Icons.Default.LocationOn, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { showPlanner = true },
                    label = { Text("+${preferences.maxExtraMinutes} min") },
                    leadingIcon = { Icon(Icons.Default.MoreTime, null, Modifier.size(18.dp)) },
                )
                if (preferences.avoidMotorways) {
                    AssistChip(
                        onClick = { showPlanner = true },
                        label = { Text("No motorway") },
                        leadingIcon = { Icon(Icons.Default.Block, null, Modifier.size(18.dp)) },
                    )
                }
            }

            Button(
                onClick = { showPlanner = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = destination != null && !routeLoading,
            ) {
                Icon(Icons.Default.Route, null)
                Spacer(Modifier.width(8.dp))
                Text(if (routePlan == null) "Plan the beautiful route" else "Edit journey plan")
            }
        }

        routeError?.let { message ->
            RootErrorCard(
                message = message,
                onDismiss = { routeError = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 90.dp),
            )
        }

        activeRoute?.let { route ->
            CompactRouteBar(
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
                onEdit = { showPlanner = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 78.dp, bottom = 16.dp)
                    .fillMaxWidth(),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (locationPermissionGranted) {
                SmallFloatingActionButton(onClick = { recenterToken++ }) {
                    Icon(Icons.Default.MyLocation, "Center on my location")
                }
            }
            SmallFloatingActionButton(onClick = { showPlanner = true }) {
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
            onPlanChange = { plan = it; routePlan = null },
            onPreferencesChange = { preferences = it; routePlan = null },
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
        RootStopKindDialog(
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
        RootScenicDnaSheet(
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
private fun CompactRouteBar(
    route: RouteCandidateUi,
    candidateIndex: Int,
    candidateCount: Int,
    note: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAddHighlight: (ScenePointUi) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(route.id) { mutableStateOf(false) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Route, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(rootRouteCharacterLabel(route.character), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "${rootFormatDistance(route.distanceMeters)} · ${rootFormatDuration(route.durationSeconds)}" +
                            if (route.extraMinutes > 0.5) " · +${route.extraMinutes.toInt()}m" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (route.scenicScore > 0.5) {
                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("${route.scenicScore.toInt()} scenic")
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (candidateCount > 1) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.ChevronLeft, "Previous route")
                    }
                    Text("${candidateIndex + 1}/$candidateCount", style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.ChevronRight, "Next route")
                    }
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(38.dp)) {
                    Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (expanded) "Minimize route details" else "Show route details")
                }
            }

            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (route.strongestSignals.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            route.strongestSignals.take(4).forEach { signal ->
                                AssistChip(onClick = {}, label = { Text(rootSignalLabel(signal)) })
                            }
                        }
                    }

                    if (route.scenePoints.isNotEmpty()) {
                        Text("Scenic discoveries", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        route.scenePoints.take(3).forEach { highlight ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(rootSceneEmoji(highlight.kind), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(highlight.name, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(
                                        buildString {
                                            if (highlight.distanceFromRouteMeters > 0) append("${highlight.distanceFromRouteMeters} m off route")
                                            if (isNotEmpty()) append(" · ")
                                            append("${highlight.suggestedDwellMinutes} min")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onAddHighlight(highlight) }, modifier = Modifier.size(38.dp)) {
                                    Icon(Icons.Default.AddCircleOutline, "Add ${highlight.name}")
                                }
                            }
                        }
                        if (route.scenePoints.size > 3) {
                            Text(
                                "+${route.scenePoints.size - 3} more along the route",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (!note.isNullOrBlank()) {
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(route.provider, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootPlaceField(
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
private fun RootErrorCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}

@Composable
private fun RootStopKindDialog(
    selected: StopKind,
    onDismiss: () -> Unit,
    onSelect: (StopKind) -> Unit,
) {
    val manualKinds = prototypeSelectableSceneKinds + StopKind.CUSTOM
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What kind of stop?") },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootScenicDnaSheet(
    preferences: ScenicPreferences,
    onChange: (ScenicPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scenic DNA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Fine-tune what beautiful means to you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }
            RootWeightSlider("Beautiful roads", preferences.weights.beautifulRoads) { v -> onChange(preferences.copy(weights = preferences.weights.copy(beautifulRoads = v))) }
            RootWeightSlider("Forests", preferences.weights.forest) { v -> onChange(preferences.copy(weights = preferences.weights.copy(forest = v))) }
            RootWeightSlider("Lakes & rivers", preferences.weights.water) { v -> onChange(preferences.copy(weights = preferences.weights.copy(water = v))) }
            RootWeightSlider("Mountains & relief", preferences.weights.mountains) { v -> onChange(preferences.copy(weights = preferences.weights.copy(mountains = v))) }
            RootWeightSlider("Viewpoints", preferences.weights.viewpoints) { v -> onChange(preferences.copy(weights = preferences.weights.copy(viewpoints = v))) }
            RootWeightSlider("Culture", preferences.weights.culture) { v -> onChange(preferences.copy(weights = preferences.weights.copy(culture = v))) }
            RootWeightSlider("Monuments & history", preferences.weights.monuments) { v -> onChange(preferences.copy(weights = preferences.weights.copy(monuments = v))) }
            RootWeightSlider("Museums", preferences.weights.museums) { v -> onChange(preferences.copy(weights = preferences.weights.copy(museums = v))) }
            RootWeightSlider("Art", preferences.weights.art) { v -> onChange(preferences.copy(weights = preferences.weights.copy(art = v))) }
            RootWeightSlider("Historic worship", preferences.weights.worship) { v -> onChange(preferences.copy(weights = preferences.weights.copy(worship = v))) }
            RootWeightSlider("Architecture", preferences.weights.architecture) { v -> onChange(preferences.copy(weights = preferences.weights.copy(architecture = v))) }
            RootWeightSlider("Parks & gardens", preferences.weights.parks) { v -> onChange(preferences.copy(weights = preferences.weights.copy(parks = v))) }
            RootWeightSlider("Top-rated food", preferences.weights.food) { v -> onChange(preferences.copy(weights = preferences.weights.copy(food = v))) }
            RootWeightSlider("Scenic highlights", preferences.weights.scenicHighlights) { v -> onChange(preferences.copy(weights = preferences.weights.copy(scenicHighlights = v))) }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Use this Scenic DNA") }
        }
    }
}

@Composable
private fun RootWeightSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text("${(value * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
    }
}

private fun rootRouteCharacterLabel(character: String): String = when (character.uppercase()) {
    "BEAUTIFUL" -> "Beautiful route"
    "BALANCED" -> "Balanced route"
    "DIRECT" -> "Direct route"
    "CUSTOM" -> "Custom scenic route"
    else -> "Scenic route"
}

private fun rootSignalLabel(signal: String): String = when (signal) {
    "motorwayAvoidance" -> "No motorway"
    "autoHighlights" -> "Smart stops included"
    "beautifulRoads" -> "Scenic roads"
    "forest" -> "Nature"
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
    else -> signal.replaceFirstChar { it.uppercase() }
}

private fun rootSceneEmoji(kind: String): String = StopKind.entries.firstOrNull { it.name == kind }?.emoji ?: "⭐"

private fun rootFormatDistance(meters: Double): String = if (meters >= 1000) {
    String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
} else "${meters.toInt()} m"

private fun rootFormatDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).toInt()
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "${hours}h ${rest}m" else "${minutes}m"
}
