package cloud.kosch.scenicpath

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopIdeasSheet(
    routePoints: List<GeoPoint>,
    initialSuggestions: List<ScenePointUi>,
    enabledKinds: Set<StopKind>,
    alreadyAddedIds: Set<String>,
    onAddSuggestion: (ScenePointUi) -> Unit,
    onManualSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    var discovered by remember(routePoints) { mutableStateOf(initialSuggestions) }
    var loading by remember(routePoints) { mutableStateOf(routePoints.size >= 2 && initialSuggestions.size < 8) }
    var discoveryFailed by remember(routePoints) { mutableStateOf(false) }

    LaunchedEffect(routePoints, initialSuggestions, enabledKinds) {
        if (routePoints.size < 2) {
            discovered = initialSuggestions
            loading = false
            return@LaunchedEffect
        }
        if (initialSuggestions.size >= 8) {
            discovered = initialSuggestions
            loading = false
            return@LaunchedEffect
        }

        loading = true
        discoveryFailed = false
        val fresh = runCatching {
            OsmSceneDiscovery.discover(
                route = routePoints,
                enabledKinds = enabledKinds,
                maxResults = 24,
            )
        }.onFailure { discoveryFailed = true }.getOrElse { emptyList() }

        discovered = (initialSuggestions + fresh)
            .distinctBy { it.id }
            .sortedByDescending { it.suggestionScore }
        loading = false
    }

    val suggestions = discovered.filterNot { it.id in alreadyAddedIds }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Scenic stop suggestions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Recommended automatically for this route — no searching required.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close suggestions") }
            }

            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Finding worthwhile places along the route…")
                }
            }

            when {
                routePoints.size < 2 -> {
                    Text(
                        "Build a route first and Scenic Path will suggest places along it automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                suggestions.isEmpty() && !loading -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("No automatic suggestions available yet", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (discoveryFailed) "The public OSM discovery services did not answer. You can still search manually."
                                else "No suitable OSM scene points were returned close enough to this route.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        "Best matches",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 430.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(suggestions.take(16), key = { it.id }) { idea ->
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth().clickable { onAddSuggestion(idea) },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stopEmoji(idea.kind), style = MaterialTheme.typography.titleLarge)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(idea.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        val details = buildList {
                                            idea.subtype?.replace('_', ' ')?.let { add(it) }
                                            if (idea.distanceFromRouteMeters > 0) add("${idea.distanceFromRouteMeters} m from route")
                                            add("${idea.suggestedDwellMinutes} min stop")
                                        }
                                        Text(
                                            details.joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    Icon(Icons.Default.AddCircleOutline, "Add ${idea.name}")
                                }
                            }
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

            if (BuildConfig.DEBUG) {
                Text(
                    "Automatic suggestions use OpenStreetMap data via development discovery services.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun stopEmoji(kind: String): String = when (kind) {
    StopKind.VIEWPOINT.name -> "👁️"
    StopKind.MUSEUM.name -> "🏛️"
    StopKind.NATURE.name -> "⛰️"
    StopKind.MONUMENT.name -> "🏰"
    StopKind.PARK.name -> "🌳"
    StopKind.ART.name -> "🎨"
    StopKind.WORSHIP.name -> "⛪"
    StopKind.WATER.name -> "💧"
    StopKind.ARCHITECTURE.name -> "🏗️"
    else -> "⭐"
}
