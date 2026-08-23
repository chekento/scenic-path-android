package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unified scene discovery entry point used by the Journey Optimizer.
 *
 * The old implementation was an early prototype-era scanner. It sampled a handful of
 * circles, explicitly removed FOOD, clustered different landmarks aggressively and then
 * fell back to Photon. That meant the automatic itinerary could still be dominated by
 * mountains, water and parks even though Smart Stops and the map had already learned the
 * richer taxonomy.
 *
 * v0.5 delegates to the same coverage-first stack used by the rest of the app. A cheap
 * targeted pass gives quick feedback; the continuous route-corridor precision pass is used
 * whenever a route is long, coverage is thin, or any high-value urban/cultural family is
 * missing. The final selection is balanced across the 23 user-facing Scenic lanes.
 */
object OsmSceneDiscovery {
    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 24,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) {
            return@withContext emptyList()
        }

        val fast = runCatching {
            FastRoutePoiDiscovery.discover(
                route = route,
                enabledKinds = enabledKinds,
                maxResults = maxOf(72, maxResults * 3),
            )
        }.getOrElse { emptyList() }

        val importantKinds = setOf(
            StopKind.VIEWPOINT,
            StopKind.MUSEUM,
            StopKind.MONUMENT,
            StopKind.ART,
            StopKind.WORSHIP,
            StopKind.FOOD,
            StopKind.ARCHITECTURE,
        ).intersect(enabledKinds)
        val missingImportant = importantKinds.count { kind -> fast.none { it.kind == kind.name } }
        val routeLength = route.zipWithNext().sumOf { (a, b) -> haversineMeters(a, b) }

        val needsPrecision =
            routeLength >= 70_000.0 ||
                fast.size < maxResults ||
                missingImportant > 0

        if (!needsPrecision) {
            return@withContext PrecisionRoutePoiDiscovery.mergeForDisplay(
                first = fast,
                second = emptyList(),
                maxResults = maxResults,
            )
        }

        val precision = runCatching {
            PrecisionRoutePoiDiscovery.discover(
                route = route,
                enabledKinds = enabledKinds,
                maxResults = maxOf(120, maxResults * 5),
                radiusMeters = 15_000,
                maxSamples = 10,
            )
        }.getOrElse { emptyList() }

        PrecisionRoutePoiDiscovery.mergeForDisplay(
            first = precision,
            second = fast,
            maxResults = maxResults,
        )
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
