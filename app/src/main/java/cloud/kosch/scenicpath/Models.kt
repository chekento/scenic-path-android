package cloud.kosch.scenicpath

data class GeoPoint(val lat: Double, val lon: Double)

/**
 * Default Scenic DNA is deliberately diverse.
 *
 * Earlier builds heavily over-weighted water/forest/viewpoints while food was only 0.35.
 * That made a nominally "Beautiful" journey look like a nature-only filter even when the
 * user had enabled museums, culture and restaurants. The defaults now keep natural scenery
 * strong without starving urban, heritage, architecture or food candidates. Users can still
 * push any dimension to 0–100% in the native planner.
 */
data class ScenicWeights(
    val beautifulRoads: Float = 0.90f,
    val forest: Float = 0.70f,
    val water: Float = 0.72f,
    val mountains: Float = 0.68f,
    val viewpoints: Float = 0.82f,
    val culture: Float = 0.82f,
    val monuments: Float = 0.80f,
    val museums: Float = 0.78f,
    val art: Float = 0.72f,
    val worship: Float = 0.62f,
    val architecture: Float = 0.75f,
    val parks: Float = 0.68f,
    val food: Float = 0.72f,
    val scenicHighlights: Float = 0.80f,
)

data class ScenicPreferences(
    val weights: ScenicWeights = ScenicWeights(),
    val maxExtraMinutes: Int = 45,
    val maxExtraPercent: Int = 35,
    val maxStops: Int = 5,
    val minimumFoodRating: Double = 4.6,
    val minimumFoodReviewCount: Int = 100,
    val onlyOpenFood: Boolean = false,
    val avoidMotorways: Boolean = true,
    val avoidTolls: Boolean = false,
    val windingness: Int = 70,
    val hilliness: Int = 55,
    val vehicle: VehicleProfile = VehicleSettingsState.profile,
    /**
     * Internal execution marker. Presets are applied while the user edits the planner; once the
     * final controls are committed for a route build, deeper routing layers must not silently
     * re-apply a character preset over explicit user choices.
     */
    val constraintsCommitted: Boolean = false,
)

data class RouteSummary(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val scenicScore: Double,
    val extraMinutes: Double,
    val points: List<GeoPoint> = emptyList(),
    val stops: List<ScenicStop> = emptyList(),
)

data class ScenicStop(
    val id: String,
    val name: String,
    val type: String,
    val point: GeoPoint,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val description: String? = null,
    val attribution: String? = null,
)
