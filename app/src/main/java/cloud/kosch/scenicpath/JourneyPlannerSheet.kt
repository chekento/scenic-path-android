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

    val budget = draftPreferences.maxExtraMinutes
    val corridorKm = (4.0 + budget * 0.15).coerceIn(6.0, 42.0)
    val autoStops = autoStopPreview(budget, draftPreferences.maxStops)
    val dirty = draftPlan != plan || draftPreferences != preferences
    val sceneCount = draftPlan.enabledSceneKinds.size

    // Parent state updates are synchronous but the parent's buildRoute callback belongs to the
    // previous composition until Compose applies the new plan/preferences. Waiting for both
    // values to match the draft prevents the classic "press Build twice" bug where the first
    // rebuild silently used stale filters.
    LaunchedEffect(rebuildRequested, plan, preferences) {
        if (rebuildRequested && plan == draftPlan && preferences == draftPreferences) {
            rebuildRequested = false
            ScenicSceneSelectionState.activate(draftPlan.enabledSceneKinds)
            onBuildRoute()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Build an experience", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "The prototype controls the idea. This planner controls the real route, stops and map.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close planner") }
            }

            if (hasRoute && dirty) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EditRoad, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Current route stays visible. Your changes become active only after a successful rebuild.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f))) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Explore, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Current exploration space", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "+$budget min · ~${corridorKm.roundToInt()} km search corridor · up to $autoStops Smart Stops",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "$sceneCount/${allSelectableSceneKinds.size} scene families enabled · ${start.take(24)} → ${destination.take(24)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Text("Trip style", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                            draftPlan = draftPlan.copy(mode = mode)
                            draftPreferences = draftPreferences.copy(maxStops = maxStops)
                        },
                        label = { Text(mode.label) },
                    )
                }
            }

            Text("Route character", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalChoiceRowV5 {
                RouteCharacter.entries.forEach { character ->
                    FilterChip(
                        selected = draftPlan.routeCharacter == character,
                        onClick = {
                            draftPlan = draftPlan.copy(routeCharacter = character)
                            draftPreferences = draftPreferences.forCharacter(character)
                        },
                        leadingIcon = if (character == RouteCharacter.BEAUTIFUL) {
                            { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) }
                        } else null,
                        label = { Text(character.label) },
                    )
                }
            }

            Text("Exploration time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalChoiceRowV5 {
                listOf(30, 60, 120, 180, 240).forEach { minutes ->
                    FilterChip(
                        selected = budget == minutes,
                        onClick = { draftPreferences = draftPreferences.copy(maxExtraMinutes = minutes) },
                        label = { Text(if (minutes < 60) "+${minutes}m" else "+${minutes / 60}h${if (minutes % 60 == 0) "" else " ${minutes % 60}m"}") },
                    )
                }
            }
            Slider(
                value = budget.toFloat(),
                onValueChange = { draftPreferences = draftPreferences.copy(maxExtraMinutes = it.roundToInt()) },
                valueRange = 0f..360f,
                steps = 23,
            )
            Text(
                when {
                    budget >= 240 -> "Adventure space: large detours and radically different road corridors are allowed."
                    budget >= 120 -> "Explorer space: major highlights may justify leaving the obvious corridor."
                    else -> "Local scenic space: worthwhile places with efficient detours are preferred."
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
                            "The optimizer may include the best mix of enabled categories inside the total time budget."
                        } else {
                            "Only roads and manually fixed stops are used."
                        },
                        style = MaterialTheme.typography.bodySmall,
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
                        Text("Scenic mix", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("$sceneCount/${allSelectableSceneKinds.size}", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        "These are real discovery filters: the route optimizer, Smart Stops and map use the same committed selection.",
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
                            onClick = {
                                draftPlan = draftPlan.copy(
                                    enabledSceneKinds = linkedSetOf(
                                        StopKind.VIEWPOINT, StopKind.NATURE, StopKind.PARK, StopKind.WATER,
                                    )
                                )
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
                            "Allow the optimizer to reorder non-critical stops when that creates a better journey.",
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
                        SettingSwitchV5("Avoid motorways", "Hard validation remains active after routing.", draftPreferences.avoidMotorways) {
                            draftPreferences = draftPreferences.copy(avoidMotorways = it)
                        }
                        SettingSwitchV5("Avoid tolls", "Prefer routes without toll roads.", draftPreferences.avoidTolls) {
                            draftPreferences = draftPreferences.copy(avoidTolls = it)
                        }

                        IntPreferenceSliderV5("Winding roads", draftPreferences.windingness) {
                            draftPreferences = draftPreferences.copy(windingness = it)
                        }
                        IntPreferenceSliderV5("Hills & relief", draftPreferences.hilliness) {
                            draftPreferences = draftPreferences.copy(hilliness = it)
                        }

                        HorizontalDivider()
                        Text("Scenic DNA", fontWeight = FontWeight.SemiBold)
                        Text(
                            "DNA controls ranking inside the enabled categories. A filter decides what may appear; DNA decides what should win.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DnaSliderV5("Beautiful roads", draftPreferences.weights.beautifulRoads) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(beautifulRoads = v))
                        }
                        DnaSliderV5("Viewpoints", draftPreferences.weights.viewpoints) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(viewpoints = v))
                        }
                        DnaSliderV5("Water", draftPreferences.weights.water) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(water = v))
                        }
                        DnaSliderV5("Forests", draftPreferences.weights.forest) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(forest = v))
                        }
                        DnaSliderV5("Mountains & relief", draftPreferences.weights.mountains) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(mountains = v))
                        }
                        DnaSliderV5("Culture", draftPreferences.weights.culture) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(culture = v))
                        }
                        DnaSliderV5("Monuments & history", draftPreferences.weights.monuments) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(monuments = v))
                        }
                        DnaSliderV5("Museums", draftPreferences.weights.museums) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(museums = v))
                        }
                        DnaSliderV5("Art & galleries", draftPreferences.weights.art) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(art = v))
                        }
                        DnaSliderV5("Historic worship", draftPreferences.weights.worship) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(worship = v))
                        }
                        DnaSliderV5("Parks & gardens", draftPreferences.weights.parks) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(parks = v))
                        }
                        DnaSliderV5("Architecture", draftPreferences.weights.architecture) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(architecture = v))
                        }
                        DnaSliderV5("Food & cafés", draftPreferences.weights.food) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(food = v))
                        }
                        DnaSliderV5("Scenic attractions", draftPreferences.weights.scenicHighlights) { v ->
                            draftPreferences = draftPreferences.copy(weights = draftPreferences.weights.copy(scenicHighlights = v))
                        }
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
                if (rebuildRequested) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Route, null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        rebuildRequested -> "Applying controls…"
                        hasRoute && dirty -> "Rebuild with changes · +$budget min"
                        hasRoute -> "Recalculate experience · +$budget min"
                        else -> "Build the best experiences"
                    }
                )
            }
        }
    }
}

private fun autoStopPreview(budgetMinutes: Int, configuredMax: Int): Int {
    val budgetLimit = when {
        budgetMinutes >= 240 -> 6
        budgetMinutes >= 180 -> 5
        budgetMinutes >= 120 -> 4
        budgetMinutes >= 75 -> 3
        budgetMinutes >= 40 -> 2
        budgetMinutes >= 20 -> 1
        else -> 0
    }
    return minOf(configuredMax.coerceAtLeast(1), budgetLimit)
}

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
private fun IntPreferenceSliderV5(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text("$value%", fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..100f,
        )
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
