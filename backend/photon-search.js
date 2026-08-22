function subtitle(properties = {}) {
  const streetLine = [properties.street, properties.housenumber].filter(Boolean).join(" ");
  const cityLine = [properties.postcode, properties.city || properties.locality || properties.district || properties.county]
    .filter(Boolean)
    .join(" ");
  return [streetLine, cityLine, properties.state, properties.country]
    .filter(Boolean)
    .filter((value, index, array) => array.indexOf(value) === index)
    .join(" · ");
}

/**
 * Search an OSM-backed Photon instance. Configure PHOTON_URL to a self-hosted
 * or contracted service in production. The Android debug build may use the
 * public komoot demo directly for moderate development testing only.
 */
export async function photonSearch({ endpoint, query, lat, lon, limit = 8, language = "de" }) {
  if (!endpoint) return [];
  const base = endpoint.replace(/\/$/, "");
  const params = new URLSearchParams({
    q: query,
    limit: String(limit),
    lang: language,
  });
  if (Number.isFinite(lat) && Number.isFinite(lon)) {
    params.set("lat", String(lat));
    params.set("lon", String(lon));
    params.set("zoom", "12");
    params.set("location_bias_scale", "0.35");
  }

  const res = await fetch(`${base}/api?${params}`, {
    headers: {
      Accept: "application/geo+json, application/json",
      "User-Agent": "ScenicPath-Backend/0.4"
    }
  });
  if (!res.ok) throw new Error(`Photon search failed: ${res.status}`);
  const data = await res.json();

  return (data.features ?? []).flatMap((feature, index) => {
    const coordinates = feature.geometry?.coordinates;
    if (!Array.isArray(coordinates) || coordinates.length < 2) return [];
    const [longitude, latitude] = coordinates;
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return [];
    const p = feature.properties ?? {};
    const name = p.name || p.street || query;
    const osmId = p.osm_id ? `${p.osm_type || "osm"}-${p.osm_id}` : `${latitude}-${longitude}-${index}`;
    return [{
      id: `photon-${osmId}`,
      title: name,
      subtitle: subtitle(p),
      position: { lat: latitude, lon: longitude },
      source: "OpenStreetMap/Photon",
      osm: {
        type: p.osm_type,
        id: p.osm_id,
        key: p.osm_key,
        value: p.osm_value,
      }
    }];
  });
}
