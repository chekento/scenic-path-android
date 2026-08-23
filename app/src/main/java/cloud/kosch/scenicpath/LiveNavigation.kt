package cloud.kosch.scenicpath

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * First native live-navigation layer for Scenic Path.
 *
 * It follows the committed route geometry and deliberately does not invent street names or turn
 * instructions that are not present in the current routing model. The HUD is already useful on a
 * real drive: route progress, remaining distance/time, speed, heading, off-route distance and the
 * next fixed Scenic POI are derived continuously from GPS. Provider maneuvers can be plugged into
 * the same model later without replacing the UI.
 */
data class NavigationSnapshot(
    val routeIndex: Int,
    val progress: Double,
    val remainingMeters: Double,
    val etaMinutes: Int,
    val offRouteMeters: Double,
    val routeBearing: Double,
    val speedKmh: Int,
    val nextStop: PlannedStop?,
    val nextStopDistanceMeters: Double?,
    val arrived: Boolean,
) {
    val offRoute: Boolean get() = offRouteMeters > 90.0
}

object LiveNavigationEngine {
    fun snapshot(
        route: List<GeoPoint>,
        location: GeoPoint,
        speedMetersPerSecond: Float?,
        gpsBearingDegrees: Float?,
        stops: List<PlannedStop>,
    ): NavigationSnapshot {
        if (route.size < 2) {
            return NavigationSnapshot(0, 0.0, 0.0, 0, 0.0, 0.0, 0, null, null, true)
        }

        val nearestIndex = route.indices.minByOrNull { haversineMeters(route[it], location) } ?: 0
        val offRoute = haversineMeters(route[nearestIndex], location)
        val cumulative = cumulativeMeters(route)
        val total = cumulative.last().coerceAtLeast(1.0)
        val progressMeters = cumulative[nearestIndex]
        val remaining = (total - progressMeters).coerceAtLeast(0.0)
        val progress = (progressMeters / total).coerceIn(0.0, 1.0)

        val routeBearing = when {
            nearestIndex < route.lastIndex -> bearing(route[nearestIndex], route[nearestIndex + 1])
            nearestIndex > 0 -> bearing(route[nearestIndex - 1], route[nearestIndex])
            else -> gpsBearingDegrees?.toDouble() ?: 0.0
        }
        val speedMps = speedMetersPerSecond?.takeIf { it >= 1.5f }?.toDouble()
            ?: 16.67
        val etaMinutes = (remaining / speedMps / 60.0).roundToInt().coerceAtLeast(0)
        val speedKmh = ((speedMetersPerSecond ?: 0f) * 3.6f).roundToInt().coerceAtLeast(0)

        val stopProgress = stops.mapNotNull { stop ->
            val point = stop.point ?: return@mapNotNull null
            val index = route.indices.minByOrNull { haversineMeters(route[it], point) } ?: return@mapNotNull null
            Triple(stop, index, cumulative[index])
        }
        val next = stopProgress
            .filter { (_, index, _) -> index >= nearestIndex - 2 }
            .minByOrNull { (_, index, _) -> index }
        val nextDistance = next?.let { (_, _, meters) -> (meters - progressMeters).coerceAtLeast(0.0) }

        return NavigationSnapshot(
            routeIndex = nearestIndex,
            progress = progress,
            remainingMeters = remaining,
            etaMinutes = etaMinutes,
            offRouteMeters = offRoute,
            routeBearing = gpsBearingDegrees?.toDouble()?.takeIf { speedKmh >= 8 } ?: routeBearing,
            speedKmh = speedKmh,
            nextStop = next?.first,
            nextStopDistanceMeters = nextDistance,
            arrived = remaining < 80.0,
        )
    }

