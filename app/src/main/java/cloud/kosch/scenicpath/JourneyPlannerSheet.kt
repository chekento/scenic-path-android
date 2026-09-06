package cloud.kosch.scenicpath

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyPlannerSheet(
    start: String,
    destination: String,
    plan: TripPlan,
    preferences: ScenicPreferences,
    hasRoute: Boolean,
    onPlanChange: (TripPlan) -> Unit,
    onPreferencesChange: (ScenicPreferences) -> Unit,
    onRequestSuggestions: () -> Unit,
    onBuildRoute: () -> Unit,
    onDismiss: () -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }
    var draftPlan by remember(plan) { mutableStateOf(plan) }
    var draftPreferences by remember(preferences) { mutableStateOf(preferences) }
    var rebuildRequested by remember { mutableStateOf(false) }

    val isDayTrip = draftPlan.mode == PlanningMode.DAY_TRIP
    val budget = draftPreferences.maxExtraMinutes
    val corridorKm = NativeAutoStopPolicy.distanceLimitMeters(budget) / 1000.0
    val autoStops = autoStopPreview(budget, draftPreferences.maxStops)
    val dirty = draftPlan != plan || draftPreferences != preferences
    val sceneCount = draftPlan.enabledSceneKinds.size
    val vehicleKind = draftPreferences.vehicle.kind
    val supportsRoadAvoidance = NativeRouteConstraintPolicy.supportsRoadAvoidance(vehicleKind)
    val supportsWindingAndHills = NativeRouteConstraintPolicy.supportsWindingAndHills(vehicleKind)

    LaunchedEffect(rebuildRequested, plan, preferences) {
        if (rebuildRequested && plan == draftPlan && preferences == draftPreferences) {
            rebuildRequested = false
            ScenicSceneSelectionState.activate(draftPlan.enabledSceneKinds)
            onBuildRoute()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Build an experience", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Simple presets first; every active routing constraint stays adjustable below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Minimize planner") }
            }

            if (hasRoute && dirty) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EditRoad, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Current route stays visible. Changes become active only after a successful rebuild.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f))) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isDayTrip) Icons.Default.Schedule else Icons.Default.Explore, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isDayTrip) "Current day-trip budget" else "Current exploration space", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        if (isDayTrip) {
                            "$budget min time budget · ~${corridorKm.roundToInt()} km POI reach · up to $autoStops automatic Smart Stops · ${draftPlan.requestedAlternatives} route variants"
                        } else {
                            "+$budget min · max ${draftPreferences.maxExtraPercent}% drive detour · ~${corridorKm.roundToInt()} km POI reach · up to $autoStops automatic Smart Stops"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "$sceneCount/${allSelectableSceneKinds.size} scene families enabled · ${start.take(24)} → ${destination.take(24)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (isDayTrip) {
                        Text(
                            "If start and destination are the same, this is the total outing time and Scenic Path actively tries to use it. With different endpoints it is the exploration allowance beyond the direct trip.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Text("Journey scope", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalChoiceRowV5 {
                PlanningMode.entries.forEach { mode ->
                    FilterChip(
                        selected = draftPlan.mode == mode,
                        onClick = {
                            val maxStops = when (mode) {
                                PlanningMode.QUICK -> 5
                                PlanningMode.DAY_TRIP -> 8
                                PlanningMode.ROAD_TRIP -> 12
                            }
                            draftPlan = draftPlan.copy(
                                mode = mode,
                                requestedAlternatives = if (mode == PlanningMode.QUICK && draftPlan.routeCharacter == RouteCharacter.DIRECT) 1
                                else maxOf(2, draftPlan.requestedAlternatives),
                            )
                            draftPreferences = draftPreferences.copy(
                                maxStops = maxStops,
                                maxExtraMinutes = if (mode == PlanningMode.DAY_TRIP) maxOf(30, draftPreferences.maxExtraMinutes) else draftPreferences.maxExtraMinutes,
                            )
                        },
                        label = { Text(mode.label) },
                    )
                }
            }

            Text("Route priority", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalChoiceRowV5 {
                RouteCharacter.entries.forEach { character ->
                    FilterChip(
                        selected = draftPlan.routeCharacter == character,
                        onClick = {
                            draftPlan = draftPlan.copy(
                                routeCharacter = character,
                                requestedAlternatives = if (character == RouteCharacter.DIRECT && draftPlan.mode == PlanningMode.QUICK) 1
                                else maxOf(2, draftPlan.requestedAlternatives),
                            )
                            draftPreferences = draftPreferences.forCharacter(character)
                        },
                        leadingIcon = if (character == RouteCharacter.BEAUTIFUL) {
                            { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) }
                        } else null,
                        label = { Text(character.label) },
                    )
                }
            }

            Text(
                if (isDayTrip) "Day-trip time budget" else "Exploration time",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalChoiceRowV5 {
                listOf(30, 60, 120, 180, 240, 360).forEach { minutes ->
                    FilterChip(
                        selected = budget == minutes,
                        onClick = { draftPreferences = draftPreferences.copy(maxExtraMinutes = minutes) },
                        label = { Text(formatBudgetChip(minutes, isDayTrip)) },
                    )
                }
            }
            Slider(
                value = budget.toFloat().coerceIn(if (isDayTrip) 30f else 0f, 360f),
                onValueChange = {
                    val minimum = if (isDayTrip) 30 else 0
                    draftPreferences = draftPreferences.copy(maxExtraMinutes = it.roundToInt().coerceAtLeast(minimum))
                },
                valueRange = if (isDayTrip) 30f..360f else 0f..360f,
                steps = if (isDayTrip) 21 else 23,
            )
            Text(
                if (isDayTrip) {
                    when {
                        budget >= 240 -> "Full outing: the planner may build a wide loop or destination experience and should use most of this time for beautiful roads plus worthwhile visits."
                        budget >= 120 -> "Half-day exploration: wider corridors and multiple meaningful stops are encouraged instead of returning early with unused time."
                        else -> "Compact outing: route and stops are optimized to make useful use of the available time without exceeding it."
                    }
                } else {
                    when {
                        budget >= 240 -> "Adventure space: large detours and radically different road corridors are allowed."
                        budget >= 120 -> "Explorer space: major highlights may justify leaving the obvious corridor."
                        budget >= 30 -> "Local scenic space: worthwhile places with efficient detours are preferred."
                        else -> "Road scenery only: automatic Smart Stops require at least 30 extra minutes."
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Smart Stops", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = draftPlan.autoSuggestStops,
                            onCheckedChange = { draftPlan = draftPlan.copy(autoSuggestStops = it) },
                        )
                    }
                    Text(
                        if (draftPlan.autoSuggestStops) {
                            if (isDayTrip) {
                                "Automatic stops are real route waypoints. Driving time plus visit time must fit the day budget; unused time is actively available for better stops and a richer route."
                            } else {
                                "Automatic stops are inserted as real route waypoints only when real driving time plus dwell time fits both budgets."
                            }
                        } else {
                            "Only roads and manually fixed stops are used."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Maximum automatic stops", fontWeight = FontWeight.Medium)
                            Text("Practical cap grows with time: 1 / 2 / 3 / 4 / 5 / 6 as the exploration budget becomes larger, never exceeding your chosen maximum.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(draftPreferences.maxStops.toString(), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = draftPreferences.maxStops.toFloat(),
                        onValueChange = { draftPreferences = draftPreferences.copy(maxStops = it.roundToInt()) },
                        valueRange = 0f..12f,
                        steps = 11,
                    )
                    OutlinedButton(onClick = onRequestSuggestions, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AddLocationAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (hasRoute) "Open Smart Stops browser" else "Preview Smart Stops")
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FilterAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scenic categories", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("$sceneCount/${allSelectableSceneKinds.size}", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        "This is a hard filter shared by route optimization, Smart Stops and map markers. An empty selection really means no automatic POIs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalChoiceRowV5 {
                        AssistChip(
                            onClick = { draftPlan = draftPlan.copy(enabledSceneKinds = allSelectableSceneKinds) },
                            label = { Text("All") },
                            leadingIcon = { Icon(Icons.Default.SelectAll, null, Modifier.size(18.dp)) },
                        )
                        AssistChip(
                            onClick = { draftPlan = draftPlan.copy(enabledSceneKinds = emptySet()) },
                            label = { Text("None") },
                        )
                        AssistChip(
                            onClick = {
                                draftPlan = draftPlan.copy(enabledSceneKinds = linkedSetOf(StopKind.VIEWPOINT, StopKind.NATURE, StopKind.PARK, StopKind.WATER))
                            },
                            label = { Text("Nature + views") },
                        )
                        AssistChip(
                            onClick = {
                                draftPlan = draftPlan.copy(
                                    enabledSceneKinds = linkedSetOf(
                                        StopKind.MUSEUM, StopKind.MONUMENT, StopKind.ART,
                                        StopKind.WORSHIP, StopKind.ARCHITECTURE, StopKind.FOOD,
                                    )
                                )
                            },
                            label = { Text("Culture + food") },
                        )
                    }

                    allSelectableSceneKinds.groupBy { it.group }.forEach { (group, kinds) ->
                        Text(group.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            kinds.forEach { kind ->
                                val selected = kind in draftPlan.enabledSceneKinds
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val next = draftPlan.enabledSceneKinds.toMutableSet()
                                        if (selected) next.remove(kind) else next.add(kind)
                                        draftPlan = draftPlan.copy(enabledSceneKinds = next)
                                    },
                                    label = { Text("${kind.emoji} ${kind.label}") },
                                )
                            }
                        }
                    }
                }
            }

            if (StopKind.FOOD in draftPlan.enabledSceneKinds) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.38f))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Restaurant, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Top Food quality", fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "These are strict filters for automatic restaurant selection. Unknown rating/review data cannot satisfy a positive minimum.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Minimum rating", Modifier.weight(1f))
                            Text(String.format(java.util.Locale.US, "%.1f / 5", draftPreferences.minimumFoodRating), fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = draftPreferences.minimumFoodRating.toFloat(),
                            onValueChange = { draftPreferences = draftPreferences.copy(minimumFoodRating = (it * 10).roundToInt() / 10.0) },
                            valueRange = 0f..5f,
                            steps = 49,
                        )
                        Text("Minimum review count", fontWeight = FontWeight.Medium)
                        HorizontalChoiceRowV5 {
                            listOf(0, 25, 100, 250, 500, 1000).forEach { count ->
                                FilterChip(
                                    selected = draftPreferences.minimumFoodReviewCount == count,
                                    onClick = { draftPreferences = draftPreferences.copy(minimumFoodReviewCount = count) },
                                    label = { Text(if (count == 0) "Any" else "$count+") },
                                )
                            }
                        }
                        SettingSwitchV5(
                            "Only open restaurants",
                            "Requires a provider-confirmed open-now state; unknown status is excluded.",
                            draftPreferences.onlyOpenFood,
                        ) { draftPreferences = draftPreferences.copy(onlyOpenFood = it) }
                    }
                }
            }

            if (draftPlan.stops.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Fixed by you", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${draftPlan.stops.size}", style = MaterialTheme.typography.labelMedium)
                        }
                        draftPlan.stops.forEachIndexed { index, stop ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stop.kind.emoji)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stop.name, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text("${stop.dwellMinutes} min · fixed anchor", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(
                                    onClick = { draftPlan = draftPlan.copy(stops = draftPlan.stops.moveItem(index, index - 1)) },
                                    enabled = index > 0,
                                ) { Icon(Icons.Default.KeyboardArrowUp, "Move ${stop.name} earlier") }
                                IconButton(
                                    onClick = { draftPlan = draftPlan.copy(stops = draftPlan.stops.moveItem(index, index + 1)) },
                                    enabled = index < draftPlan.stops.lastIndex,
                                ) { Icon(Icons.Default.KeyboardArrowDown, "Move ${stop.name} later") }
                                IconButton(onClick = { draftPlan = draftPlan.copy(stops = draftPlan.stops.filterNot { it.id == stop.id }) }) {
                                    Icon(Icons.Default.DeleteOutline, "Remove ${stop.name}")
                                }
                            }
                        }
                        SettingSwitchV5(
                            "Flexible stop order",
                            if (isDayTrip) "Allow the optimizer to place flexible stops in the most efficient order around the outing."
                            else "Allow the optimizer to reorder non-critical stops when that creates a better forward journey.",
                            draftPlan.flexibleStopOrder,
                        ) { draftPlan = draftPlan.copy(flexibleStopOrder = it) }
                    }
                }
            }

            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.Tune, null)
                Spacer(Modifier.width(8.dp))
                Text(if (advanced) "Hide Scenic DNA & constraints" else "Scenic DNA & constraints")
            }

            if (advanced) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Hard route constraints", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Constraints change the routing engine itself or reject routes outside your limits; they are not merely score hints.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (supportsRoadAvoidance) {
                            SettingSwitchV5(
                                "Avoid motorways",
                                "Sets motorway preference to zero in native routing and requests motorway avoidance in production routing.",
                                draftPreferences.avoidMotorways,
                            ) { draftPreferences = draftPreferences.copy(avoidMotorways = it) }
                            SettingSwitchV5(
                                "Avoid tolls",
                                "Sets toll preference to zero / requests toll-road avoidance where the vehicle mode supports it.",
                                draftPreferences.avoidTolls,
                            ) { draftPreferences = draftPreferences.copy(avoidTolls = it) }
                        } else {
                            Text(
                                "Bicycle routing uses bicycle type and surface permission instead of car-style motorway/toll controls.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IntPreferenceSliderV5(
                            if (isDayTrip) "Maximum road-time variation" else "Maximum drive detour",
                            draftPreferences.maxExtraPercent,
                            if (isDayTrip) {
                                "For trips with a different destination, caps driving-time growth versus the efficient baseline. Same-point round trips use the total day budget instead."
                            } else {
                                "Hard cap on driving-time growth versus the efficient baseline. POI visit time is counted separately in the minute budget."
                            },
                        ) {
                            draftPreferences = draftPreferences.copy(maxExtraPercent = it)
                        }
                        if (supportsWindingAndHills) {
                            IntPreferenceSliderV5(
                                "Winding roads",
                                draftPreferences.windingness,
                                "Car / motorcycle: higher values reduce highway preference and increase winding or secondary-road preference.",
                            ) { draftPreferences = draftPreferences.copy(windingness = it) }
                            IntPreferenceSliderV5(
                                "Hills & relief",
                                draftPreferences.hilliness,
                                "Car / motorcycle: higher values directly increase hill preference in the scenic route cost model.",
                            ) { draftPreferences = draftPreferences.copy(hilliness = it) }
                        } else {
                            Text(
                                "Winding-road and hill sliders are hidden for ${vehicleKind.label} because the active production/native provider pair cannot honor them consistently for this vehicle.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        HorizontalDivider()
                        Text("Scenic DNA", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Categories decide what may appear. DNA weights directly change POI utility and route scoring, so increasing a theme makes matching routes and stops win more often.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DnaSliderV5("Beautiful roads", draftPreferences.weights.beautifulRoads) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(beautifulRoads = v)) }
                        DnaSliderV5("Viewpoints", draftPreferences.weights.viewpoints) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(viewpoints = v)) }
                        DnaSliderV5("Water", draftPreferences.weights.water) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(water = v)) }
                        DnaSliderV5("Forests", draftPreferences.weights.forest) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(forest = v)) }
                        DnaSliderV5("Mountains & relief", draftPreferences.weights.mountains) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(mountains = v)) }
                        DnaSliderV5("Culture", draftPreferences.weights.culture) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(culture = v)) }
                        DnaSliderV5("Monuments & history", draftPreferences.weights.monuments) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(monuments = v)) }
                        DnaSliderV5("Museums", draftPreferences.weights.museums) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(museums = v)) }
                        DnaSliderV5("Art & galleries", draftPreferences.weights.art) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(art = v)) }
                        DnaSliderV5("Historic worship", draftPreferences.weights.worship) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(worship = v)) }
                        DnaSliderV5("Parks & gardens", draftPreferences.weights.parks) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(parks = v)) }
                        DnaSliderV5("Architecture", draftPreferences.weights.architecture) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(architecture = v)) }
                        DnaSliderV5("Food & cafés", draftPreferences.weights.food) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(food = v)) }
                        DnaSliderV5("Scenic attractions", draftPreferences.weights.scenicHighlights) { v -> draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(scenicHighlights = v)) }
                    }
                }
            }

            if (draftPlan.autoSuggestStops && draftPlan.enabledSceneKinds.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WarningAmber, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Enable at least one Scenic category or switch Smart Stops off.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Button(
                onClick = {
                    ScenicSceneSelectionState.activate(draftPlan.enabledSceneKinds)
                    onPlanChange(draftPlan)
                    onPreferencesChange(draftPreferences)
                    rebuildRequested = true
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = destination.isNotBlank() && (!draftPlan.autoSuggestStops || draftPlan.enabledSceneKinds.isNotEmpty()) && !rebuildRequested,
            ) {
                if (rebuildRequested) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Route, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        rebuildRequested -> "Applying controls…"
                        hasRoute && dirty && isDayTrip -> "Rebuild day trip · $budget min budget"
                        hasRoute && isDayTrip -> "Recalculate day trip · $budget min budget"
                        hasRoute && dirty -> "Rebuild with changes · +$budget min"
                        hasRoute -> "Recalculate experience · +$budget min"
                        isDayTrip -> "Build the best $budget min day trip"
                        else -> "Build the best experiences"
                    }
                )
            }
        }
    }
}

private fun formatBudgetChip(minutes: Int, isDayTrip: Boolean): String {
    val value = if (minutes < 60) "${minutes}m" else "${minutes / 60}h${if (minutes % 60 == 0) "" else " ${minutes % 60}m"}"
    return if (isDayTrip) value else "+$value"
}

private fun autoStopPreview(budgetMinutes: Int, configuredMax: Int): Int =
    NativeAutoStopPolicy.limit(budgetMinutes, configuredMax)

private fun <T> List<T>.moveItem(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    val copy = toMutableList()
    val item = copy.removeAt(from)
    copy.add(to, item)
    return copy
}

@Composable
private fun HorizontalChoiceRowV5(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SettingSwitchV5(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun IntPreferenceSliderV5(label: String, value: Int, subtitle: String? = null, onChange: (Int) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text("$value%", fontWeight = FontWeight.SemiBold)
        }
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) }, valueRange = 0f..100f)
    }
}

@Composable
private fun DnaSliderV5(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
    }
}
