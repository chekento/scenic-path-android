package cloud.kosch.scenicpath

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Scene-point taxonomy for Scenic Path.
 *
 * StopKind remains deliberately compact for route planning/provider selection. Smart Stops
 * and map markers use the richer ScenicCategoryLane taxonomy so distinctive places do not
 * disappear inside a handful of broad buckets.
 */
enum class ScenePointGroup(val label: String) {
    VIEWS_NATURE("Views & nature"),
    CULTURE_HISTORY("Culture & history"),
    PLACES_SPACES("Places & spaces"),
    FOOD("Food"),
    OTHER("Other"),
}

enum class StopKind(
    val label: String,
    val emoji: String,
    val group: ScenePointGroup,
    val defaultDwellMinutes: Int,
    val autoDiscoverable: Boolean = true,
) {
    VIEWPOINT("Viewpoint", "👁️", ScenePointGroup.VIEWS_NATURE, 12),
    MUSEUM("Museum", "🏛️", ScenePointGroup.CULTURE_HISTORY, 50),
    NATURE("Nature", "⛰️", ScenePointGroup.VIEWS_NATURE, 20),
    MONUMENT("Monument & history", "🗿", ScenePointGroup.CULTURE_HISTORY, 25),
    PARK("Park & garden", "🌳", ScenePointGroup.PLACES_SPACES, 25),
    ART("Art & public art", "🎨", ScenePointGroup.CULTURE_HISTORY, 35),
    WORSHIP("Historic worship", "⛪", ScenePointGroup.CULTURE_HISTORY, 20),
    WATER("Water", "💧", ScenePointGroup.VIEWS_NATURE, 25),
    FOOD("Food", "🍽️", ScenePointGroup.FOOD, 45),
    ARCHITECTURE("Architecture", "🏗️", ScenePointGroup.PLACES_SPACES, 18),
    SCENIC("Scenic attraction", "✨", ScenePointGroup.OTHER, 15, autoDiscoverable = true),
    CUSTOM("Custom", "📍", ScenePointGroup.OTHER, 30, autoDiscoverable = false),
}

/** Stable list used by the planner so disabled categories never disappear from the UI. */
val allSelectableSceneKinds: Set<StopKind> = linkedSetOf(
    StopKind.VIEWPOINT,
    StopKind.MUSEUM,
    StopKind.NATURE,
    StopKind.MONUMENT,
    StopKind.PARK,
    StopKind.ART,
    StopKind.WORSHIP,
    StopKind.WATER,
    StopKind.FOOD,
    StopKind.ARCHITECTURE,
    StopKind.SCENIC,
)

/**
 * The committed scene selection is shared by every discovery surface.
 * Draft changes are activated only when the user rebuilds the route.
 */
object ScenicSceneSelectionState {
    var activeKinds: Set<StopKind> by mutableStateOf(allSelectableSceneKinds)
        private set

    fun activate(kinds: Set<StopKind>) {
        activeKinds = kinds.intersect(allSelectableSceneKinds)
    }

    fun reset() {
        activeKinds = allSelectableSceneKinds
    }
}

val prototypeSelectableSceneKinds: Set<StopKind>
    get() = ScenicSceneSelectionState.activeKinds

