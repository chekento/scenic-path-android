package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fast first-stage discovery.
 *
 * v0.5.16 treats an empty first provider response as a transient condition, not as a valid final
 * state. Physical-device testing showed that the route itself can be available while every POI
 * marker remains absent when the first Photon/Overpass wave times out. The old implementation
 * silently converted every provider exception to `emptyList()` and never retried until the route
 * geometry changed, so one temporary network/provider failure could leave the complete planning
 * session without clickable locations.
 *
 * The first wave still uses the two fast Photon strategies and publishes each successful partial
 * result immediately into ScenicPoiSharedState. If both are empty, an independent rescue wave uses
 * bounded OSM/Overpass coverage plus a slower direct Photon retry. This keeps initial-route latency
 * low when the normal providers work, but prevents a one-shot outage from becoming a permanently
 * empty map. ScenicMap's deeper Rapid/Precision passes remain complementary enrichment.
 */
object FastRoutePoiDiscovery {
    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 40,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        suspend fun publishPartial(points: List<ScenePointUi>) {
            if (points.isEmpty()) return
            val balanced = mergeResults(
                first = points,
                second = emptyList(),
                enabledKinds = enabledKinds,
                maxResults = maxResults,
            )
            if (balanced.isEmpty()) return
            withContext(Dispatchers.Main.immediate) {
                ScenicPoiSharedState.publish(route, balanced)
            }
        }

        val (categoryPhoton, genericPhoton) = coroutineScope {
            val categoryJob = async(Dispatchers.IO) {
                val result = withTimeoutOrNull(7_200) {
                    runCatching {
                        PhotonCorridorPoiDiscovery.discover(
                            route = route,
                            enabledKinds = enabledKinds,
                            maxResults = maxOf(96, minOf(maxResults * 3, 220)),
                        )
                    }.getOrElse { emptyList() }
                }.orEmpty()
                publishPartial(result)
                result
            }
            val genericJob = async(Dispatchers.IO) {
                val result = withTimeoutOrNull(6_500) {
                    runCatching {
                        PhotonSceneFallback.discover(
                            route = route,
                            enabledKinds = enabledKinds,
                            maxResults = maxOf(28, minOf(maxResults, 90)),
                            fast = true,
                            includeTargetedBackfill = false,
                        )
                    }.getOrElse { emptyList() }
                }.orEmpty()
                publishPartial(result)
                result
            }
            categoryJob.await() to genericJob.await()
        }

        val firstWave = mergeResults(
            first = categoryPhoton,
            second = genericPhoton,
            enabledKinds = enabledKinds,
            maxResults = maxResults,
        )
        if (firstWave.isNotEmpty()) return@withContext firstWave

        // One temporary provider miss must not freeze the route with zero POIs. Give the public
        // services a brief cooldown, then use two independent rescue paths. RoutePoiCoverage uses
        // explicit bounded OSM category queries; the Photon retry uses a slower reverse pass.
        delay(1_200)
        val (coverageRescue, photonRescue) = coroutineScope {
            val coverageJob = async(Dispatchers.IO) {
                val result = withTimeoutOrNull(11_500) {
                    runCatching {
                        RoutePoiCoverageDiscovery.discover(
                            route = route,
                            enabledKinds = enabledKinds,
                            maxResults = maxOf(96, minOf(maxResults, 180)),
                            corridorMeters = 15_000,
                        )
                    }.getOrElse { emptyList() }
                }.orEmpty()
                publishPartial(result)
                result
            }
            val photonJob = async(Dispatchers.IO) {
                val result = withTimeoutOrNull(8_500) {
                    runCatching {
                        PhotonSceneFallback.discover(
                            route = route,
                            enabledKinds = enabledKinds,
                            maxResults = maxOf(36, minOf(maxResults, 120)),
                            fast = false,
                            includeTargetedBackfill = false,
                        )
                    }.getOrElse { emptyList() }
                }.orEmpty()
                publishPartial(result)
                result
            }
            coverageJob.await() to photonJob.await()
        }

        val rescued = mergeResults(
            first = coverageRescue,
            second = photonRescue,
            enabledKinds = enabledKinds,
            maxResults = maxResults,
        )
        if (rescued.isNotEmpty()) {
            withContext(Dispatchers.Main.immediate) {
                ScenicPoiSharedState.publish(route, rescued)
            }
        }
        rescued
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
