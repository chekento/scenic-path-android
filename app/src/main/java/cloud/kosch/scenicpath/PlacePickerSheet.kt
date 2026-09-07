package cloud.kosch.scenicpath

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.util.Locale

/** Retained by the journey session when a search is minimised or the device rotates. */
class PlaceSearchState(initialQuery: String = "") {
    var query by mutableStateOf(initialQuery)
    var results by mutableStateOf<List<PlaceSuggestion>>(emptyList())
    var searching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var generation = 0L
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerSheet(
    title: String,
    initialQuery: String = "",
    bias: GeoPoint? = null,
    onDismiss: () -> Unit,
    onPick: (PlaceSuggestion) -> Unit,
    searchState: PlaceSearchState? = null,
) {
    val context = LocalContext.current
    val retained = searchState ?: remember(initialQuery) { PlaceSearchState(initialQuery) }
    var query by retained::query
    var results by retained::results
    var searching by retained::searching
    var error by retained::error
    // Freeze the bias for this sheet visit. GPS ticks must not restart a typed search.
    val searchBias = remember { bias }
    var submitNonce by remember { mutableIntStateOf(0) }
    var handledSubmitNonce by remember { mutableIntStateOf(0) }
    var submittedQuery by remember { mutableStateOf("") }
    fun submitSearch() { submittedQuery = query.trim(); submitNonce++ }

    LaunchedEffect(query, submitNonce) {
        val token = ++retained.generation
        val normalized = query.trim()
        results = emptyList()
        error = null
        searching = normalized.length >= 2
        if (!searching) return@LaunchedEffect
        val explicit = submitNonce > handledSubmitNonce && submittedQuery == normalized
        if (explicit) handledSubmitNonce = submitNonce
        var exact = emptyList<PlaceSuggestion>()
        var photon = emptyList<PlaceSuggestion>()
        var standard = emptyList<PlaceSuggestion>()
        var failed = 0
        try {
            delay(if (explicit) 20 else 340)
            supervisorScope {
                suspend fun lane(load: suspend () -> List<PlaceSuggestion>, accept: (List<PlaceSuggestion>) -> Unit) {
                    try {
                        val found = withTimeout(12_000L) { load() }
                        if (token == retained.generation) {
                            accept(found)
                            results = mergePlaceSuggestions(exact, photon, standard)
                        }
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        failed++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { failed++ }
                }
                launch { lane({ ScenicApi.searchPlaces(context, normalized, searchBias, includePhotonFallback = false) }) { standard = it } }
                launch { lane({ OsmPlaceSearch.search(normalized, searchBias) }) { photon = it } }
                if (explicit) launch { lane({ OsmAddressSearch.search(normalized, searchBias) }) { exact = it } }
            }
            if (token == retained.generation && results.isEmpty()) {
                error = if (failed > 0) "Address search is temporarily unavailable. Check your connection and press Search to retry."
                else "No matching address found. Try a street, house number and town, or a landmark."
            }
        } finally {
            if (token == retained.generation) searching = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Search towns, exact streets, house numbers, addresses and landmarks",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.ExpandMore, "Minimize search") }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { retained.generation++; query = it; results = emptyList(); error = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Street, house number, place or landmark") },
                placeholder = { Text("e.g. Hamburger Straße 12, Ahrensburg") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                trailingIcon = {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (query.trim().length >= 2) {
                        IconButton(onClick = { submitSearch() }) {
                            Icon(Icons.Default.Search, "Search exact address")
                        }
                    }
                },
            )

            Text(
                "Tip: press Search for exact street + house-number lookup.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(results, key = { it.id }) { suggestion ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(suggestion) },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(suggestion.title, fontWeight = FontWeight.SemiBold)
                                if (suggestion.subtitle.isNotBlank()) {
                                    Text(
                                        suggestion.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (BuildConfig.DEBUG) {
                Text(
                    "Type-ahead: device geocoder + Photon/OpenStreetMap · explicit exact-address search: OpenStreetMap Nominatim.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun mergePlaceSuggestions(
    exact: List<PlaceSuggestion>,
    photon: List<PlaceSuggestion>,
    standard: List<PlaceSuggestion>,
): List<PlaceSuggestion> {
    val seen = mutableSetOf<String>()
    val seenIds = mutableSetOf<String>()
    return buildList {
        (exact + photon + standard).forEach { suggestion ->
            val coordinateKey = "%.5f,%.5f".format(Locale.US, suggestion.point.lat, suggestion.point.lon)
            val titleKey = suggestion.title.trim().lowercase(Locale.ROOT)
            val key = "$coordinateKey:$titleKey"
            if (key !in seen && suggestion.id !in seenIds) {
                seen.add(key)
                seenIds.add(suggestion.id)
                add(suggestion)
            }
        }
    }.take(16)
}
