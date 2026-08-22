package cloud.kosch.scenicpath

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyStopsSheet(
    route: RouteCandidateUi?,
    manuallyAddedIds: Set<String>,
    onAddAlternative: (ScenePointUi) -> Unit,
    onManualSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val included = route?.scenePoints.orEmpty().filter { it.includedInRoute || it.id in route?.autoStopIds.orEmpty() }
    val alternatives = route?.scenePoints.orEmpty().filterNot { point ->
        point.id in included.map { it.id }.toSet() || point.id in manuallyAddedIds
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Smart Stops", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("The route is built around the best combination — alternatives stay one tap away.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close Smart Stops") }
            }

            if (route == null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Build an experience first", fontWeight = FontWeight.SemiBold)
                        Text("Scenic Path will search the complete time-budget corridor and automatically construct several stop combinations.")
                    }
                }
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(route.variantLabel ?: "Scenic experience", fontWeight = FontWeight.Bold)
                        Text(
                            "${route.autoStopIds.size} automatic stops · ${route.dwellMinutes} min visiting · +${route.driveExtraMinutes.roundToInt()} min driving detour",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (route.corridorRadiusKm > 0) {
                            Text("Search space ~${route.corridorRadiusKm.roundToInt()} km around candidate corridors", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (included.isNotEmpty()) {
                    Text("Built into this route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    included.forEachIndexed { index, stop ->
                        IncludedStopRow(index + 1, stop)
                    }
                } else {
                    Text("This variant has no automatic stop yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (alternatives.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Good alternatives", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 330.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(alternatives.take(14), key = { it.id }) { stop ->
                            AlternativeStopRow(stop, onClick = { onAddAlternative(stop) })
                        }
                    }
                }
            }

            HorizontalDivider()
            OutlinedButton(onClick = onManualSearch, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("Search manually instead")
            }
        }
    }
}

@Composable
private fun IncludedStopRow(number: Int, stop: ScenePointUi) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${journeyStopEmoji(stop.kind)} ${stop.name}", fontWeight = FontWeight.SemiBold)
                Text(
                    buildList {
                        stop.personalMatch?.let { add("${it.roundToInt()}% match") }
                        add("${stop.suggestedDwellMinutes} min")
                        stop.subtype?.replace('_', ' ')?.let(::add)
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                stop.rationale?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(Icons.Default.CheckCircle, "Included", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AlternativeStopRow(stop: ScenePointUi, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(journeyStopEmoji(stop.kind), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stop.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    buildList {
                        stop.personalMatch?.let { add("${it.roundToInt()}% match") }
                        if (stop.distanceFromRouteMeters > 0) add("${stop.distanceFromRouteMeters / 1000.0} km from route")
                        add("${stop.suggestedDwellMinutes} min")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                stop.rationale?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
            Icon(Icons.Default.AddCircleOutline, "Add ${stop.name}")
        }
    }
}

private fun journeyStopEmoji(kind: String): String = when (kind) {
    StopKind.VIEWPOINT.name -> "👁️"
    StopKind.MUSEUM.name -> "🏛️"
    StopKind.NATURE.name -> "⛰️"
    StopKind.MONUMENT.name -> "🏰"
    StopKind.PARK.name -> "🌳"
    StopKind.ART.name -> "🎨"
    StopKind.WORSHIP.name -> "⛪"
    StopKind.WATER.name -> "💧"
    StopKind.FOOD.name -> "🍽️"
    StopKind.ARCHITECTURE.name -> "🏗️"
    else -> "⭐"
}
