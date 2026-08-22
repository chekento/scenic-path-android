package cloud.kosch.scenicpath

/**
 * Scene-point taxonomy restored from the original Scenic Path WebSim prototype.
 *
 * The first level stays intentionally compact in the route-planning UI. Smart Stops and
 * the map expose the richer scene taxonomy below so castles, waterfalls, beaches, bridges,
 * reserves, ruins, galleries, etc. do not collapse into a handful of generic buckets.
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
    FOOD("Top food", "🍽️", ScenePointGroup.FOOD, 45),
    ARCHITECTURE("Towers & bridges", "🏗️", ScenePointGroup.PLACES_SPACES, 18),

    // Internal fallback: automatically considered, but not another route-planner switch.
    SCENIC("Scenic highlight", "✨", ScenePointGroup.OTHER, 15, autoDiscoverable = true),
    CUSTOM("Custom", "📍", ScenePointGroup.OTHER, 30, autoDiscoverable = false),
}

/** Compact top-level category set used by the route planner and discovery providers. */
val prototypeSelectableSceneKinds: Set<StopKind> = linkedSetOf(
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
)

enum class SceneSubtype(val rawId: String, val parent: StopKind, val label: String) {
    VIEWPOINT("viewpoint", StopKind.VIEWPOINT, "Viewpoint"),
    MUSEUM("museum", StopKind.MUSEUM, "Museum"),

    PEAK("peak", StopKind.NATURE, "Peak"),
    NATURAL_LANDMARK("natural_landmark", StopKind.NATURE, "Natural landmark"),
    CAPE("cape", StopKind.NATURE, "Cape"),
    STONE("stone", StopKind.NATURE, "Natural stone"),
    NATURE_RESERVE("nature_reserve", StopKind.PARK, "Nature reserve"),

    CASTLE("castle", StopKind.MONUMENT, "Castle"),
    DEFENSIVE_CASTLE("defensive_castle", StopKind.MONUMENT, "Castle / fortress"),
    STATELY_HOME("stately", StopKind.MONUMENT, "Stately home / château"),
    PALACE("palace", StopKind.MONUMENT, "Palace"),
    MANOR("manor", StopKind.MONUMENT, "Manor house"),
    RUINS("ruins", StopKind.MONUMENT, "Ruins"),
    MEMORIAL("memorial", StopKind.MONUMENT, "Memorial"),
    HISTORIC("historic", StopKind.MONUMENT, "Historic site"),
    ATTRACTION("attraction", StopKind.SCENIC, "Attraction"),

    PARK("park", StopKind.PARK, "Park"),
    GARDEN("garden", StopKind.PARK, "Garden"),

    ARTWORK("artwork", StopKind.ART, "Artwork"),
    GALLERY("gallery", StopKind.ART, "Gallery"),

    CHURCH("church", StopKind.WORSHIP, "Church"),
    CATHEDRAL("cathedral", StopKind.WORSHIP, "Cathedral"),
    MOSQUE("mosque", StopKind.WORSHIP, "Mosque"),
    TEMPLE("temple", StopKind.WORSHIP, "Temple"),

    WATERFALL("waterfall", StopKind.WATER, "Waterfall"),
    BEACH("beach", StopKind.WATER, "Beach"),
    LAKE("lake", StopKind.WATER, "Lake"),
    RIVER("river", StopKind.WATER, "River"),

    RESTAURANT("restaurant", StopKind.FOOD, "Restaurant"),
    CAFE("cafe", StopKind.FOOD, "Cafe"),

    TOWER("tower", StopKind.ARCHITECTURE, "Tower"),
    LIGHTHOUSE("lighthouse", StopKind.ARCHITECTURE, "Lighthouse"),
    BRIDGE("bridge", StopKind.ARCHITECTURE, "Bridge"),

    SCENIC("scenic", StopKind.SCENIC, "Scenic highlight"),
}

/**
 * Rich presentation taxonomy shared by Smart Stops and map markers.
 *
 * It intentionally has more lanes than the compact discovery switches. Provider data is
 * still fetched efficiently by StopKind, then split here by subtype for a much more useful
 * human-facing result set.
 */
data class ScenicCategoryLane(
    val id: String,
    val label: String,
    val emoji: String,
)

