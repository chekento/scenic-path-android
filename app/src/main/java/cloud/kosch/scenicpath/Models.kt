package cloud.kosch.scenicpath

data class GeoPoint(val lat: Double, val lon: Double)

data class ScenicWeights(
    val beautifulRoads: Float = 0.90f,
    val forest: Float = 0.85f,
    val water: Float = 0.90f,
    val mountains: Float = 0.75f,
    val viewpoints: Float = 1.00f,
    val culture: Float = 0.80f,
    val monuments: Float = 0.78f,
    val museums: Float = 0.65f,
    val art: Float = 0.58f,
    val worship: Float = 0.48f,
    val architecture: Float = 0.65f,
    val parks: Float = 0.60f,
    val food: Float = 0.35f,
    val scenicHighlights: Float = 0.70f,
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
