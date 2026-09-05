package cloud.kosch.scenicpath

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
                var showNavigationDisclaimer by remember {
                    mutableStateOf(
                        !getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getBoolean(KEY_NAVIGATION_SAFETY_ACKNOWLEDGED, false)
                    )
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    locationPermissionGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                        vehicleProfile = VehicleSettingsState.profile,
                        onVehicleSettings = { showVehicleSettings = true },
                        externalOverlayVisible = showVehicleSettings || showNavigationDisclaimer,
                    )
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
                        // First-run safety acknowledgement is explicit. Once accepted it is stored
                        // locally and no longer interrupts future launches.
                        onDismissRequest = {},
                        title = { Text("Navigation safety") },
                        text = {
                            Text(
                                "Scenic Path navigation is advisory. Always follow traffic laws, road signs, closures and real-world conditions. " +
                                    "Do not interact with the app while driving; stop safely before changing the route or Smart Stops."
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                        .edit()
                                        .putBoolean(KEY_NAVIGATION_SAFETY_ACKNOWLEDGED, true)
                                        .apply()
                                    showNavigationDisclaimer = false
                                }
                            ) {
                                Text("I understand")
                            }
                        },
                        dismissButton = {
                            if (BuildConfig.PRIVACY_POLICY_URL.startsWith("https://")) {
                                TextButton(
                                    onClick = {
                                        runCatching {
                                            startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(BuildConfig.PRIVACY_POLICY_URL),
                                                )
                                            )
                                        }
                                    }
                                ) {
                                    Text("Privacy policy")
                                }
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

    private companion object {
        const val PREFS_NAME = "scenic_path_preferences"
        const val KEY_NAVIGATION_SAFETY_ACKNOWLEDGED = "navigation_safety_acknowledged"
    }
}
