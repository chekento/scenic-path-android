package cloud.kosch.scenicpath

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
fun ScenicPathApp(locationPermissionGranted: Boolean) {
    var start by remember { mutableStateOf("Current location") }
    var destination by remember { mutableStateOf("") }
    var showScenicDNA by remember { mutableStateOf(false) }
    var showPlanner by remember { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(ScenicPreferences()) }
    var plan by remember { mutableStateOf(TripPlan()) }
    var recenterToken by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        ScenicMap(
            modifier = Modifier.fillMaxSize(),
            locationPermissionGranted = locationPermissionGranted,
            recenterToken = recenterToken,
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Landscape, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Scenic Path", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("The beautiful way", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showScenicDNA = true }) {
                    Icon(Icons.Default.Tune, "Scenic DNA")
                }
            }

            OutlinedTextField(
                value = start,
                onValueChange = { start = it },
                label = { Text("Start") },
                leadingIcon = { Icon(Icons.Default.MyLocation, null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Where do you want to go?") },
                leadingIcon = { Icon(Icons.Default.Flag, null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { showPlanner = true },
                    label = { Text(plan.routeCharacter.label) },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { showPlanner = true },
                    label = { Text(if (plan.stops.isEmpty()) "No fixed stops" else "${plan.stops.size} stops") },
                    leadingIcon = { Icon(Icons.Default.LocationOn, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { showPlanner = true },
                    label = { Text("+${preferences.maxExtraMinutes} min") },
                    leadingIcon = { Icon(Icons.Default.MoreTime, null, Modifier.size(18.dp)) },
                )
            }

            Button(
                onClick = { showPlanner = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = destination.isNotBlank(),
            ) {
                Icon(Icons.Default.Route, null)
                Spacer(Modifier.width(8.dp))
                Text(if (plan.stops.isEmpty()) "Plan the beautiful route" else "Review route plan")
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (locationPermissionGranted) {
                SmallFloatingActionButton(onClick = { recenterToken++ }) {
                    Icon(Icons.Default.MyLocation, "Center on my location")
                }
            }
            FloatingActionButton(onClick = { showPlanner = true }) {
                Icon(Icons.Default.EditRoad, "Open route planner")
            }
        }
    }

    if (showPlanner) {
        RoutePlannerSheet(
            start = start,
            destination = destination,
            plan = plan,
            preferences = preferences,
            onPlanChange = { plan = it },
            onPreferencesChange = { preferences = it },
            onDismiss = { showPlanner = false },
        )
    }

    if (showScenicDNA) {
        ScenicSettingsSheet(
            preferences = preferences,
            onChange = {
                preferences = it
                plan = plan.copy(routeCharacter = RouteCharacter.CUSTOM)
            },
            onDismiss = { showScenicDNA = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScenicSettingsSheet(
    preferences: ScenicPreferences,
    onChange: (ScenicPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scenic DNA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Fine-tune what beautiful means to you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }

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
            Text("Road feel", fontWeight = FontWeight.SemiBold)
            Text("Winding ${preferences.windingness}%")
            Slider(
                value = preferences.windingness.toFloat(),
                onValueChange = { onChange(preferences.copy(windingness = it.toInt())) },
                valueRange = 0f..100f,
            )
            Text("Hilly ${preferences.hilliness}%")
            Slider(
                value = preferences.hilliness.toFloat(),
                onValueChange = { onChange(preferences.copy(hilliness = it.toInt())) },
                valueRange = 0f..100f,
            )

            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Use this Scenic DNA") }
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
