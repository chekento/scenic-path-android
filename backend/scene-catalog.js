const has = (tags, key, values) => values.includes(String(tags?.[key] ?? "").toLowerCase());
const truthyTag = value => ["yes", "true", "1", "designated"].includes(String(value ?? "").toLowerCase());

function baseObservation({ kind, subtype, tags, signals = {}, relevance = 0.65 }) {
  return {
    kind,
    subtype,
    name: tags.name || tags["name:de"] || tags["name:en"],
    relevance,
    signals,
    tags,
  };
}

/**
 * Convert OSM-compatible tags into Scenic Path's restored scene taxonomy and
 * corridor signals. This is deliberately provider-neutral: any source that can
 * expose an OSM-like tag object can reuse the classifier.
 */
export function classifyTags(tags = {}) {
  const tourism = String(tags.tourism ?? "").toLowerCase();
  const historic = String(tags.historic ?? "").toLowerCase();
  const natural = String(tags.natural ?? "").toLowerCase();
  const amenity = String(tags.amenity ?? "").toLowerCase();
  const leisure = String(tags.leisure ?? "").toLowerCase();
  const landuse = String(tags.landuse ?? "").toLowerCase();
  const manMade = String(tags.man_made ?? "").toLowerCase();
  const highway = String(tags.highway ?? "").toLowerCase();
  const water = String(tags.water ?? "").toLowerCase();
  const waterway = String(tags.waterway ?? "").toLowerCase();

  if (highway === "motorway" || highway === "motorway_link") {
    return baseObservation({ tags, signals: { motorway: 1 }, relevance: 0.2 });
  }
  if (landuse === "industrial") {
    return baseObservation({ tags, signals: { industrial: 1 }, relevance: 0.2 });
  }
  if (landuse === "forest" || natural === "wood") {
    return baseObservation({ tags, signals: { forest: 1 }, relevance: 0.55 });
  }
  if (natural === "water" || water || ["river", "stream", "canal"].includes(waterway)) {
    return baseObservation({
      kind: tags.name ? "WATER" : undefined,
      subtype: water === "lake" || water === "reservoir" ? "lake" : waterway ? "river" : "water",
      tags,
      signals: { water: 1 },
      relevance: tags.name ? 0.7 : 0.5,
    });
  }
  if (truthyTag(tags.scenic)) {
    return baseObservation({ kind: "SCENIC", subtype: "scenic", tags, signals: { scenicHighlights: 1, beautifulRoads: 0.9 }, relevance: 0.8 });
  }

  if (tourism === "viewpoint") {
    return baseObservation({ kind: "VIEWPOINT", subtype: "viewpoint", tags, signals: { viewpoints: 1, scenicHighlights: 0.85 }, relevance: 0.95 });
  }
  if (tourism === "museum") {
    return baseObservation({ kind: "MUSEUM", subtype: "museum", tags, signals: { museums: 1, culture: 0.9 }, relevance: 0.9 });
  }
  if (["gallery", "artwork"].includes(tourism) || amenity === "arts_centre") {
    return baseObservation({ kind: "ART", subtype: tourism || "arts_centre", tags, signals: { art: 1, culture: 0.8 }, relevance: 0.82 });
  }
  if (tourism === "attraction") {
    return baseObservation({ kind: "SCENIC", subtype: "attraction", tags, signals: { scenicHighlights: 0.8, culture: 0.45 }, relevance: 0.7 });
  }

  if (["peak", "cape"].includes(natural)) {
    return baseObservation({
      kind: "NATURE",
      subtype: natural,
      tags,
      signals: { mountains: natural === "peak" ? 1 : 0.45, scenicHighlights: 0.8 },
      relevance: natural === "peak" ? 0.9 : 0.78,
    });
  }
  if (natural === "waterfall") {
    return baseObservation({ kind: "WATER", subtype: "waterfall", tags, signals: { water: 1, scenicHighlights: 1 }, relevance: 0.95 });
  }
  if (natural === "beach") {
    return baseObservation({ kind: "WATER", subtype: "beach", tags, signals: { water: 1, scenicHighlights: 0.8 }, relevance: 0.82 });
  }

  if (["castle", "ruins", "memorial", "monument", "archaeological_site", "battlefield"].includes(historic)) {
    return baseObservation({ kind: "MONUMENT", subtype: historic, tags, signals: { monuments: 1, culture: 0.9 }, relevance: 0.88 });
  }
  if (historic) {
    return baseObservation({ kind: "MONUMENT", subtype: historic, tags, signals: { monuments: 0.8, culture: 0.8 }, relevance: 0.76 });
  }

  if (["park", "garden", "nature_reserve"].includes(leisure)) {
    return baseObservation({ kind: "PARK", subtype: leisure, tags, signals: { parks: 1, forest: leisure === "nature_reserve" ? 0.7 : 0.25 }, relevance: 0.78 });
  }

  if (amenity === "place_of_worship") {
    const isHistoric = Boolean(tags.historic || tags.heritage || tags.start_date || tags.tourism);
    if (isHistoric) {
      return baseObservation({ kind: "WORSHIP", subtype: tags.building || "place_of_worship", tags, signals: { worship: 1, culture: 0.85 }, relevance: 0.82 });
    }
  }

  if (["restaurant", "cafe"].includes(amenity)) {
    // OSM supplies discovery only. Rating thresholds are enforced separately by
    // the licensed food provider before a stop becomes "Top food".
    return baseObservation({ kind: "FOOD", subtype: amenity, tags, signals: { food: 0.4 }, relevance: 0.35 });
  }

  if (["tower", "lighthouse", "bridge"].includes(manMade) || truthyTag(tags.bridge)) {
    return baseObservation({ kind: "ARCHITECTURE", subtype: manMade || "bridge", tags, signals: { architecture: 1, culture: 0.45 }, relevance: 0.8 });
  }

  if (has(tags, "building", ["cathedral", "church", "chapel", "mosque", "synagogue", "temple"]) && (tags.historic || tags.heritage)) {
    return baseObservation({ kind: "WORSHIP", subtype: tags.building, tags, signals: { worship: 1, culture: 0.85 }, relevance: 0.84 });
  }

  return null;
}
