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
    val budget = preferences.maxExtraMinutes
    val corridorKm = (4.0 + budget * 0.15).coerceIn(6.0, 42.0)
    val autoStops = autoStopPreview(budget, preferences.maxStops)

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
                    Text("Give Scenic Path time. It turns that time into roads and places worth seeing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close planner") }
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
                        "More time expands both the road alternatives and the area in which Scenic Path may search for exceptional detours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Text("Trip style", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalChoiceRowV4 {
                PlanningMode.entries.forEach { mode ->
                    FilterChip(
                        selected = plan.mode == mode,
                        onClick = {
                            val maxStops = when (mode) {
                                PlanningMode.QUICK -> 5
                                PlanningMode.DAY_TRIP -> 8
                                PlanningMode.ROAD_TRIP -> 12
                            }
                            onPlanChange(plan.copy(mode = mode))
                            onPreferencesChange(preferences.copy(maxStops = maxStops))
                        },
                        label = { Text(mode.label) },
                    )
                }
            }

            Text("Route character", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalChoiceRowV4 {
                RouteCharacter.entries.forEach { character ->
                    FilterChip(
                        selected = plan.routeCharacter == character,
                        onClick = {
                            onPlanChange(plan.copy(routeCharacter = character))
                            onPreferencesChange(preferences.forCharacter(character))
                        },
                        leadingIcon = if (character == RouteCharacter.BEAUTIFUL) {
                            { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) }
                        } else null,
                        label = { Text(character.label) },
                    )
                }
            }

            Text("Extra exploration time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalChoiceRowV4 {
                listOf(30, 60, 120, 180, 240).forEach { minutes ->
                    FilterChip(
                        selected = budget == minutes,
                        onClick = { onPreferencesChange(preferences.copy(maxExtraMinutes = minutes)) },
                        label = { Text(if (minutes < 60) "+${minutes}m" else "+${minutes / 60}h${if (minutes % 60 == 0) "" else " ${minutes % 60}m"}") },
                    )
                }
            }
            Slider(
                value = budget.toFloat(),
                onValueChange = { onPreferencesChange(preferences.copy(maxExtraMinutes = it.roundToInt())) },
                valueRange = 0f..360f,
                steps = 23,
            )
            Text(
                if (budget >= 240) "Adventure space: large detours and radically different route corridors are allowed."
                else if (budget >= 120) "Explorer space: Scenic Path can leave the obvious corridor for major highlights."
                else "Local scenic space: preference is given to worthwhile places with efficient detours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Smart Stops", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = plan.autoSuggestStops,
                            onCheckedChange = { onPlanChange(plan.copy(autoSuggestStops = it)) },
                        )
                    }
                    Text(
                        if (plan.autoSuggestStops) "The Journey Optimizer may automatically include the best combination of places inside your total time budget."
                        else "Only roads and your manually fixed stops will be used.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onRequestSuggestions, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AddLocationAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (hasRoute) "View stops & alternatives" else "How Smart Stops will work")
                    }
                }
            }

            if (plan.stops.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Fixed by you", fontWeight = FontWeight.SemiBold)
                        plan.stops.forEach { stop ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stop.kind.emoji)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stop.name, fontWeight = FontWeight.Medium)
                                    Text("${stop.dwellMinutes} min · fixed anchor", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { onPlanChange(plan.copy(stops = plan.stops.filterNot { it.id == stop.id })) }) {
                                    Icon(Icons.Default.DeleteOutline, "Remove ${stop.name}")
                                }
                            }
                        }
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
                        SettingSwitchV4("Avoid motorways", "Hard-validation remains active after routing.", preferences.avoidMotorways) {
                            onPreferencesChange(preferences.copy(avoidMotorways = it))
                        }
                        SettingSwitchV4("Avoid tolls", "Prefer routes without toll roads.", preferences.avoidTolls) {
                            onPreferencesChange(preferences.copy(avoidTolls = it))
                        }

                        HorizontalDivider()
                        Text("Scene categories", fontWeight = FontWeight.SemiBold)
                        prototypeSelectableSceneKinds.groupBy { it.group }.forEach { (group, kinds) ->
                            Text(group.label, style = MaterialTheme.typography.labelMedium)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                kinds.forEach { kind ->
                                    val selected = kind in plan.enabledSceneKinds
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            val next = plan.enabledSceneKinds.toMutableSet()
                                            if (selected) next.remove(kind) else next.add(kind)
                                            onPlanChange(plan.copy(enabledSceneKinds = next))
                                        },
                                        label = { Text("${kind.emoji} ${kind.label}") },
                                    )
                                }
                            }
                        }

                        HorizontalDivider()
                        Text("Scenic DNA", fontWeight = FontWeight.SemiBold)
                        DnaSliderV4("Beautiful roads", preferences.weights.beautifulRoads) { v ->
                            onPreferencesChange(preferences.copy(weights = preferences.weights.copy(beautifulRoads = v)))
                        }
                        DnaSliderV4("Viewpoints", preferences.weights.viewpoints) { v ->
                            onPreferencesChange(preferences.copy(weights = preferences.weights.copy(viewpoints = v)))
                        }
                        DnaSliderV4("Water", preferences.weights.water) { v ->
                            onPreferencesChange(preferences.copy(weights = preferences.weights.copy(water = v)))
                        }
                        DnaSliderV4("Forests", preferences.weights.forest) { v ->
                            onPreferencesChange(preferences.copy(weights = preferences.weights.copy(forest = v)))
                        }
                        DnaSliderV4("Mountains & relief", preferences.weights.mountains) { v ->
                            onPreferencesChange(preferences.copy(weights = preferences.weights.copy(mountains = v)))
                        }
                        DnaSliderV4("Culture", preferences.weights.culture) { v ->
                            onPreferencesChange(preferences.copy(weights = preferences.weights.copy(culture = v)))
                        }
                        DnaSliderV4("Monuments & history", preferences.weights.monuments) { v ->
                            onPreferencesChange(preferences.copy(weights = preferences.weights.copy(monuments = v)))
                        }
                        DnaSliderV4("Museums", preferences.weights.museums) { v ->
                            onPreferencesChange(preferences.copy(weights = preferences.weights.copy(museums = v)))
                        }
                        DnaSliderV4("Parks & gardens", preferences.weights.parks) { v ->
                            onPreferencesChange(preferences.copy(weights = preferences.weights.copy(parks = v)))
                        }
                        DnaSliderV4("Architecture", preferences.weights.architecture) { v ->
                            onPreferencesChange(preferences.copy(weights = preferences.weights.copy(architecture = v)))
                        }
                    }
                }
            }

            Button(
                onClick = onBuildRoute,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = destination.isNotBlank(),
            ) {
                Icon(Icons.Default.Route, null)
                Spacer(Modifier.width(8.dp))
                Text(if (hasRoute) "Recalculate experience with +$budget min" else "Build the best experiences")
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

@Composable
private fun HorizontalChoiceRowV4(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SettingSwitchV4(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DnaSliderV4(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
    }
}
