package cloud.kosch.scenicpath

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ScenicPathApp() {
    var start by remember { mutableStateOf("Current location") }
    var destination by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(ScenicPreferences()) }

    Box(Modifier.fillMaxSize()) {
        ScenicMap(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Landscape, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Scenic Path", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Tune, contentDescription = "Scenic preferences")
                }
            }
            OutlinedTextField(
                value = start,
                onValueChange = { start = it },
                label = { Text("Start") },
                leadingIcon = { Icon(Icons.Default.MyLocation, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Destination") },
                leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = { /* wired to backend in milestone M1 */ },
                modifier = Modifier.fillMaxWidth(),
                enabled = destination.isNotBlank()
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Find the beautiful way")
            }
        }

        AssistChip(
            onClick = { showSettings = true },
            label = { Text("≤ ${preferences.maxExtraMinutes} min extra · Scenic ${preferences.weights.viewpoints.times(100).toInt()}%") },
            leadingIcon = { Icon(Icons.Default.Route, contentDescription = null) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp)
        )
    }

    if (showSettings) {
        ScenicSettingsSheet(
            preferences = preferences,
            onChange = { preferences = it },
            onDismiss = { showSettings = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScenicSettingsSheet(
    preferences: ScenicPreferences,
    onChange: (ScenicPreferences) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("What should the route feel like?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Scenic Path treats beauty as a weighted objective, not as a fixed route type.")

            WeightSlider("Beautiful roads", preferences.weights.beautifulRoads) { v -> onChange(preferences.copy(weights = preferences.weights.copy(beautifulRoads = v))) }
            WeightSlider("Forests", preferences.weights.forest) { v -> onChange(preferences.copy(weights = preferences.weights.copy(forest = v))) }
            WeightSlider("Lakes & rivers", preferences.weights.water) { v -> onChange(preferences.copy(weights = preferences.weights.copy(water = v))) }
            WeightSlider("Mountains", preferences.weights.mountains) { v -> onChange(preferences.copy(weights = preferences.weights.copy(mountains = v))) }
            WeightSlider("Viewpoints", preferences.weights.viewpoints) { v -> onChange(preferences.copy(weights = preferences.weights.copy(viewpoints = v))) }
            WeightSlider("Culture & sights", preferences.weights.culture) { v -> onChange(preferences.copy(weights = preferences.weights.copy(culture = v))) }
            WeightSlider("Museums & art", preferences.weights.museums) { v -> onChange(preferences.copy(weights = preferences.weights.copy(museums = v))) }
            WeightSlider("Architecture", preferences.weights.architecture) { v -> onChange(preferences.copy(weights = preferences.weights.copy(architecture = v))) }
            WeightSlider("Parks & gardens", preferences.weights.parks) { v -> onChange(preferences.copy(weights = preferences.weights.copy(parks = v))) }
            WeightSlider("Top-rated food", preferences.weights.food) { v -> onChange(preferences.copy(weights = preferences.weights.copy(food = v))) }

            HorizontalDivider()
            Text("Detour budget", fontWeight = FontWeight.SemiBold)
            Text("Maximum +${preferences.maxExtraMinutes} minutes")
            Slider(
                value = preferences.maxExtraMinutes.toFloat(),
                onValueChange = { onChange(preferences.copy(maxExtraMinutes = it.toInt())) },
                valueRange = 0f..180f,
                steps = 17
            )

            Text("Food quality", fontWeight = FontWeight.SemiBold)
            Text("At least ${"%.1f".format(preferences.minimumFoodRating)}★ and ${preferences.minimumFoodReviewCount}+ reviews")
            Slider(
                value = preferences.minimumFoodRating.toFloat(),
                onValueChange = { onChange(preferences.copy(minimumFoodRating = it.toDouble())) },
                valueRange = 4.2f..5.0f,
                steps = 7
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Avoid motorways", modifier = Modifier.weight(1f))
                Switch(checked = preferences.avoidMotorways, onCheckedChange = { onChange(preferences.copy(avoidMotorways = it)) })
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Apply profile") }
        }
    }
}

@Composable
private fun WeightSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text("${(value * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
    }
}
