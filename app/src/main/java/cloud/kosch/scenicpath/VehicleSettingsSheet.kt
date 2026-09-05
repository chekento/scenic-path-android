package cloud.kosch.scenicpath

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleSettingsSheet(
    current: VehicleProfile,
    onSave: (VehicleProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    var showPrivacy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vehicle & journey", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Vehicle access, healthy day length, overnight planning and e-bike range.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VehicleKind.entries.forEach { kind ->
                    FilterChip(
                        selected = draft.kind == kind,
                        onClick = { draft = VehicleProfile.defaults(kind) },
                        label = { Text("${kind.emoji} ${kind.label}") },
                    )
                }
            }

            when (draft.kind) {
                VehicleKind.BICYCLE -> BicycleSettings(draft) { draft = it }
                VehicleKind.CAMPER, VehicleKind.TRUCK, VehicleKind.COACH -> HeavyVehicleSettings(draft) { draft = it }
                VehicleKind.MOTORCYCLE -> InfoCard("Motorcycle profile favors secondary, winding roads when Scenic is selected and avoids trails in Direct mode.")
                VehicleKind.CAR -> InfoCard("Car profile keeps normal road access rules. Scenic Path can still avoid motorways and prefer smaller scenic roads.")
            }

            JourneyDaySettings(draft) { draft = it }

            Text(
                "Height, width, length and weight are sent to the routing engine for restricted vehicles. Bridge, tunnel and HGV restrictions are therefore part of route calculation where the map data provides them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { onSave(draft); onDismiss() },
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text("Use ${draft.kind.emoji} ${draft.kind.label}")
            }
            Text("The profile is stored on this device and applies to the next route calculation.", style = MaterialTheme.typography.labelSmall)

            HorizontalDivider()
            OutlinedButton(
                onClick = { showPrivacy = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Info, null)
                Spacer(Modifier.width(8.dp))
                Text("Privacy, data & attributions")
            }
        }
    }

    if (showPrivacy) {
        PrivacyAndAttributionDialog(onDismiss = { showPrivacy = false })
    }
}

