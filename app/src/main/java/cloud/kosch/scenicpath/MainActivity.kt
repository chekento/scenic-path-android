package cloud.kosch.scenicpath

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VehicleSettingsState.initialize(this)
        MapLibre.getInstance(this)

        setContent {
            ScenicPathTheme {
                var locationPermissionGranted by remember {
                    mutableStateOf(hasForegroundLocationPermission())
                }
                var showVehicleSettings by remember { mutableStateOf(false) }
                var showNavigationDisclaimer by remember { mutableStateOf(true) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    locationPermissionGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        ScenicExperienceRootV2(
                            locationPermissionGranted = locationPermissionGranted,
                            requestLocationPermission = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                    )
                                )
                            },
                        )

                        // Always reachable, but placed on the opposite side of the minimized
                        // route-panel dock so OSD controls never cover each other.
                        SmallFloatingActionButton(
                            onClick = { showVehicleSettings = true },
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
                        ) {
                            Text(VehicleSettingsState.profile.kind.emoji)
                        }
                    }
                }

                if (showVehicleSettings) {
                    VehicleSettingsSheet(
                        current = VehicleSettingsState.profile,
                        onSave = { VehicleSettingsState.update(this@MainActivity, it) },
                        onDismiss = { showVehicleSettings = false },
                    )
                }

                if (showNavigationDisclaimer) {
                    AlertDialog(
                        onDismissRequest = { showNavigationDisclaimer = false },
                        title = { Text("Navigation safety") },
                        text = {
                            Text(
                                "Scenic Path navigation is advisory. Always follow traffic laws, road signs, closures and real-world conditions. " +
                                    "Do not interact with the app while driving; stop safely before changing the route or Smart Stops."
                            )
                        },
                        confirmButton = {
                            Button(onClick = { showNavigationDisclaimer = false }) {
                                Text("I understand")
                            }
                        },
                    )
                }
            }
        }
    }

    private fun hasForegroundLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
