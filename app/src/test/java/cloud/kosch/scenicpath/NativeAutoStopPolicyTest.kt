package cloud.kosch.scenicpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAutoStopPolicyTest {
    private fun point(
        id: String,
        kind: StopKind,
        relevance: Double = 0.8,
        rating: Double? = null,
        reviews: Int? = null,
        open: Boolean? = null,
    ) = ScenePointUi(
        id = id,
        name = id,
        kind = kind.name,
        subtype = if (kind == StopKind.FOOD) "restaurant" else null,
        point = GeoPoint(53.6, 10.0),
        relevance = relevance,
        distanceFromRouteMeters = 700,
        suggestedDwellMinutes = if (kind == StopKind.FOOD) 45 else 20,
        rating = rating,
        ratingCount = reviews,
        openNow = open,
    )

    @Test
    fun emptyCategorySelectionCannotCreateAutomaticStops() {
        val selected = NativeAutoStopPolicy.select(
            listOf(point("view", StopKind.VIEWPOINT)),
            ScenicPreferences(maxExtraMinutes = 120),
            emptySet(),
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun foodQualityAndOpeningFiltersAreStrict() {
        val preferences = ScenicPreferences(
            maxExtraMinutes = 120,
            maxStops = 3,
            minimumFoodRating = 4.6,
            minimumFoodReviewCount = 100,
            onlyOpenFood = true,
        )
        assertTrue(NativeAutoStopPolicy.foodMatches(point("good", StopKind.FOOD, rating = 4.8, reviews = 500, open = true), preferences))
        assertFalse(NativeAutoStopPolicy.foodMatches(point("low", StopKind.FOOD, rating = 4.4, reviews = 500, open = true), preferences))
        assertFalse(NativeAutoStopPolicy.foodMatches(point("few", StopKind.FOOD, rating = 4.9, reviews = 20, open = true), preferences))
        assertFalse(NativeAutoStopPolicy.foodMatches(point("closed", StopKind.FOOD, rating = 4.9, reviews = 500, open = false), preferences))
        assertFalse(NativeAutoStopPolicy.foodMatches(point("unknown", StopKind.FOOD, rating = 4.9, reviews = null, open = true), preferences))
    }

    @Test
    fun automaticStopLimitMatchesProductionBudgetTiers() {
        assertEquals(0, NativeAutoStopPolicy.limit(29, 8))
        assertEquals(1, NativeAutoStopPolicy.limit(30, 8))
        assertEquals(2, NativeAutoStopPolicy.limit(100, 8))
        assertEquals(3, NativeAutoStopPolicy.limit(210, 8))
        assertEquals(2, NativeAutoStopPolicy.limit(300, 2))
    }

    @Test
    fun foodGetsReservedSlotWhenItPassesUserQualitySettings() {
        val preferences = ScenicPreferences(
            maxExtraMinutes = 120,
            maxStops = 2,
            minimumFoodRating = 4.6,
            minimumFoodReviewCount = 100,
        )
        val selected = NativeAutoStopPolicy.select(
            listOf(
                point("castle", StopKind.MONUMENT, relevance = 1.0),
                point("view", StopKind.VIEWPOINT, relevance = 0.98),
                point("food", StopKind.FOOD, relevance = 0.75, rating = 4.8, reviews = 900, open = true),
            ),
            preferences,
            setOf(StopKind.MONUMENT, StopKind.VIEWPOINT, StopKind.FOOD),
        )
        assertEquals(2, selected.size)
        assertTrue(selected.any { it.id == "food" })
    }
}
