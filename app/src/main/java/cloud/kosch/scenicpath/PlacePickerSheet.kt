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
    var submitNonce by remember { mutableIntStateOf(0) }
    var handledSubmitNonce by remember { mutableIntStateOf(0) }
    var submittedQuery by remember { mutableStateOf("") }

    fun submitSearch() {
        submittedQuery = query.trim()
        submitNonce++
    }

    LaunchedEffect(query, bias, submitNonce) {
        val normalized = query.trim()
        if (normalized.length < 2) {
            results = emptyList()
            searching = false
            error = null
            return@LaunchedEffect
        }

        val explicit = submitNonce > handledSubmitNonce && submittedQuery == normalized
        delay(if (explicit) 20 else 340)
        searching = true
        error = null

        val found = runCatching {
            OriginalSearchStack.search(
                context = context,
                query = normalized,
                bias = bias,
                exactAddressRequested = explicit,
                maxResults = 16,
            )
        }.getOrElse { emptyList() }

        if (explicit) handledSubmitNonce = submitNonce
        results = found
        error = if (found.isEmpty()) {
            "No matching address found. Try street + house number + town/postcode, or a landmark."
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
                        "Search towns, exact streets, house numbers, addresses and landmarks",
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
                "Tip: type-ahead keeps the original Photon + device/backend lanes. Press Search for exact Nominatim street + house-number lookup.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    "Original search stack active: Android/device + backend + Photon type-ahead · Nominatim exact address on explicit Search.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
