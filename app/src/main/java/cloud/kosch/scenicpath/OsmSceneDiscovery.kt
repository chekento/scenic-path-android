package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Journey-optimizer scene discovery.
 *
 * v0.5 intentionally has one route-wide discovery stack. FastRoutePoiDiscovery combines a
 * quick Photon pass with a bounded continuous-corridor Precision pass, so the optimizer,
 * Smart Stops and map no longer disagree simply because they used different algorithms.
 */
object OsmSceneDiscovery {
    suspend fun discover(
        route: List<GeoPoint>,
        enabledKinds: Set<StopKind> = prototypeSelectableSceneKinds,
        maxResults: Int = 24,
    ): List<ScenePointUi> = withContext(Dispatchers.IO) {
        FastRoutePoiDiscovery.discover(
            route = route,
            enabledKinds = enabledKinds,
            maxResults = maxResults,
        )
    }
}
