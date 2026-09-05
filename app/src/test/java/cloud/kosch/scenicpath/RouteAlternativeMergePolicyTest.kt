package cloud.kosch.scenicpath

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteAlternativeMergePolicyTest {
    private fun route(id: String, offset: Double) = RouteCandidateUi(
        id = id,
        character = RouteCharacter.BEAUTIFUL.name,
        distanceMeters = 10_000.0 + offset * 1_000,
        durationSeconds = 1_200.0 + offset * 60,
        scenicScore = 80.0,
        extraMinutes = 10.0,
        points = listOf(
            GeoPoint(53.60, 10.00),
            GeoPoint(53.62 + offset, 10.05 + offset),
            GeoPoint(53.64, 10.10),
        ),
        provider = "test",
    )

    @Test
    fun plusRouteKeepsExistingOrderAndAppendsOnlyNewCandidate() {
        val one = route("one", 0.0)
        val two = route("two", 0.03)
        val three = route("three", 0.07)
        val existing = RoutePlanUi(candidates = listOf(one, two))
        val refreshed = RoutePlanUi(candidates = listOf(one.copy(variantLabel = "new label"), three, two))

        val merged = RouteAlternativeMergePolicy.merge(existing, refreshed, 3)

        assertEquals(listOf("one", "two", "three"), merged.candidates.map { it.id })
        assertEquals(null, merged.candidates[0].variantLabel)
    }
}
