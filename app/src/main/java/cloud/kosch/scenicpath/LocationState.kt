package cloud.kosch.scenicpath

import android.annotation.SuppressLint
import android.os.Looper
import android.os.SystemClock
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
        var newestFixNanos = 0L
        fun accept(location: android.location.Location) {
            if (!active || location.elapsedRealtimeNanos < newestFixNanos) return
            val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            if (ageNanos !in 0L..120_000_000_000L) return
            newestFixNanos = location.elapsedRealtimeNanos
            state = LocationUiState(
                point = GeoPoint(location.latitude, location.longitude),
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
                bearingDegrees = if (location.hasBearing()) location.bearing else null,
            )
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(::accept)
            }
        }

        fun start() {
            if (active) return
            active = true
            state = LocationUiState(error = "Waiting for a fresh GPS position")
            runCatching {
                client.lastLocation.addOnSuccessListener { it?.let(::accept) }
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                    .addOnSuccessListener {
                        // Registration can complete after disposal or a GPS toggle.
                        if (!active) client.removeLocationUpdates(callback)
                    }
                    .addOnFailureListener {
                        if (active) state = LocationUiState(error = "GPS unavailable. Check location settings or choose a start manually.")
                    }
            }.onFailure {
                state = LocationUiState(error = "GPS unavailable. Check location settings or choose a start manually.")
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
