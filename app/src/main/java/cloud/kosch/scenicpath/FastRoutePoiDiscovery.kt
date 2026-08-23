package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fast first-stage discovery.
 *
 * v0.5.2 no longer asks unfiltered Photon reverse geocoding to define the human-interest
 * marker population. PhotonCorridorPoiDiscovery performs route-window searches using
 * Photon's indexed `include` categories and bounding boxes. The older reverse pass remains
 * useful for natural context, while Overpass is now only a best-effort secondary enrichment.
 */
object FastRoutePoiDiscovery {
    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 40,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        val (categoryPhoton, genericPhoton) = coroutineScope {
            val categoryJob = async(Dispatchers.IO) {
                withTimeoutOrNull(7_200) {
                    runCatching {
                        PhotonCorridorPoiDiscovery.discover(
                            route = route,
                            enabledKinds = enabledKinds,
                            maxResults = maxOf(96, minOf(maxResults * 3, 220)),
                        )
                    }.getOrElse { emptyList() }
                }.orEmpty()
            }
            val genericJob = async(Dispatchers.IO) {
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
            categoryJob.await() to genericJob.await()
        }

        mergeResults(
            first = categoryPhoton,
            second = genericPhoton,
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

        val normal = withTimeoutOrNull(7_200) {
            runCatching {
                PhotonCorridorPoiDiscovery.discover(
                    route = route,
                    enabledKinds = enabledKinds,
                    maxResults = maxResults,
                )
            }.getOrElse { emptyList() }
        }.orEmpty()
        if (!allowBackfill) return@withContext normal

        // Overpass remains useful for tags Photon does not index as principal categories, but
        // it is no longer allowed to be the only path to restaurants/museums/culture.
        val missing = enabledKinds.filterTo(linkedSetOf()) { kind ->
            kind.autoDiscoverable && normal.none { point -> point.kind == kind.name }
        }
        if (missing.isEmpty()) return@withContext normal

        val wider = withTimeoutOrNull(8_500) {
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
