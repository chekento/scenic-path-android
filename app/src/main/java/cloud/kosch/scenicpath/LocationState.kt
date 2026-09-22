package cloud.kosch.scenicpath

import android.annotation.SuppressLint
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(LocationUiState()) }

    DisposableEffect(permissionGranted, context, lifecycleOwner) {
        if (!permissionGranted) {
            state = LocationUiState(error = "Location permission not granted")
            return@DisposableEffect onDispose { }
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setWaitForAccurateLocation(false)
            .build()
        var active = false
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!active) return
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

        fun start() {
            if (active) return
            active = true
            runCatching {
                client.lastLocation
                    .addOnSuccessListener { location ->
                        if (active) location?.let {
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
                        if (active) state = state.copy(error = error.message ?: "Last location unavailable")
                    }
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            }.onFailure { error ->
                state = state.copy(error = error.message ?: "Location service unavailable")
            }

        }
        fun stop() {
            active = false
            runCatching { client.removeLocationUpdates(callback) }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }

    return state
}
