package cloud.kosch.scenicpath

import androidx.compose.runtime.mutableStateOf

/**
 * Journey-scoped POI memory shared by Smart Stops and the map.
 *
 * A route geometry is allowed to change many times while the user is still planning the same
 * journey: selected POIs become mandatory waypoints, flexible order can change and the corridor
 * can deliberately expand through a detour city. None of those edits should erase discoveries
 * that were already useful a few seconds earlier.
 *
 * Earlier builds inferred journey identity from the first/last coordinates of the *routed
 * polyline*. That is not stable: Valhalla snaps start/end coordinates to nearby road edges and a
 * later pairwise waypoint rebuild can choose a slightly different snap. A difference of only a
 * few metres was enough to look like a new journey and clear the complete POI population.
 *
 * The state is now keyed by an explicit planning-session id owned by ScenicExperienceRoot.
 * Recalculating the same plan only MERGES discoveries. The pool is reset only when the user
 * explicitly changes start/destination and therefore starts a new planning session.
 */
object ScenicPoiSharedState {
    private const val MAX_SHARED_POINTS = 520

    private val activeJourneyKey = mutableStateOf<String?>(null)
    private val publishedPoints = mutableStateOf<List<ScenePointUi>>(emptyList())

    fun beginJourney(journeyKey: String) {
        if (activeJourneyKey.value == journeyKey) return
        activeJourneyKey.value = journeyKey
        publishedPoints.value = emptyList()
    }

    fun publish(
        journeyKey: String,
        route: List<GeoPoint>,
        points: List<ScenePointUi>,
    ) {
        if (route.size < 2 || points.isEmpty()) return
        beginJourney(journeyKey)

        val next = PrecisionRoutePoiDiscovery.mergeForDisplay(
            first = points,
            second = publishedPoints.value,
            maxResults = MAX_SHARED_POINTS,
        )
        if (next.isNotEmpty()) publishedPoints.value = next
    }

    fun pointsFor(journeyKey: String): List<ScenePointUi> {
        return if (activeJourneyKey.value == journeyKey) publishedPoints.value else emptyList()
    }

    fun clearJourney(journeyKey: String) {
        if (activeJourneyKey.value != journeyKey) return
        publishedPoints.value = emptyList()
    }
}
