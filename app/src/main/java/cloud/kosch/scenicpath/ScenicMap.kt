package cloud.kosch.scenicpath

import android.content.Context
import android.content.Intent
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
import kotlin.math.hypot
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
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
private const val HIGHLIGHT_SOURCE = "scenic-highlight-source"
private const val HIGHLIGHT_LAYER = "scenic-highlight-layer"
private const val HIGHLIGHT_SYMBOL_LAYER = "scenic-highlight-symbol-layer"

/**
 * MapLibre host with stable lifecycle, GPS recentering and interactive scenic POIs.
 *
 * v0.4.3 keeps route-provided POIs and independently enriched map POIs together. Route
 * rendering never depends on the discovery service finishing successfully.
 */
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
    var localHighlights by remember { mutableStateOf<List<ScenePointUi>>(emptyList()) }
    var selectedHighlight by remember { mutableStateOf<ScenePointUi?>(null) }
    val latestUserLocation by rememberUpdatedState(userLocation)

    val visibleHighlights = remember(highlights, localHighlights) {
        buildList {
            addAll(highlights)
            localHighlights.forEach { candidate ->
                val duplicate = any { existing ->
                    existing.id == candidate.id ||
                        existing.name.equals(candidate.name, ignoreCase = true)
                }
                if (!duplicate) add(candidate)
            }
        }.take(36)
    }
    val latestVisibleHighlights by rememberUpdatedState(visibleHighlights)

    // Map enrichment is intentionally separate from route generation. A successful route
    // appears immediately; POIs can arrive moments later without replacing or blanking it.
    LaunchedEffect(routePoints, highlights) {
        selectedHighlight = null
        if (routePoints.size < 2 || !BuildConfig.DEBUG) {
            localHighlights = emptyList()
            return@LaunchedEffect
        }
        if (highlights.size >= 18) {
            localHighlights = emptyList()
            return@LaunchedEffect
        }

        localHighlights = emptyList()
        val quick = runCatching {
            PhotonSceneFallback.discover(
                route = routePoints,
                enabledKinds = prototypeSelectableSceneKinds,
                maxResults = 30,
                fast = true,
            )
        }.getOrElse { emptyList() }

        val enriched = if (quick.isNotEmpty()) {
            quick
        } else {
            runCatching {
                OsmSceneDiscovery.discover(
                    route = routePoints,
                    enabledKinds = prototypeSelectableSceneKinds,
                    maxResults = 30,
                )
            }.getOrElse { emptyList() }
        }

        localHighlights = enriched.filterNot { candidate ->
            highlights.any { existing ->
                existing.id == candidate.id || existing.name.equals(candidate.name, ignoreCase = true)
            }
        }
    }

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
                            map.addOnMapClickListener { latLng ->
                                val tap = map.projection.toScreenLocation(latLng)
                                val hit = latestVisibleHighlights
                                    .mapNotNull { highlight ->
                                        val marker = map.projection.toScreenLocation(
                                            LatLng(highlight.point.lat, highlight.point.lon)
                                        )
                                        val distance = hypot(
                                            (marker.x - tap.x).toDouble(),
                                            (marker.y - tap.y).toDouble(),
                                        )
                                        if (distance <= 44.0) highlight to distance else null
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
                                map.setStyle(BuildConfig.MAP_STYLE_URL) {
                                    styleLoaded = true
                                    updateMapData(map, userLocation, routePoints, stops, visibleHighlights)
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
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
            )
        }
    }

    LaunchedEffect(userLocation, routePoints, stops, visibleHighlights, styleLoaded) {
        if (styleLoaded) {
            mapRef?.let { updateMapData(it, userLocation, routePoints, stops, visibleHighlights) }
        }
    }

    // Before the first route, focus the map on the first reliable live GPS position once.
    LaunchedEffect(userLocation, routePoints, styleLoaded) {
        if (styleLoaded && routePoints.size < 2 && !initialLocationFocused) {
            userLocation?.let { point ->
                mapRef?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 10.5),
                    650,
                )
                initialLocationFocused = true
            }
        }
    }

    // New routes show the whole journey once, then camera control stays with the user.
    LaunchedEffect(routePoints, styleLoaded) {
        if (styleLoaded && routePoints.size >= 2) {
            runCatching {
                val bounds = LatLngBounds.Builder().apply {
                    routePoints.forEach { include(LatLng(it.lat, it.lon)) }
                }.build()
                mapRef?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96), 650)
            }.onFailure { onMapError(it.message ?: "Route overview failed") }
        }
    }

    // Recenter exactly once per explicit Locate Me tap.
    LaunchedEffect(recenterToken, styleLoaded) {
        if (styleLoaded && recenterToken > lastHandledRecenterToken) {
            latestUserLocation?.let { point ->
                mapRef?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 15.2),
                    700,
                )
                lastHandledRecenterToken = recenterToken
            }
        }
    }
}

