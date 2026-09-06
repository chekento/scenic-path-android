package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes expensive public POI-provider work and caches it per routed geometry/category set.
 * Empty/transient provider results are never treated as authoritative route state: a later broad
 * map pass may escalate immediately to the next provider stage instead of being blocked by a
 * stale empty cache entry.
 */
object RoutePoiDiscoveryCoordinator {
    private data class CacheEntry(
        val points: List<ScenePointUi>,
        val storedAtMs: Long,
        val retryAfterMs: Long = 0L,
        val complete: Boolean = false,
        val fastAttempted: Boolean = false,
        val rapidAttempted: Boolean = false,
        val precisionAttempted: Boolean = false,
    )

    private data class Attempt(
        val points: List<ScenePointUi>,
        val failed: Boolean,
    )

    private const val CACHE_TTL_MS = 20 * 60 * 1000L
    private const val FAILURE_COOLDOWN_MS = 8 * 1000L
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
        readUsableEntry(key, now, maxResults, broad)?.let { return@withContext it }

        mutex.withLock {
            val recheckNow = System.currentTimeMillis()
            readUsableEntry(key, recheckNow, maxResults, broad)?.let { return@withLock it }

            val previous = readEntry(key)
            val minimum = minimumUsefulCount(route)
            var fastAttempted = previous?.fastAttempted == true
            var rapidAttempted = previous?.rapidAttempted == true
            var precisionAttempted = previous?.precisionAttempted == true
            var providerFailed = false

            // Preserve every route-local partial result that a provider may have already published.
            var merged = PrecisionRoutePoiDiscovery.mergeForDisplay(
                first = ScenicPoiSharedState.pointsForOwnRoute(route),
                second = ScenicPoiSharedState.knownPointsNear(
                    route = route,
                    enabledKinds = enabledKinds,
                    maxDistanceMeters = if (broad) 30_000.0 else 22_000.0,
                    maxResults = maxResults,
                ),
                maxResults = maxResults,
            )

            // FastRoutePoiDiscovery has its own staged timeouts. We only allow its first wave here;
            // if it times out, salvage partial results it already published and move on immediately.
            if (merged.size < minimum && !fastAttempted) {
                fastAttempted = true
                val fast = attempt(8_500) {
                    FastRoutePoiDiscovery.discover(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = maxOf(80, minOf(maxResults, 180)),
                    )
                }
                providerFailed = providerFailed || fast.failed
                merged = PrecisionRoutePoiDiscovery.mergeForDisplay(merged, fast.points, maxResults)
                merged = PrecisionRoutePoiDiscovery.mergeForDisplay(
                    merged,
                    ScenicPoiSharedState.pointsForOwnRoute(route),
                    maxResults,
                )
            }

            // If the first wave is empty, use the independent Overpass corridor pass right away.
            // A broad map pass is also allowed to run this stage after a thin earlier planner pass.
            if (merged.size < minimum && !rapidAttempted) {
                rapidAttempted = true
                val rapid = attempt(7_500) {
                    RapidRoutePoiDiscovery.discover(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = maxOf(90, minOf(maxResults, 180)),
                    )
                }
                providerFailed = providerFailed || rapid.failed
                merged = PrecisionRoutePoiDiscovery.mergeForDisplay(merged, rapid.points, maxResults)
                merged = PrecisionRoutePoiDiscovery.mergeForDisplay(
                    merged,
                    ScenicPoiSharedState.pointsForOwnRoute(route),
                    maxResults,
                )
            }

            // Precision is deliberately reserved for the visible map/deep pass. It is the strongest
            // fallback, but should not make every route calculation wait for a large targeted scan.
            if (broad && merged.size < minimum && !precisionAttempted) {
                precisionAttempted = true
                val precision = attempt(11_500) {
                    PrecisionRoutePoiDiscovery.discover(
                        route = route,
                        enabledKinds = enabledKinds,
                        maxResults = maxOf(80, minOf(maxResults, 160)),
                        radiusMeters = 22_000,
                        maxSamples = 8,
                    )
                }
                providerFailed = providerFailed || precision.failed
                merged = PrecisionRoutePoiDiscovery.mergeForDisplay(merged, precision.points, maxResults)
            }

            val existing = previous?.points.orEmpty()
            val finalPoints = PrecisionRoutePoiDiscovery.mergeForDisplay(merged, existing, maxResults)
            val complete = finalPoints.size >= minimum
            val allRelevantStagesTried = fastAttempted && rapidAttempted && (!broad || precisionAttempted)
            writeEntry(
                key,
                CacheEntry(
                    points = finalPoints,
                    storedAtMs = recheckNow,
                    retryAfterMs = if (complete) 0L else recheckNow + FAILURE_COOLDOWN_MS,
                    complete = complete,
                    fastAttempted = fastAttempted,
                    rapidAttempted = rapidAttempted,
                    precisionAttempted = precisionAttempted,
                )
            )
            if (finalPoints.isNotEmpty()) {
                ScenicPoiSharedState.publish(route, finalPoints)
            } else if (!providerFailed && !allRelevantStagesTried) {
                // Intentionally do nothing: a stronger subsequent pass must be allowed immediately.
            }
            finalPoints
        }
    }

    fun seed(route: List<GeoPoint>, enabledKinds: Set<StopKind>, points: List<ScenePointUi>) {
        if (route.size < 2 || enabledKinds.isEmpty() || points.isEmpty()) return
        val allowedNames = enabledKinds.mapTo(mutableSetOf()) { it.name }
        val filtered = points.filter { point -> isTravelSupportPoint(point) || point.kind in allowedNames }
        if (filtered.isEmpty()) return
        val key = cacheKey(route, enabledKinds)
        val current = readEntry(key)
        val merged = PrecisionRoutePoiDiscovery.mergeForDisplay(filtered, current?.points.orEmpty(), 420)
        writeEntry(
            key,
            CacheEntry(
                points = merged,
                storedAtMs = System.currentTimeMillis(),
                complete = merged.size >= minimumUsefulCount(route),
                retryAfterMs = 0L,
                fastAttempted = current?.fastAttempted == true,
                rapidAttempted = current?.rapidAttempted == true,
                precisionAttempted = current?.precisionAttempted == true,
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

    /** Pure retry policy kept internal so JVM tests can protect empty-cache escalation. */
    internal fun shouldRetryEmpty(
        broad: Boolean,
        retryAfterMs: Long,
        nowMs: Long,
        fastAttempted: Boolean,
        rapidAttempted: Boolean,
        precisionAttempted: Boolean,
    ): Boolean {
        if (broad && !precisionAttempted) return true
        if (!fastAttempted || !rapidAttempted) return true
        return retryAfterMs <= nowMs
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

    private fun readUsableEntry(
        key: String,
        now: Long,
        maxResults: Int,
        broad: Boolean,
    ): List<ScenePointUi>? {
        val entry = readEntry(key) ?: return null
        val age = now - entry.storedAtMs
        if (entry.complete && entry.points.isNotEmpty() && age <= CACHE_TTL_MS) {
            return entry.points.take(maxResults)
        }
        if (entry.points.isNotEmpty()) {
            // Partial POIs are always better than a blank map. Return them immediately and avoid
            // hammering public providers just because the count is below the ideal coverage target.
            return entry.points.take(maxResults)
        }
        return if (
            shouldRetryEmpty(
                broad = broad,
                retryAfterMs = entry.retryAfterMs,
                nowMs = now,
                fastAttempted = entry.fastAttempted,
                rapidAttempted = entry.rapidAttempted,
                precisionAttempted = entry.precisionAttempted,
            )
        ) null else emptyList()
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
