package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded route-wide discovery used while a journey is being planned.
 *
 * Earlier versions had a second, independent Overpass implementation here. On long routes
 * it sampled a few circular windows, deduplicated unrelated nearby POIs and could therefore
 * hand the long-route optimizer mostly water/nature even though the post-route map had a
 * better precision scanner.
 *
 * v0.5 has one taxonomy and one corridor algorithm. Photon remains a quick independent OSM
 * source; PrecisionRoutePoiDiscovery follows the actual route line. The precision task is
 * time-bounded so the long-route planner can still meet its own latency guard.
 */
object FastRoutePoiDiscovery {
    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 40,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) {
            return@withContext emptyList()
        }

        val (photon, precision) = coroutineScope {
            val photonJob = async(Dispatchers.IO) {
                runCatching {
                    PhotonSceneFallback.discover(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = maxOf(28, maxResults),
                        fast = true,
                        includeTargetedBackfill = false,
                    )
                }.getOrElse { emptyList() }
            }
            val precisionJob = async(Dispatchers.IO) {
                withTimeoutOrNull(8_500) {
                    runCatching {
                        PrecisionRoutePoiDiscovery.discover(
                            route = route,
                            enabledKinds = enabledKinds,
                            maxResults = maxOf(96, maxResults * 3),
                            radiusMeters = 15_000,
                            maxSamples = 10,
                        )
                    }.getOrElse { emptyList() }
                }.orEmpty()
            }
            photonJob.await() to precisionJob.await()
        }

        mergeResults(
            first = precision,
            second = photon,
            enabledKinds = enabledKinds,
            maxResults = maxResults,
        )
    }

    /**
     * Compatibility entry point for Photon missing-category rescue. It deliberately skips
     * Photon so there is no recursion and queries the continuous corridor directly.
     */
    internal suspend fun discoverTargetedOnly(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
        radiusMeters: Int = 15_000,
        maxSamples: Int = 10,
        allowBackfill: Boolean = true,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) {
            return@withContext emptyList()
        }

        val normal = withTimeoutOrNull(9_000) {
            runCatching {
                PrecisionRoutePoiDiscovery.discover(
                    route = route,
                    enabledKinds = enabledKinds,
                    maxResults = maxResults,
                    radiusMeters = radiusMeters,
                    maxSamples = maxSamples,
                )
            }.getOrElse { emptyList() }
        }.orEmpty()

        if (!allowBackfill) return@withContext normal

        val missing = enabledKinds.filterTo(linkedSetOf()) { kind ->
            kind.autoDiscoverable && normal.none { point -> point.kind == kind.name }
        }
        if (missing.isEmpty()) return@withContext normal

        val wider = withTimeoutOrNull(7_000) {
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
        return PrecisionRoutePoiDiscovery.mergeForDisplay(
            first = allowed,
            second = emptyList(),
            maxResults = maxResults,
        )
    }
}
