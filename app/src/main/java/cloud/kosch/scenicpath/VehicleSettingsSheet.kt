package cloud.kosch.scenicpath

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleSettingsSheet(
    current: VehicleProfile,
    onSave: (VehicleProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }

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
                    Text("Vehicle & route access", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("The vehicle changes the routing network itself — not only the icon.", style = MaterialTheme.typography.bodySmall)
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
                VehicleKind.MOTORCYCLE -> InfoCard("Motorcycle profile favors secondary, winding roads when Beautiful is selected and avoids trails in Direct mode.")
                VehicleKind.CAR -> InfoCard("Car profile keeps normal road access rules. Scenic Path can still avoid motorways and prefer smaller scenic roads.")
            }

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
            Text("The new profile is stored on this device and applies to the next route calculation.", style = MaterialTheme.typography.labelSmall)
        }
    }
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
            if (profile.kind == VehicleKind.TRUCK) {
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
            Text("Beautiful strongly prefers cycleways and paths over parallel main roads — especially through parks and green corridors.", style = MaterialTheme.typography.bodySmall)
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
