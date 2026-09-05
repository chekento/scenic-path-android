package cloud.kosch.scenicpath

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.ln

/** Route-wide Smart Stops browser. Manual browsing never changes the route until Add is pressed. */
@Composable
fun JourneyStopsSheet(
    route: RouteCandidateUi?,
    manuallyAddedIds: Set<String>,
    onAddAlternative: (ScenePointUi) -> Unit,
    onManualSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val enabledKinds = prototypeSelectableSceneKinds
    var enriched by remember(route?.id, enabledKinds) { mutableStateOf<List<ScenePointUi>>(emptyList()) }
    var enrichmentLoading by remember(route?.id, enabledKinds) { mutableStateOf(route != null && route.points.size >= 2 && enabledKinds.isNotEmpty()) }
    var enrichmentFailed by remember(route?.id, enabledKinds) { mutableStateOf(false) }
    var refreshToken by remember(route?.id, enabledKinds) { mutableIntStateOf(0) }

    LaunchedEffect(route?.id, refreshToken, enabledKinds) {
        val current = route
        if (current == null || current.points.size < 2 || enabledKinds.isEmpty()) {
            enriched = emptyList()
            enrichmentFailed = false
            enrichmentLoading = false
            return@LaunchedEffect
        }

        enrichmentLoading = true
        enrichmentFailed = false
        val result = runCatching {
            val (fast, precision) = coroutineScope {
                val fastJob = async(Dispatchers.IO) {
                    FastRoutePoiDiscovery.discover(
                        route = current.points,
                        enabledKinds = enabledKinds,
                        maxResults = 150,
                    )
                }
                val precisionJob = async(Dispatchers.IO) {
                    PrecisionRoutePoiDiscovery.discover(
                        route = current.points,
                        enabledKinds = enabledKinds,
                        maxResults = 220,
                        radiusMeters = if (refreshToken > 0) 30_000 else 15_000,
                        maxSamples = if (refreshToken > 0) 14 else 10,
                    )
                }
                fastJob.await() to precisionJob.await()
            }
            mergeStopsStrict(fast + precision, enabledKinds, 340)
        }

        enriched = result.getOrElse { emptyList() }
        enrichmentFailed = result.isFailure
        enrichmentLoading = false
    }

    val routePoints = remember(route) {
        route?.scenePoints.orEmpty().map { point ->
            if (point.includedInRoute || point.id in route?.autoStopIds.orEmpty()) point.copy(includedInRoute = true) else point
        }
    }
    val merged = remember(routePoints, enriched, enabledKinds) {
        // Current route inclusions remain visible even if the user has just changed the draft
        // filter. Every optional discovery result must match the committed category set exactly.
        val included = routePoints.filter { it.includedInRoute }
        val optional = mergeStopsStrict(routePoints.filterNot { it.includedInRoute } + enriched, enabledKinds, 360)
        (included + optional.filterNot { option -> included.any { it.id == option.id } }).take(360)
    }

    LaunchedEffect(route?.id, merged) {
        val current = route
        if (current != null && current.points.size >= 2) ScenicPoiSharedState.publish(current.points, merged)
    }

    val included = merged.filter { it.includedInRoute }
    val includedIds = included.mapTo(mutableSetOf()) { it.id }
    val available = merged.filterNot { it.id in includedIds || it.id in manuallyAddedIds }
    val topFood = available.filter { it.kind == StopKind.FOOD.name }.maxByOrNull(::foodPickScore)
    val lanes = remember(available) {
        scenicCategoryLanes.mapNotNull { lane ->
            val points = available.filter { scenicCategoryLaneFor(it).id == lane.id }
                .sortedByDescending { browserScore(it) }
            if (points.isEmpty()) null else lane to points
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
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
                            Text("Real POIs along the complete route corridor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Minimize Smart Stops") }
                    }
                }

                item(key = "filters") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Committed filter", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (enabledKinds.isEmpty()) "No automatic POI categories are enabled."
                                else "${enabledKinds.size}/${allSelectableSceneKinds.size} categories · disabled categories cannot appear as optional stops.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (route == null) {
                    item(key = "empty-route") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Build a route first", fontWeight = FontWeight.SemiBold)
                                Text("Route-wide discovery needs a route corridor. You can still add a place manually.")
                                OutlinedButton(onClick = onManualSearch, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Search, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Add place manually")
                                }
                            }
                        }
                    }
                } else {
                    item(key = "summary") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(route.variantLabel ?: "Scenic experience", fontWeight = FontWeight.Bold)
                                Text("${included.size} included · ${available.size} optional places discovered")
                                if (enrichmentLoading) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Searching the route corridor…", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (enrichmentFailed) {
                                    Text("One discovery pass failed. Existing route POIs remain available; refresh can retry deeper.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    if (included.isNotEmpty()) {
                        item(key = "included-title") { Text("Already in this route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                        items(included, key = { "included-${it.id}" }) { point ->
                            StopCard(point = point, included = true, onAdd = {})
                        }
                    }

                    topFood?.let { food ->
                        item(key = "top-food") {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Restaurant, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Top Food candidate", fontWeight = FontWeight.Bold)
                                    }
                                    StopCard(point = food, included = false, onAdd = { onAddAlternative(food) }, embedded = true)
                                }
                            }
                        }
                    }

                    lanes.forEach { (lane, points) ->
                        item(key = "lane-${lane.id}") {
                            Text("${lane.emoji} ${lane.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        items(points.take(12), key = { "${lane.id}-${it.id}" }) { point ->
                            StopCard(point = point, included = false, onAdd = { onAddAlternative(point) })
                        }
                    }

                    if (!enrichmentLoading && available.isEmpty() && included.isEmpty()) {
                        item(key = "no-results") {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                Text(
                                    if (enabledKinds.isEmpty()) "No categories are enabled. Change the planner filter to discover POIs."
                                    else "No matching POIs were found on this pass. Try a deeper refresh or add a place manually.",
                                    Modifier.padding(14.dp),
                                )
                            }
                        }
                    }

                    item(key = "actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { refreshToken++ },
                                enabled = !enrichmentLoading && enabledKinds.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Deep refresh")
                            }
                            OutlinedButton(onClick = onManualSearch, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Search, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Add place manually")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StopCard(
    point: ScenePointUi,
    included: Boolean,
    onAdd: () -> Unit,
    embedded: Boolean = false,
) {
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(if (embedded) 4.dp else 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(scenicCategoryLaneFor(point).emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(point.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val meta = buildList {
                    add("${point.suggestedDwellMinutes} min")
                    if (point.distanceFromRouteMeters > 0) add("${point.distanceFromRouteMeters} m from route")
                    point.rating?.let { add(String.format(java.util.Locale.US, "★ %.1f", it)) }
                    point.ratingCount?.let { add("$it reviews") }
                    point.openNow?.let { add(if (it) "open" else "closed") }
                }
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (included) {
                Icon(Icons.Default.CheckCircle, "Included", tint = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalIconButton(onClick = onAdd) { Icon(Icons.Default.AddLocationAlt, "Add ${point.name}") }
            }
        }
    }
    if (embedded) body() else Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, content = body)
}

private fun mergeStopsStrict(points: List<ScenePointUi>, enabledKinds: Set<StopKind>, maxResults: Int): List<ScenePointUi> {
    if (enabledKinds.isEmpty() || maxResults <= 0) return emptyList()
    val enabledNames = enabledKinds.mapTo(mutableSetOf()) { it.name }
    return points
        .filter { it.kind in enabledNames }
        .groupBy { it.id }
        .map { (_, versions) -> versions.maxByOrNull(::browserScore) ?: versions.first() }
        .sortedByDescending(::browserScore)
        .take(maxResults)
}

private fun browserScore(point: ScenePointUi): Double =
    point.suggestionScore * 50.0 + point.relevance * 30.0 + (point.rating ?: 0.0) * 6.0 + ln(((point.ratingCount ?: 0) + 10).toDouble()) - point.distanceFromRouteMeters / 1500.0

private fun foodPickScore(point: ScenePointUi): Double =
    (point.rating ?: 0.0) * 20.0 + ln(((point.ratingCount ?: 0) + 10).toDouble()) * 4.0 + point.relevance * 8.0
