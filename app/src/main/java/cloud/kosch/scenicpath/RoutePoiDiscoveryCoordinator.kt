package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes expensive public POI-provider work and caches it per routed geometry/category set.
 * A map switch must not fan out three large public-provider scans per alternative and accidentally
 * rate-limit the device IP. Fast discovery is tried first; broader fallbacks run only when useful
 * coverage is still missing.
 */
object RoutePoiDiscoveryCoordinator {
    private data class CacheEntry(
        val points: List<ScenePointUi>,
        val storedAtMs: Long,
        val retryAfterMs: Long = 0L,
    )

    private const val CACHE_TTL_MS = 20 * 60 * 1000L
    private const val FAILURE_COOLDOWN_MS = 45 * 1000L
    private const val MAX_CACHE_ENTRIES = 16

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>()

    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind>,
        maxResults: Int = 220,
        broad: Boolean = false,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        if (route.size < 2 || enabledKinds.isEmpty() || maxResults <= 0) return@withContext emptyList()
        val key = cacheKey(route, enabledKinds)
        val now = System.currentTimeMillis()
        readEntry(key)?.takeIf { it.points.isNotEmpty() && now - it.storedAtMs <= CACHE_TTL_MS }?.let {
            return@withContext it.points.take(maxResults)
        }

        mutex.withLock {
            val recheckNow = System.currentTimeMillis()
            readEntry(key)?.let { entry ->
                if (entry.points.isNotEmpty() && recheckNow - entry.storedAtMs <= CACHE_TTL_MS) {
                    return@withLock entry.points.take(maxResults)
                }
                if (entry.retryAfterMs > recheckNow) {
                    return@withLock entry.points.take(maxResults)
                }
            }

            val minimum = minimumUsefulCount(route)
            var merged = withTimeoutOrNull(11_500) {
                runCatching {
                    FastRoutePoiDiscovery.discover(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = maxOf(80, minOf(maxResults, 180)),
                    )
                }.getOrElse { emptyList() }
            }.orEmpty()

            if (merged.size < minimum) {
                val rapid = withTimeoutOrNull(9_500) {
                    runCatching {
                        RapidRoutePoiDiscovery.discover(
                            route = route,
                            enabledKinds = enabledKinds,
                            maxResults = maxOf(90, minOf(maxResults, 180)),
                        )
                    }.getOrElse { emptyList() }
                }.orEmpty()
                merged = PrecisionRoutePoiDiscovery.mergeForDisplay(merged, rapid, maxResults)
            }

            if (broad && merged.size < minimum) {
                val precision = withTimeoutOrNull(10_500) {
                    runCatching {
                        PrecisionRoutePoiDiscovery.discover(
                            route = route,
                            enabledKinds = enabledKinds,
                            maxResults = maxOf(80, minOf(maxResults, 160)),
                            radiusMeters = 22_000,
                            maxSamples = 6,
                        )
                    }.getOrElse { emptyList() }
                }.orEmpty()
                merged = PrecisionRoutePoiDiscovery.mergeForDisplay(merged, precision, maxResults)
            }

            val existing = readEntry(key)?.points.orEmpty()
            val finalPoints = PrecisionRoutePoiDiscovery.mergeForDisplay(merged, existing, maxResults)
            writeEntry(
                key,
                CacheEntry(
                    points = finalPoints,
                    storedAtMs = recheckNow,
                    retryAfterMs = if (finalPoints.isEmpty()) recheckNow + FAILURE_COOLDOWN_MS else 0L,
                )
            )
            if (finalPoints.isNotEmpty()) ScenicPoiSharedState.publish(route, finalPoints)
            finalPoints
        }
    }

    fun seed(route: List<GeoPoint>, enabledKinds: Set<StopKind>, points: List<ScenePointUi>) {
        if (route.size < 2 || enabledKinds.isEmpty() || points.isEmpty()) return
        val allowedNames = enabledKinds.mapTo(mutableSetOf()) { it.name }
        val filtered = points.filter { point -> isTravelSupportPoint(point) || point.kind in allowedNames }
        if (filtered.isEmpty()) return
        val key = cacheKey(route, enabledKinds)
        val current = readEntry(key)?.points.orEmpty()
        val merged = PrecisionRoutePoiDiscovery.mergeForDisplay(filtered, current, 420)
        writeEntry(key, CacheEntry(merged, System.currentTimeMillis()))
    }

    fun invalidate(route: List<GeoPoint>) {
        if (route.size < 2) return
        val prefix = ScenicPoiSharedState.routeKey(route) + ":"
        synchronized(cache) {
            val keys = cache.keys.filter { it.startsWith(prefix) }
            keys.forEach(cache::remove)
        }
    }

    internal fun minimumUsefulCount(route: List<GeoPoint>): Int {
        val distance = RouteCoveragePolicy.totalDistanceMeters(route)
        return when {
            distance >= 600_000 -> 18
            distance >= 250_000 -> 14
            distance >= 100_000 -> 10
            else -> 6
        }
    }

    private fun cacheKey(route: List<GeoPoint>, enabledKinds: Set<StopKind>): String =
        ScenicPoiSharedState.routeKey(route) + ":" + enabledKinds.map { it.name }.sorted().joinToString(",")

    private fun readEntry(key: String): CacheEntry? = synchronized(cache) { cache[key] }

    private fun writeEntry(key: String, entry: CacheEntry) {
        synchronized(cache) {
            cache.remove(key)
            cache[key] = entry
            while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
        }
    }
}
