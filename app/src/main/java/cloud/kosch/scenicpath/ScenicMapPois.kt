package cloud.kosch.scenicpath

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Camera movement stays inside the native renderer; Compose only hosts the selected card. */
internal object ScenicMapPois {
    const val SOURCE = "scenic-pois"
    const val LAYER = "scenic-poi-icons"
    const val CLUSTERS = "scenic-poi-clusters"
    const val STOPS_SOURCE = "scenic-planned-stops"
    const val STOPS_LAYER = "scenic-stop-icons"

    fun install(style: Style) {
        val empty = FeatureCollection.fromFeatures(emptyArray<Feature>())
        if (style.getSource(SOURCE) != null) return
        style.addSource(GeoJsonSource(SOURCE, empty, GeoJsonOptions()
            .withCluster(true).withClusterMaxZoom(13).withClusterRadius(48)))
        style.addSource(GeoJsonSource(STOPS_SOURCE, empty))
        scenicCategoryLanes.forEach { lane ->
            style.addImage(lane.id, icon(lane.emoji, false))
            style.addImage("stop-${lane.id}", icon(lane.emoji, true))
        }
        style.addLayer(CircleLayer(CLUSTERS, SOURCE)
            .withFilter(Expression.has("point_count"))
            .withProperties(circleColor("#17685D"), circleRadius(21f),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
        style.addLayer(SymbolLayer("scenic-poi-count", SOURCE)
            .withFilter(Expression.has("point_count"))
            .withProperties(textField(Expression.toString(Expression.get("point_count"))),
                textSize(13f), textColor("#FFFFFF"), textAllowOverlap(true), textIgnorePlacement(true)))
        style.addLayer(SymbolLayer(LAYER, SOURCE)
            .withFilter(Expression.not(Expression.has("point_count")))
            .withProperties(iconImage(Expression.get("icon")), iconSize(0.5f),
                iconAllowOverlap(false), iconPadding(3f)))
        // Fixed waypoints never disappear into a cluster or lose priority to discoveries.
        style.addLayer(SymbolLayer(STOPS_LAYER, STOPS_SOURCE)
            .withProperties(iconImage(Expression.get("icon")), iconSize(0.6f),
                iconAllowOverlap(true), iconIgnorePlacement(true)))
    }

    fun features(points: List<ScenePointUi>): Pair<FeatureCollection, FeatureCollection> {
        val stops = ArrayList<Feature>()
        val discoveries = ArrayList<Feature>()
        points.forEach { point ->
            if (!point.point.lat.isFinite() || !point.point.lon.isFinite() ||
                point.point.lat !in -90.0..90.0 || point.point.lon !in -180.0..180.0) return@forEach
            val feature = Feature.fromGeometry(Point.fromLngLat(point.point.lon, point.point.lat))
            feature.addStringProperty("poi_id", point.id)
            feature.addStringProperty("icon", (if (point.includedInRoute) "stop-" else "") + scenicCategoryLaneFor(point).id)
            if (point.includedInRoute) stops += feature else discoveries += feature
        }
        return FeatureCollection.fromFeatures(discoveries) to FeatureCollection.fromFeatures(stops)
    }

    private fun icon(emoji: String, planned: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888)
        // Explicit density keeps symbol dimensions consistent across Android screen densities.
        bitmap.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(44f, 44f, 40f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (planned) 7f else 4f
        paint.color = Color.rgb(23, 104, 93)
        canvas.drawCircle(44f, 44f, 40f, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 42f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(emoji, 44f, 44f - (paint.ascent() + paint.descent()) / 2f, paint)
        return bitmap
    }
}
