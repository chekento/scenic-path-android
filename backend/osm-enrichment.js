import { samplePolyline } from "./corridor-analyzer.js";
import { classifyTags } from "./scene-catalog.js";

function elementPoint(element) {
  const lat = element.lat ?? element.center?.lat;
  const lon = element.lon ?? element.center?.lon;
  return Number.isFinite(lat) && Number.isFinite(lon) ? { lat, lon } : null;
}

export function lateralSearchOffsetKm(maxExtraMinutes = 0) {
  if (maxExtraMinutes < 45) return 0;
  if (maxExtraMinutes < 90) return 4;
  if (maxExtraMinutes < 150) return 7;
  if (maxExtraMinutes < 210) return 11;
  if (maxExtraMinutes < 300) return 16;
  return 20;
}

function bearingDegrees(a, b) {
  const lat1 = a.lat * Math.PI / 180;
  const lat2 = b.lat * Math.PI / 180;
  const dLon = (b.lon - a.lon) * Math.PI / 180;
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) -
    Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}

function offsetPoint(point, bearing, distanceKm) {
  const earthKm = 6371;
  const angular = distanceKm / earthKm;
  const brng = bearing * Math.PI / 180;
  const lat1 = point.lat * Math.PI / 180;
  const lon1 = point.lon * Math.PI / 180;
  const lat2 = Math.asin(
    Math.sin(lat1) * Math.cos(angular) +
    Math.cos(lat1) * Math.sin(angular) * Math.cos(brng)
  );
  const lon2 = lon1 + Math.atan2(
    Math.sin(brng) * Math.sin(angular) * Math.cos(lat1),
    Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2)
  );
  return { lat: lat2 * 180 / Math.PI, lon: lon2 * 180 / Math.PI };
}

export function buildSearchAnchors(points, maxExtraMinutes = 0) {
  const central = samplePolyline(points, { spacingMeters: 18_000, maxSamples: 6 });
  if (central.length < 2) return central;
  const offsetKm = lateralSearchOffsetKm(maxExtraMinutes);
  if (offsetKm <= 0) return central;

  const anchors = [];
  central.forEach((point, index) => {
    anchors.push(point);
    const previous = central[Math.max(0, index - 1)];
    const next = central[Math.min(central.length - 1, index + 1)];
    if (previous === next) return;
    const heading = bearingDegrees(previous, next);
    anchors.push(offsetPoint(point, heading + 90, offsetKm));
    anchors.push(offsetPoint(point, heading - 90, offsetKm));
  });
  return anchors.slice(0, 18);
}

function queryForAnchors(anchors, radiusMeters = 2400) {
  const clauses = anchors.flatMap(({ lat, lon }) => {
    const around = `(around:${radiusMeters},${lat.toFixed(6)},${lon.toFixed(6)})`;
    return [
      `nwr${around}["tourism"~"viewpoint|museum|gallery|artwork|attraction"];`,
      `nwr${around}["historic"~"castle|manor|palace|fort|ruins|monument|memorial|archaeological_site"];`,
      `nwr${around}["castle_type"];`,
      `nwr${around}["natural"~"peak|cape|stone|waterfall|beach|wood|water"];`,
      `nwr${around}["landuse"~"forest|industrial"];`,
      `nwr${around}["water"~"lake|reservoir|pond"];`,
      `nwr${around}["waterway"~"river|stream|canal|waterfall"];`,
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
 * OSM-compatible corridor enrichment. No public endpoint is hard-coded: production must
 * configure a managed/self-hosted service. Extra time expands the *searched space* by
 * adding lateral virtual corridors rather than increasing one giant Overpass radius.
 */
export async function enrichRouteFromOsm({
  points,
  endpoint,
  maxExtraMinutes = 0,
  signal,
}) {
  if (!endpoint) return { observations: [], source: "disabled", reason: "OSM_ENRICHMENT_URL not configured" };
  const anchors = buildSearchAnchors(points, maxExtraMinutes);
  if (!anchors.length) return { observations: [], source: "osm-compatible" };

  const query = queryForAnchors(anchors);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12_000);
  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
        "User-Agent": "ScenicPath-Backend/0.6.1",
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
    return {
      observations,
      source: "osm-compatible",
      anchorCount: anchors.length,
      lateralOffsetKm: lateralSearchOffsetKm(maxExtraMinutes),
    };
  } finally {
    clearTimeout(timeout);
  }
}
