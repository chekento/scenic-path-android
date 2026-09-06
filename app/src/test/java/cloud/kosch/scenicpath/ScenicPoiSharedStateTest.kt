package cloud.kosch.scenicpath

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenicPoiSharedStateTest {
    private val routeOne = listOf(
        GeoPoint(47.87, 12.65),
        GeoPoint(50.00, 11.20),
        GeoPoint(53.67, 10.24),
    )
    private val newlyAddedRoute = listOf(
        GeoPoint(47.87, 12.65),
        GeoPoint(49.60, 13.30),
        GeoPoint(53.67, 10.24),
    )
    private val nearbyAlternative = listOf(
        GeoPoint(47.87, 12.65),
        GeoPoint(50.00, 11.30),
        GeoPoint(53.67, 10.24),
    )

    private fun poi(id: String, point: GeoPoint) = ScenePointUi(
        id = id,
        name = id,
        kind = StopKind.SCENIC.name,
        subtype = "attraction",
        point = point,
        relevance = 1.0,
    )

    @After
    fun cleanup() {
        ScenicPoiSharedState.clear()
    }

    @Test
    fun newlyAddedRouteGetsIndependentPoiMemory() {
        ScenicPoiSharedState.publish(routeOne, listOf(poi("route-one-poi", routeOne[1])))
        ScenicPoiSharedState.publish(newlyAddedRoute, listOf(poi("new-route-poi", newlyAddedRoute[1])))

        val firstIds = ScenicPoiSharedState.pointsFor(routeOne).map { it.id }.toSet()
        val addedIds = ScenicPoiSharedState.pointsFor(newlyAddedRoute).map { it.id }.toSet()

        assertEquals(setOf("route-one-poi"), firstIds)
        assertEquals(setOf("new-route-poi"), addedIds)
        assertFalse("newly added route must not inherit route 1 POIs", "route-one-poi" in addedIds)
    }

    @Test
    fun nearbyKnownPoiCanSeedNewRouteWithoutMergingItsStorage() {
        ScenicPoiSharedState.publish(routeOne, listOf(poi("known-nearby", routeOne[1])))

        val fallback = ScenicPoiSharedState.knownPointsNear(
            route = nearbyAlternative,
            enabledKinds = setOf(StopKind.SCENIC),
            maxDistanceMeters = 20_000.0,
        )

        assertTrue("a spatially relevant known POI should be reusable during provider throttling", fallback.any { it.id == "known-nearby" })
        assertTrue("new route storage must still be empty until the caller explicitly seeds it", ScenicPoiSharedState.pointsFor(nearbyAlternative).isEmpty())

        ScenicPoiSharedState.publish(nearbyAlternative, fallback)
        assertTrue(ScenicPoiSharedState.pointsFor(nearbyAlternative).any { it.id == "known-nearby" })
        assertEquals(setOf("known-nearby"), ScenicPoiSharedState.pointsFor(routeOne).map { it.id }.toSet())
    }

    @Test
    fun allRoutesModeIsExplicitAndDoesNotMergeStorageByDefault() {
        ScenicPoiSharedState.publish(routeOne, listOf(poi("route-one-poi", routeOne[1])))
        ScenicPoiSharedState.publish(newlyAddedRoute, listOf(poi("new-route-poi", newlyAddedRoute[1])))

        assertEquals(listOf("route-one-poi"), ScenicPoiSharedState.pointsFor(routeOne).map { it.id })

        ScenicPoiSharedState.updateShowAllRoutes(true)
        val allIds = ScenicPoiSharedState.pointsFor(routeOne).map { it.id }.toSet()
        assertTrue("route-one-poi" in allIds)
        assertTrue("new-route-poi" in allIds)
    }
}
