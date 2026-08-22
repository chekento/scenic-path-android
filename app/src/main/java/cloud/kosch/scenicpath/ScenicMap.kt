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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
private const val STOP_SOURCE = "scenic-stop-source"
private const val STOP_LAYER = "scenic-stop-layer"
private const val MAX_SCENIC_MARKERS = 240

/** MapLibre host. The map performs the same precision discovery as Smart Stops. */
@Suppress("DEPRECATION")
@Composable
fun ScenicMap(
    modifier: Modifier = Modifier,
    userLocation: GeoPoint? = null,
    routePoints: List<GeoPoint> = emptyList(),
    stops: List<PlannedStop> = emptyList(),
    highlights: List<ScenePointUi> = emptyList(),
    recenterToken: Int = 0,
    onMapError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var lastHandledRecenterToken by remember { mutableIntStateOf(0) }
    var initialLocationFocused by remember { mutableStateOf(false) }
    var localHighlights by remember(routePoints) { mutableStateOf<List<ScenePointUi>>(emptyList()) }
    var selectedHighlight by remember { mutableStateOf<ScenePointUi?>(null) }
    val latestUserLocation by rememberUpdatedState(userLocation)
    val sharedHighlights = ScenicPoiSharedState.pointsFor(routePoints)

    val visibleHighlights = remember(highlights, sharedHighlights, localHighlights) {
        PrecisionRoutePoiDiscovery.mergeForDisplay(
            first = highlights + sharedHighlights,
            second = localHighlights,
            maxResults = MAX_SCENIC_MARKERS,
        )
    }
    val latestVisibleHighlights by rememberUpdatedState(visibleHighlights)

    LaunchedEffect(routePoints) {
        selectedHighlight = null
        if (routePoints.size < 2) {
            localHighlights = emptyList()
            return@LaunchedEffect
        }
        localHighlights = withContext(Dispatchers.IO) {
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
                                    if (distance <= 48.0) highlight to distance else null
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
                                updateBaseMapData(map, userLocation, routePoints, stops)
                                syncScenicMarkers(map, context, visibleHighlights)
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
            ScenicLocationCard(
                highlight = highlight,
                onClose = { selectedHighlight = null },
                onOpenWebsite = highlight.url?.let { url -> { openExternal(context, url) } },
                onOpenOsm = {
                    openExternal(
                        context,
                        "https://www.openstreetmap.org/?mlat=${highlight.point.lat}&mlon=${highlight.point.lon}#map=17/${highlight.point.lat}/${highlight.point.lon}",
                    )
                },
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp).fillMaxWidth().widthIn(max = 420.dp),
            )
        }
    }

    LaunchedEffect(userLocation, routePoints, stops, styleLoaded) {
        if (styleLoaded) mapRef?.let { updateBaseMapData(it, userLocation, routePoints, stops) }
    }
    LaunchedEffect(visibleHighlights, styleLoaded) {
        if (styleLoaded) mapRef?.let { syncScenicMarkers(it, context, visibleHighlights) }
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
    if (style.getSource(STOP_SOURCE) == null) style.addSource(GeoJsonSource(STOP_SOURCE, empty))
    if (style.getLayer(STOP_LAYER) == null) {
        style.addLayer(CircleLayer(STOP_LAYER, STOP_SOURCE).withProperties(
            circleRadius(9f), circleColor("#F59E0B"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f)
        ))
    }
}

private fun updateBaseMapData(map: MapLibreMap, userLocation: GeoPoint?, routePoints: List<GeoPoint>, stops: List<PlannedStop>) {
    val style = map.style ?: return
    ensureBaseLayers(style)
    val userSource = style.getSourceAs<GeoJsonSource>(USER_SOURCE)
    if (userLocation != null) userSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(userLocation.lon, userLocation.lat)))
    else userSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))

    val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
    if (routePoints.size >= 2) {
        routeSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(routePoints.map { Point.fromLngLat(it.lon, it.lat) })))
    } else routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))

    val stopFeatures = stops.mapNotNull { stop -> stop.point?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) } }
    style.getSourceAs<GeoJsonSource>(STOP_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(stopFeatures))
}

