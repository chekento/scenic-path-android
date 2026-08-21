package cloud.kosch.scenicpath

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView

@Composable
fun ScenicMap(modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).also { mapView ->
                mapViewRef = mapView
                mapView.onCreate(null)
                mapView.getMapAsync { map ->
                    map.setStyle(BuildConfig.MAP_STYLE_URL)
                    map.uiSettings.isCompassEnabled = true
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.isLogoEnabled = true
                }
            }
        }
    )

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
