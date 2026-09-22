package cloud.kosch.scenicpath

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * The first wave still uses the two fast Photon strategies and passes each successful partial
 * result to its caller. If both are empty, an independent rescue wave uses
 * bounded OSM/Overpass coverage plus a slower direct Photon retry. This keeps initial-route latency
 * low when the normal providers work, but prevents a one-shot outage from becoming a permanently
 * empty map. ScenicMap's deeper Rapid/Precision passes remain complementary enrichment.
 */
object FastRoutePoiDiscovery {
    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 40,
        completeRoute: Boolean = false,
        onPartial: suspend (List<ScenePointUi>) -> Unit = {},
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()
        val lock = Mutex()
        var accumulated = emptyList<ScenePointUi>()

        suspend fun publishPartial(points: List<ScenePointUi>) {
            if (points.isEmpty()) return
            lock.withLock {
                accumulated = mergeResults(points, accumulated, enabledKinds, maxResults, route)
                onPartial(accumulated)
            }
        }

        suspend fun runProvider(timeoutMillis: Long, block: suspend () -> List<ScenePointUi>) {
            val result = try {
                if (completeRoute) block() else withTimeoutOrNull(timeoutMillis) { block() }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            publishPartial(result)
        }

        coroutineScope {
            val category = async {
                runProvider(7_200) {
                    PhotonCorridorPoiDiscovery.discover(
                        route, enabledKinds, maxOf(96, minOf(maxResults * 3, 220)),
                        onPartial = ::publishPartial,
                    )
                }
            }
            val generic = async {
                runProvider(6_500) {
                    PhotonSceneFallback.discover(
                        route, enabledKinds, maxOf(28, minOf(maxResults, 90)),
                        fast = true, includeTargetedBackfill = false, onPartial = ::publishPartial,
                    )
                }
            }
            category.await()
            generic.await()
        }
        // Planner timeouts retain completed windows. The map runs completeRoute=true and
        // continues through the whole route instead of imposing a whole-journey deadline.
        if (accumulated.isNotEmpty()) return@withContext accumulated

        delay(1_200)
        coroutineScope {
            val coverage = async {
                runProvider(11_500) {
                    RoutePoiCoverageDiscovery.discover(
                        route, enabledKinds, maxOf(96, minOf(maxResults, 180)),
                        corridorMeters = 15_000, onPartial = ::publishPartial,
                    )
                }
            }
            val photon = async {
                runProvider(8_500) {
                    PhotonSceneFallback.discover(
                        route, enabledKinds, maxOf(36, minOf(maxResults, 120)),
                        fast = false, includeTargetedBackfill = false, onPartial = ::publishPartial,
                    )
                }
            }
            coverage.await()
            photon.await()
        }
        accumulated
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
            }.getOrElse { if (it is CancellationException) throw it else emptyList() }
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
            }.getOrElse { if (it is CancellationException) throw it else emptyList() }
        }.orEmpty()
        mergeResults(normal, wider, enabledKinds, maxResults, route)
    }

    internal fun mergeResults(
        first: List<ScenePointUi>,
        second: List<ScenePointUi>,
        enabledKinds: Set<StopKind>,
        maxResults: Int,
        route: List<GeoPoint> = emptyList(),
    ): List<ScenePointUi> {
        val allowed = (first + second).filter { point ->
            val kind = StopKind.entries.firstOrNull { it.name == point.kind } ?: StopKind.SCENIC
            kind == StopKind.SCENIC || kind in enabledKinds
        }
        return PrecisionRoutePoiDiscovery.mergeForDisplay(allowed, emptyList(), maxResults, route)
    }
}
