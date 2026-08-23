package cloud.kosch.scenicpath

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerSheet(
    title: String,
    initialQuery: String = "",
    bias: GeoPoint? = null,
    onDismiss: () -> Unit,
    onPick: (PlaceSuggestion) -> Unit,
) {
    val context = LocalContext.current
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(query, bias, searchNonce) {
        val normalized = query.trim()
        if (normalized.length < 2) {
            results = emptyList()
            searching = false
            error = null
            return@LaunchedEffect
        }

        // Debounce normal typing. An explicit keyboard/search-button request runs immediately.
        delay(if (searchNonce > 0) 70 else 360)
        searching = true
        error = null

        // ScenicApi owns the provider fallback order. On physical debug devices it deliberately
        // skips the emulator-only backend, tries Android's geocoder first, then Photon/OSM.
        // This prevents a temporarily throttled POI provider from also breaking destination entry.
        val found = runCatching {
            ScenicApi.searchPlaces(context, normalized, bias)
        }.getOrNull().orEmpty()

        results = found
        error = if (found.isEmpty()) {
            "No matching places found. Try the city name, postcode, street or a landmark."
        } else null
        searching = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (BuildConfig.DEBUG) "Search device geocoder + OpenStreetMap/Photon" else "Search and choose the exact place",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Place, address or landmark") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { searchNonce++ }),
                trailingIcon = {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (query.trim().length >= 2) {
                        IconButton(onClick = { searchNonce++ }) {
                            Icon(Icons.Default.Search, "Search now")
                        }
                    }
                },
            )

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
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
                    "Place search uses the device geocoder and OpenStreetMap/Photon as independent fallbacks.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
