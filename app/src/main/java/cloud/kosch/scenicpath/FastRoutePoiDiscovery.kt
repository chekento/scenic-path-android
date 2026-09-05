package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Fast first-stage discovery with independent rescue providers. */
object FastRoutePoiDiscovery {
    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 40,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()

        suspend fun publishPartial(points: List<ScenePointUi>) {
            if (points.isEmpty()) return
            val balanced = mergeResults(points, emptyList(), enabledKinds, maxResults)
            if (balanced.isEmpty()) return
            withContext(Dispatchers.Main.immediate) { ScenicPoiSharedState.publish(route, balanced) }
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

        val firstWave = mergeResults(categoryPhoton, genericPhoton, enabledKinds, maxResults)
        if (firstWave.isNotEmpty()) return@withContext firstWave

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

        val rescued = mergeResults(coverageRescue, photonRescue, enabledKinds, maxResults)
        if (rescued.isNotEmpty()) {
            withContext(Dispatchers.Main.immediate) { ScenicPoiSharedState.publish(route, rescued) }
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
                PhotonCorridorPoiDiscovery.discover(route = route, enabledKinds = enabledKinds, maxResults = maxResults)
            }.getOrElse { emptyList() }
        }.orEmpty()
        if (!allowBackfill) return@withContext mergeResults(normal, emptyList(), enabledKinds, maxResults)

        val missing = enabledKinds.filterTo(linkedSetOf()) { kind ->
            kind.autoDiscoverable && normal.none { point -> point.kind == kind.name }
        }
        if (missing.isEmpty()) return@withContext mergeResults(normal, emptyList(), enabledKinds, maxResults)

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
        if (enabledKinds.isEmpty() || maxResults <= 0) return emptyList()
        val allowed = (first + second).filter { point ->
            val kind = StopKind.entries.firstOrNull { it.name == point.kind } ?: StopKind.SCENIC
            point.includedInRoute || kind in enabledKinds
        }
        return PrecisionRoutePoiDiscovery.mergeForDisplay(allowed, emptyList(), maxResults)
    }
}