val scenicCategoryLanes: List<ScenicCategoryLane> = listOf(
    ScenicCategoryLane("viewpoints", "Viewpoints", "👁️"),
    ScenicCategoryLane("museums", "Museums", "🏛️"),
    ScenicCategoryLane("peaks-landmarks", "Peaks & natural landmarks", "⛰️"),
    ScenicCategoryLane("nature-reserves", "Nature reserves", "🌲"),
    ScenicCategoryLane("castles-fortresses", "Castles & fortresses", "🏰"),
    ScenicCategoryLane("palaces-manors", "Palaces & manor houses", "🏯"),
    ScenicCategoryLane("ruins-archaeology", "Ruins & archaeology", "🏺"),
    ScenicCategoryLane("monuments-memorials", "Monuments & memorials", "🗿"),
    ScenicCategoryLane("parks-gardens", "Parks & gardens", "🌳"),
    ScenicCategoryLane("art-galleries", "Art & galleries", "🎨"),
    ScenicCategoryLane("historic-worship", "Historic worship", "⛪"),
    ScenicCategoryLane("waterfalls", "Waterfalls", "🌊"),
    ScenicCategoryLane("beaches", "Beaches", "🏖️"),
    ScenicCategoryLane("lakes-rivers", "Lakes & rivers", "💧"),
    ScenicCategoryLane("top-food", "Top food", "🍽️"),
    ScenicCategoryLane("towers-lighthouses", "Towers & lighthouses", "🗼"),
    ScenicCategoryLane("bridges-aqueducts", "Bridges & aqueducts", "🌉"),
    ScenicCategoryLane("scenic-highlights", "Scenic attractions", "✨"),
)

private val scenicCategoryLaneById: Map<String, ScenicCategoryLane> =
    scenicCategoryLanes.associateBy { it.id }

fun scenicCategoryLaneFor(point: ScenePointUi): ScenicCategoryLane {
    val subtype = point.subtype.orEmpty().lowercase()
    val kind = StopKind.entries.firstOrNull { it.name == point.kind } ?: sceneKindForRawType(subtype)
    val id = when (kind) {
        StopKind.VIEWPOINT -> "viewpoints"
        StopKind.MUSEUM -> "museums"
        StopKind.NATURE -> "peaks-landmarks"
        StopKind.PARK -> if (subtype == "nature_reserve" || subtype == "protected_area") {
            "nature-reserves"
        } else {
            "parks-gardens"
        }
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
        StopKind.FOOD -> "top-food"
        StopKind.ARCHITECTURE -> when (subtype) {
            "bridge", "aqueduct" -> "bridges-aqueducts"
            else -> "towers-lighthouses"
        }
        StopKind.SCENIC, StopKind.CUSTOM -> "scenic-highlights"
    }
    return scenicCategoryLaneById.getValue(id)
}

fun sceneKindForRawType(type: String?): StopKind {
    val t = type.orEmpty().lowercase()
    return when {
        "view" in t -> StopKind.VIEWPOINT
        "museum" in t -> StopKind.MUSEUM
        "nature_reserve" in t || "protected_area" in t -> StopKind.PARK
        "park" in t || "garden" in t -> StopKind.PARK
        "art" in t || "gallery" in t -> StopKind.ART
        listOf("waterfall", "beach", "water", "lake", "river").any(t::contains) -> StopKind.WATER
        listOf("peak", "natural", "landmark", "cape", "stone", "rock").any(t::contains) -> StopKind.NATURE
        "attraction" in t || "scenic" in t -> StopKind.SCENIC
        listOf(
            "monument", "castle", "defensive_castle", "stately", "palace", "manor",
            "ruins", "memorial", "historic", "fort", "archaeological_site", "battlefield"
        ).any(t::contains) -> StopKind.MONUMENT
        listOf("church", "worship", "cathedral", "mosque", "synagogue", "temple", "chapel").any(t::contains) -> StopKind.WORSHIP
        listOf("food", "cafe", "restaurant").any(t::contains) -> StopKind.FOOD
        listOf("architecture", "tower", "lighthouse", "bridge", "aqueduct").any(t::contains) -> StopKind.ARCHITECTURE
        else -> StopKind.SCENIC
    }
}
