package cloud.kosch.scenicpath

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LongDistanceRoutingTest {
    private val network = (0..80).map { GeoPoint(48.0 + it * 0.002, -9.0 + it * 0.4) }
    private fun leg(a: GeoPoint, b: GeoPoint) = RoadRoute(LongDistanceRouting.distance(a, b), 500.0, listOf(a, b))

    @Test fun longJourneyIncludesEverySectionAndDestination() = runBlocking {
        val calls = mutableListOf<Pair<GeoPoint, GeoPoint>>()
        val route = LongDistanceRouting.route(network.first(), network.last(), requestGuide = { network }) { a, b ->
            calls += a to b
            assertTrue(LongDistanceRouting.distance(a, b) < 600_000)
            leg(a, b)
        }
        assertTrue(calls.size >= 4)
        assertEquals(network.first(), route.points.first())
        assertEquals(network.last(), route.points.last())
        assertTrue(route.points.all { it in network })
        assertEquals(calls.size * 500.0, route.durationSeconds, 0.01)
        calls.zipWithNext().forEach { (a, b) -> assertEquals(a.second, b.first) }
    }

    @Test fun shortRouteDoesNotRequestGuideOrDuplicateBaseline() = runBlocking {
        var calls = 0
        LongDistanceRouting.route(network[0], network[1], requestGuide = { error("No guide needed") }) { a, b ->
            calls++; leg(a, b)
        }
        assertEquals(1, calls)
    }

    @Test fun providerLimitTriggersSmallerRealNetworkSections() = runBlocking {
        var rejected = 0
        val guide = network.take(16)
        val result = LongDistanceRouting.route(guide.first(), guide.last(), requestGuide = { guide }) { a, b ->
            if (LongDistanceRouting.distance(a, b) > 160_000) {
                rejected++
                throw RoutingHttpException(400, "{\"error_code\":154,\"error\":\"Path distance exceeds the max distance limit\"}")
            }
            leg(a, b)
        }
        assertTrue(rejected > 0)
        assertEquals(guide.last(), result.points.last())
        assertTrue(result.points.all { it in guide })
    }

    @Test fun unavailableSectionNeverBecomesStraightLineOrPartialSuccess() = runBlocking {
        var calls = 0
        try {
            LongDistanceRouting.route(network.first(), network.last(), requestGuide = { network }) { a, b ->
                if (++calls == 2) throw IOException("offline")
                leg(a, b)
            }
            fail("Incomplete journey must fail")
        } catch (expected: IOException) { assertEquals("offline", expected.message) }
    }

    @Test fun cancellationNeverStartsFallbackGuide() = runBlocking {
        try {
            LongDistanceRouting.route(network[0], network[1], requestGuide = { error("Cancelled request must stop") }) { _, _ ->
                throw CancellationException("cancelled")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun discontinuousRoadSectionsAreRejected() {
        try {
            LongDistanceRouting.stitch(listOf(leg(network[0], network[1]), leg(network[4], network[5])))
            fail("Disconnected sections must not be joined")
        } catch (_: IOException) { }
    }

    @Test
    fun waypointLegsUseARealRoadBridgeForNearbyProviderSnaps() = runBlocking {
        val start = GeoPoint(50.0, 8.0)
        val incomingSnap = GeoPoint(50.001, 8.010)
        val outgoingSnap = GeoPoint(50.001, 8.012)
        val destination = GeoPoint(50.0, 8.020)
        val first = leg(start, incomingSnap)
        val second = leg(outgoingSnap, destination)
        val connector = RoadRoute(
            distanceMeters = LongDistanceRouting.distance(incomingSnap, outgoingSnap),
            durationSeconds = 42.0,
            points = listOf(incomingSnap, outgoingSnap),
        )

        val result = LongDistanceRouting.stitchWithBridges(listOf(first, second), bridge = { from, to ->
            assertEquals(incomingSnap, from)
            assertEquals(outgoingSnap, to)
            connector
        })

        assertEquals(start, result.points.first())
        assertEquals(destination, result.points.last())
        assertTrue(result.points.contains(incomingSnap))
        assertTrue(result.points.contains(outgoingSnap))
        assertEquals(first.distanceMeters + connector.distanceMeters + second.distanceMeters, result.distanceMeters, 0.01)
        assertEquals(first.durationSeconds + connector.durationSeconds + second.durationSeconds, result.durationSeconds, 0.01)
    }

    @Test
    fun failedWaypointBridgeStillRejectsTheReplacementRoute() = runBlocking {
        val start = GeoPoint(50.0, 8.0)
        val incomingSnap = GeoPoint(50.001, 8.010)
        val outgoingSnap = GeoPoint(50.001, 8.012)
        val destination = GeoPoint(50.0, 8.020)
        try {
            LongDistanceRouting.stitchWithBridges(
                listOf(leg(start, incomingSnap), leg(outgoingSnap, destination)),
                bridge = { _, _ -> throw IOException("connector unavailable") },
            )
            fail("A missing road connector must not create a partial route")
        } catch (expected: IOException) {
            assertEquals("connector unavailable", expected.message)
        }
    }

    @Test fun distanceSplitsPreserveLoopsAndUseExistingVertices() {
        val bend = listOf(GeoPoint(52.0, 8.0), GeoPoint(55.0, 8.0), GeoPoint(55.0, 12.0), GeoPoint(52.0, 12.0))
        val pieces = LongDistanceRouting.splitByDistance(bend, 400_000.0)
        assertEquals(bend, pieces.first() + pieces.drop(1).flatMap { it.drop(1) })
        assertEquals(bend.last(), pieces.last().last())
    }

    @Test fun changingInputRejectsOldRouteResponseAndCleanup() {
        val request = LatestRequest()
        val old = request.advance()
        val latest = request.advance()
        assertFalse(request.accepts(old))
        assertTrue(request.accepts(latest))
        request.advance() // cancel / endpoint / vehicle / preferences changed
        assertFalse(request.accepts(latest))
    }
}