@Suppress("DEPRECATION")
private fun syncScenicMarkers(map: MapLibreMap, context: Context, highlights: List<ScenePointUi>) {
    map.removeAnnotations()
    if (highlights.isEmpty()) return
    val included = highlights.filter { it.includedInRoute }
    val includedOrder = included.mapIndexed { index, point -> point.id to (index + 1).toString() }.toMap()
    val iconFactory = IconFactory.getInstance(context)
    val cache = mutableMapOf<String, org.maplibre.android.annotations.Icon>()
    val options = highlights.take(MAX_SCENIC_MARKERS).map { highlight ->
        val number = includedOrder[highlight.id]
        val symbol = number ?: scenicCategoryLaneFor(highlight).emoji
        val includedStop = number != null
        val cacheKey = "$symbol:$includedStop"
        val icon = cache.getOrPut(cacheKey) { iconFactory.fromBitmap(createMarkerBitmap(symbol, includedStop)) }
        MarkerOptions().position(LatLng(highlight.point.lat, highlight.point.lon)).title(highlight.name).snippet(highlight.id).icon(icon)
    }
    map.addMarkers(options)
}

private fun createMarkerBitmap(symbol: String, included: Boolean): Bitmap {
    val size = if (included) 78 else 76
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (included) Color.rgb(245, 158, 11) else Color.WHITE
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (included) Color.WHITE else Color.rgb(20, 124, 82)
        style = Paint.Style.STROKE
        strokeWidth = if (included) 6f else 5f
    }
    canvas.drawCircle(center, center, center - 5f, fill)
    canvas.drawCircle(center, center, center - 5f, stroke)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (included) Color.WHITE else Color.rgb(20, 92, 65)
        textAlign = Paint.Align.CENTER
        typeface = if (included) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textSize = if (included) size * 0.42f else size * 0.50f
    }
    canvas.drawText(symbol, center, center - (text.ascent() + text.descent()) / 2f, text)
    return bitmap
}

private fun sceneTypeLabel(highlight: ScenePointUi): String {
    val category = scenicCategoryLaneFor(highlight).label
    val subtype = highlight.subtype?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
        ?.takeIf { it.isNotBlank() && !category.contains(it, ignoreCase = true) }
    return listOfNotNull(category, subtype).joinToString(" · ")
}

@Composable
private fun ScenicLocationCard(
    highlight: ScenePointUi,
    onClose: () -> Unit,
    onOpenWebsite: (() -> Unit)?,
    onOpenOsm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = if (highlight.includedInRoute) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(scenicCategoryLaneFor(highlight).emoji, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(highlight.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(sceneTypeLabel(highlight), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close location info") }
            }
            if (highlight.includedInRoute) Text("✓ Automatically included in this journey", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            val details = buildList {
                if (highlight.distanceFromRouteMeters > 0) add("${highlight.distanceFromRouteMeters} m from route")
                add("Suggested stop ${highlight.suggestedDwellMinutes} min")
                highlight.personalMatch?.let { add("${it.toInt()}% match") }
                highlight.rating?.let { add(String.format("%.1f★", it)) }
            }
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            highlight.rationale?.takeIf { it.isNotBlank() }?.let {
                Text("Why here: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!highlight.attribution.isNullOrBlank()) Text(highlight.attribution, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenOsm, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("OpenStreetMap")
                }
                if (onOpenWebsite != null) {
                    Button(onClick = onOpenWebsite, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Website")
                    }
                }
            }
        }
    }
}

private fun openExternal(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun MapFallback(modifier: Modifier, message: String) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Map, null, Modifier.size(42.dp)); Spacer(Modifier.height(8.dp))
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
