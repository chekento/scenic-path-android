package cloud.kosch.scenicpath

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RoutePoiCoverageTest {
    private val longRoute = (0..240).map { GeoPoint(50.0, -2.0 + it * 0.1) }

    @Before
    fun resetFilters() {
        ScenicSceneSelectionState.reset()
        ScenicPoiSharedState.clear()
    }

    private fun poi(id: String, point: GeoPoint, score: Double = 10.0, kind: StopKind = StopKind.MUSEUM) = ScenePointUi(
        id = id, name = "Place $id", kind = kind.name,
        subtype = if (kind == StopKind.FOOD) "restaurant" else "museum",
        point = point, relevance = 1.0, suggestionScore = score,
    )

    @Test
    fun everyWindowIsCoveredBeyondTheFormer455And540KmCaps() {
        val geometry = RoutePoiGeometry(longRoute)
        for (size in listOf(50_000.0, 58_000.0, 65_000.0, 82_000.0)) {
            val windows = geometry.windows(size)
            assertTrue(windows.size > 7)
            assertEquals(longRoute.first(), windows.first().first())
            assertEquals(longRoute.last(), windows.last().last())
            windows.zipWithNext().forEach { (a, b) -> assertEquals(a.last(), b.first()) }
            assertTrue(longRoute.all { point -> windows.any { point in it } })
            assertEquals(geometry.lengthMeters, windows.sumOf { RoutePoiGeometry(it).lengthMeters }, 5.0)
        }
    }

    @Test
    fun sparseLongEdgesAreSplitWithoutOmittingTheDestination() {
        val route = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 15.0))
        val windows = RoutePoiGeometry(route).windows(65_000.0)
        assertTrue(windows.size > 20)
        assertEquals(route.last(), windows.last().last())
        assertTrue(windows.all { RoutePoiGeometry(it).lengthMeters <= 65_001.0 })
    }

    @Test
    fun repeatedVerticesAndVeryShortRoutesStayFinite() {
        val point = GeoPoint(53.0, 10.0)
        assertTrue(RoutePoiGeometry(emptyList()).windows(65_000.0).isEmpty())
        assertTrue(RoutePoiGeometry(listOf(point)).windows(65_000.0).isEmpty())
        val repeated = RoutePoiGeometry(List(20) { point })
        assertEquals(1, repeated.windows(65_000.0).size)
        assertEquals(0.0, repeated.project(point).distanceMeters, 0.01)
        assertEquals(0.0, repeated.project(point).alongMeters, 0.01)
    }

    @Test
    fun samplingUsesTravelDistanceInsteadOfVertexCount() {
        val route = (0..1000).map { GeoPoint(0.0, it / 100_000.0) } + GeoPoint(0.0, 12.0)
        val samples = RoutePoiGeometry(route).samples(5)
        assertEquals(listOf(0.0, 3.0, 6.0, 9.0, 12.0), samples.map { kotlin.math.round(it.lon * 1000) / 1000 })
    }

    @Test
    fun corridorDistanceIncludesTheInteriorOfLongSparseSegments() {
        val route = RoutePoiGeometry(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0)))
        val onRoute = route.project(GeoPoint(0.0, 8.5))
        assertEquals(0.0, onRoute.distanceMeters, 0.01)
        assertEquals(0.85, onRoute.alongMeters / route.lengthMeters, 0.001)
        assertEquals(1_112.0, route.project(GeoPoint(0.01, 8.5)).distanceMeters, 2.0)
        assertTrue(route.project(GeoPoint(0.5, 8.5)).distanceMeters > 15_000)
    }

    @Test
    fun windingRoutePreservesItsExcursionsAndReturnLeg() {
        val route = listOf(GeoPoint(50.0, 10.0), GeoPoint(51.0, 10.0), GeoPoint(51.0, 11.0), GeoPoint(50.0, 11.0))
        val geometry = RoutePoiGeometry(route)
        val windows = geometry.windows(65_000.0)
        assertTrue(route.all { point -> windows.any { point in it } })
        assertEquals(0.0, geometry.project(GeoPoint(50.5, 11.0)).distanceMeters, 1.0)
        assertTrue(geometry.project(GeoPoint(50.5, 11.0)).alongMeters > geometry.lengthMeters * 0.7)
    }

    @Test
    fun denseHighScoringDepartureCityCannotDisplaceTheTail() {
        val city = (0..180).map { poi("city$it", GeoPoint(50.0 + it * 0.0005, -2.0), 1_000.0) }
        val along = RoutePoiGeometry(longRoute).samples(40).mapIndexed { index, point -> poi("route$index", point) }
        val result = PrecisionRoutePoiDiscovery.mergeForDisplay(city, along, 40, longRoute)
        assertEquals(40, result.size)
        assertTrue(result.any { it.point.lon > 21.0 })
        assertTrue(result.any { it.point.lon in 9.0..11.0 })
        assertTrue(result.count { it.id.startsWith("route") } >= 25)
    }

    @Test
    fun repeatedPartialMergesKeepDestinationResultsVisible() {
        val tail = listOf(poi("destination", longRoute.last()))
        var result = PrecisionRoutePoiDiscovery.mergeForDisplay(tail, emptyList(), 24, longRoute)
        repeat(4) { batch ->
            val city = (0..80).map { poi("city$batch-$it", GeoPoint(50.0 + it * 0.001, -2.0 + batch * 0.001), 500.0) }
            result = PrecisionRoutePoiDiscovery.mergeForDisplay(city, result, 24, longRoute)
            assertTrue(result.any { it.id == "destination" })
            assertTrue(result.size <= 24)
        }
    }

    @Test
    fun routeWideSelectionRetainsCategoryVarietyAndDeduplicatesProviders() {
        val samples = RoutePoiGeometry(longRoute).samples(30)
        val museums = samples.mapIndexed { i, p -> poi("m$i", p) }
        val food = samples.mapIndexed { i, p -> poi("f$i", p, 200.0, StopKind.FOOD) }
        val duplicate = museums.last().copy(id = "other-provider", suggestionScore = 11.0)
        val result = PrecisionRoutePoiDiscovery.mergeForDisplay(museums + duplicate, food, 60, longRoute)
        assertEquals(60, result.size)
        assertEquals(30, result.count { it.kind == StopKind.MUSEUM.name })
        assertEquals(30, result.count { it.kind == StopKind.FOOD.name })
        assertEquals(1, result.count { it.name == museums.last().name })
    }

    @Test
    fun activeFiltersAndZeroLimitsStillApply() {
        ScenicSceneSelectionState.activate(setOf(StopKind.FOOD))
        val input = listOf(poi("museum", longRoute.first()), poi("food", longRoute.last(), kind = StopKind.FOOD))
        assertEquals(listOf("food"), PrecisionRoutePoiDiscovery.mergeForDisplay(input, emptyList(), 10, longRoute).map { it.id })
        assertTrue(PrecisionRoutePoiDiscovery.mergeForDisplay(input, emptyList(), 0, longRoute).isEmpty())
    }

    @Test
    fun sharedPoolUsesTheSameRouteWideBalanceAsTheMap() = runBlocking {
        ScenicPoiSharedState.publish(longRoute, listOf(poi("end", longRoute.last())))
        ScenicPoiSharedState.publish(longRoute, (0..600).map { poi("start$it", GeoPoint(50.0 + it * 0.0005, -2.0), 900.0) })
        assertTrue(ScenicPoiSharedState.pointsFor(longRoute).any { it.id == "end" })
        assertTrue(ScenicPoiSharedState.pointsFor(longRoute).size <= 520)
    }

    @Test
    fun scanPrioritySpreadsEarlyResultsAndVisitsEveryWindowExactlyOnce() {
        val order = RoutePoiScan.order(51)
        assertEquals(listOf(0, 50, 25), order.take(3))
        assertEquals((0..50).toList(), order.sorted())
        assertEquals(emptyList<Int>(), RoutePoiScan.order(0))
        assertEquals(listOf(0), RoutePoiScan.order(1))
        assertEquals(listOf(0, 1), RoutePoiScan.order(2))
    }

    @Test(timeout = 5_000)
    fun allWindowsFinishWithBoundedConcurrencyDespiteProviderFailure() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val visited = mutableSetOf<Int>()
        val result = RoutePoiScan.collect((0..30).toList()) { index ->
            visited += index
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try {
                yield()
                if (index == 0) error("departure provider unavailable")
                listOf(poi("$index", longRoute.last()))
            } finally {
                active.decrementAndGet()
            }
        }
        assertEquals((0..30).toSet(), visited)
        assertEquals(30, result.size)
        assertTrue(peak.get() <= 3)
        assertTrue(result.any { it.id == "30" })
    }

    @Test(timeout = 5_000)
    fun destinationIsPublishedWhileTheFirstWindowIsStillWaiting() = runBlocking {
        val unblockStart = CompletableDeferred<Unit>()
        val destinationPublished = CompletableDeferred<Unit>()
        val task = async {
            RoutePoiScan.collect((0..12).toList(), onPartial = { points ->
                if (points.any { it.id == "12" }) destinationPublished.complete(Unit)
            }) { index ->
                if (index == 0) unblockStart.await()
                listOf(poi("$index", longRoute.last()))
            }
        }
        destinationPublished.await()
        assertFalse(task.isCompleted)
        unblockStart.complete(Unit)
        assertEquals(13, task.await().size)
    }

    @Test(timeout = 5_000)
    fun changingRouteCancelsOldQueriesAndPreventsLatePublication() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var published = 0
        var visited = 0
        val task = launch {
            RoutePoiScan.collect((0..40).toList(), onPartial = { published++ }) {
                visited++
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(visited <= 3)
        assertEquals(0, published)
    }
}
