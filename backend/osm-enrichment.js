import { samplePolyline } from "./corridor-analyzer.js";
import { classifyTags } from "./scene-catalog.js";

function elementPoint(element) {
  const lat = element.lat ?? element.center?.lat;
  const lon = element.lon ?? element.center?.lon;
  return Number.isFinite(lat) && Number.isFinite(lon) ? { lat, lon } : null;
}

function queryForAnchors(anchors) {
  const clauses = anchors.flatMap(({ lat, lon }) => {
    const around = `(around:1600,${lat.toFixed(6)},${lon.toFixed(6)})`;
    return [
      `nwr${around}["tourism"~"viewpoint|museum|gallery|artwork|attraction"];`,
      `nwr${around}["historic"];`,
      `nwr${around}["natural"~"peak|cape|waterfall|beach|wood|water"];`,
      `nwr${around}["landuse"~"forest|industrial"];`,
      `nwr${around}["water"~"lake|reservoir"];`,
      `nwr${around}["waterway"~"river|stream|canal"];`,
      `nwr${around}["leisure"~"park|garden|nature_reserve"];`,
      `nwr${around}["amenity"~"place_of_worship|restaurant|cafe|arts_centre"];`,
      `nwr${around}["man_made"~"tower|lighthouse|bridge"];`,
      `way${around}["bridge"="yes"];`,
      `way${around}["highway"~"motorway|motorway_link"];`,
      `nwr${around}["scenic"="yes"];`,
    ];
  });
  return `[out:json][timeout:25];(${clauses.join("")});out center tags;`;
}

/**
 * Optional OSM-compatible enrichment. No public endpoint is hard-coded: production
 * must configure OSM_ENRICHMENT_URL to a managed/self-hosted service with suitable SLA.
 */
export async function enrichRouteFromOsm({ points, endpoint, signal }) {
  if (!endpoint) return { observations: [], source: "disabled", reason: "OSM_ENRICHMENT_URL not configured" };
  const anchors = samplePolyline(points, { spacingMeters: 5_000, maxSamples: 10 });
  if (!anchors.length) return { observations: [], source: "osm-compatible" };

  const query = queryForAnchors(anchors);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 9_000);
  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
        "User-Agent": "ScenicPath-Backend/0.3",
      },
      body: `data=${encodeURIComponent(query)}`,
      signal: signal ?? controller.signal,
    });
    if (!response.ok) throw new Error(`OSM enrichment failed: ${response.status}`);
    const json = await response.json();
    const seen = new Set();
    const observations = [];
    for (const element of json.elements ?? []) {
      const id = `${element.type}:${element.id}`;
      if (seen.has(id)) continue;
      seen.add(id);
      const point = elementPoint(element);
      const classified = classifyTags(element.tags ?? {});
      if (!point || !classified) continue;
      observations.push({ id, point, ...classified });
    }
    return { observations, source: "osm-compatible", anchorCount: anchors.length };
  } finally {
    clearTimeout(timeout);
  }
}