@Composable
private fun JourneyDaySettings(profile: VehicleProfile, onChange: (VehicleProfile) -> Unit) {
    val effectiveMinutes = JourneyStagePolicy.effectiveDailyMinutes(profile)
    val effectiveHours = effectiveMinutes / 60.0
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.48f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Daily journey planning", fontWeight = FontWeight.SemiBold)
            Text(
                if (profile.kind == VehicleKind.BICYCLE) {
                    "Overnight options appear only when the route exceeds your chosen healthy riding day."
                } else {
                    "Overnight options appear only when the route exceeds your chosen comfortable travel day."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Maximum per ${if (profile.kind == VehicleKind.BICYCLE) "rider" else "driver"}: ${formatHours(profile.dailyTravelHours)}", fontWeight = FontWeight.Medium)
            Slider(
                value = profile.dailyTravelHours.toFloat(),
                onValueChange = { onChange(profile.copy(dailyTravelHours = (it * 2).roundToInt() / 2.0)) },
                valueRange = 2f..12f,
                steps = 19,
            )

            if (profile.kind != VehicleKind.BICYCLE) {
                Text("Drivers who can alternate", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { count ->
                        FilterChip(
                            selected = profile.driverCount == count,
                            onClick = { onChange(profile.copy(driverCount = count)) },
                            label = { Text(if (count == 1) "1 driver" else "$count drivers") },
                        )
                    }
                }
                Text(
                    "Effective travel-day window: ${formatHours(effectiveHours)}. Scenic Path caps shared-driver planning at 16 h elapsed travel so swapping drivers never becomes a no-sleep assumption.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Find overnight options at day ends", fontWeight = FontWeight.Medium)
                    Text(
                        when (profile.kind) {
                            VehicleKind.CAMPER -> "Prioritize camp/caravan sites and motorhome-suitable parking."
                            VehicleKind.TRUCK -> "Prioritize HGV parking, services and rest areas."
                            VehicleKind.COACH -> "Look for lodging plus suitable bus/coach parking."
                            VehicleKind.BICYCLE -> "Look for hotels, guest houses, hostels and campsites."
                            else -> "Look for lodging and camping near the planned end of each travel day."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = profile.overnightPlanningEnabled,
                    onCheckedChange = { onChange(profile.copy(overnightPlanningEnabled = it)) },
                )
            }

            if (profile.kind == VehicleKind.TRUCK || profile.kind == VehicleKind.COACH) {
                Text(
                    "This is comfort/trip planning, not a legal driving-time or tachograph calculation. Applicable statutory rest and duty rules still take precedence.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PrivacyAndAttributionDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val privacyUrl = BuildConfig.PRIVACY_POLICY_URL.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy, data & attributions") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Location", fontWeight = FontWeight.Bold)
                Text(
                    "Scenic Path requests approximate or precise location only while the app is open. It is used for Current Location, route planning and on-screen navigation. This release does not request background-location permission.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("Route requests", fontWeight = FontWeight.Bold)
                Text(
                    "In production, start/destination coordinates, selected stops, search text and route preferences are sent over HTTPS to the Scenic Path backend and its routing/place service providers only to answer the requested route or place lookup.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("On-device settings", fontWeight = FontWeight.Bold)
                Text(
                    "The selected vehicle, daily journey preferences and optional e-bike range are stored on this device. Scenic Path does not require an account and contains no advertising SDK.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("Maps & POIs", fontWeight = FontWeight.Bold)
                Text(
                    "Map, POI, overnight and charging data may include OpenStreetMap contributors. The Android map renderer uses MapLibre. Production routing/place providers and their required attribution are shown where applicable.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("Navigation scope", fontWeight = FontWeight.Bold)
                Text(
                    "The current Play release candidate navigates while Scenic Path is visible. It does not claim background or always-on navigation.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (privacyUrl.startsWith("https://")) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl)))
                        }
                    },
                ) {
                    Icon(Icons.Default.OpenInNew, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Privacy policy")
                }
            }
        },
    )
}

@Composable
private fun HeavyVehicleSettings(profile: VehicleProfile, onChange: (VehicleProfile) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Physical vehicle envelope", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField("Height m", profile.heightMeters, Modifier.weight(1f)) { onChange(profile.copy(heightMeters = it)) }
                DecimalField("Width m", profile.widthMeters, Modifier.weight(1f)) { onChange(profile.copy(widthMeters = it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField("Length m", profile.lengthMeters, Modifier.weight(1f)) { onChange(profile.copy(lengthMeters = it)) }
                DecimalField("Weight t", profile.weightTons, Modifier.weight(1f)) { onChange(profile.copy(weightTons = it)) }
            }
            if (profile.kind == VehicleKind.TRUCK || profile.kind == VehicleKind.COACH) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Axle load t", profile.axleLoadTons, Modifier.weight(1f)) { onChange(profile.copy(axleLoadTons = it)) }
                    IntegerField("Axles", profile.axleCount, Modifier.weight(1f)) { onChange(profile.copy(axleCount = it)) }
                }
            }
            Text(
                when (profile.kind) {
                    VehicleKind.TRUCK -> "HGV access, truck routes and mapped physical restrictions are prioritized over scenic shortcuts."
                    VehicleKind.COACH -> "Bus access plus the coach dimensions are respected; narrow scenic shortcuts are deliberately avoided."
                    else -> "Motorhome dimensions prevent scenic routing from treating a tall/wide camper like a normal car."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BicycleSettings(profile: VehicleProfile, onChange: (VehicleProfile) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Scenic cycling", fontWeight = FontWeight.SemiBold)
            Text("Scenic strongly prefers cycleways and paths over parallel main roads — especially through parks and green corridors.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ScenicBicycleType.entries.forEach { type ->
                    FilterChip(
                        selected = profile.bicycleType == type,
                        onClick = { onChange(profile.copy(bicycleType = type)) },
                        label = { Text(type.label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow good gravel / unpaved cycle paths", fontWeight = FontWeight.Medium)
                    Text("Useful for parks, river paths and scenic greenways. Bad surfaces are still penalized.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = profile.allowUnpavedBikePaths,
                    onCheckedChange = { onChange(profile.copy(allowUnpavedBikePaths = it)) },
                )
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("E-bike / pedelec", fontWeight = FontWeight.Medium)
                    Text("Plan charging before your practical battery range is exhausted.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = profile.eBikeEnabled,
                    onCheckedChange = { onChange(profile.copy(eBikeEnabled = it)) },
                )
            }
            if (profile.eBikeEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Practical range km", profile.eBikeRangeKm, Modifier.weight(1f)) {
                        onChange(profile.copy(eBikeRangeKm = it))
                    }
                    IntegerField("Reserve %", profile.eBikeReservePercent, Modifier.weight(1f)) {
                        onChange(profile.copy(eBikeReservePercent = it))
                    }
                }
                val usable = JourneyStagePolicy.usableEBikeRangeKm(profile)
                Text(
                    "Charging search begins about every ${usable.roundToInt()} km (${profile.eBikeRangeKm.roundToInt()} km configured minus ${profile.eBikeReservePercent}% reserve). Terrain, assistance level, temperature, load and battery health can change real range.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DecimalField(label: String, value: Double, modifier: Modifier, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(String.format(java.util.Locale.US, "%.2f", value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            next.replace(',', '.').toDoubleOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun IntegerField(label: String, value: Int, modifier: Modifier, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            next.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

private fun formatHours(hours: Double): String {
    val whole = hours.toInt()
    val minutes = ((hours - whole) * 60).roundToInt()
    return if (minutes == 0) "${whole}h" else "${whole}h ${minutes}m"
}
