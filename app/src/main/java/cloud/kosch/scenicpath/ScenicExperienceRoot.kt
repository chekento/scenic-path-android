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

/**
 * Single live shell for v0.4+. Map-first, experience-first, no duplicate UI path.
 *
 * A calculated route is a committed display state. Planner/filter edits are drafts until a
 * replacement route has been calculated successfully, so opening menus, moving sliders or
 * adding Smart Stops can never make the current route disappear.
 */
@Composable
fun ScenicExperienceRoot(
    locationPermissionGranted: Boolean,
    requestLocationPermission: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val location = rememberLocationUiState(locationPermissionGranted)

    var startSelection by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var destinationSelection by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showDestinationPicker by remember { mutableStateOf(false) }
    var showPlanner by remember { mutableStateOf(false) }
    var showStops by remember { mutableStateOf(false) }
    var showManualKind by remember { mutableStateOf(false) }
    var showManualPlace by remember { mutableStateOf(false) }
    var pendingStopKind by remember { mutableStateOf(StopKind.CUSTOM) }
    var preferences by remember { mutableStateOf(ScenicPreferences(maxStops = 6)) }
    var plan by remember { mutableStateOf(TripPlan()) }
    var recenterToken by remember { mutableIntStateOf(0) }
    var routePlan by remember { mutableStateOf<RoutePlanUi?>(null) }
    var selectedCandidateIndex by remember { mutableIntStateOf(0) }
    var routeLoading by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var topExpanded by remember { mutableStateOf(true) }
    var routeDirty by remember { mutableStateOf(false) }

    val origin = startSelection?.point ?: location.point
    val destination = destinationSelection?.point
    val startLabel = startSelection?.title ?: when {
        location.point != null -> "Current location"
        locationPermissionGranted -> "Waiting for GPS"
        else -> "Choose start or enable GPS"
    }
    val destinationLabel = destinationSelection?.title.orEmpty()
    val activeRoute = routePlan?.candidates?.getOrNull(selectedCandidateIndex)

    fun clearRouteForEndpointChange() {
        routePlan = null
        selectedCandidateIndex = 0
        routeDirty = false
        topExpanded = true
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
        showStops = false
        scope.launch {
            ScenicApi.planRoute(from, to, plan, preferences)
                .onSuccess { result ->
                    routeLoading = false
                    if (result.candidates.isNotEmpty()) {
                        routePlan = result
                        selectedCandidateIndex = 0
                        routeDirty = false
                        topExpanded = false
                    } else {
                        // Never replace a valid displayed route with an empty calculation.
                        topExpanded = routePlan == null
                        routeError = if (routePlan != null) {
                            "No replacement journey matched the selected time budget. Your previous route is still shown."
                        } else {
                            "No journey matched the selected time budget."
                        }
                    }
                }
                .onFailure { error ->
                    routeLoading = false
                    topExpanded = routePlan == null
                    routeError = if (routePlan != null) {
                        "${error.message ?: "Journey planning failed"}. Your previous route is still shown."
                    } else {
                        error.message ?: "Journey planning failed"
                    }
                }
        }
    }

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
        routeDirty = true
        showStops = false
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

        ExperienceTopPanel(
            expanded = topExpanded,
            onToggle = { topExpanded = !topExpanded },
            startLabel = startLabel,
            destinationLabel = destinationLabel,
            startSupporting = when {
                startSelection != null -> startSelection?.subtitle
                location.accuracyMeters != null -> "GPS ±${location.accuracyMeters!!.toInt()} m"
                !locationPermissionGranted -> "Live GPS is off"
                location.error != null -> location.error
                else -> "Using live GPS"
            },
            destinationSupporting = destinationSelection?.subtitle,
            plan = plan,
            preferences = preferences,
            routeLoading = routeLoading,
            hasDestination = destination != null,
            hasRoute = routePlan != null,
            routeDirty = routeDirty,
            onStart = { showStartPicker = true },
            onDestination = { showDestinationPicker = true },
            onPlanner = { showPlanner = true },
            onStops = { showStops = true },
            onEnableGps = requestLocationPermission,
            locationPermissionGranted = locationPermissionGranted,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        routeError?.let { message ->
            Card(
                modifier = Modifier.align(Alignment.Center).padding(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WarningAmber, null)
                    Spacer(Modifier.width(8.dp))
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { routeError = null }) { Icon(Icons.Default.Close, "Dismiss") }
                }
            }
        }

        activeRoute?.let { route ->
            ExperienceRouteBar(
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
                onStops = { showStops = true },
                onEdit = { showPlanner = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 78.dp, bottom = 16.dp)
                    .fillMaxWidth(),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (locationPermissionGranted) {
                SmallFloatingActionButton(onClick = { recenterToken++ }) {
                    Icon(Icons.Default.MyLocation, "Center on my location")
                }
            }
            SmallFloatingActionButton(onClick = { showPlanner = true }) {
                Icon(Icons.Default.EditRoad, "Edit experience")
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
        JourneyPlannerSheet(
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
                preferences = it
                if (routePlan != null) routeDirty = true
            },
            onRequestSuggestions = {
                showPlanner = false
                showStops = true
            },
            onBuildRoute = ::buildRoute,
            onDismiss = { showPlanner = false },
        )
    }

    if (showStops) {
        JourneyStopsSheet(
            route = activeRoute,
            manuallyAddedIds = plan.stops.mapTo(mutableSetOf()) { it.id },
            onAddAlternative = ::addAlternative,
            onManualSearch = {
                showStops = false
                showManualKind = true
            },
            onDismiss = { showStops = false },
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
                clearRouteForEndpointChange()
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
                clearRouteForEndpointChange()
            },
        )
    }

    if (showManualKind) {
        ExperienceStopKindDialog(
            selected = pendingStopKind,
            onDismiss = {
                showManualKind = false
                showStops = true
            },
            onSelect = { kind ->
                pendingStopKind = kind
                showManualKind = false
                showManualPlace = true
            },
        )
    }

    if (showManualPlace) {
        PlacePickerSheet(
            title = "Manually add ${pendingStopKind.label.lowercase()}",
            bias = origin,
            onDismiss = {
                showManualPlace = false
                showStops = true
            },
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
                showManualPlace = false
                showPlanner = true
            },
        )
    }
}