enum class SceneSubtype(val rawId: String, val parent: StopKind, val label: String) {
    VIEWPOINT("viewpoint", StopKind.VIEWPOINT, "Viewpoint"),
    OBSERVATION_TOWER("observation_tower", StopKind.VIEWPOINT, "Observation tower"),
    MUSEUM("museum", StopKind.MUSEUM, "Museum"),
    PEAK("peak", StopKind.NATURE, "Peak"),
    NATURAL_LANDMARK("natural_landmark", StopKind.NATURE, "Natural landmark"),
    CAPE("cape", StopKind.NATURE, "Cape"),
    STONE("stone", StopKind.NATURE, "Natural stone"),
    CAVE("cave", StopKind.NATURE, "Cave"),
    GEOLOGICAL("geological", StopKind.NATURE, "Geological feature"),
    FOREST("forest", StopKind.NATURE, "Forest"),
    NATURE_RESERVE("nature_reserve", StopKind.PARK, "Nature reserve"),
    CASTLE("castle", StopKind.MONUMENT, "Castle"),
    DEFENSIVE_CASTLE("defensive_castle", StopKind.MONUMENT, "Castle / fortress"),
    STATELY_HOME("stately", StopKind.MONUMENT, "Stately home / château"),
    PALACE("palace", StopKind.MONUMENT, "Palace"),
    MANOR("manor", StopKind.MONUMENT, "Manor house"),
    RUINS("ruins", StopKind.MONUMENT, "Ruins"),
    ARCHAEOLOGY("archaeological_site", StopKind.MONUMENT, "Archaeological site"),
    BATTLEFIELD("battlefield", StopKind.MONUMENT, "Battlefield"),
    MEMORIAL("memorial", StopKind.MONUMENT, "Memorial"),
    HISTORIC("historic", StopKind.MONUMENT, "Historic site"),
    PARK("park", StopKind.PARK, "Park"),
    GARDEN("garden", StopKind.PARK, "Garden"),
    ARTWORK("artwork", StopKind.ART, "Artwork"),
    GALLERY("gallery", StopKind.ART, "Gallery"),
    CHURCH("church", StopKind.WORSHIP, "Church"),
    CATHEDRAL("cathedral", StopKind.WORSHIP, "Cathedral"),
    MOSQUE("mosque", StopKind.WORSHIP, "Mosque"),
    SYNAGOGUE("synagogue", StopKind.WORSHIP, "Synagogue"),
    TEMPLE("temple", StopKind.WORSHIP, "Temple"),
    WATERFALL("waterfall", StopKind.WATER, "Waterfall"),
    BEACH("beach", StopKind.WATER, "Beach"),
    LAKE("lake", StopKind.WATER, "Lake"),
    RIVER("river", StopKind.WATER, "River"),
    SPRING("spring", StopKind.WATER, "Spring"),
    RESTAURANT("restaurant", StopKind.FOOD, "Restaurant"),
    CAFE("cafe", StopKind.FOOD, "Cafe"),
    TOWER("tower", StopKind.ARCHITECTURE, "Tower"),
    LIGHTHOUSE("lighthouse", StopKind.ARCHITECTURE, "Lighthouse"),
    BRIDGE("bridge", StopKind.ARCHITECTURE, "Bridge"),
    AQUEDUCT("aqueduct", StopKind.ARCHITECTURE, "Aqueduct"),
    WINDMILL("windmill", StopKind.ARCHITECTURE, "Windmill"),
    WATERMILL("watermill", StopKind.ARCHITECTURE, "Watermill"),
    ZOO("zoo", StopKind.SCENIC, "Zoo / animal park"),
    THEME_PARK("theme_park", StopKind.SCENIC, "Theme park"),
    ATTRACTION("attraction", StopKind.SCENIC, "Attraction"),
    SCENIC("scenic", StopKind.SCENIC, "Scenic highlight"),
}

data class ScenicCategoryLane(val id: String, val label: String, val emoji: String)

val scenicCategoryLanes: List<ScenicCategoryLane> = listOf(
    ScenicCategoryLane("viewpoints", "Viewpoints & observation points", "👁️"),
    ScenicCategoryLane("museums", "Museums", "🏛️"),
    ScenicCategoryLane("peaks-landmarks", "Peaks & natural landmarks", "⛰️"),
    ScenicCategoryLane("caves-geology", "Caves & geology", "🪨"),
    ScenicCategoryLane("forests-woodland", "Forests & woodland", "🌲"),
    ScenicCategoryLane("nature-reserves", "Nature reserves", "🌿"),
    ScenicCategoryLane("castles-fortresses", "Castles & fortresses", "🏰"),
    ScenicCategoryLane("palaces-manors", "Palaces & manor houses", "🏯"),
    ScenicCategoryLane("ruins-archaeology", "Ruins & archaeology", "🏺"),
    ScenicCategoryLane("monuments-memorials", "Monuments & memorials", "🗿"),
    ScenicCategoryLane("parks-gardens", "Parks & gardens", "🌳"),
    ScenicCategoryLane("art-galleries", "Art & galleries", "🎨"),
    ScenicCategoryLane("historic-worship", "Historic worship", "⛪"),
    ScenicCategoryLane("waterfalls", "Waterfalls", "🌊"),
    ScenicCategoryLane("beaches", "Beaches", "🏖️"),
    ScenicCategoryLane("lakes-rivers", "Lakes, rivers & springs", "💧"),
    ScenicCategoryLane("restaurants-cafes", "Restaurants & cafés", "🍽️"),
    ScenicCategoryLane("towers-lighthouses", "Towers & lighthouses", "🗼"),
    ScenicCategoryLane("bridges-aqueducts", "Bridges & aqueducts", "🌉"),
    ScenicCategoryLane("mills-industrial", "Mills & industrial heritage", "⚙️"),
    ScenicCategoryLane("zoos-wildlife", "Zoos & animal parks", "🦒"),
    ScenicCategoryLane("theme-parks", "Theme parks", "🎢"),
    ScenicCategoryLane("scenic-highlights", "Scenic attractions", "✨"),
)

