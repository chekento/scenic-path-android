function level(v) {
  if (v >= 67) return "high";
  if (v <= 33) return "low";
  return "normal";
}

export async function tomTomRoute({ apiKey, origin, destination, preferences, routeType = "thrilling" }) {
  if (!apiKey) throw new Error("TOMTOM_API_KEY is not configured");
  const loc = `${origin.lat},${origin.lon}:${destination.lat},${destination.lon}`;
  const params = new URLSearchParams({
    key: apiKey,
    routeType,
    traffic: "true",
    travelMode: "car",
    instructionsType: "text",
    language: "en-GB",
    routeRepresentation: "polyline",
    computeTravelTimeFor: "all",
    maxAlternatives: routeType === "thrilling" ? "2" : "0"
  });
  if (routeType === "thrilling") {
    params.set("hilliness", level(preferences.hilliness ?? 50));
    params.set("windingness", level(preferences.windingness ?? 50));
  }
  if (preferences.avoidMotorways) params.append("avoid", "motorways");
  if (preferences.avoidTolls) params.append("avoid", "tollRoads");

  const url = `https://api.tomtom.com/routing/1/calculateRoute/${loc}/json?${params}`;
  const res = await fetch(url, { headers: { "User-Agent": "ScenicPath/0.1" } });
  if (!res.ok) throw new Error(`TomTom routing failed: ${res.status}`);
  return res.json();
}
