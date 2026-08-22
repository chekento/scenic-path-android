export async function tomTomSearch({ apiKey, query, lat, lon, limit = 8 }) {
  if (!apiKey) throw new Error("TOMTOM_API_KEY is not configured");
  const params = new URLSearchParams({
    key: apiKey,
    limit: String(limit),
    language: "de-DE",
    typeahead: "true",
    minFuzzyLevel: "1",
    maxFuzzyLevel: "2"
  });
  if (Number.isFinite(lat) && Number.isFinite(lon)) {
    params.set("lat", String(lat));
    params.set("lon", String(lon));
    params.set("radius", "100000");
  }

  const url = `https://api.tomtom.com/search/2/search/${encodeURIComponent(query)}.json?${params}`;
  const response = await fetch(url, { headers: { "User-Agent": "ScenicPath/0.2" } });
  if (!response.ok) throw new Error(`TomTom search failed: ${response.status}`);
  const body = await response.json();

  return (body.results ?? []).map((result) => ({
    id: result.id ?? `${result.position?.lat},${result.position?.lon}`,
    title: result.poi?.name ?? result.address?.streetName ?? result.address?.municipality ?? query,
    subtitle: result.address?.freeformAddress ?? result.address?.countrySubdivision ?? "",
    type: result.type ?? "PLACE",
    position: {
      lat: result.position?.lat,
      lon: result.position?.lon
    },
    categories: result.poi?.categories ?? []
  })).filter((item) => Number.isFinite(item.position.lat) && Number.isFinite(item.position.lon));
}
