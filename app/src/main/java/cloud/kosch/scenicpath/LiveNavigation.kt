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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class NavigationTurn {
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    U_TURN,
    SHARP_RIGHT,
    RIGHT,
    SLIGHT_RIGHT,
    ARRIVE,
}

data class NavigationManeuver(
    val turn: NavigationTurn,
    val instruction: String,
    val distanceMeters: Double,
    val routeIndex: Int,
    val angleDegrees: Double = 0.0,
)

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
    val nextManeuver: NavigationManeuver?,
    val arrived: Boolean,
) {
    val offRoute: Boolean get() = offRouteMeters > 90.0
}

/**
 * GPS matcher and route-geometry guidance engine.
 *
 * Until every routing provider returns a normalized maneuver list, turn guidance is extracted
 * from the actual committed route polyline. The detector uses a distance window around a bend,
 * ignores ordinary road curvature and only surfaces meaningful heading changes. It never invents
 * street names. Provider maneuvers can later override these geometry instructions one-for-one.
 */
object LiveNavigationEngine {
    fun snapshot(
        route: List<GeoPoint>,
        location: GeoPoint,
        speedMetersPerSecond: Float?,
        gpsBearingDegrees: Float?,
        stops: List<PlannedStop>,
    ): NavigationSnapshot {
        if (route.size < 2) {
            return NavigationSnapshot(0, 0.0, 0.0, 0, 0.0, 0.0, 0, null, null, null, true)
        }

        val nearestIndex = nearestRouteIndex(route, location)
        val offRoute = haversineMeters(route[nearestIndex], location)
        val cumulative = cumulativeMeters(route)
        val total = cumulative.last().coerceAtLeast(1.0)
        val progressMeters = cumulative[nearestIndex]
        val remaining = (total - progressMeters).coerceAtLeast(0.0)
        val progress = (progressMeters / total).coerceIn(0.0, 1.0)

        val routeBearing = when {
            nearestIndex < route.lastIndex -> bearing(route[nearestIndex], route[(nearestIndex + 1).coerceAtMost(route.lastIndex)])
            nearestIndex > 0 -> bearing(route[nearestIndex - 1], route[nearestIndex])
            else -> gpsBearingDegrees?.toDouble() ?: 0.0
        }
        val speedMps = speedMetersPerSecond?.takeIf { it >= 1.5f }?.toDouble() ?: 16.67
        val etaMinutes = (remaining / speedMps / 60.0).roundToInt().coerceAtLeast(0)
        val speedKmh = ((speedMetersPerSecond ?: 0f) * 3.6f).roundToInt().coerceAtLeast(0)

        val stopProgress = stops.mapNotNull { stop ->
            val point = stop.point ?: return@mapNotNull null
            val index = nearestRouteIndex(route, point)
            Triple(stop, index, cumulative[index])
        }
        val next = stopProgress
            .filter { (_, index, _) -> index >= nearestIndex - 2 }
            .minByOrNull { (_, index, _) -> index }
        val nextDistance = next?.let { (_, _, meters) -> (meters - progressMeters).coerceAtLeast(0.0) }
        val arrived = remaining < 80.0
        val maneuver = if (arrived) {
            NavigationManeuver(NavigationTurn.ARRIVE, "You have reached your destination", remaining, route.lastIndex)
        } else {
            findNextManeuver(route, cumulative, nearestIndex, progressMeters)
        }

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
            nextManeuver = maneuver,
            arrived = arrived,
        )
    }

    private fun findNextManeuver(
        route: List<GeoPoint>,
        cumulative: List<Double>,
        currentIndex: Int,
        progressMeters: Double,
    ): NavigationManeuver? {
        if (route.size < 5) return null
        val scanEndMeters = progressMeters + 8_000.0
        var candidateIndex = currentIndex + 1

        while (candidateIndex < route.lastIndex - 1 && cumulative[candidateIndex] <= scanEndMeters) {
            val ahead = cumulative[candidateIndex] - progressMeters
            if (ahead < 35.0) {
                candidateIndex++
                continue
            }

            val before = indexAtDistanceBefore(cumulative, candidateIndex, 65.0)
            val after = indexAtDistanceAfter(cumulative, candidateIndex, 85.0)
            if (before == candidateIndex || after == candidateIndex || before == after) {
                candidateIndex++
                continue
            }

            val incoming = bearing(route[before], route[candidateIndex])
            val outgoing = bearing(route[candidateIndex], route[after])
            val delta = signedHeadingDelta(incoming, outgoing)
            val magnitude = abs(delta)

            // Below ~32 degrees this is normally a bend, not a driver decision. Requiring enough
            // road length on both sides also suppresses dense-polyline noise at roundabouts/curves.
            if (magnitude >= 32.0) {
                val turn = classifyTurn(delta)
                return NavigationManeuver(
                    turn = turn,
                    instruction = maneuverInstruction(turn),
                    distanceMeters = ahead,
                    routeIndex = candidateIndex,
                    angleDegrees = delta,
                )
            }
            candidateIndex++
        }

        return if (route.lastIndex > currentIndex) {
            NavigationManeuver(
                turn = NavigationTurn.STRAIGHT,
                instruction = "Continue on the route",
                distanceMeters = (cumulative.last() - progressMeters).coerceAtLeast(0.0),
                routeIndex = route.lastIndex,
            )
        } else null
    }

    private fun classifyTurn(delta: Double): NavigationTurn {
        val magnitude = abs(delta)
        if (magnitude >= 150.0) return NavigationTurn.U_TURN
        return if (delta < 0) {
            when {
                magnitude >= 105.0 -> NavigationTurn.SHARP_LEFT
                magnitude >= 48.0 -> NavigationTurn.LEFT
                else -> NavigationTurn.SLIGHT_LEFT
            }
        } else {
            when {
                magnitude >= 105.0 -> NavigationTurn.SHARP_RIGHT
                magnitude >= 48.0 -> NavigationTurn.RIGHT
                else -> NavigationTurn.SLIGHT_RIGHT
            }
        }
    }

    private fun maneuverInstruction(turn: NavigationTurn): String = when (turn) {
        NavigationTurn.SLIGHT_LEFT -> "Bear slightly left"
        NavigationTurn.LEFT -> "Turn left"
        NavigationTurn.SHARP_LEFT -> "Turn sharp left"
        NavigationTurn.U_TURN -> "Make a U-turn"
        NavigationTurn.SHARP_RIGHT -> "Turn sharp right"
        NavigationTurn.RIGHT -> "Turn right"
        NavigationTurn.SLIGHT_RIGHT -> "Bear slightly right"
        NavigationTurn.ARRIVE -> "Destination reached"
        NavigationTurn.STRAIGHT -> "Continue straight"
    }

    private fun nearestRouteIndex(route: List<GeoPoint>, point: GeoPoint): Int {
        if (route.isEmpty()) return 0
        // Dense long routes can contain thousands of points. Coarse scan first, then refine locally.
        val step = max(1, route.size / 700)
        var coarseBest = 0
        var coarseDistance = Double.POSITIVE_INFINITY
        var index = 0
        while (index < route.size) {
            val distance = haversineMeters(route[index], point)
            if (distance < coarseDistance) {
                coarseDistance = distance
                coarseBest = index
            }
            index += step
        }
        val start = (coarseBest - step * 2).coerceAtLeast(0)
        val end = (coarseBest + step * 2).coerceAtMost(route.lastIndex)
        return (start..end).minByOrNull { haversineMeters(route[it], point) } ?: coarseBest
    }

    private fun indexAtDistanceBefore(cumulative: List<Double>, index: Int, meters: Double): Int {
        val target = cumulative[index] - meters
        var i = index
        while (i > 0 && cumulative[i] > target) i--
        return i
    }

    private fun indexAtDistanceAfter(cumulative: List<Double>, index: Int, meters: Double): Int {
        val target = cumulative[index] + meters
        var i = index
        while (i < cumulative.lastIndex && cumulative[i] < target) i++
        return i
    }

    private fun signedHeadingDelta(from: Double, to: Double): Double {
        var delta = (to - from + 540.0) % 360.0 - 180.0
        if (delta <= -180.0) delta += 360.0
        return delta
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
    val maneuver = snapshot.nextManeuver

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (snapshot.offRoute) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    modifier = Modifier.size(70.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (snapshot.offRoute) "!" else maneuverSymbol(maneuver?.turn ?: NavigationTurn.STRAIGHT),
                            fontSize = 39.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            snapshot.arrived -> "Destination reached"
                            snapshot.offRoute -> "Off route · ${formatDistance(snapshot.offRouteMeters)}"
                            maneuver != null -> maneuver.instruction
                            else -> "Continue on Scenic Path"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        when {
                            snapshot.offRoute -> "Rerouting is available"
                            maneuver != null && maneuver.turn != NavigationTurn.ARRIVE -> "in ${formatDistance(maneuver.distanceMeters)}"
                            else -> "Follow the highlighted route"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (nextStop != null && nextDistance != null) {
                        Text(
                            "${nextStop.kind.emoji} ${nextStop.name} · ${formatDistance(nextDistance)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(snapshot.speedKmh.toString(), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        Text("km/h", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavigationMetric("Remaining", formatDistance(snapshot.remainingMeters), Modifier.weight(1f))
                    NavigationMetric("ETA", formatEta(snapshot.etaMinutes), Modifier.weight(1f))
                    NavigationMetric("Progress", "${(snapshot.progress * 100).roundToInt()}%", Modifier.weight(1f))
                }
                LinearProgressIndicator(progress = { snapshot.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
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
    val maneuver = snapshot.nextManeuver
    val maneuverBucket = maneuver?.let {
        when {
            it.distanceMeters <= 90 -> 90
            it.distanceMeters <= 260 -> 260
            it.distanceMeters <= 850 -> 850
            else -> 0
        }
    } ?: 0

    LaunchedEffect(
        enabled,
        ready,
        snapshot.offRoute,
        snapshot.arrived,
        nextStop?.id,
        nextDistance?.roundToInt(),
        maneuver?.routeIndex,
        maneuverBucket,
    ) {
        if (!enabled || !ready) return@LaunchedEffect
        val message: String
        val key: String

        when {
            snapshot.arrived -> {
                message = "You have reached your destination."
                key = "arrived"
            }
            snapshot.offRoute && snapshot.offRouteMeters > 150 -> {
                message = "You are off the planned route. A new route can be calculated."
                key = "offroute"
            }
            maneuver != null && maneuver.turn != NavigationTurn.STRAIGHT && maneuverBucket > 0 -> {
                message = when (maneuverBucket) {
                    850 -> "In about 800 meters, ${maneuver.instruction.lowercase()}."
                    260 -> "In about 250 meters, ${maneuver.instruction.lowercase()}."
                    else -> maneuver.instruction + "."
                }
                key = "turn-${maneuver.routeIndex}-$maneuverBucket"
            }
            nextStop != null && nextDistance != null && nextDistance < 250 -> {
                message = "Your Scenic stop, ${nextStop.name}, is just ahead."
                key = "${nextStop.id}-near"
            }
            nextStop != null && nextDistance != null && nextDistance in 900.0..1100.0 -> {
                message = "In about one kilometer, Scenic stop ${nextStop.name}."
                key = "${nextStop.id}-1km"
            }
            else -> return@LaunchedEffect
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

private fun maneuverSymbol(turn: NavigationTurn): String = when (turn) {
    NavigationTurn.STRAIGHT -> "↑"
    NavigationTurn.SLIGHT_LEFT -> "↖"
    NavigationTurn.LEFT -> "←"
    NavigationTurn.SHARP_LEFT -> "↰"
    NavigationTurn.U_TURN -> "↶"
    NavigationTurn.SHARP_RIGHT -> "↱"
    NavigationTurn.RIGHT -> "→"
    NavigationTurn.SLIGHT_RIGHT -> "↗"
    NavigationTurn.ARRIVE -> "◎"
}

private fun formatEta(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

fun formatDistance(meters: Double): String = when {
    meters >= 10_000 -> "${(meters / 1000.0).roundToInt()} km"
    meters >= 1_000 -> String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
    else -> "${max(0, meters.roundToInt())} m"
}