    private fun cumulativeMeters(route: List<GeoPoint>): List<Double> {
        val out = ArrayList<Double>(route.size)
        var sum = 0.0
        out += 0.0
        for (i in 1 until route.size) {
            sum += haversineMeters(route[i - 1], route[i])
            out += sum
        }
        return out
    }

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earth * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

@Composable
fun LiveNavigationHud(
    snapshot: NavigationSnapshot,
    voiceEnabled: Boolean,
    onVoiceToggle: () -> Unit,
    onOverview: () -> Unit,
    onFollow: () -> Unit,
    onReroute: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nextStop = snapshot.nextStop
    val nextDistance = snapshot.nextStopDistanceMeters
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (snapshot.offRoute) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (snapshot.offRoute) Icons.Default.ReportProblem else Icons.Default.Navigation, null)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                snapshot.arrived -> "Destination reached"
                                snapshot.offRoute -> "Off route · ${formatDistance(snapshot.offRouteMeters)}"
                                nextStop != null -> "${nextStop.kind.emoji} ${nextStop.name}"
                                else -> "Continue on Scenic Path"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Text(
                            if (nextDistance != null) "Next Scenic stop in ${formatDistance(nextDistance)}"
                            else "Follow the highlighted route",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text("${snapshot.speedKmh}\nkm/h", style = MaterialTheme.typography.labelLarge)
                }
                LinearProgressIndicator(
                    progress = { snapshot.progress.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavigationMetric("Remaining", formatDistance(snapshot.remainingMeters), Modifier.weight(1f))
                    NavigationMetric("ETA", if (snapshot.etaMinutes < 60) "${snapshot.etaMinutes} min" else "${snapshot.etaMinutes / 60}h ${snapshot.etaMinutes % 60}m", Modifier.weight(1f))
                    NavigationMetric("Progress", "${(snapshot.progress * 100).roundToInt()}%", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalIconButton(onClick = onVoiceToggle) {
                        Icon(if (voiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff, "Voice guidance")
                    }
                    FilledTonalIconButton(onClick = onOverview) { Icon(Icons.Default.Route, "Route overview") }
                    FilledTonalIconButton(onClick = onFollow) { Icon(Icons.Default.MyLocation, "Follow location") }
                    if (snapshot.offRoute) {
                        Button(onClick = onReroute, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Sync, null)
                            Spacer(Modifier.width(5.dp))
                            Text("Reroute")
                        }
                    } else Spacer(Modifier.weight(1f))
                    FilledTonalIconButton(onClick = onStop) { Icon(Icons.Default.StopCircle, "Stop navigation") }
                }
            }
        }
    }
}

@Composable
private fun NavigationMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NavigationVoiceGuide(snapshot: NavigationSnapshot, enabled: Boolean) {
    val context = LocalContext.current
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ready by remember { mutableStateOf(false) }
    var lastMessageKey by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }
        tts = engine
        onDispose { runCatching { engine.stop(); engine.shutdown() } }
    }

    val nextStop = snapshot.nextStop
    val nextDistance = snapshot.nextStopDistanceMeters
    LaunchedEffect(enabled, ready, snapshot.offRoute, snapshot.arrived, nextStop?.id, nextDistance?.roundToInt()) {
        if (!enabled || !ready) return@LaunchedEffect
        val message = when {
            snapshot.arrived -> "You have reached your destination."
            snapshot.offRoute && snapshot.offRouteMeters > 150 -> "You are off the planned route. Please recalculate when safe."
            nextStop != null && nextDistance != null && nextDistance < 250 ->
                "Your Scenic stop, ${nextStop.name}, is just ahead."
            nextStop != null && nextDistance != null && nextDistance in 900.0..1100.0 ->
                "In about one kilometer, Scenic stop ${nextStop.name}."
            else -> null
        } ?: return@LaunchedEffect
        val key = when {
            snapshot.arrived -> "arrived"
            snapshot.offRoute -> "offroute"
            nextStop != null && nextDistance != null && nextDistance < 250 -> "${nextStop.id}-near"
            else -> "${nextStop?.id}-1km"
        }
        if (key != lastMessageKey) {
            lastMessageKey = key
            runCatching {
                tts?.language = Locale.getDefault()
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "scenic-$key")
            }
        }
    }
}

fun formatDistance(meters: Double): String = when {
    meters >= 10_000 -> "${(meters / 1000.0).roundToInt()} km"
    meters >= 1_000 -> String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
    else -> "${max(0, meters.roundToInt())} m"
}
