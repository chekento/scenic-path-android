package cloud.kosch.scenicpath

import android.annotation.SuppressLint
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

@SuppressLint("MissingPermission")
@Composable
fun ScenicMap(
    modifier: Modifier = Modifier,
    locationPermissionGranted: Boolean = false,
    recenterToken: Int = 0,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }

    fun enableLocation(map: MapLibreMap) {
        if (!locationPermissionGranted || !styleLoaded) return
        val style = map.style ?: return
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) {
            val options = LocationComponentOptions.builder(mapViewRef?.context ?: return)
                .pulseEnabled(true)
                .enableStaleState(true)
                .build()
            val request = LocationEngineRequest.Builder(1_000L)
                .setFastestInterval(750L)
                .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                .build()
            val activation = LocationComponentActivationOptions
                .builder(mapViewRef?.context ?: return, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(true)
                .locationEngineRequest(request)
                .build()
            component.activateLocationComponent(activation)
        }
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.COMPASS
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).also { mapView ->
                mapViewRef = mapView
                mapView.onCreate(null)
                mapView.getMapAsync { map ->
                    mapRef = map
                    map.uiSettings.isCompassEnabled = true
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.isLogoEnabled = true
                    map.setStyle(BuildConfig.MAP_STYLE_URL) {
                        styleLoaded = true
                        enableLocation(map)
                    }
                }
            }
        },
        update = {
            mapRef?.let(::enableLocation)
        },
    )

    LaunchedEffect(locationPermissionGranted, styleLoaded) {
        mapRef?.let(::enableLocation)
    }

    LaunchedEffect(recenterToken, locationPermissionGranted, styleLoaded) {
        if (recenterToken > 0 && locationPermissionGranted && styleLoaded) {
            mapRef?.locationComponent?.cameraMode = CameraMode.TRACKING
        }
    }

    DisposableEffect(lifecycleOwner, mapViewRef) {
        val observer = LifecycleEventObserver { _, event ->
            val mapView = mapViewRef ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef?.onDestroy()
        }
    }
}
