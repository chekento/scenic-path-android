package cloud.kosch.scenicpath

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
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
import kotlin.math.hypot

private const val USER_SOURCE = "scenic-user-source"
private const val USER_LAYER = "scenic-user-layer"
private const val ROUTE_SOURCE = "scenic-route-source"
private const val ROUTE_LAYER = "scenic-route-layer"
private const val MAX_SCENIC_MARKERS = 240

/**
 * MapLibre host.
 *
 * Marker population is committed display state just like the route itself. A provider timeout,
 * reroute geometry change or transient empty Compose frame must never erase a valid POI layer.
 * Manual waypoints are rendered as their original Scenic category symbol with a luminous frame;
 * they are never replaced by a generic yellow circle.
 */
@Suppress("DEPRECATION")
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
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var lastHandledRecenterToken by remember { mutableIntStateOf(0) }
    var initialLocationFocused by remember { mutableStateOf(false) }

    var localHighlights by remember { mutableStateOf<List<ScenePointUi>>(emptyList()) }
    var retainedHighlights by remember { mutableStateOf<List<ScenePointUi>>(emptyList()) }
    var selectedHighlight by remember { mutableStateOf<ScenePointUi?>(null) }
    var selectedDetails by remember { mutableStateOf<ScenicPoiDetails?>(null) }
    var detailsLoading by remember { mutableStateOf(false) }

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
        if (candidateCore.isNotEmpty()) retainedHighlights = candidateCore
    }

    val coreVisible = if (candidateCore.isNotEmpty()) candidateCore else retainedHighlights
    val visibleHighlights = remember(coreVisible, plannedHighlights, plannedStopIds) {
        buildList {
            // Planned stops win deduplication and stay visible even if a provider temporarily omits
            // them from its current route-corridor response.
            addAll(plannedHighlights)
            coreVisible.forEach { point -> if (point.id !in plannedStopIds) add(point) }
        }.take(MAX_SCENIC_MARKERS)
    }
    val latestVisibleHighlights by rememberUpdatedState(visibleHighlights)

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

    LaunchedEffect(routePoints) {
        selectedHighlight = null
        if (routePoints.size < 2) {
            localHighlights = emptyList()
            retainedHighlights = emptyList()
            mapRef?.removeAnnotations()
            return@LaunchedEffect
        }

        val discovered = withContext(Dispatchers.IO) {
            runCatching {
                val (fast, precision) = coroutineScope {
                    val fastJob = async(Dispatchers.IO) {
                        FastRoutePoiDiscovery.discover(
                            route = routePoints,
                            enabledKinds = prototypeSelectableSceneKinds,
                            maxResults = 150,
                        )
                    }
                    val precisionJob = async(Dispatchers.IO) {
                        PrecisionRoutePoiDiscovery.discover(
                            route = routePoints,
                            enabledKinds = prototypeSelectableSceneKinds,
                            maxResults = MAX_SCENIC_MARKERS,
                            radiusMeters = 15_000,
                            maxSamples = 10,
                        )
                    }
                    fastJob.await() to precisionJob.await()
                }
                PrecisionRoutePoiDiscovery.mergeForDisplay(
                    first = precision,
                    second = fast,
                    maxResults = MAX_SCENIC_MARKERS,
                )
            }.getOrElse { emptyList() }
        }

        if (discovered.isNotEmpty()) {
            localHighlights = discovered
            retainedHighlights = discovered
            ScenicPoiSharedState.publish(routePoints, discovered)
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
                    view.getMapAsync { map ->
                        mapRef = map
                        map.uiSettings.isCompassEnabled = true
                        map.uiSettings.isAttributionEnabled = true
                        map.uiSettings.isLogoEnabled = true

                        map.setOnMarkerClickListener { marker ->
                            val hit = latestVisibleHighlights.firstOrNull { it.id == marker.snippet }
                            if (hit != null) {
                                selectedHighlight = hit
                                true
                            } else false
                        }

                        map.addOnMapClickListener { latLng ->
                            val tap = map.projection.toScreenLocation(latLng)
                            val hit = latestVisibleHighlights
                                .mapNotNull { highlight ->
                                    val markerPoint = map.projection.toScreenLocation(LatLng(highlight.point.lat, highlight.point.lon))
                                    val distance = hypot(
                                        (markerPoint.x - tap.x).toDouble(),
                                        (markerPoint.y - tap.y).toDouble(),
                                    )
                                    if (distance <= 52.0) highlight to distance else null
                                }
                                .minByOrNull { it.second }
                                ?.first
                            if (hit != null) {
                                selectedHighlight = hit
                                true
                            } else {
                                selectedHighlight = null
                                false
                            }
                        }

                        runCatching {
                            map.setStyle(BuildConfig.MAP_STYLE_URL) { style ->
                                ensureBaseLayers(style)
                                styleLoaded = true
                                updateBaseMapData(map, userLocation, routePoints)
                                syncScenicMarkers(map, context, visibleHighlights, plannedStopIds)
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
                    openExternal(
                        context,
                        "https://www.openstreetmap.org/?mlat=${highlight.point.lat}&mlon=${highlight.point.lon}#map=17/${highlight.point.lat}/${highlight.point.lon}",
                    )
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
    LaunchedEffect(visibleHighlights, plannedStopIds, styleLoaded) {
        if (styleLoaded && visibleHighlights.isNotEmpty()) {
            mapRef?.let { syncScenicMarkers(it, context, visibleHighlights, plannedStopIds) }
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
    LaunchedEffect(routePoints, styleLoaded) {
        if (styleLoaded && routePoints.size >= 2) {
            runCatching {
                val bounds = LatLngBounds.Builder().apply { routePoints.forEach { include(LatLng(it.lat, it.lon)) } }.build()
                mapRef?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96), 650)
            }.onFailure { onMapError(it.message ?: "Route overview failed") }
        }
    }
    LaunchedEffect(recenterToken, styleLoaded) {
        if (styleLoaded && recenterToken > lastHandledRecenterToken) {
            latestUserLocation?.let { point ->
                mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 15.2), 700)
                lastHandledRecenterToken = recenterToken
            }
        }
    }
}

private fun ensureBaseLayers(style: Style) {
    val empty = FeatureCollection.fromFeatures(emptyArray<Feature>())
    if (style.getSource(USER_SOURCE) == null) style.addSource(GeoJsonSource(USER_SOURCE, empty))
    if (style.getLayer(USER_LAYER) == null) {
        style.addLayer(CircleLayer(USER_LAYER, USER_SOURCE).withProperties(
            circleRadius(8f), circleColor("#1769E0"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
        ))
    }
    if (style.getSource(ROUTE_SOURCE) == null) style.addSource(GeoJsonSource(ROUTE_SOURCE, empty))
    if (style.getLayer(ROUTE_LAYER) == null) {
        style.addLayer(LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            lineColor("#1769E0"), lineWidth(6f), lineOpacity(0.92f)
        ))
    }
}

private fun updateBaseMapData(map: MapLibreMap, userLocation: GeoPoint?, routePoints: List<GeoPoint>) {
    val style = map.style ?: return
    ensureBaseLayers(style)

    val userSource = style.getSourceAs<GeoJsonSource>(USER_SOURCE)
    if (userLocation != null) {
        userSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(userLocation.lon, userLocation.lat)))
    } else {
        userSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
    }

    val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
    if (routePoints.size >= 2) {
        routeSource?.setGeoJson(
            Feature.fromGeometry(LineString.fromLngLats(routePoints.map { Point.fromLngLat(it.lon, it.lat) }))
        )
    } else {
        routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
    }
}