private fun updateMapData(
    map: MapLibreMap,
    userLocation: GeoPoint?,
    routePoints: List<GeoPoint>,
    stops: List<PlannedStop>,
    highlights: List<ScenePointUi>,
) {
    val style = map.style ?: return

    val userFeature = userLocation?.let { location ->
        Feature.fromGeometry(Point.fromLngLat(location.lon, location.lat))
    }
    val userSource = style.getSourceAs<GeoJsonSource>(USER_SOURCE)
    if (userSource == null && userFeature != null) {
        style.addSource(GeoJsonSource(USER_SOURCE, userFeature))
        style.addLayer(
            CircleLayer(USER_LAYER, USER_SOURCE).withProperties(
                circleRadius(8f),
                circleColor("#1769E0"),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
            )
        )
    } else if (userFeature != null) {
        userSource?.setGeoJson(userFeature)
    } else {
        userSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
    }

    val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
    if (routePoints.size >= 2) {
        val line = LineString.fromLngLats(routePoints.map { Point.fromLngLat(it.lon, it.lat) })
        if (routeSource == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE, Feature.fromGeometry(line)))
            style.addLayer(
                LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    lineColor("#1769E0"),
                    lineWidth(6f),
                    lineOpacity(0.92f),
                )
            )
        } else {
            routeSource.setGeoJson(Feature.fromGeometry(line))
        }
    } else {
        routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
    }

    val includedOrder = highlights
        .filter { it.includedInRoute }
        .mapIndexed { index, point -> point.id to (index + 1).toString() }
        .toMap()

    val highlightFeatures = highlights.map { highlight ->
        Feature.fromGeometry(Point.fromLngLat(highlight.point.lon, highlight.point.lat)).also {
            it.addStringProperty("id", highlight.id)
            it.addStringProperty("name", highlight.name)
            it.addStringProperty("kind", highlight.kind)
            it.addStringProperty("symbol", includedOrder[highlight.id] ?: mapSymbol(highlight.kind))
            it.addBooleanProperty("included", highlight.includedInRoute)
        }
    }
    val highlightSource = style.getSourceAs<GeoJsonSource>(HIGHLIGHT_SOURCE)
    if (highlightFeatures.isNotEmpty()) {
        val collection = FeatureCollection.fromFeatures(highlightFeatures)
        if (highlightSource == null) {
            style.addSource(GeoJsonSource(HIGHLIGHT_SOURCE, collection))
            style.addLayer(
                CircleLayer(HIGHLIGHT_LAYER, HIGHLIGHT_SOURCE).withProperties(
                    circleRadius(11f),
                    circleColor("#2E7D32"),
                    circleOpacity(0.92f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2.5f),
                )
            )
            style.addLayer(
                SymbolLayer(HIGHLIGHT_SYMBOL_LAYER, HIGHLIGHT_SOURCE).withProperties(
                    textField(get("symbol")),
                    textSize(12f),
                    textColor("#FFFFFF"),
                    textAllowOverlap(true),
                    textIgnorePlacement(true),
                )
            )
        } else {
            highlightSource.setGeoJson(collection)
        }
    } else {
        highlightSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
    }

    // Fixed journey stops stay visually stronger than optional discoveries.
    val stopFeatures = stops.mapNotNull { stop ->
        val point = stop.point ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)).also {
            it.addStringProperty("name", stop.name)
            it.addStringProperty("kind", stop.kind.name)
        }
    }
    val stopSource = style.getSourceAs<GeoJsonSource>(STOP_SOURCE)
    if (stopFeatures.isNotEmpty()) {
        val collection = FeatureCollection.fromFeatures(stopFeatures)
        if (stopSource == null) {
            style.addSource(GeoJsonSource(STOP_SOURCE, collection))
            style.addLayer(
                CircleLayer(STOP_LAYER, STOP_SOURCE).withProperties(
                    circleRadius(8f),
                    circleColor("#F59E0B"),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2f),
                )
            )
        } else {
            stopSource.setGeoJson(collection)
        }
    } else {
        stopSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
    }
}

private fun mapSymbol(kind: String): String = when (kind) {
    "VIEWPOINT" -> "V"
    "MUSEUM" -> "M"
    "NATURE" -> "N"
    "MONUMENT" -> "H"
    "PARK" -> "P"
    "ART" -> "A"
    "WORSHIP" -> "W"
    "WATER" -> "~"
    "FOOD" -> "F"
    "ARCHITECTURE" -> "B"
    else -> "★"
}

private fun sceneTypeLabel(highlight: ScenePointUi): String {
    val kind = StopKind.entries.firstOrNull { it.name == highlight.kind }?.label ?: "Scenic location"
    val subtype = highlight.subtype
        ?.replace('_', ' ')
        ?.replaceFirstChar { it.uppercase() }
        ?.takeIf { it.isNotBlank() && !kind.contains(it, ignoreCase = true) }
    return listOfNotNull(kind, subtype).joinToString(" · ")
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
                    Text(
                        mapSymbol(highlight.kind),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(highlight.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        sceneTypeLabel(highlight),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close location info") }
            }

            if (highlight.includedInRoute) {
                Text(
                    "✓ Automatically included in this journey",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val details = buildList {
                if (highlight.distanceFromRouteMeters > 0) add("${highlight.distanceFromRouteMeters} m from route")
                add("Suggested stop ${highlight.suggestedDwellMinutes} min")
                highlight.personalMatch?.let { add("${it.toInt()}% match") }
                highlight.rating?.let { add(String.format("%.1f★", it)) }
            }
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)

            highlight.rationale?.takeIf { it.isNotBlank() }?.let { rationale ->
                Text(
                    "Why here: $rationale",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!highlight.attribution.isNullOrBlank()) {
                Text(
                    highlight.attribution,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpenOsm, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Map, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("OpenStreetMap")
                }
                if (onOpenWebsite != null) {
                    Button(onClick = onOpenWebsite, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Website")
                    }
                }
            }
        }
    }
}

private fun openExternal(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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