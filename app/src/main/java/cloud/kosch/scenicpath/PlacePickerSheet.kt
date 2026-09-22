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
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerSheet(
    title: String,
    initialQuery: String = "",
    bias: GeoPoint? = null,
    currentLocation: GeoPoint? = null,
    currentLocationAccuracyMeters: Float? = null,
    onUseCurrentLocation: (() -> Unit)? = null,
    onRequestLocationPermission: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onPick: (PlaceSuggestion) -> Unit,
    onQueryChange: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf(initialQuery) }
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

    val searchBias = remember { bias }
    LaunchedEffect(query, submitNonce) {
        val normalized = query.trim()
        if (normalized.length < 2) {
            results = emptyList()
            searching = false
            error = null
            return@LaunchedEffect
        }

        val explicit = submitNonce > handledSubmitNonce && submittedQuery == normalized
        searching = true
        error = null
        results = emptyList()
        delay(if (explicit) 0 else 300)

        val found = try {
            OriginalSearchStack.search(
                context = context,
                query = normalized,
                bias = searchBias,
                exactAddressRequested = explicit,
                maxResults = 16,
                onPartial = { results = it },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        currentCoroutineContext().ensureActive()

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
                .padding(bottom = 28.dp)
                .imePadding(),
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

            if (onUseCurrentLocation != null || onRequestLocationPermission != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
                    ),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Use current GPS location", fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    currentLocation != null && currentLocationAccuracyMeters != null ->
                                        "Live position available · ±${currentLocationAccuracyMeters.roundToInt()} m"
                                    currentLocation != null -> "Live position available"
                                    onRequestLocationPermission != null -> "Allow location access to use this as your start"
                                    else -> "Waiting for a GPS fix…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        when {
                            currentLocation != null && onUseCurrentLocation != null -> {
                                FilledTonalButton(onClick = { onUseCurrentLocation?.invoke() }) {
                                    Icon(Icons.Default.GpsFixed, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Use")
                                }
                            }
                            currentLocation == null && onRequestLocationPermission != null -> {
                                OutlinedButton(onClick = { onRequestLocationPermission?.invoke() }) {
                                    Icon(Icons.Default.GpsFixed, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Enable")
                                }
                            }
                            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it; onQueryChange(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Street, house number, place or landmark") },
                placeholder = { Text("e.g. Hamburger Straße 12, Ahrensburg") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        if (query.trim().length >= 2) {
                            IconButton(onClick = { submitSearch() }) { Icon(Icons.Default.Search, "Search exact address") }
                        }
                    }
                },
            )

            Text(
                "Suggestions appear as you type. For an exact address, enter the street, house number and town, then tap Search.",
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


        }
    }
}
