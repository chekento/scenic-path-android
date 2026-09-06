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
        val complete: Boolean = true,
    )

    private data class Attempt(
        val points: List<ScenePointUi>,
        val failed: Boolean,
    )

    private const val CACHE_TTL_MS = 20 * 60 * 1000L
    private const val EMPTY_SUCCESS_TTL_MS = 3 * 60 * 1000L
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
        readUsableEntry(key, now, maxResults)?.let { return@withContext it }

        mutex.withLock {
            val recheckNow = System.currentTimeMillis()
            readUsableEntry(key, recheckNow, maxResults)?.let { return@withLock it }

            val minimum = minimumUsefulCount(route)
            var providerFailed = false

            // Route 3+ can immediately inherit only places that are genuinely close to its own
            // geometry. They are copied into this route's independent pool, never displayed as a
            // global Route-1 overlay. This also gives useful continuity during provider throttling.
            var merged = ScenicPoiSharedState.knownPointsNear(
                route = route,
                enabledKinds = enabledKinds,
                maxDistanceMeters = if (broad) 30_000.0 else 22_000.0,
                maxResults = maxResults,
            )

            if (merged.size < minimum) {
                val fast = attempt(11_500) {
                    FastRoutePoiDiscovery.discover(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = maxOf(80, minOf(maxResults, 180)),
                    )
                }
                providerFailed = providerFailed || fast.failed
                merged = PrecisionRoutePoiDiscovery.mergeForDisplay(merged, fast.points, maxResults)
            }

            if (merged.size < minimum) {
                val rapid = attempt(9_500) {
                    RapidRoutePoiDiscovery.discover(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = maxOf(90, minOf(maxResults, 180)),
                    )
                }
                providerFailed = providerFailed || rapid.failed
                merged = PrecisionRoutePoiDiscovery.mergeForDisplay(merged, rapid.points, maxResults)
            }

            if (broad && merged.size < minimum) {
                val precision = attempt(10_500) {
                    PrecisionRoutePoiDiscovery.discover(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = maxOf(80, minOf(maxResults, 160)),
                        radiusMeters = 22_000,
                        maxSamples = 6,
                    )
                }
                providerFailed = providerFailed || precision.failed
                merged = PrecisionRoutePoiDiscovery.mergeForDisplay(merged, precision.points, maxResults)
            }

            val existing = readEntry(key)?.points.orEmpty()
            val finalPoints = PrecisionRoutePoiDiscovery.mergeForDisplay(merged, existing, maxResults)
            val complete = !providerFailed && (finalPoints.size >= minimum || merged.isEmpty())
            writeEntry(
                key,
                CacheEntry(
                    points = finalPoints,
                    storedAtMs = recheckNow,
                    retryAfterMs = if (complete) 0L else recheckNow + FAILURE_COOLDOWN_MS,
                    complete = complete,
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
        writeEntry(
            key,
            CacheEntry(
                points = merged,
                storedAtMs = System.currentTimeMillis(),
                // Candidate-provided POIs are valid immediately, but a later map visit may still
                // enrich them when coverage is thin.
                complete = merged.size >= minimumUsefulCount(route),
                retryAfterMs = 0L,
            )
        )
        ScenicPoiSharedState.publish(route, merged)
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

    private suspend fun attempt(timeoutMs: Long, block: suspend () -> List<ScenePointUi>): Attempt {
        var failed = false
        val points = withTimeoutOrNull(timeoutMs) {
            runCatching { block() }
                .onFailure { failed = true }
                .getOrElse { emptyList() }
        }
        if (points == null) return Attempt(emptyList(), failed = true)
        return Attempt(points, failed)
    }

    private fun readUsableEntry(key: String, now: Long, maxResults: Int): List<ScenePointUi>? {
        val entry = readEntry(key) ?: return null
        val age = now - entry.storedAtMs
        return when {
            entry.complete && entry.points.isNotEmpty() && age <= CACHE_TTL_MS -> entry.points.take(maxResults)
            entry.complete && entry.points.isEmpty() && age <= EMPTY_SUCCESS_TTL_MS -> emptyList()
            !entry.complete && entry.retryAfterMs > now -> entry.points.take(maxResults)
            else -> null
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
