package cloud.kosch.scenicpath

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class VehicleKind(val label: String, val emoji: String) {
    CAR("Car", "🚗"),
    MOTORCYCLE("Motorcycle", "🏍️"),
    CAMPER("Camper / motorhome", "🚐"),
    TRUCK("Truck", "🚛"),
    COACH("Coach / bus", "🚌"),
    BICYCLE("Bicycle / e-bike", "🚲"),
}

enum class ScenicBicycleType(val label: String, val apiValue: String) {
    CITY("City / touring", "city"),
    HYBRID("Hybrid / trekking", "hybrid"),
    ROAD("Road bike", "road"),
    CROSS("Gravel / cross", "cross"),
    MOUNTAIN("Mountain bike", "mountain"),
}

data class VehicleProfile(
    val kind: VehicleKind = VehicleKind.CAR,
    val heightMeters: Double = 1.65,
    val widthMeters: Double = 1.90,
    val lengthMeters: Double = 4.60,
    val weightTons: Double = 1.80,
    val axleLoadTons: Double = 1.10,
    val axleCount: Int = 2,
    val bicycleType: ScenicBicycleType = ScenicBicycleType.HYBRID,
    val allowUnpavedBikePaths: Boolean = true,
) {
    val hasPhysicalRestrictions: Boolean
        get() = kind in setOf(VehicleKind.CAMPER, VehicleKind.TRUCK, VehicleKind.COACH)

    companion object {
        fun defaults(kind: VehicleKind): VehicleProfile = when (kind) {
            VehicleKind.CAR -> VehicleProfile(kind, 1.65, 1.90, 4.60, 1.80, 1.10, 2)
            VehicleKind.MOTORCYCLE -> VehicleProfile(kind, 1.45, 0.90, 2.20, 0.30, 0.30, 2)
            VehicleKind.CAMPER -> VehicleProfile(kind, 3.05, 2.35, 7.00, 3.50, 1.90, 2)
            VehicleKind.TRUCK -> VehicleProfile(kind, 4.00, 2.55, 12.00, 18.00, 9.00, 3)
            VehicleKind.COACH -> VehicleProfile(kind, 3.80, 2.55, 12.00, 18.00, 7.50, 3)
            VehicleKind.BICYCLE -> VehicleProfile(
                kind = kind,
                heightMeters = 1.80,
                widthMeters = 0.75,
                lengthMeters = 1.85,
                weightTons = 0.11,
                axleLoadTons = 0.06,
                axleCount = 2,
                bicycleType = ScenicBicycleType.HYBRID,
                allowUnpavedBikePaths = true,
            )
        }
    }
}

/** Persisted routing identity shared by planner, debug Valhalla and production backend payloads. */
object VehicleSettingsState {
    private const val PREFS = "scenic_vehicle_profile"

    var profile: VehicleProfile by mutableStateOf(VehicleProfile.defaults(VehicleKind.CAR))
        private set

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kind = runCatching {
            VehicleKind.valueOf(prefs.getString("kind", VehicleKind.CAR.name) ?: VehicleKind.CAR.name)
        }.getOrDefault(VehicleKind.CAR)
        val defaults = VehicleProfile.defaults(kind)
        profile = defaults.copy(
            heightMeters = prefs.getString("height", null)?.toDoubleOrNull() ?: defaults.heightMeters,
            widthMeters = prefs.getString("width", null)?.toDoubleOrNull() ?: defaults.widthMeters,
            lengthMeters = prefs.getString("length", null)?.toDoubleOrNull() ?: defaults.lengthMeters,
            weightTons = prefs.getString("weight", null)?.toDoubleOrNull() ?: defaults.weightTons,
            axleLoadTons = prefs.getString("axleLoad", null)?.toDoubleOrNull() ?: defaults.axleLoadTons,
            axleCount = prefs.getInt("axles", defaults.axleCount),
            bicycleType = runCatching {
                ScenicBicycleType.valueOf(prefs.getString("bikeType", defaults.bicycleType.name) ?: defaults.bicycleType.name)
            }.getOrDefault(defaults.bicycleType),
            allowUnpavedBikePaths = prefs.getBoolean("bikeUnpaved", defaults.allowUnpavedBikePaths),
        ).sanitized()
    }

    fun update(context: Context, next: VehicleProfile) {
        profile = next.sanitized()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("kind", profile.kind.name)
            .putString("height", profile.heightMeters.toString())
            .putString("width", profile.widthMeters.toString())
            .putString("length", profile.lengthMeters.toString())
            .putString("weight", profile.weightTons.toString())
            .putString("axleLoad", profile.axleLoadTons.toString())
            .putInt("axles", profile.axleCount)
            .putString("bikeType", profile.bicycleType.name)
            .putBoolean("bikeUnpaved", profile.allowUnpavedBikePaths)
            .apply()
    }

    private fun VehicleProfile.sanitized(): VehicleProfile = copy(
        heightMeters = heightMeters.coerceIn(0.5, 6.0),
        widthMeters = widthMeters.coerceIn(0.4, 4.0),
        lengthMeters = lengthMeters.coerceIn(1.0, 30.0),
        weightTons = weightTons.coerceIn(0.05, 60.0),
        axleLoadTons = axleLoadTons.coerceIn(0.02, 40.0),
        axleCount = axleCount.coerceIn(2, 20),
    )
}
