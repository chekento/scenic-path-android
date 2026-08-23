package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fast first-stage discovery.
 *
 * Photon is kept as an independent OSM fallback, while RapidRoutePoiDiscovery explicitly
 * asks for the human-interest categories Photon reverse lookups tend to miss. Both complete
 * inside the long-route planner's latency budget; the deeper precision scan remains a later
 * enrichment layer rather than blocking the first useful map.
 */
object FastRoutePoiDiscovery {
    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 40,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        val (photon, rapid) = coroutineScope {
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
            val rapidJob = async(Dispatchers.IO) {
                withTimeoutOrNull(7_000) {
                    runCatching {
                        RapidRoutePoiDiscovery.discover(
                            route = route,
                            enabledKinds = enabledKinds,
                            maxResults = maxOf(72, minOf(maxResults, 140)),
                        )
                    }.getOrElse { emptyList() }
                }.orEmpty()
            }
            photonJob.await() to rapidJob.await()
        }

        mergeResults(
            first = rapid,
            second = photon,
            enabledKinds = enabledKinds,
            maxResults = maxResults,
        )
    }

    internal suspend fun discoverTargetedOnly(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
        radiusMeters: Int = 15_000,
        maxSamples: Int = 10,
        allowBackfill: Boolean = true,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        val normal = withTimeoutOrNull(7_000) {
            runCatching {
                RapidRoutePoiDiscovery.discover(
                    route = route,
                    enabledKinds = enabledKinds,
                    maxResults = maxResults,
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
