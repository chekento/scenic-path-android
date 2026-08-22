package cloud.kosch.scenicpath

/**
 * Scene-point taxonomy restored from the original Scenic Path WebSim prototype.
 *
 * The first level stays intentionally compact in the UI. The second level keeps
 * the richer source types so routing/discovery can distinguish e.g. castles,
 * waterfalls and lighthouses without presenting dozens of switches at once.
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

    // Internal fallback: automatically considered, but not another visible switch.
    SCENIC("Scenic highlight", "⭐", ScenePointGroup.OTHER, 15, autoDiscoverable = true),
    CUSTOM("Custom", "📍", ScenePointGroup.OTHER, 30, autoDiscoverable = false),
}

/** Exact top-level category set exposed by the original prototype. */
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

    CASTLE("castle", StopKind.MONUMENT, "Castle"),
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

fun sceneKindForRawType(type: String?): StopKind {
    val t = type.orEmpty().lowercase()
    return when {
        "view" in t -> StopKind.VIEWPOINT
        "museum" in t -> StopKind.MUSEUM
        "park" in t || "garden" in t -> StopKind.PARK
        "art" in t || "gallery" in t -> StopKind.ART
        listOf("waterfall", "beach", "water", "lake", "river").any(t::contains) -> StopKind.WATER
        listOf("peak", "natural", "landmark", "cape").any(t::contains) -> StopKind.NATURE
        "attraction" in t -> StopKind.SCENIC
        listOf("monument", "castle", "ruins", "memorial", "historic").any(t::contains) -> StopKind.MONUMENT
        listOf("church", "worship", "cathedral", "mosque", "temple").any(t::contains) -> StopKind.WORSHIP
        listOf("food", "cafe", "restaurant").any(t::contains) -> StopKind.FOOD
        listOf("architecture", "tower", "lighthouse", "bridge").any(t::contains) -> StopKind.ARCHITECTURE
        else -> StopKind.SCENIC
    }
}