@Composable
private fun ExperienceTopPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    startLabel: String,
    destinationLabel: String,
    startSupporting: String?,
    destinationSupporting: String?,
    plan: TripPlan,
    preferences: ScenicPreferences,
    routeLoading: Boolean,
    hasDestination: Boolean,
    hasRoute: Boolean,
    routeDirty: Boolean,
    onStart: () -> Unit,
    onDestination: () -> Unit,
    onPlanner: () -> Unit,
    onStops: () -> Unit,
    onEnableGps: () -> Unit,
    locationPermissionGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
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
                        "${startLabel.take(18)}  →  ${destinationLabel.ifBlank { "Destination" }.take(18)}",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        "${plan.routeCharacter.label} · +${preferences.maxExtraMinutes} min · ${if (plan.autoSuggestStops) "Smart Stops" else "roads only"}${if (routeDirty) " · changes pending" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (routeLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            IconButton(onClick = onToggle) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Minimize start and destination" else "Expand start and destination")
            }
        }

        if (expanded) {
            ExperiencePlaceField("Start", startLabel, Icons.Default.MyLocation, startSupporting, onStart)
            ExperiencePlaceField("Destination", destinationLabel.ifBlank { "Where do you want to go?" }, Icons.Default.Flag, destinationSupporting, onDestination)

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!locationPermissionGranted) {
                    AssistChip(onClick = onEnableGps, label = { Text("Enable GPS") }, leadingIcon = { Icon(Icons.Default.GpsFixed, null, Modifier.size(18.dp)) })
                }
                AssistChip(onClick = onPlanner, label = { Text(plan.routeCharacter.label) }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) })
                AssistChip(onClick = onPlanner, label = { Text("+${preferences.maxExtraMinutes} min") }, leadingIcon = { Icon(Icons.Default.MoreTime, null, Modifier.size(18.dp)) })
                AssistChip(onClick = onStops, label = { Text("Smart Stops") }, leadingIcon = { Icon(Icons.Default.AddLocationAlt, null, Modifier.size(18.dp)) })
                if (routeDirty) {
                    AssistChip(onClick = onPlanner, label = { Text("Changes pending") }, leadingIcon = { Icon(Icons.Default.Update, null, Modifier.size(18.dp)) })
                }
                if (preferences.avoidMotorways) {
                    AssistChip(onClick = onPlanner, label = { Text("No motorway") }, leadingIcon = { Icon(Icons.Default.Block, null, Modifier.size(18.dp)) })
                }
            }

            Button(onClick = onPlanner, modifier = Modifier.fillMaxWidth().height(50.dp), enabled = hasDestination && !routeLoading) {
                Icon(Icons.Default.Route, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        routeDirty && hasRoute -> "Rebuild with changes"
                        hasRoute -> "Edit this experience"
                        else -> "Build scenic experiences"
                    }
                )
            }
        }
    }
}

