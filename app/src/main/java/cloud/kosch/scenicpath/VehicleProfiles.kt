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
    /** Comfort-planning limit per active driver/rider, not a legal driving-time statement. */
    val dailyTravelHours: Double = 7.0,
    /** Number of people who can share driving. Bicycles always plan with one rider. */
    val driverCount: Int = 1,
    val overnightPlanningEnabled: Boolean = true,
    val eBikeEnabled: Boolean = false,
    /** User-estimated practical range under their normal assistance level and terrain. */
    val eBikeRangeKm: Double = 80.0,
    /** Keep this percentage of the configured range as a reserve before suggesting charging. */
    val eBikeReservePercent: Int = 15,
) {
    val hasPhysicalRestrictions: Boolean
        get() = kind in setOf(VehicleKind.CAMPER, VehicleKind.TRUCK, VehicleKind.COACH)

    val effectiveDriverCount: Int
        get() = if (kind == VehicleKind.BICYCLE) 1 else driverCount.coerceIn(1, 3)

    companion object {
        fun defaults(kind: VehicleKind): VehicleProfile = when (kind) {
            VehicleKind.CAR -> VehicleProfile(
                kind = kind,
                dailyTravelHours = 7.0,
            )
            VehicleKind.MOTORCYCLE -> VehicleProfile(
                kind = kind,
                heightMeters = 1.45,
                widthMeters = 0.90,
                lengthMeters = 2.20,
                weightTons = 0.30,
                axleLoadTons = 0.30,
                dailyTravelHours = 6.0,
            )
            VehicleKind.CAMPER -> VehicleProfile(
                kind = kind,
                heightMeters = 3.05,
                widthMeters = 2.35,
                lengthMeters = 7.00,
                weightTons = 3.50,
                axleLoadTons = 1.90,
                dailyTravelHours = 6.0,
            )
            VehicleKind.TRUCK -> VehicleProfile(
                kind = kind,
                heightMeters = 4.00,
                widthMeters = 2.55,
                lengthMeters = 12.00,
                weightTons = 18.00,
                axleLoadTons = 9.00,
                axleCount = 3,
                dailyTravelHours = 8.0,
            )
            VehicleKind.COACH -> VehicleProfile(
                kind = kind,
                heightMeters = 3.80,
                widthMeters = 2.55,
                lengthMeters = 12.00,
                weightTons = 18.00,
                axleLoadTons = 7.50,
                axleCount = 3,
                dailyTravelHours = 8.0,
            )
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
                dailyTravelHours = 5.0,
                driverCount = 1,
                eBikeRangeKm = 80.0,
                eBikeReservePercent = 15,
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
            dailyTravelHours = prefs.getString("dailyTravelHours", null)?.toDoubleOrNull() ?: defaults.dailyTravelHours,
            driverCount = prefs.getInt("driverCount", defaults.driverCount),
            overnightPlanningEnabled = prefs.getBoolean("overnightPlanning", defaults.overnightPlanningEnabled),
            eBikeEnabled = prefs.getBoolean("eBikeEnabled", defaults.eBikeEnabled),
            eBikeRangeKm = prefs.getString("eBikeRangeKm", null)?.toDoubleOrNull() ?: defaults.eBikeRangeKm,
            eBikeReservePercent = prefs.getInt("eBikeReservePercent", defaults.eBikeReservePercent),
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
            .putString("dailyTravelHours", profile.dailyTravelHours.toString())
            .putInt("driverCount", profile.driverCount)
            .putBoolean("overnightPlanning", profile.overnightPlanningEnabled)
            .putBoolean("eBikeEnabled", profile.eBikeEnabled)
            .putString("eBikeRangeKm", profile.eBikeRangeKm.toString())
            .putInt("eBikeReservePercent", profile.eBikeReservePercent)
            .apply()
    }

    private fun VehicleProfile.sanitized(): VehicleProfile = copy(
        heightMeters = heightMeters.coerceIn(0.5, 6.0),
        widthMeters = widthMeters.coerceIn(0.4, 4.0),
        lengthMeters = lengthMeters.coerceIn(1.0, 30.0),
        weightTons = weightTons.coerceIn(0.05, 60.0),
        axleLoadTons = axleLoadTons.coerceIn(0.02, 40.0),
        axleCount = axleCount.coerceIn(2, 20),
        dailyTravelHours = dailyTravelHours.coerceIn(2.0, 12.0),
        driverCount = if (kind == VehicleKind.BICYCLE) 1 else driverCount.coerceIn(1, 3),
        eBikeRangeKm = eBikeRangeKm.coerceIn(15.0, 300.0),
        eBikeReservePercent = eBikeReservePercent.coerceIn(0, 40),
    )
}
