package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object FastRoutePoiDiscovery {
    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 40,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        // Planning calls are small (typically 24-40 results) and need enough category
        // coverage before the itinerary is chosen. Map and Smart Stops calls request 100+
        // results and already launch PrecisionRoutePoiDiscovery themselves, so they only
        // need Photon from this quick source.
        val includePrecision = maxResults < 100
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
                if (!includePrecision) emptyList() else withTimeoutOrNull(8_500) {
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
        mergeResults(precision, photon, enabledKinds, maxResults)
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
        return PrecisionRoutePoiDiscovery.mergeForDisplay(allowed, emptyList(), maxResults)
    }
}
