package cloud.kosch.scenicpath

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
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

// A long planning session can deliberately widen its corridor through selected POIs. Keep enough
// capacity that old route discoveries and newly reached areas can coexist instead of trading one
// marker set for another after the third/fourth waypoint.
private const val MAX_SCENIC_MARKERS = 420

/** MapLibre route host + clustered native POIs + first native live-navigation mode. */
@Composable
fun ScenicMap(
    modifier: Modifier = Modifier,
    userLocation: GeoPoint? = null,
    routePoints: List<GeoPoint> = emptyList(),
    stops: List<PlannedStop> = emptyList(),
    highlights: List<ScenePointUi> = emptyList(),
    routeDirty: Boolean = false,
    recenterToken: Int = 0,
    onToggleRouteStop: (ScenePointUi) -> Unit = {},
    onRecalculateRoute: () -> Unit = {},
    onMapError: (String) -> Unit = {},
    onPoiSearchStateChange: (loading: Boolean, count: Int) -> Unit = { _, _ -> },
    discoverPois: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var lastHandledRecenterToken by remember { mutableIntStateOf(0) }
    var initialLocationFocused by remember { mutableStateOf(false) }

    var selectedHighlight by remember { mutableStateOf<ScenePointUi?>(null) }
    var selectedDetails by remember { mutableStateOf<ScenicPoiDetails?>(null) }
    var detailsLoading by remember { mutableStateOf(false) }

    // Navigation state deliberately lives with the map so it survives ordinary POI/card changes
    // without introducing another app navigation stack.
    var navigationActive by remember { mutableStateOf(false) }
    var navigationFollow by remember { mutableStateOf(true) }
    var voiceEnabled by remember { mutableStateOf(true) }
    val liveNavigationLocation = rememberLocationUiState(navigationActive && userLocation != null)
    val navigationPoint = liveNavigationLocation.point ?: userLocation
    val navigationState = produceState<NavigationSnapshot?>(null, navigationActive, routePoints,
        navigationPoint, liveNavigationLocation.speedMetersPerSecond, liveNavigationLocation.bearingDegrees, stops) {
        value = if (navigationActive && navigationPoint != null && routePoints.size >= 2) {
            withContext(Dispatchers.Default) {
                LiveNavigationEngine.snapshot(routePoints, navigationPoint,
                    liveNavigationLocation.speedMetersPerSecond, liveNavigationLocation.bearingDegrees, stops)
            }
        } else null
    }

    val navigationSnapshot = navigationState.value

    val latestUserLocation by rememberUpdatedState(userLocation)
    val latestPoiSearchStateChange by rememberUpdatedState(onPoiSearchStateChange)
    val sharedHighlights = ScenicPoiSharedState.pointsFor(routePoints)
    val activeKinds = ScenicSceneSelectionState.activeKinds
    val plannedStopIds = remember(stops) { stops.mapTo(mutableSetOf()) { it.id } }
    val plannedHighlights = remember(stops) {
        stops.mapNotNull { stop ->
            stop.point?.let { point ->
                ScenePointUi(
                    id = stop.id,
                    name = stop.name,
                    kind = stop.kind.name,
                    subtype = stop.subtype,
                    point = point,
                    relevance = 1.25,
                    suggestionScore = 250.0,
                    distanceFromRouteMeters = 0,
                    suggestedDwellMinutes = stop.dwellMinutes,
                    rating = stop.rating,
                    ratingCount = stop.ratingCount,
                    includedInRoute = true,
                    personalMatch = 100.0,
                    rationale = "fixed waypoint · must visit",
                    estimatedDetourMinutes = 0.0,
                )
            }
        }
    }

    val visibleHighlights = remember(sharedHighlights, plannedHighlights, plannedStopIds, activeKinds) {
        buildList {
            addAll(plannedHighlights)
            sharedHighlights.forEach { point ->
                val enabled = point.kind == StopKind.SCENIC.name || activeKinds.any { it.name == point.kind }
                if (enabled && point.id !in plannedStopIds) add(point)
            }
        }.take(MAX_SCENIC_MARKERS)
    }

    LaunchedEffect(selectedHighlight?.id) {
        val selected = selectedHighlight
        if (selected == null) {
            selectedDetails = null
            detailsLoading = false
        } else {
            selectedDetails = ScenicPoiDetails(
                rating = selected.rating,
                ratingCount = selected.ratingCount,
                ratingSource = if (selected.rating != null) selected.attribution else null,
                openNow = selected.openNow,
            )
            detailsLoading = true
            val resolved = optionalRequest { PoiDetailsResolver.resolve(selected) }
                ?: selectedDetails ?: ScenicPoiDetails()
            if (selectedHighlight?.id == selected.id) {
                selectedDetails = resolved
                detailsLoading = false
            }
        }
    }

    // Preserve discoveries on reroutes, but cancel network work when the app is backgrounded.
    LaunchedEffect(routePoints, lifecycleOwner, discoverPois, activeKinds) {
        selectedHighlight = null
        if (routePoints.size < 2) {
            navigationActive = false
            ScenicPoiSharedState.clear()
            latestPoiSearchStateChange(false, 0)
            return@LaunchedEffect
        }
        val epoch = ScenicPoiSharedState.epoch()
        ScenicPoiSharedState.publish(routePoints, highlights, epoch)
        if (!discoverPois) {
            latestPoiSearchStateChange(false, ScenicPoiSharedState.pointsFor(routePoints).size)
            return@LaunchedEffect
        }
        latestPoiSearchStateChange(true, ScenicPoiSharedState.pointsFor(routePoints).size)
        var completed = false
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (completed) return@repeatOnLifecycle
            val enabledKinds = activeKinds
            val updates = PoiUpdateBuffer()
            val publisher = launch(Dispatchers.Default) {
                updates.consume {
                    ScenicPoiSharedState.publish(routePoints, it, epoch)
                    withContext(Dispatchers.Main.immediate) {
                        latestPoiSearchStateChange(true, ScenicPoiSharedState.pointsFor(routePoints).size)
                    }
                }
            }
            try {
                coroutineScope {
                    launch(Dispatchers.IO) {
                        updates.submit(optionalRequest {
                            RapidRoutePoiDiscovery.discover(routePoints, enabledKinds, 220, onPartial = updates::submit)
                        }.orEmpty())
                    }
                    launch(Dispatchers.IO) {
                        updates.submit(optionalRequest {
                            FastRoutePoiDiscovery.discover(routePoints, enabledKinds, 220,
                                completeRoute = true, onPartial = updates::submit)
                        }.orEmpty())
                    }
                    launch(Dispatchers.IO) {
                        updates.submit(optionalRequest {
                            PrecisionRoutePoiDiscovery.discover(routePoints, enabledKinds, MAX_SCENIC_MARKERS,
                                radiusMeters = 15_000, maxSamples = 10, onPartial = updates::submit)
                        }.orEmpty())
                    }
                }
                updates.close()
                publisher.join()
                completed = true
            } finally {
                updates.close()
                publisher.cancel()
            }
        }
        latestPoiSearchStateChange(false, ScenicPoiSharedState.pointsFor(routePoints).size)
    }
    val latestHighlights by rememberUpdatedState(visibleHighlights)
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val mapView = remember(context) {
        runCatching { MapView(context).also { it.onCreate(null) } }
            .onFailure {
                mapError = it.message ?: "Map could not be created"
                onMapError(mapError!!)
            }
            .getOrNull()
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
            if (resumed) { runCatching { mapView.onPause() }; resumed = false }
        }
        fun stopIfNeeded() {
            pauseIfNeeded()
            if (started) { runCatching { mapView.onStop() }; started = false }
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
        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() { mapView.onLowMemory() }
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory()
            }
        }
        context.registerComponentCallbacks(memoryCallbacks)
        onDispose {
            disposed.set(true)
            mapRef = null
            styleLoaded = false
            context.unregisterComponentCallbacks(memoryCallbacks)
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
                    view.getMapAsync { map ->
                        if (disposed.get()) return@getMapAsync
                        mapRef = map
                        map.uiSettings.isCompassEnabled = true
                        map.uiSettings.isAttributionEnabled = true
                        map.uiSettings.isLogoEnabled = true
                        map.addOnMapClickListener { coordinate ->
                            val hit = map.queryRenderedFeatures(map.projection.toScreenLocation(coordinate),
                                ScenicMapPois.STOPS_LAYER, ScenicMapPois.LAYER, ScenicMapPois.CLUSTERS).firstOrNull()
                            if (hit?.hasProperty("point_count") == true) {
                                selectedHighlight = null
                                val center = hit.geometry() as? Point
                                // Avoid native cluster-id lookups racing with a source replacement.
                                if (center != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                    LatLng(center.latitude(), center.longitude()), (map.cameraPosition.zoom + 2.0).coerceAtMost(16.0)), 450)
                                true
                            } else {
                                val id = hit?.getStringProperty("poi_id")
                                selectedHighlight = latestHighlights.firstOrNull { it.id == id }
                                selectedHighlight != null
                            }
                        }
                        runCatching {
                            map.setStyle(BuildConfig.MAP_STYLE_URL) { style ->
                                if (!disposed.get()) {
                                    runCatching {
                                        ensureBaseLayers(style)
                                        ScenicMapPois.install(style)
                                        styleLoaded = true
                                    }.onFailure { error ->
                                        mapError = error.message ?: "Map layers could not be loaded"
                                        onMapError(mapError!!)
                                    }
                                }
                            }
                        }.onFailure { error ->
                            mapError = error.message ?: "Map style failed"
                            onMapError(mapError!!)
                        }
                    }
                }
            },
        )

        if (!styleLoaded && mapError == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        mapError?.let { MapStatusBadge(it, Modifier.align(Alignment.BottomStart).padding(12.dp)) }

        // Navigation can be started directly from the route map. In active mode it switches to a
        // driver-focused HUD and follows GPS with route bearing/tilt while POIs remain visible.
        if (!navigationActive && routePoints.size >= 2 && userLocation != null) {
            ExtendedFloatingActionButton(
                onClick = { navigationActive = true; navigationFollow = true; selectedHighlight = null },
                icon = { Icon(Icons.Default.Navigation, null) },
                text = { Text("Navigate") },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
            )
        }
        if (navigationActive && navigationSnapshot != null) {
            NavigationVoiceGuide(navigationSnapshot, voiceEnabled)
            LiveNavigationHud(
                snapshot = navigationSnapshot,
                voiceEnabled = voiceEnabled,
                onVoiceToggle = { voiceEnabled = !voiceEnabled },
                onOverview = {
                    navigationFollow = false
                    runCatching {
                        val bounds = LatLngBounds.Builder().apply {
                            routePoints.forEach { include(LatLng(it.lat, it.lon)) }
                        }.build()
                        mapRef?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96), 650)
                    }
                },
                onFollow = { navigationFollow = true },
                onReroute = onRecalculateRoute,
                onStop = { navigationActive = false; navigationFollow = false },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 12.dp, end = 12.dp, top = 138.dp)
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
            )
        }

        selectedHighlight?.let { highlight ->
            ScenicLocationDetailsCard(
                highlight = highlight,
                details = selectedDetails ?: ScenicPoiDetails(
                    rating = highlight.rating,
                    ratingCount = highlight.ratingCount,
                    ratingSource = if (highlight.rating != null) highlight.attribution else null,
                    openNow = highlight.openNow,
                ),
                detailsLoading = detailsLoading,
                isPlannedStop = highlight.id in plannedStopIds,
                routeDirty = routeDirty,
                onClose = { selectedHighlight = null },
                onOpenUrl = { url -> openExternal(context, url) },
                onCall = { phone -> openExternal(context, "tel:${Uri.encode(phone)}") },
                onEmail = { email -> openExternal(context, "mailto:${Uri.encode(email)}") },
                onOpenOsm = {
                    openExternal(context, "https://www.openstreetmap.org/?mlat=${highlight.point.lat}&mlon=${highlight.point.lon}#map=17/${highlight.point.lat}/${highlight.point.lon}")
                },
                onTogglePlannedStop = { onToggleRouteStop(highlight) },
                onRecalculateRoute = onRecalculateRoute,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp).fillMaxWidth().widthIn(max = 430.dp),
            )
        }
    }

    LaunchedEffect(userLocation, styleLoaded) {
        if (styleLoaded) {
            withContext(Dispatchers.Main.immediate) {
                val source = mapRef?.style?.getSourceAs<GeoJsonSource>(USER_SOURCE)
                if (userLocation != null) source?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(userLocation.lon, userLocation.lat)))
                else source?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
            }
        }
    }
    LaunchedEffect(routePoints, styleLoaded) {
        if (styleLoaded) {
            val data = withContext(Dispatchers.Default) {
                if (routePoints.size >= 2) FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(
                    LineString.fromLngLats(routePoints.map { Point.fromLngLat(it.lon, it.lat) }))))
                else FeatureCollection.fromFeatures(emptyArray<Feature>())
            }
            withContext(Dispatchers.Main.immediate) {
                mapRef?.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(data)
            }
        }
    }
    LaunchedEffect(visibleHighlights, styleLoaded) {
        if (styleLoaded) {
            val (pois, fixed) = withContext(Dispatchers.Default) { ScenicMapPois.features(visibleHighlights) }
            withContext(Dispatchers.Main.immediate) {
                mapRef?.style?.let { style ->
                    style.getSourceAs<GeoJsonSource>(ScenicMapPois.SOURCE)?.setGeoJson(pois)
                    style.getSourceAs<GeoJsonSource>(ScenicMapPois.STOPS_SOURCE)?.setGeoJson(fixed)
                }
            }
        }
    }
    LaunchedEffect(userLocation, routePoints, styleLoaded) {
        if (styleLoaded && routePoints.size < 2 && !initialLocationFocused) {
            userLocation?.let { point ->
                mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 10.5), 650)
                initialLocationFocused = true
            }
        }
    }
    LaunchedEffect(routePoints, styleLoaded, navigationActive) {
        if (styleLoaded && routePoints.size >= 2 && !navigationActive) {
            runCatching {
                val bounds = LatLngBounds.Builder().apply { routePoints.forEach { include(LatLng(it.lat, it.lon)) } }.build()
                mapRef?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96), 650)
            }.onFailure { onMapError(it.message ?: "Route overview failed") }
        }
    }
    LaunchedEffect(recenterToken, styleLoaded) {
        if (styleLoaded && recenterToken > lastHandledRecenterToken) {
            latestUserLocation?.let { point ->
                navigationFollow = navigationActive
                mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 15.2), 700)
                lastHandledRecenterToken = recenterToken
            }
        }
    }
    LaunchedEffect(
        navigationActive,
        navigationFollow,
        navigationPoint,
        navigationSnapshot?.routeBearing,
        styleLoaded,
    ) {
        if (navigationActive && navigationFollow && styleLoaded && navigationPoint != null && navigationSnapshot != null) {
            val camera = CameraPosition.Builder()
                .target(LatLng(navigationPoint.lat, navigationPoint.lon))
                .zoom(16.6)
                .bearing(navigationSnapshot.routeBearing)
                .tilt(48.0)
                .build()
            mapRef?.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 700)
        }
    }
}

private fun ensureBaseLayers(style: Style) {
    val empty = FeatureCollection.fromFeatures(emptyArray<Feature>())
    if (style.getSource(USER_SOURCE) == null) style.addSource(GeoJsonSource(USER_SOURCE, empty))
    if (style.getLayer(USER_LAYER) == null) {
        style.addLayer(
            CircleLayer(USER_LAYER, USER_SOURCE).withProperties(
                circleRadius(8f), circleColor("#1769E0"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
            )
        )
    }
    if (style.getSource(ROUTE_SOURCE) == null) style.addSource(GeoJsonSource(ROUTE_SOURCE, empty))
    if (style.getLayer(ROUTE_LAYER) == null) {
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                lineColor("#1769E0"), lineWidth(6f), lineOpacity(0.92f)
            )
        )
    }
}

private fun openExternal(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun MapFallback(modifier: Modifier, message: String) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Map, null, Modifier.size(42.dp))
            Spacer(Modifier.height(8.dp))
            Text("Map temporarily unavailable", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MapStatusBadge(message: String, modifier: Modifier = Modifier) {
    Text(
        "Map: $message",
        modifier.background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.labelSmall,
    )
}
