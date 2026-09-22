package cloud.kosch.scenicpath

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File

/** Real native renderer exercise; deterministic POIs, no public enrichment requests. */
@RunWith(AndroidJUnit4::class)
class MapRendererStressTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun denseRoutePoiUpdatesCameraMotionAndBackgroundResume() {
        val route = (0..70_000).map { GeoPoint(50.0 + it * 0.00002, 8.0 + it * 0.00003) }
        val initial = (0..519).map { index ->
            ScenePointUi(id = "poi-$index", name = "Place $index", kind = StopKind.MUSEUM.name,
                subtype = "museum", point = route[index * 100], relevance = 1.0, suggestionScore = 10.0)
        }
        val location = mutableStateOf<GeoPoint?>(route.first())
        val map = AtomicReference<MapLibreMap?>()
        val ready = AtomicBoolean(false)
        val error = AtomicReference<String?>()
        compose.runOnUiThread {
            MapLibre.getInstance(compose.activity)
            ScenicPoiSharedState.clear()
        }
        compose.setContent {
            ScenicMap(Modifier.fillMaxSize(), userLocation = location.value, routePoints = route,
                highlights = initial, discoverPois = false, onMapError = { error.set(it) })
        }
        compose.waitUntil(30_000) {
            compose.activity.runOnUiThread {
                findMap(compose.activity.window.decorView)?.getMapAsync {
                    map.set(it)
                    ready.set(it.style?.isFullyLoaded == true)
                }
            }
            ready.get()
        }
        assertNull(error.get())
        repeat(24) { iteration ->
            // Replacement stresses native source updates while panning and receiving GPS fixes.
            runBlocking { ScenicPoiSharedState.publish(route, initial.map {
                it.copy(suggestionScore = 10.0 + iteration)
            }) }
            compose.runOnUiThread {
                location.value = route[iteration * 1_000]
                map.get()!!.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(location.value!!.lat, location.value!!.lon), if (iteration % 2 == 0) 7.0 else 15.0))
            }
            compose.waitForIdle()
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { findMap(compose.activity.window.decorView)!!.onLowMemory() }
        compose.waitForIdle()
        assertNull(error.get())
        compose.runOnUiThread {
            assertNotNull(map.get()!!.style!!.getLayer(ScenicMapPois.LAYER))
            assertNotNull(map.get()!!.style!!.getLayer(ScenicMapPois.POINTS_LAYER))
            assertNotNull(map.get()!!.style!!.getLayer(ScenicMapPois.CLUSTERS))
            assertNotNull(map.get()!!.style!!.getLayer(ScenicMapPois.STOPS_LAYER))
        }
        assertTrue(ScenicPoiSharedState.pointsFor(route).size <= 520)
        val memory = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(memory)
        println("Map renderer stress complete: 70,001 vertices, 520 POIs, 24 source/GPS/camera updates, background/resume; total PSS ${memory.totalPss} KiB")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screenshot ->
            File(compose.activity.externalCacheDir, "scenic-map-stress.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findMap(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
