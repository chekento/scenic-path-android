package cloud.kosch.scenicpath

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Stable Smart Stops surface.
 *
 * A fixed dialog avoids the draggable-sheet/list gesture conflict. Recommendations are
 * rendered category-first so a common category can never visually hide a rarer one.
 */
@Composable
fun JourneyStopsSheet(
    route: RouteCandidateUi?,
    manuallyAddedIds: Set<String>,
    onAddAlternative: (ScenePointUi) -> Unit,
    onManualSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    var enriched by remember(route?.id) { mutableStateOf<List<ScenePointUi>>(emptyList()) }
    var enrichmentLoading by remember(route?.id) { mutableStateOf(false) }
    var enrichmentFailed by remember(route?.id) { mutableStateOf(false) }

    LaunchedEffect(route?.id) {
        val current = route
        if (current == null || current.points.size < 2) {
            enriched = emptyList()
            enrichmentFailed = false
            return@LaunchedEffect
        }
        enrichmentLoading = true
        enrichmentFailed = false
        val result = runCatching {
            FastRoutePoiDiscovery.discover(
                route = current.points,
                enabledKinds = prototypeSelectableSceneKinds,
                maxResults = 50,
            )
        }
        enriched = result.getOrElse { emptyList() }
        enrichmentFailed = result.isFailure
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
                    existing.id == candidate.id ||
                        existing.name.equals(candidate.name, ignoreCase = true)
                }
                if (!duplicate) add(candidate)
            }
        }
    }

    val included = merged.filter { it.includedInRoute }
    val includedIds = included.mapTo(mutableSetOf()) { it.id }
    val topFood = merged
        .filter {
            it.kind == StopKind.FOOD.name &&
                it.id !in includedIds &&
                it.id !in manuallyAddedIds
        }
        .maxByOrNull(::foodPickScore)

    val availableByKind = remember(merged, includedIds, manuallyAddedIds, topFood?.id) {
        merged
            .filterNot {
                it.id in includedIds ||
                    it.id in manuallyAddedIds ||
                    it.id == topFood?.id
            }
            .groupBy { it.kind }
            .mapValues { (_, values) -> values.sortedByDescending { it.suggestionScore } }
    }

    val coveredKinds = remember(merged) {
        prototypeSelectableSceneKinds.filter { kind -> merged.any { it.kind == kind.name } }
    }
    val missingKinds = remember(coveredKinds) {
        prototypeSelectableSceneKinds.filterNot { it in coveredKinds }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                item(key = "header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Smart Stops", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "Every enabled Scenic Category gets its own result lane.",
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
                                Text(
                                    "${merged.size} locations · ${coveredKinds.size}/${prototypeSelectableSceneKinds.size} Scenic Categories covered",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (!enrichmentLoading && missingKinds.isNotEmpty()) {
                                    Text(
                                        "Still missing in this corridor: ${missingKinds.joinToString { "${it.emoji} ${it.label}" }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                    }

                    if (enrichmentLoading) {
                        item(key = "loading-more") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Searching the full corridor for missing categories…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (enrichmentFailed) {
                        item(key = "enrichment-failed") {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Text(
                                    "Some public development POI services did not answer. Existing route results are still shown.",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }

                    topFood?.let { food ->
                        item(key = "top-food-divider") { HorizontalDivider() }
                        item(key = "top-food-title") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Restaurant, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(7.dp))
                                Text("Top Food pick", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        item(key = "top-food-${food.id}") {
                            TopFoodRow(food, onClick = { onAddAlternative(food) })
                        }
                    }

                    item(key = "categories-divider") { HorizontalDivider() }
                    item(key = "categories-title") {
                        Text("Recommendations by Scenic Category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    prototypeSelectableSceneKinds.forEach { kind ->
                        item(key = "kind-title-${kind.name}") {
                            CategoryHeader(kind, count = merged.count { it.kind == kind.name })
                        }

                        if (kind == StopKind.FOOD && topFood != null) {
                            item(key = "kind-food-note") {
                                Text(
                                    "The strongest food candidate is highlighted above.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            val candidates = availableByKind[kind.name].orEmpty().take(3)
                            if (candidates.isEmpty()) {
                                item(key = "kind-empty-${kind.name}") {
                                    Text(
                                        if (enrichmentLoading) "Searching…" else "No mapped candidate found in the current search corridor.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                items(candidates, key = { "kind-${kind.name}-${it.id}" }) { stop ->
                                    AlternativeStopRow(stop, onClick = { onAddAlternative(stop) })
                                }
                            }
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
}

@Composable
private fun CategoryHeader(kind: StopKind, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(kind.emoji, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(8.dp))
        Text(kind.label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = if (count > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                count.toString(),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun foodPickScore(stop: ScenePointUi): Double {
    val rating = stop.rating
    val reviews = stop.ratingCount ?: 0
    val verified = if (rating != null) {
        rating * 20.0 + ln((reviews + 1).toDouble()) * 4.0
    } else 0.0
    val restaurantBonus = if (stop.subtype.equals("restaurant", ignoreCase = true)) 8.0 else 0.0
    val detourPenalty = stop.distanceFromRouteMeters / 700.0
    return verified + stop.suggestionScore + restaurantBonus - detourPenalty
}

@Composable
private fun TopFoodRow(stop: ScenePointUi, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f))) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🍽️", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(stop.name, fontWeight = FontWeight.Bold)
                    Text(
                        buildList {
                            stop.rating?.let { rating ->
                                add(String.format("%.1f★", rating))
                                stop.ratingCount?.let { add("$it reviews") }
                            }
                            if (stop.distanceFromRouteMeters > 0) add(String.format("%.1f km from route", stop.distanceFromRouteMeters / 1000.0))
                            add("${stop.suggestedDwellMinutes} min")
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Text(
                if (stop.rating != null) "Verified rating-based food candidate."
                else "Best available route-food candidate from OSM metadata. Verified ratings require the configured food provider.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AddLocationAlt, null)
                Spacer(Modifier.width(7.dp))
                Text("Add Top Food to route")
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
                stop.rationale?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
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
