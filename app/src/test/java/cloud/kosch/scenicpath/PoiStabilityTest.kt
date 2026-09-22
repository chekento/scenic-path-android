package cloud.kosch.scenicpath

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

class PoiStabilityTest {
    @Before fun reset() { ScenicSceneSelectionState.reset(); ScenicPoiSharedState.clear() }
    private fun poi(id: String, point: GeoPoint) = ScenePointUi(id = id, name = id,
        kind = StopKind.MUSEUM.name, subtype = "museum", point = point, relevance = 1.0, suggestionScore = 10.0)

    @Test(timeout = 10_000) fun denseIntercontinentalRouteKeepsBoundedPoiPoolAndDestination() = runBlocking {
        val started = System.nanoTime()
        val route = (0..70_000).map { GeoPoint(48.0 + sin(it / 600.0) * 0.15, -9.0 + it * 0.00045) }
        val results = RoutePoiScan.collect((0..39).toList(), route = route, maxRetained = 520) { window ->
            (0..49).map { item ->
                val index = ((window * 50 + item) / 1999.0 * route.lastIndex).roundToInt()
                poi("$window-$item", route[index])
            }
        }
        ScenicPoiSharedState.publish(route, results)
        val retained = ScenicPoiSharedState.pointsFor(route)
        assertTrue(retained.size <= 520)
        assertTrue(retained.any { RoutePoiGeometry.meters(it.point, route.last()) < 5_000 })
        assertTrue(retained.any { abs(it.point.lon - route[35_000].lon) < 0.5 })
        println("POI load: 70,001 route vertices / 2,000 candidates / ${retained.size} retained / ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    @Test fun indexedProjectionMatchesExhaustiveSegmentsIncludingDatelineAndLoops() {
        val routes = listOf(
            (0..400).map { GeoPoint(52.0 + sin(it * 0.07), 10.0 + cos(it * 0.05)) },
            (0..400).map { GeoPoint(10.0 + sin(it * 0.03) * 0.5, wrap(179.0 + it * 0.01)) },
        )
        val random = java.util.Random(75)
        routes.forEach { route ->
            val geometry = RoutePoiGeometry(route)
            repeat(100) {
                val base = route[random.nextInt(route.size)]
                val point = GeoPoint(base.lat + random.nextDouble() - 0.5, wrap(base.lon + random.nextDouble() - 0.5))
                val expected = exhaustive(route, point)
                val actual = geometry.project(point)
                assertEquals(expected.first, actual.distanceMeters, 0.0001)
                assertEquals(expected.second, actual.alongMeters, 0.01)
            }
        }
    }

    @Test(timeout = 5_000) fun batchBackpressurePreservesAllCompletedWindows() = runBlocking {
        val buffer = PoiUpdateBuffer(intervalMs = 1)
        val received = mutableSetOf<String>()
        val consumer = launch { buffer.consume { batch -> delay(1); received += batch.map { it.id } } }
        coroutineScope {
            repeat(4) { provider -> launch {
                repeat(20) { window -> buffer.submit((0..49).map { poi("$provider-$window-$it", GeoPoint(0.0, 0.0)) }) }
            } }
        }
        buffer.close()
        consumer.join()
        assertEquals(4_000, received.size)
    }

    @Test fun clearingJourneyRejectsResultsFromOldEpoch() = runBlocking {
        val route = listOf(GeoPoint(50.0, 9.0), GeoPoint(51.0, 10.0))
        val old = ScenicPoiSharedState.epoch()
        ScenicPoiSharedState.clear()
        ScenicPoiSharedState.publish(route, listOf(poi("stale", route.last())), old)
        assertTrue(ScenicPoiSharedState.pointsFor(route).isEmpty())
        ScenicPoiSharedState.publish(route, listOf(poi("current", route.last())))
        assertEquals("current", ScenicPoiSharedState.pointsFor(route).single().id)
    }

    @Test fun startingAnotherRouteRemovesOldMarkersAndRejectsLateResults() = runBlocking {
        val firstRoute = listOf(GeoPoint(40.0, -74.0), GeoPoint(41.0, -73.0))
        val secondRoute = listOf(GeoPoint(53.7, 10.2), GeoPoint(52.5, 13.4))
        val firstEpoch = ScenicPoiSharedState.begin(firstRoute)
        ScenicPoiSharedState.publish(firstRoute, listOf(poi("new-york", firstRoute.first())), firstEpoch)
        assertEquals("new-york", ScenicPoiSharedState.pointsFor(firstRoute).single().id)

        val secondEpoch = ScenicPoiSharedState.begin(secondRoute)
        assertTrue(ScenicPoiSharedState.pointsFor(firstRoute).isEmpty())
        assertTrue(ScenicPoiSharedState.pointsFor(secondRoute).isEmpty())
        ScenicPoiSharedState.publish(firstRoute, listOf(poi("late-old-route", firstRoute.last())), firstEpoch)
        assertTrue(ScenicPoiSharedState.pointsFor(secondRoute).isEmpty())

        ScenicPoiSharedState.publish(secondRoute, listOf(poi("berlin", secondRoute.last())), secondEpoch)
        assertEquals("berlin", ScenicPoiSharedState.pointsFor(secondRoute).single().id)
    }

    @Test(timeout = 5_000) fun busyPoiWorkersDoNotBlockForegroundAndCancelPromptly() = runBlocking {
        val entered = CountDownLatch(3)
        val hold = CountDownLatch(1)
        val jobs = List(3) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                CancellableNetwork.blocking(background = true) { entered.countDown(); hold.await() }
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals("search available", withTimeout(1_000) { CancellableNetwork.blocking { "search available" } })
            withTimeout(1_000) { jobs.forEach { it.cancelAndJoin() } }
        } finally { hold.countDown(); jobs.forEach { it.cancel() } }
    }

    @Test(expected = IOException::class) fun oversizedProviderResponseStopsBeforeParsing() {
        CancellableNetwork.readLimited(StringReader("x".repeat(100_000)), 16_384)
    }

    @Test fun fixedStopsStaySeparateFromClustersAndInvalidMarkersAreRejected() {
        val points = listOf(poi("plain", GeoPoint(50.0, 9.0)),
            poi("stop", GeoPoint(51.0, 10.0)).copy(includedInRoute = true),
            poi("bad", GeoPoint(Double.NaN, 10.0)))
        val (discoveries, stops) = ScenicMapPois.features(points)
        assertEquals(listOf("plain"), discoveries.features()!!.map { it.getStringProperty("poi_id") })
        assertEquals(listOf("stop"), stops.features()!!.map { it.getStringProperty("poi_id") })
    }

    private fun wrap(lon: Double) = ((lon + 540.0) % 360.0) - 180.0
    private fun exhaustive(route: List<GeoPoint>, p: GeoPoint): Pair<Double, Double> {
        var best = Double.POSITIVE_INFINITY
        var along = 0.0
        var traveled = 0.0
        val scale = 111_195 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.01)
        route.zipWithNext().forEach { (a, b) ->
            val x = wrap(a.lon - p.lon) * scale
            val y = (a.lat - p.lat) * 111_195
            val dx = wrap(b.lon - a.lon) * scale
            val dy = (b.lat - a.lat) * 111_195
            val length = RoutePoiGeometry.meters(a, b)
            val t = if (dx * dx + dy * dy == 0.0) 0.0 else (-(x * dx + y * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            val distance = hypot(x + t * dx, y + t * dy)
            if (distance < best) { best = distance; along = traveled + t * length }
            traveled += length
        }
        return best to along
    }
}