private val scenicCategoryLaneById: Map<String, ScenicCategoryLane> = scenicCategoryLanes.associateBy { it.id }

fun scenicCategoryLaneFor(point: ScenePointUi): ScenicCategoryLane {
    val subtype = point.subtype.orEmpty().lowercase()
    val kind = StopKind.entries.firstOrNull { it.name == point.kind } ?: sceneKindForRawType(subtype)
    val id = when (kind) {
        StopKind.VIEWPOINT -> "viewpoints"
        StopKind.MUSEUM -> "museums"
        StopKind.NATURE -> when (subtype) {
            "cave", "cave_entrance", "geological" -> "caves-geology"
            "forest", "wood" -> "forests-woodland"
            else -> "peaks-landmarks"
        }
        StopKind.PARK -> if (subtype in setOf("nature_reserve", "protected_area", "national_park")) "nature-reserves" else "parks-gardens"
        StopKind.MONUMENT -> when (subtype) {
            "castle", "defensive_castle", "fort" -> "castles-fortresses"
            "stately", "palace", "manor", "manor_house" -> "palaces-manors"
            "ruins", "archaeological_site", "battlefield" -> "ruins-archaeology"
            else -> "monuments-memorials"
        }
        StopKind.ART -> "art-galleries"
        StopKind.WORSHIP -> "historic-worship"
        StopKind.WATER -> when (subtype) {
            "waterfall" -> "waterfalls"
            "beach" -> "beaches"
            else -> "lakes-rivers"
        }
        StopKind.FOOD -> "restaurants-cafes"
        StopKind.ARCHITECTURE -> when (subtype) {
            "bridge", "aqueduct" -> "bridges-aqueducts"
            "windmill", "watermill" -> "mills-industrial"
            else -> "towers-lighthouses"
        }
        StopKind.SCENIC, StopKind.CUSTOM -> when (subtype) {
            "zoo" -> "zoos-wildlife"
            "theme_park" -> "theme-parks"
            else -> "scenic-highlights"
        }
    }
    return scenicCategoryLaneById.getValue(id)
}

fun sceneKindForRawType(type: String?): StopKind {
    val t = type.orEmpty().lowercase()
    return when {
        "viewpoint" in t || "observation_tower" in t -> StopKind.VIEWPOINT
        "museum" in t -> StopKind.MUSEUM
        "nature_reserve" in t || "protected_area" in t || "national_park" in t -> StopKind.PARK
        t == "park" || t == "garden" -> StopKind.PARK
        "art" in t || "gallery" in t -> StopKind.ART
        listOf("waterfall", "beach", "water", "lake", "river", "spring").any(t::contains) -> StopKind.WATER
        listOf("peak", "natural", "landmark", "cape", "stone", "rock", "cave", "geological", "forest", "wood").any(t::contains) -> StopKind.NATURE
        listOf("zoo", "theme_park", "attraction", "scenic").any(t::contains) -> StopKind.SCENIC
        listOf("monument", "castle", "defensive_castle", "stately", "palace", "manor", "ruins", "memorial", "historic", "fort", "archaeological_site", "battlefield").any(t::contains) -> StopKind.MONUMENT
        listOf("church", "worship", "cathedral", "mosque", "synagogue", "temple", "chapel").any(t::contains) -> StopKind.WORSHIP
        listOf("food", "cafe", "restaurant").any(t::contains) -> StopKind.FOOD
        listOf("architecture", "tower", "lighthouse", "bridge", "aqueduct", "windmill", "watermill").any(t::contains) -> StopKind.ARCHITECTURE
        else -> StopKind.SCENIC
    }
}