@Suppress("DEPRECATION")
private fun syncScenicMarkers(
    map: MapLibreMap,
    context: Context,
    highlights: List<ScenePointUi>,
    plannedStopIds: Set<String>,
) {
    if (highlights.isEmpty()) return
    map.removeAnnotations()

    val iconFactory = IconFactory.getInstance(context)
    val cache = mutableMapOf<String, org.maplibre.android.annotations.Icon>()
    val options = highlights.take(MAX_SCENIC_MARKERS).map { highlight ->
        val symbol = scenicCategoryLaneFor(highlight).emoji
        val emphasized = highlight.includedInRoute || highlight.id in plannedStopIds
        val cacheKey = "$symbol:$emphasized"
        val icon = cache.getOrPut(cacheKey) { iconFactory.fromBitmap(createMarkerBitmap(symbol, emphasized)) }
        MarkerOptions()
            .position(LatLng(highlight.point.lat, highlight.point.lon))
            .title(highlight.name)
            .snippet(highlight.id)
            .icon(icon)
    }
    map.addMarkers(options)
}

/**
 * Normal candidates keep the established white/green marker. A route waypoint keeps that same
 * category emoji but receives a bright multi-ring halo, so selection is obvious without losing
 * the semantic meaning of 🍽️ / 🏛️ / 🏰 / 👁️ / etc.
 */
private fun createMarkerBitmap(symbol: String, emphasized: Boolean): Bitmap {
    val size = if (emphasized) 94 else 76
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f

    if (emphasized) {
        val outerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(72, 91, 246, 196)
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        val middleGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 35, 220, 166)
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        val brightRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(124, 255, 215)
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        canvas.drawCircle(center, center, center - 8f, outerGlow)
        canvas.drawCircle(center, center, center - 12f, middleGlow)
        canvas.drawCircle(center, center, center - 16f, brightRing)
    }

    val markerRadius = if (emphasized) center - 18f else center - 5f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 124, 82)
        style = Paint.Style.STROKE
        strokeWidth = if (emphasized) 5.5f else 5f
    }
    canvas.drawCircle(center, center, markerRadius, fill)
    canvas.drawCircle(center, center, markerRadius, stroke)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 92, 65)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        textSize = if (emphasized) 42f else size * 0.50f
    }
    canvas.drawText(symbol, center, center - (text.ascent() + text.descent()) / 2f, text)
    return bitmap
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
