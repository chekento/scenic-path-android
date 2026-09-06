function level(v) {
  if (v >= 67) return "high";
  if (v <= 33) return "low";
  return "normal";
}

export function vehicleConfig(preferences = {}, requestedRouteType = "thrilling") {
  const vehicle = preferences.vehicle ?? {};
  const kind = vehicle.kind ?? "CAR";
  const travelMode = {
    CAR: "car",
    MOTORCYCLE: "motorcycle",
    CAMPER: "truck",
    TRUCK: "truck",
    COACH: "bus",
    BICYCLE: "bicycle"
  }[kind] ?? "car";

  let routeType = requestedRouteType;
  if (kind === "BICYCLE") {
    // TomTom does not expose Valhalla-style bicycle sub-costings. Give the user's bicycle type a
    // real production effect through the supported route strategy: road/city bikes prioritize
    // travel time, while hybrid/cross/MTB profiles prioritize a shorter path. Surface permission
    // is handled separately through the unpaved-roads avoidance below.
    const bikeType = vehicle.bicycleType ?? "hybrid";
    routeType = ["road", "city"].includes(bikeType) ? "fastest" : "shortest";
  }
  if (["CAMPER", "TRUCK", "COACH"].includes(kind) && routeType === "thrilling") routeType = "fastest";

  return { kind, travelMode, routeType, vehicle };
}

/** Pure projection used by tests and by the actual TomTom request builder. */
export function routeConstraintConfig(preferences = {}, requestedRouteType = "thrilling") {
  const config = vehicleConfig(preferences, requestedRouteType);
  const avoid = [];
  if (preferences.avoidMotorways && config.travelMode !== "bicycle") avoid.push("motorways");
  if (preferences.avoidTolls && config.travelMode !== "bicycle") avoid.push("tollRoads");
  if (config.kind === "BICYCLE" && config.vehicle.allowUnpavedBikePaths === false) avoid.push("unpavedRoads");

  return {
    ...config,
    avoid,
    hilliness: config.routeType === "thrilling" && ["car", "motorcycle"].includes(config.travelMode)
      ? level(preferences.hilliness ?? 50)
      : null,
    windingness: config.routeType === "thrilling" && ["car", "motorcycle"].includes(config.travelMode)
      ? level(preferences.windingness ?? 50)
      : null,
  };
}

export async function tomTomRoute({
  apiKey,
  origin,
  destination,
  waypoints = [],
  preferences,
  routeType = "thrilling"
}) {
  if (!apiKey) throw new Error("TOMTOM_API_KEY is not configured");

  const coordinates = [origin, ...waypoints, destination]
    .map((point) => `${point.lat},${point.lon}`)
    .join(":");

  const config = routeConstraintConfig(preferences, routeType);
  const params = new URLSearchParams({
    key: apiKey,
    routeType: config.routeType,
    traffic: config.travelMode === "bicycle" ? "false" : "true",
    travelMode: config.travelMode,
    instructionsType: "text",
    language: "de-DE",
    routeRepresentation: "polyline",
    computeTravelTimeFor: "all",
    maxAlternatives: config.routeType === "thrilling" && waypoints.length === 0 ? "2" : "0"
  });

  if (config.hilliness) params.set("hilliness", config.hilliness);
  if (config.windingness) params.set("windingness", config.windingness);
  config.avoid.forEach(value => params.append("avoid", value));

  if (["CAMPER", "TRUCK", "COACH"].includes(config.kind)) {
    const v = config.vehicle;
    if (Number.isFinite(v.heightMeters)) params.set("vehicleHeight", String(v.heightMeters));
    if (Number.isFinite(v.widthMeters)) params.set("vehicleWidth", String(v.widthMeters));
    if (Number.isFinite(v.lengthMeters)) params.set("vehicleLength", String(v.lengthMeters));
    if (Number.isFinite(v.weightTons)) params.set("vehicleWeight", String(Math.round(v.weightTons * 1000)));
    if (["TRUCK", "COACH"].includes(config.kind) && Number.isFinite(v.axleLoadTons)) {
      params.set("vehicleAxleWeight", String(Math.round(v.axleLoadTons * 1000)));
    }
    params.set("vehicleCommercial", config.kind === "TRUCK" || config.kind === "COACH" ? "true" : "false");
  }

  const url = `https://api.tomtom.com/routing/1/calculateRoute/${coordinates}/json?${params}`;
  const res = await fetch(url, { headers: { "User-Agent": "ScenicPath/0.6" } });
  if (!res.ok) throw new Error(`TomTom routing failed: ${res.status}`);
  return res.json();
}
