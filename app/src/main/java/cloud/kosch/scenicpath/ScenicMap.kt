package cloud.kosch.scenicpath

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val USER_SOURCE = "scenic-user-source"
private const val USER_LAYER = "scenic-user-layer"
private const val ROUTE_SOURCE = "scenic-route-source"
private const val ROUTE_LAYER = "scenic-route-layer"
private const val STOP_SOURCE = "scenic-stop-source"
private const val STOP_LAYER = "scenic-stop-layer"

/**
 * MapLibre host with deliberately boring lifecycle management.
 *
 * Important: the MapView is remembered and is NOT a DisposableEffect key that changes when
 * the view is created. The previous implementation could dispose/destroy a freshly-created
 * MapView during the first recomposition, which is a plausible cause of the instant-close bug.
 * GPS is supplied by FusedLocationProviderClient outside MapLibre, so a location-engine failure
 * cannot take down map startup.
 */
@Composable
fun ScenicMap(
    modifier: Modifier = Modifier,
    userLocation: GeoPoint? = null,
    routePoints: List<GeoPoint> = emptyList(),
    stops: List<PlannedStop> = emptyList(),
    recenterToken: Int = 0,
    onMapError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf<String?>(null) }

    val mapView = remember(context) {
        runCatching {
            MapView(context).also { it.onCreate(null) }
        }.onFailure {
            mapError = it.message ?: "Map could not be created"
            onMapError(mapError!!)
        }.getOrNull()
    }

    if (mapView == null) {
        MapFallback(modifier, mapError ?: "Map unavailable")
        return
    }

    DisposableEffect(lifecycleOwner, mapView) {
        var started = false
        var resumed = false

        fun startIfNeeded() {
            if (!started) {
                runCatching { mapView.onStart() }.onFailure { onMapError(it.message ?: "Map start failed") }
                started = true
            }
        }
        fun resumeIfNeeded() {
            startIfNeeded()
            if (!resumed) {
                runCatching { mapView.onResume() }.onFailure { onMapError(it.message ?: "Map resume failed") }
                resumed = true
            }
        }
        fun pauseIfNeeded() {
            if (resumed) {
                runCatching { mapView.onPause() }
                resumed = false
            }
        }
        fun stopIfNeeded() {
            pauseIfNeeded()
            if (started) {
                runCatching { mapView.onStop() }
                started = false
            }
        }

        when {
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> resumeIfNeeded()
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> startIfNeeded()
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startIfNeeded()
                Lifecycle.Event.ON_RESUME -> resumeIfNeeded()
                Lifecycle.Event.ON_PAUSE -> pauseIfNeeded()
                Lifecycle.Event.ON_STOP -> stopIfNeeded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopIfNeeded()
            runCatching { mapView.onDestroy() }
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.also { view ->
                    runCatching {
                        view.getMapAsync { map ->
                            mapRef = map
                            map.uiSettings.isCompassEnabled = true
                            map.uiSettings.isAttributionEnabled = true
                            map.uiSettings.isLogoEnabled = true
                            runCatching {
                                map.setStyle(BuildConfig.MAP_STYLE_URL) {
                                    styleLoaded = true
                                    updateMapData(map, userLocation, routePoints, stops)
                                }
                            }.onFailure { error ->
                                mapError = error.message ?: "Map style failed"
                                onMapError(mapError!!)
                            }
                        }
                    }.onFailure { error ->
                        mapError = error.message ?: "Map initialization failed"
                        onMapError(mapError!!)
                    }
                }
            },
        )

        if (!styleLoaded && mapError == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        mapError?.let { error ->
            MapStatusBadge(error, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }

    LaunchedEffect(userLocation, routePoints, stops, styleLoaded) {
        if (styleLoaded) mapRef?.let { updateMapData(it, userLocation, routePoints, stops) }
    }

    LaunchedEffect(recenterToken, userLocation, styleLoaded) {
        if (recenterToken > 0 && styleLoaded) {
            userLocation?.let { point ->
                mapRef?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 15.2),
                    700,
                )
            }
        }
    }
}

private fun updateMapData(
    map: MapLibreMap,
    userLocation: GeoPoint?,
    routePoints: List<GeoPoint>,
    stops: List<PlannedStop>,
) {
    val style = map.style ?: return

    userLocation?.let { location ->
        val feature = Feature.fromGeometry(Point.fromLngLat(location.lon, location.lat))
        val existing = style.getSourceAs<GeoJsonSource>(USER_SOURCE)
        if (existing == null) {
            style.addSource(GeoJsonSource(USER_SOURCE, feature))
            style.addLayer(
                CircleLayer(USER_LAYER, USER_SOURCE).withProperties(
                    circleRadius(8f),
                    circleColor("#1769E0"),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(3f),
                )
            )
        } else {
            existing.setGeoJson(feature)
        }
    }

    if (routePoints.size >= 2) {
        val line = LineString.fromLngLats(routePoints.map { Point.fromLngLat(it.lon, it.lat) })
        val existing = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
        if (existing == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE, Feature.fromGeometry(line)))
            style.addLayer(
                LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    lineColor("#1769E0"),
                    lineWidth(6f),
                    lineOpacity(0.92f),
                )
            )
        } else {
            existing.setGeoJson(Feature.fromGeometry(line))
        }
    }

    val stopFeatures = stops.mapNotNull { stop ->
        val point = stop.point ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)).also {
            it.addStringProperty("name", stop.name)
            it.addStringProperty("kind", stop.kind.name)
        }
    }
    if (stopFeatures.isNotEmpty()) {
        val collection = FeatureCollection.fromFeatures(stopFeatures)
        val existing = style.getSourceAs<GeoJsonSource>(STOP_SOURCE)
        if (existing == null) {
            style.addSource(GeoJsonSource(STOP_SOURCE, collection))
            style.addLayer(
                CircleLayer(STOP_LAYER, STOP_SOURCE).withProperties(
                    circleRadius(7f),
                    circleColor("#F59E0B"),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2f),
                )
            )
        } else {
            existing.setGeoJson(collection)
        }
    }
}

@Composable
private fun MapFallback(modifier: Modifier, message: String) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(8.dp))
            Text("Map temporarily unavailable", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MapStatusBadge(message: String, modifier: Modifier = Modifier) {
    Text(
        text = "Map: $message",
        modifier = modifier.background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.labelSmall,
    )
}