@Composable
private fun ExperiencePlaceField(
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
                if (!supporting.isNullOrBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun ExperienceRouteBar(
    route: RouteCandidateUi,
    candidateIndex: Int,
    candidateCount: Int,
    note: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStops: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(route.id) { mutableStateOf(false) }
    val included = route.scenePoints.filter { it.includedInRoute || it.id in route.autoStopIds }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (route.character == RouteCharacter.DIRECT.name) Icons.Default.NearMe else Icons.Default.Explore, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(route.variantLabel ?: experienceCharacterLabel(route.character), fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        "${experienceDistance(route.distanceMeters)} · ${experienceDuration(route.durationSeconds)} · +${route.totalExtraMinutes.roundToInt()}m total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (route.experienceScore > 0.5) {
                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) { Text("${route.experienceScore.roundToInt()}") }
                    Spacer(Modifier.width(4.dp))
                }
                if (candidateCount > 1) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ChevronLeft, "Previous experience") }
                    Text("${candidateIndex + 1}/$candidateCount", style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ChevronRight, "Next experience") }
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(38.dp)) {
                    Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (expanded) "Minimize details" else "Show experience details")
                }
            }

            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (route.character != RouteCharacter.DIRECT.name) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ExperienceMetric("Drive detour", "+${route.driveExtraMinutes.roundToInt()}m", Modifier.weight(1f))
                            ExperienceMetric("Visits", "${route.dwellMinutes}m", Modifier.weight(1f))
                            ExperienceMetric("Search", "~${route.corridorRadiusKm.roundToInt()}km", Modifier.weight(1f))
                        }
                    }

                    if (included.isNotEmpty()) {
                        Text("Automatic itinerary", fontWeight = FontWeight.SemiBold)
                        included.take(5).forEachIndexed { index, stop ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary) {
                                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                        Text((index + 1).toString(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stop.name, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(
                                        buildList {
                                            stop.personalMatch?.let { add("${it.roundToInt()}% match") }
                                            add("${stop.suggestedDwellMinutes} min")
                                        }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    if (route.strongestSignals.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            route.strongestSignals.take(5).forEach { signal -> AssistChip(onClick = {}, label = { Text(experienceSignalLabel(signal)) }) }
                        }
                    }

                    if (!note.isNullOrBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (route.dataConfidence > 0) {
                        Text("Data confidence ${(route.dataConfidence * 100).roundToInt()}% · ${route.provider}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Row(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onStops, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.AddLocationAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stops")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
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
private fun ExperienceMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExperienceStopKindDialog(
    selected: StopKind,
    onDismiss: () -> Unit,
    onSelect: (StopKind) -> Unit,
) {
    val kinds = prototypeSelectableSceneKinds + StopKind.CUSTOM
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
                        Text("${kind.emoji}  ${kind.label}", Modifier.padding(11.dp), fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun experienceCharacterLabel(character: String): String = when (character.uppercase()) {
    "BEAUTIFUL" -> "Beautiful"
    "BALANCED" -> "Balanced"
    "DIRECT" -> "Direct"
    "CUSTOM" -> "Custom scenic"
    else -> "Scenic experience"
}

private fun experienceSignalLabel(signal: String): String = when (signal) {
    "journeyOptimizer" -> "Journey optimized"
    "motorwayAvoidance" -> "No motorway"
    "monuments" -> "History"
    "viewpoints" -> "Views"
    "water" -> "Water"
    "diverseHighlights" -> "High variety"
    "scenicRoadFreedom" -> "Scenic roads"
    "expandedSearchSpace" -> "Expanded search"
    else -> signal.replaceFirstChar { it.uppercase() }
}

private fun experienceDistance(meters: Double): String = if (meters >= 1000) {
    String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
} else "${meters.roundToInt()} m"

private fun experienceDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).roundToInt()
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "${hours}h ${rest}m drive" else "${minutes}m drive"
}
