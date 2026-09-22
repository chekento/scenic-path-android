package cloud.kosch.scenicpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorationPlanningTest {
    @Test fun freeExplorationTimeAcceptsHoursDaysAndWeeks() {
        assertEquals("2h", explorationTimeLabel(120))
        assertEquals("1w", explorationTimeLabel(10_080))
        assertEquals(18 * 60, parseExplorationTime("18h"))
        assertEquals(3 * 1_440, parseExplorationTime("3d"))
        assertEquals(2 * 10_080, parseExplorationTime("2w"))
        assertEquals(240, parseExplorationTime("240"))
        assertEquals(MAX_EXPLORATION_MINUTES, parseExplorationTime("90w"))
    }

    @Test fun longerExplorationExpandsTheScenicCorridor() {
        assertTrue(explorationCorridorKm(1_440) > explorationCorridorKm(240))
        assertTrue(explorationCorridorKm(10_080) > explorationCorridorKm(1_440))
        assertTrue(explorationCorridorKm(MAX_EXPLORATION_MINUTES) <= 240.0)
    }

    @Test fun weekPlanningPromotesSpacedCandidatesIntoTheItinerary() {
        val route = (0..30).map { GeoPoint(50.0, it.toDouble()) }
        val candidates = (1..24).map { index ->
            ScenePointUi(
                id = "poi-$index",
                name = "Route place $index",
                kind = if (index % 2 == 0) StopKind.MUSEUM.name else StopKind.VIEWPOINT.name,
                subtype = if (index % 2 == 0) "museum" else "viewpoint",
                point = GeoPoint(50.0, index.toDouble()),
                relevance = 1.0,
                suggestionScore = 100.0 - index,
                suggestedDwellMinutes = 30,
            )
        }

        val stops = ScenicAutoStopPlanner.select(candidates, route, 10_080, configuredMaxStops = 5)
        assertTrue(stops.size >= 10)
        assertTrue(stops.size <= 24)
        assertTrue(stops.all { ScenicAutoStopPlanner.isAutomatic(it) })
        assertEquals(stops.map { it.point?.lon }, stops.map { it.point?.lon }.sorted())
    }
}
