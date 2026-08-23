package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fast first-stage discovery.
 *
 * Photon is excellent as a cheap OSM fallback but reverse lookups naturally favour the
 * nearest large feature, which is why long routes could initially show almost only trees,
 * water and mountains. The quick path now always runs a category-first bounding-box coverage
 * scan in parallel, so museums, food, heritage, art, worship, architecture and viewpoints
 * can reach the route candidate and the map before the deeper precision scan finishes.
 */
object FastRoutePoiDiscovery {
    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 40,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        val (photon, coverage) = coroutineScope {
            val photonJob = async(Dispatchers.IO) {
                runCatching {
                    PhotonSceneFallback.discover(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = maxOf(28, minOf(maxResults, 90)),
                        fast = true,
                        includeTargetedBackfill = false,
                    )
                }.getOrElse { emptyList() }
            }
            val coverageJob = async(Dispatchers.IO) {
                withTimeoutOrNull(12_000) {
                    runCatching {
                        RoutePoiCoverageDiscovery.discover(
                            route = route,
                            enabledKinds = enabledKinds,
                            maxResults = maxOf(72, minOf(maxResults, 140)),
                            corridorMeters = 12_000,
                        )
                    }.getOrElse { emptyList() }
                }.orEmpty()
            }
            photonJob.await() to coverageJob.await()
        }

        mergeResults(
            first = coverage,
            second = photon,
            enabledKinds = enabledKinds,
            maxResults = maxResults,
        )
    }

    /**
     * Missing-category rescue used by slower fallbacks. Start with the cheap coverage scanner;
     * only use the continuous corridor as a second attempt when a requested kind is still absent.
     */
    internal suspend fun discoverTargetedOnly(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
        radiusMeters: Int = 15_000,
        maxSamples: Int = 10,
        allowBackfill: Boolean = true,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        val normal = withTimeoutOrNull(11_000) {
            runCatching {
                RoutePoiCoverageDiscovery.discover(
                    route = route,
                    enabledKinds = enabledKinds,
                    maxResults = maxResults,
                    corridorMeters = radiusMeters.coerceIn(8_000, 20_000),
                )
            }.getOrElse { emptyList() }
        }.orEmpty()
        if (!allowBackfill) return@withContext normal

        val missing = enabledKinds.filterTo(linkedSetOf()) { kind ->
            kind.autoDiscoverable && normal.none { point -> point.kind == kind.name }
        }
        if (missing.isEmpty()) return@withContext normal

        val wider = withTimeoutOrNull(9_000) {
            runCatching {
                PrecisionRoutePoiDiscovery.discover(
                    route = route,
                    enabledKinds = missing,
                    maxResults = maxOf(18, missing.size * 4),
                    radiusMeters = maxOf(24_000, radiusMeters + 8_000),
                    maxSamples = maxOf(12, maxSamples),
                )
            }.getOrElse { emptyList() }
        }.orEmpty()
        mergeResults(normal, wider, enabledKinds, maxResults)
    }

    internal fun mergeResults(
        first: List<ScenePointUi>,
        second: List<ScenePointUi>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
    ): List<ScenePointUi> {
        val allowed = (first + second).filter { point ->
            val kind = StopKind.entries.firstOrNull { it.name == point.kind } ?: StopKind.SCENIC
            kind == StopKind.SCENIC || kind in enabledKinds
        }
        return PrecisionRoutePoiDiscovery.mergeForDisplay(allowed, emptyList(), maxResults)
    }
}
