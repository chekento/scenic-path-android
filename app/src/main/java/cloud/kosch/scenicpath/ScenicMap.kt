package cloud.kosch.scenicpath

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
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
import kotlin.math.roundToInt

private const val USER_SOURCE = "scenic-user-source"
private const val USER_LAYER = "scenic-user-layer"
private const val ROUTE_SOURCE = "scenic-route-source"
private const val ROUTE_LAYER = "scenic-route-layer"
private const val MAX_SCENIC_MARKERS = 240

/** MapLibre route host + durable Compose POIs + first native live-navigation mode. */
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var lastHandledRecenterToken by remember { mutableIntStateOf(0) }
    var initialLocationFocused by remember { mutableStateOf(false) }
    var cameraRevision by remember { mutableIntStateOf(0) }

    var localHighlights by remember { mutableStateOf<List<ScenePointUi>>(emptyList()) }
    var retainedHighlights by remember { mutableStateOf<List<ScenePointUi>>(emptyList()) }
    var retainedJourneyKey by remember { mutableStateOf<String?>(null) }
    var selectedHighlight by remember { mutableStateOf<ScenePointUi?>(null) }
    var selectedDetails by remember { mutableStateOf<ScenicPoiDetails?>(null) }
    var detailsLoading by remember { mutableStateOf(false) }

    // Navigation state deliberately lives with the map so it survives ordinary POI/card changes
    // without introducing another app navigation stack.
    var navigationActive by remember { mutableStateOf(false) }
    var navigationFollow by remember { mutableStateOf(true) }
    var voiceEnabled by remember { mutableStateOf(true) }
    val liveNavigationLocation = rememberLocationUiState(userLocation != null)
    val navigationPoint = liveNavigationLocation.point ?: userLocation
    val navigationSnapshot = remember(
        routePoints,
        navigationPoint,
        liveNavigationLocation.speedMetersPerSecond,
        liveNavigationLocation.bearingDegrees,
        stops,
    ) {
        navigationPoint?.takeIf { routePoints.size >= 2 }?.let { point ->
            LiveNavigationEngine.snapshot(
                route = routePoints,
                location = point,
                speedMetersPerSecond = liveNavigationLocation.speedMetersPerSecond,
                gpsBearingDegrees = liveNavigationLocation.bearingDegrees,
                stops = stops,
            )
        }
    }

    val latestUserLocation by rememberUpdatedState(userLocation)
    val sharedHighlights = ScenicPoiSharedState.pointsFor(routePoints)
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

    val candidateCore = remember(highlights, sharedHighlights, localHighlights) {
        PrecisionRoutePoiDiscovery.mergeForDisplay(
            first = highlights + sharedHighlights,
            second = localHighlights,
            maxResults = MAX_SCENIC_MARKERS,
        )
    }
    LaunchedEffect(candidateCore) {
        if (candidateCore.isNotEmpty()) {
            retainedHighlights = PrecisionRoutePoiDiscovery.mergeForDisplay(
                first = candidateCore,
                second = retainedHighlights,
                maxResults = MAX_SCENIC_MARKERS,
            )
        }
    }
    val coreVisible = remember(candidateCore, retainedHighlights) {
        PrecisionRoutePoiDiscovery.mergeForDisplay(candidateCore, retainedHighlights, MAX_SCENIC_MARKERS)
    }
    val visibleHighlights = remember(coreVisible, plannedHighlights, plannedStopIds) {
        buildList {
            addAll(plannedHighlights)
            coreVisible.forEach { point -> if (point.id !in plannedStopIds) add(point) }
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
            val resolved = runCatching { PoiDetailsResolver.resolve(selected) }.getOrElse {
                selectedDetails ?: ScenicPoiDetails()
            }
            if (selectedHighlight?.id == selected.id) {
                selectedDetails = resolved
                detailsLoading = false
            }
        }
    }

    // Route-wide multi-provider POI population. Any provider can paint first; later results enrich.
    LaunchedEffect(routePoints) {
        selectedHighlight = null
        if (routePoints.size < 2) {
            navigationActive = false
            return@LaunchedEffect
        }

        val journeyKey = routeJourneyKey(routePoints)
        if (retainedJourneyKey != null && retainedJourneyKey != journeyKey) {
            localHighlights = emptyList()
            retainedHighlights = emptyList()
        }
        retainedJourneyKey = journeyKey
        val enabledKinds = prototypeSelectableSceneKinds.ifEmpty { allSelectableSceneKinds }

        suspend fun commit(points: List<ScenePointUi>) {
            if (points.isEmpty()) return
            withContext(Dispatchers.Main.immediate) {
                localHighlights = PrecisionRoutePoiDiscovery.mergeForDisplay(points, localHighlights, MAX_SCENIC_MARKERS)
                retainedHighlights = PrecisionRoutePoiDiscovery.mergeForDisplay(points, retainedHighlights, MAX_SCENIC_MARKERS)
                ScenicPoiSharedState.publish(routePoints, retainedHighlights)
            }
        }

        coroutineScope {
            launch(Dispatchers.IO) {
                commit(runCatching {
                    RapidRoutePoiDiscovery.discover(routePoints, enabledKinds, 150)
                }.getOrElse { emptyList() })
            }
            launch(Dispatchers.IO) {
                commit(runCatching {
                    FastRoutePoiDiscovery.discover(routePoints, enabledKinds, 150)
                }.getOrElse { emptyList() })
            }
            launch(Dispatchers.IO) {
                commit(runCatching {
                    PrecisionRoutePoiDiscovery.discover(
                        route = routePoints,
                        enabledKinds = enabledKinds,
                        maxResults = MAX_SCENIC_MARKERS,
                        radiusMeters = 15_000,
                        maxSamples = 10,
                    )
                }.getOrElse { emptyList() })
            }
        }
    }

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
                    view.getMapAsync { map ->
                        mapRef = map
                        map.uiSettings.isCompassEnabled = true
                        map.uiSettings.isAttributionEnabled = true
                        map.uiSettings.isLogoEnabled = true
                        map.addOnCameraMoveListener { cameraRevision++ }
                        map.addOnCameraIdleListener { cameraRevision++ }
                        map.addOnMapClickListener { selectedHighlight = null; false }
                        runCatching {
                            map.setStyle(BuildConfig.MAP_STYLE_URL) { style ->
                                ensureBaseLayers(style)
                                styleLoaded = true
                                updateBaseMapData(map, userLocation, routePoints)
                                cameraRevision++
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

        // Durable category markers, projected through the live map camera.
        val revision = cameraRevision
        val map = mapRef
        if (styleLoaded && map != null && visibleHighlights.isNotEmpty()) {
            val width = mapView.width
            val height = mapView.height
            val normalHalf = with(density) { 22.dp.roundToPx() }
            val emphasizedHalf = with(density) { 27.dp.roundToPx() }
            visibleHighlights.forEach { highlight ->
                key(highlight.id) {
                    val screen = runCatching {
                        map.projection.toScreenLocation(LatLng(highlight.point.lat, highlight.point.lon))
                    }.getOrNull()
                    val emphasized = highlight.includedInRoute || highlight.id in plannedStopIds
                    val half = if (emphasized) emphasizedHalf else normalHalf
                    if (screen != null && width > 0 && height > 0 &&
                        screen.x >= -half && screen.x <= width + half &&
                        screen.y >= -half && screen.y <= height + half
                    ) {
                        ScenicPoiOverlayMarker(
                            symbol = scenicCategoryLaneFor(highlight).emoji,
                            emphasized = emphasized,
                            onClick = { selectedHighlight = highlight },
                            modifier = Modifier.offset {
                                IntOffset(screen.x.roundToInt() - half, screen.y.roundToInt() - half)
                            },
                        )
                    }
                }
            }
            @Suppress("UNUSED_VARIABLE") val keepProjectionReactive = revision
        }

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

    LaunchedEffect(userLocation, routePoints, styleLoaded) {
        if (styleLoaded) mapRef?.let { updateBaseMapData(it, userLocation, routePoints) }
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

@Composable
private fun ScenicPoiOverlayMarker(
    symbol: String,
    emphasized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outerSize = if (emphasized) 54.dp else 44.dp
    val innerSize = if (emphasized) 40.dp else 40.dp
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(outerSize)
            .then(
                if (emphasized) Modifier
                    .shadow(12.dp, CircleShape, clip = false)
                    .background(primary.copy(alpha = 0.18f), CircleShape)
                    .border(2.dp, primary.copy(alpha = 0.65f), CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(innerSize)
                .shadow(if (emphasized) 5.dp else 2.dp, CircleShape)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(if (emphasized) 3.dp else 2.dp, primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, fontSize = 20.sp, fontWeight = FontWeight.Normal, maxLines = 1)
        }
    }
}

private fun routeJourneyKey(route: List<GeoPoint>): String? {
    if (route.size < 2) return null
    fun bucket(value: Double): Int = (value * 10_000.0).roundToInt()
    val first = route.first()
    val last = route.last()
    return "${bucket(first.lat)},${bucket(first.lon)}:${bucket(last.lat)},${bucket(last.lon)}"
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

private fun updateBaseMapData(map: MapLibreMap, userLocation: GeoPoint?, routePoints: List<GeoPoint>) {
    val style = map.style ?: return
    ensureBaseLayers(style)
    val userSource = style.getSourceAs<GeoJsonSource>(USER_SOURCE)
    if (userLocation != null) userSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(userLocation.lon, userLocation.lat)))
    else userSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))

    val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
    if (routePoints.size >= 2) {
        routeSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(routePoints.map { Point.fromLngLat(it.lon, it.lat) })))
    } else routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
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
