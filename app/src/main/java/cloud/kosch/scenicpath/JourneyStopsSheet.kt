package cloud.kosch.scenicpath

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
fun JourneyStopsSheet(
    route: RouteCandidateUi?,
    manuallyAddedIds: Set<String>,
    onAddAlternative: (ScenePointUi) -> Unit,
    onManualSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var enriched by remember(route?.id) { mutableStateOf<List<ScenePointUi>>(emptyList()) }
    var enrichmentLoading by remember(route?.id) { mutableStateOf(false) }

    LaunchedEffect(route?.id) {
        val current = route
        if (current == null || current.points.size < 2) {
            enriched = emptyList()
            return@LaunchedEffect
        }
        enrichmentLoading = true
        enriched = runCatching {
            FastRoutePoiDiscovery.discover(
                route = current.points,
                enabledKinds = prototypeSelectableSceneKinds,
                maxResults = 36,
            )
        }.getOrElse { emptyList() }
        enrichmentLoading = false
    }

    val routePoints = route?.scenePoints.orEmpty().map { point ->
        if (point.includedInRoute || point.id in route?.autoStopIds.orEmpty()) {
            point.copy(includedInRoute = true)
        } else point
    }
    val merged = remember(routePoints, enriched) {
        buildList {
            addAll(routePoints)
            enriched.forEach { candidate ->
                val duplicate = any { existing ->
                    existing.id == candidate.id || existing.name.equals(candidate.name, ignoreCase = true)
                }
                if (!duplicate) add(candidate)
            }
        }
    }
    val included = merged.filter { it.includedInRoute }
    val includedIds = included.mapTo(mutableSetOf()) { it.id }
    val alternatives = merged.filterNot { it.id in includedIds || it.id in manuallyAddedIds }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // One single LazyColumn owns all vertical scrolling. The old nested LazyColumn inside
        // a sheet Column could fight the sheet drag gesture at both scroll edges and caused
        // the visible "dancing"/shaking after scrolling down and back up.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 690.dp),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Smart Stops", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Included stops and worthwhile alternatives along this journey.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close Smart Stops") }
                }
            }

            if (route == null) {
                item(key = "empty-route") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Build an experience first", fontWeight = FontWeight.SemiBold)
                            Text("Scenic Path will then discover and rank locations around the complete journey.")
                        }
                    }
                }
            } else {
                item(key = "summary") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(route.variantLabel ?: "Scenic experience", fontWeight = FontWeight.Bold)
                            Text(
                                "${included.size} automatic stops · ${route.dwellMinutes} min visiting · +${route.driveExtraMinutes.roundToInt()} min driving detour",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val kinds = merged.map { it.kind }.distinct().size
                            Text(
                                "${merged.size} visible locations across $kinds scene categories",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (included.isNotEmpty()) {
                    item(key = "included-title") {
                        Text("Built into this route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    itemsIndexed(included, key = { _, stop -> "included-${stop.id}" }) { index, stop ->
                        IncludedStopRow(index + 1, stop)
                    }
                } else {
                    item(key = "no-included") {
                        Text("This variant has no automatic stop yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (enrichmentLoading) {
                    item(key = "loading-more") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Finding more culture, history, views and places…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (alternatives.isNotEmpty()) {
                    item(key = "alternatives-divider") { HorizontalDivider() }
                    item(key = "alternatives-title") {
                        Text("Good alternatives", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    itemsIndexed(alternatives.take(28), key = { _, stop -> "alt-${stop.id}" }) { _, stop ->
                        AlternativeStopRow(stop, onClick = { onAddAlternative(stop) })
                    }
                }
            }

            item(key = "manual-divider") { HorizontalDivider() }
            item(key = "manual-search") {
                OutlinedButton(onClick = onManualSearch, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Search manually instead")
                }
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
                        if (stop.distanceFromRouteMeters > 0) add(String.format("%.1f km from route", stop.distanceFromRouteMeters / 1000.0))
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
