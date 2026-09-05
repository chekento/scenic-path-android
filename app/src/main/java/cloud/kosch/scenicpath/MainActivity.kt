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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VehicleSettingsState.initialize(this)
        MapLibre.getInstance(this)

        setContent {
            ScenicPathTheme {
                val prefs = remember { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
                var locationPermissionGranted by remember {
                    mutableStateOf(hasForegroundLocationPermission())
                }
                var gpsEnabledInApp by remember {
                    mutableStateOf(
                        prefs.getBoolean(KEY_GPS_ENABLED_IN_APP, hasForegroundLocationPermission())
                    )
                }
                var showVehicleSettings by remember { mutableStateOf(false) }
                var showWelcome by remember {
                    mutableStateOf(!prefs.getBoolean(KEY_WELCOME_ACKNOWLEDGED, false))
                }

                fun persistGps(enabled: Boolean) {
                    gpsEnabledInApp = enabled
                    prefs.edit().putBoolean(KEY_GPS_ENABLED_IN_APP, enabled).apply()
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    locationPermissionGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                        hasForegroundLocationPermission()
                    persistGps(locationPermissionGranted)
                }

                fun setGpsEnabled(enabled: Boolean) {
                    if (!enabled) {
                        persistGps(false)
                        return
                    }
                    if (hasForegroundLocationPermission()) {
                        locationPermissionGranted = true
                        persistGps(true)
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            )
                        )
                    }
                }

                val gpsActive = locationPermissionGranted && gpsEnabledInApp

                Box(Modifier.fillMaxSize()) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ScenicExperienceRootV2(
                            locationPermissionGranted = gpsActive,
                            requestLocationPermission = { setGpsEnabled(true) },
                            vehicleProfile = VehicleSettingsState.profile,
                            onVehicleSettings = { showVehicleSettings = true },
                            externalOverlayVisible = showVehicleSettings || showWelcome,
                        )
                    }

                    if (!showWelcome && !showVehicleSettings) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .navigationBarsPadding()
                                .padding(start = 108.dp, bottom = 10.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            tonalElevation = 5.dp,
                            shadowElevation = 5.dp,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        ) {
                            Row(
                                Modifier.padding(start = 10.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (gpsActive) Icons.Default.GpsFixed else Icons.Default.GpsOff,
                                    contentDescription = null,
                                    tint = if (gpsActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("GPS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                Switch(
                                    checked = gpsActive,
                                    onCheckedChange = ::setGpsEnabled,
                                    modifier = Modifier.height(32.dp),
                                )
                            }
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

                if (showWelcome) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(R.drawable.ic_launcher_foreground),
                                    contentDescription = "Scenic Path logo",
                                    modifier = Modifier.size(64.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("Welcome to Scenic Path", fontWeight = FontWeight.Bold)
                                    Text("Find the beautiful way.", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text(
                                    "Plan scenic routes, discover worthwhile stops, build round trips and turn long journeys into manageable travel days.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Surface(
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(if (gpsActive) Icons.Default.GpsFixed else Icons.Default.GpsOff, null)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Use GPS", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "Optional. Use your current position for starts, map centering and live navigation. You can switch it off again in the main view.",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        Switch(
                                            checked = gpsActive,
                                            onCheckedChange = ::setGpsEnabled,
                                        )
                                    }
                                }
                                Text(
                                    "Navigation is advisory. Always follow traffic laws, road signs, closures and real-world conditions. Do not interact with the app while driving; stop safely before changing a route.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    prefs.edit()
                                        .putBoolean(KEY_WELCOME_ACKNOWLEDGED, true)
                                        .putBoolean(KEY_NAVIGATION_SAFETY_ACKNOWLEDGED, true)
                                        .apply()
                                    showWelcome = false
                                }
                            ) {
                                Text("Start exploring")
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
        const val KEY_WELCOME_ACKNOWLEDGED = "welcome_acknowledged_v2"
        const val KEY_NAVIGATION_SAFETY_ACKNOWLEDGED = "navigation_safety_acknowledged"
        const val KEY_GPS_ENABLED_IN_APP = "gps_enabled_in_app"
    }
}
