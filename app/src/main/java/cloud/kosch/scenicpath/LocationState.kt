package cloud.kosch.scenicpath

import android.annotation.SuppressLint
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

data class LocationUiState(
    val point: GeoPoint? = null,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val error: String? = null,
)

@SuppressLint("MissingPermission")
@Composable
fun rememberLocationUiState(permissionGranted: Boolean): LocationUiState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(LocationUiState()) }

    DisposableEffect(permissionGranted, context) {
        if (!permissionGranted) {
            state = LocationUiState(error = "Location permission not granted")
            return@DisposableEffect onDispose { }
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    state = LocationUiState(
                        point = GeoPoint(location.latitude, location.longitude),
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
                        bearingDegrees = if (location.hasBearing()) location.bearing else null,
                    )
                }
            }
        }

        runCatching {
            client.lastLocation
                .addOnSuccessListener { location ->
                    location?.let {
                        state = state.copy(
                            point = GeoPoint(it.latitude, it.longitude),
                            accuracyMeters = if (it.hasAccuracy()) it.accuracy else null,
                            speedMetersPerSecond = if (it.hasSpeed()) it.speed else null,
                            bearingDegrees = if (it.hasBearing()) it.bearing else null,
                            error = null,
                        )
                    }
                }
                .addOnFailureListener { error ->
                    state = state.copy(error = error.message ?: "Last location unavailable")
                }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }.onFailure { error ->
            state = state.copy(error = error.message ?: "Location service unavailable")
        }

        onDispose {
            runCatching { client.removeLocationUpdates(callback) }
        }
    }

    return state
}
