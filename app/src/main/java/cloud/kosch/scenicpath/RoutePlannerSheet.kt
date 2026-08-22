package cloud.kosch.scenicpath

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerSheet(
    start: String,
    destination: String,
    plan: TripPlan,
    preferences: ScenicPreferences,
    onPlanChange: (TripPlan) -> Unit,
    onPreferencesChange: (ScenicPreferences) -> Unit,
    onRequestAddStop: () -> Unit,
    onBuildRoute: () -> Unit,
    onDismiss: () -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }

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
                    Text("Plan the beautiful way", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Simple first. Precise when you want it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close planner") }
            }

            SectionTitle("Trip type")
            HorizontalChoiceRow {
                PlanningMode.entries.forEach { mode ->
                    FilterChip(
                        selected = plan.mode == mode,
                        onClick = {
                            val maxStops = when (mode) {
                                PlanningMode.QUICK -> 3
                                PlanningMode.DAY_TRIP -> 7
                                PlanningMode.ROAD_TRIP -> 15
                            }
                            onPlanChange(plan.copy(mode = mode))
                            onPreferencesChange(preferences.copy(maxStops = maxStops))
                        },
                        label = { Text(mode.label) },
                    )
                }
            }

            SectionTitle("Route character")
            HorizontalChoiceRow {
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

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Journey timeline", fontWeight = FontWeight.SemiBold)
                    EndpointRow(Icons.Default.MyLocation, "Start", start.ifBlank { "Current location" })
                    plan.stops.forEachIndexed { index, stop ->
                        DraggableStopRow(
                            stop = stop,
                            index = index,
                            count = plan.stops.size,
                            onMove = { from, to ->
                                if (from == to || to !in plan.stops.indices) return@DraggableStopRow
                                val reordered = plan.stops.toMutableList()
                                val item = reordered.removeAt(from)
                                reordered.add(to, item)
                                onPlanChange(plan.copy(stops = reordered))
                            },
                            onDelete = { onPlanChange(plan.copy(stops = plan.stops.filterNot { it.id == stop.id })) },
                            onChange = { changed ->
                                onPlanChange(plan.copy(stops = plan.stops.map { if (it.id == changed.id) changed else it }))
                            },
                        )
                    }
                    EndpointRow(Icons.Default.Flag, "Destination", destination.ifBlank { "Choose destination" })
                    OutlinedButton(onClick = onRequestAddStop, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AddLocationAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Search & add stop")
                    }
                    Text(
                        "Long-press the grip to reorder. Locked stops stay fixed when later route optimization is enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionTitle("Timing")
            HorizontalChoiceRow {
                val departureOptions = listOf("Leave now", "Morning", "Afternoon", "Evening")
                departureOptions.forEach { option ->
                    FilterChip(
                        selected = plan.departureLabel == option,
                        onClick = { onPlanChange(plan.copy(departureLabel = option)) },
                        label = { Text(option) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {
                        val next = when (plan.arrivalDeadlineLabel) {
                            null -> "Within 4 h"
                            "Within 4 h" -> "Within 8 h"
                            else -> null
                        }
                        onPlanChange(plan.copy(arrivalDeadlineLabel = next))
                    },
                    label = { Text(plan.arrivalDeadlineLabel ?: "No arrival deadline") },
                    leadingIcon = { Icon(Icons.Default.Schedule, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = {
                        onPreferencesChange(
                            preferences.copy(
                                maxExtraMinutes = when {
                                    preferences.maxExtraMinutes < 30 -> 30
                                    preferences.maxExtraMinutes < 60 -> 60
                                    preferences.maxExtraMinutes < 120 -> 120
                                    else -> 15
                                }
                            )
                        )
                    },
                    label = { Text("+${preferences.maxExtraMinutes} min max") },
                    leadingIcon = { Icon(Icons.Default.MoreTime, null, Modifier.size(18.dp)) },
                )
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Smart planning", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = plan.autoSuggestStops,
                            onCheckedChange = { onPlanChange(plan.copy(autoSuggestStops = it)) },
                        )
                    }
                    Text(
                        if (plan.autoSuggestStops) "Scenic Path may add exceptional nature, culture and top-food stops inside your time budget."
                        else "Only your selected stops will be used.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${plan.enabledSceneKinds.size}/${prototypeSelectableSceneKinds.size} scene-point categories active",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            TextButton(onClick = { advanced = !advanced }, Modifier.fillMaxWidth()) {
                Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.Tune, null)
                Spacer(Modifier.width(8.dp))
                Text(if (advanced) "Hide advanced controls" else "Advanced controls")
            }

            if (advanced) {
                AdvancedPlanningControls(
                    plan = plan,
                    preferences = preferences,
                    onPlanChange = onPlanChange,
                    onPreferencesChange = onPreferencesChange,
                )
            }

            Button(
                onClick = onBuildRoute,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                enabled = destination.isNotBlank(),
            ) {
                Icon(Icons.Default.Route, null)
                Spacer(Modifier.width(8.dp))
                Text("Build route")
            }
        }
    }
}

@Composable
private fun AdvancedPlanningControls(
    plan: TripPlan,
    preferences: ScenicPreferences,
    onPlanChange: (TripPlan) -> Unit,
    onPreferencesChange: (ScenicPreferences) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Fine control", fontWeight = FontWeight.Bold)

            Text("Detour budget: +${preferences.maxExtraMinutes} min / ${preferences.maxExtraPercent}%")
            Slider(
                value = preferences.maxExtraMinutes.toFloat(),
                onValueChange = { onPreferencesChange(preferences.copy(maxExtraMinutes = it.roundToInt())) },
                valueRange = 0f..180f,
                steps = 17,
            )

            SettingSwitch(
                "Optimize unlocked stop order",
                "Scenic Path may reorder stops to improve the experience.",
                plan.flexibleStopOrder,
            ) { onPlanChange(plan.copy(flexibleStopOrder = it)) }

            SettingSwitch(
                "Preserve scenic intent on reroute",
                "A missed turn should not silently collapse back to the fastest route.",
                plan.preserveScenicIntentOnReroute,
            ) { onPlanChange(plan.copy(preserveScenicIntentOnReroute = it)) }

            SettingSwitch(
                "Avoid motorways",
                "Prefer smaller roads where sensible.",
                preferences.avoidMotorways,
            ) { onPreferencesChange(preferences.copy(avoidMotorways = it)) }

            SettingSwitch(
                "Avoid tolls",
                "Exclude toll roads where possible.",
                preferences.avoidTolls,
            ) { onPreferencesChange(preferences.copy(avoidTolls = it)) }

            HorizontalDivider()
            Text("Scene points", fontWeight = FontWeight.SemiBold)
            Text(
                "The original Scenic Path categories. Detailed subtypes such as castles, ruins, waterfalls, beaches, lighthouses and bridges are mapped automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            prototypeSelectableSceneKinds.groupBy { it.group }.forEach { (group, kinds) ->
                Text(group.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            Text("Food quality", fontWeight = FontWeight.SemiBold)
            Text("Minimum ${"%.1f".format(preferences.minimumFoodRating)}★ · ${preferences.minimumFoodReviewCount}+ reviews")
            Slider(
                value = preferences.minimumFoodRating.toFloat(),
                onValueChange = { onPreferencesChange(preferences.copy(minimumFoodRating = it.toDouble())) },
                valueRange = 4.2f..5.0f,
                steps = 7,
            )
            SettingSwitch("Only currently open food", "Useful when planning for right now.", preferences.onlyOpenFood) {
                onPreferencesChange(preferences.copy(onlyOpenFood = it))
            }
        }
    }
}

@Composable
private fun DraggableStopRow(
    stop: PlannedStop,
    index: Int,
    count: Int,
    onMove: (Int, Int) -> Unit,
    onDelete: () -> Unit,
    onChange: (PlannedStop) -> Unit,
) {
    var dragOffset by remember(index) { mutableFloatStateOf(0f) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = if (dragOffset != 0f) 6.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, dragOffset.roundToInt()) },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.DragIndicator,
                "Drag to reorder",
                modifier = Modifier
                    .size(30.dp)
                    .pointerInput(index, count) {
                        val threshold = 42.dp.toPx()
                        detectDragGesturesAfterLongPress(
                            onDragEnd = { dragOffset = 0f },
                            onDragCancel = { dragOffset = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                if (dragOffset > threshold && index < count - 1) {
                                    onMove(index, index + 1)
                                    dragOffset = 0f
                                } else if (dragOffset < -threshold && index > 0) {
                                    onMove(index, index - 1)
                                    dragOffset = 0f
                                }
                            },
                        )
                    },
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(stop.name, fontWeight = FontWeight.SemiBold)
                val resolved = if (stop.point != null) "located" else "location needed"
                Text(
                    "${stop.kind.emoji} ${stop.kind.label} · ${stop.dwellMinutes} min · $resolved",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stop.point != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = { onChange(stop.copy(locked = !stop.locked)) }) {
                Icon(if (stop.locked) Icons.Default.Lock else Icons.Default.LockOpen, if (stop.locked) "Unlock stop" else "Lock stop")
            }
            IconButton(onClick = { onChange(stop.copy(dwellMinutes = if (stop.dwellMinutes >= 120) 15 else stop.dwellMinutes + 15)) }) {
                Icon(Icons.Default.Timer, "Change visit time")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Remove stop") }
        }
    }
}

@Composable
private fun EndpointRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun HorizontalChoiceRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
