import http from "node:http";
import { rankRoutes } from "./scenic-score.js";
import { analyzeCorridor } from "./corridor-analyzer.js";
import { enrichRouteFromOsm } from "./osm-enrichment.js";
import { enrichTopFoodAlongRoute } from "./food-enrichment.js";
import { findPlaceDetails } from "./google-places.js";
import { selectSceneSuggestions } from "./scene-suggestions.js";
import { orderRoutesForCharacter } from "./route-selection.js";
import { insertAutomaticStops } from "./auto-stop-planner.js";
import { photonSearch } from "./photon-search.js";
import { tomTomRoute } from "./tomtom.js";
import { tomTomSearch } from "./tomtom-search.js";

const port = Number(process.env.PORT || 8787);
const json = (res, status, body) => {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Access-Control-Allow-Origin": "*"
  });
  res.end(JSON.stringify(body));
};
const readJson = req => new Promise((resolve, reject) => {
  let data = "";
  req.on("data", chunk => data += chunk);
  req.on("end", () => { try { resolve(JSON.parse(data || "{}")); } catch (e) { reject(e); } });
  req.on("error", reject);
});

function routePoints(route) {
  return (route.legs ?? []).flatMap((leg) =>
    (leg.points ?? []).map((point) => ({
      lat: point.latitude,
      lon: point.longitude
    }))
  );
}

function normalizeRoute(route, index, fastestSeconds, character, provider = "TomTom") {
  const durationSeconds = route.summary?.travelTimeInSeconds ?? 0;
  return {
    id: `${provider.toLowerCase()}-${character.toLowerCase()}-${index}`,
    character,
    durationSeconds,
    fastestDurationSeconds: fastestSeconds,
    distanceMeters: route.summary?.lengthInMeters ?? 0,
    extraMinutes: Math.max(0, (durationSeconds - fastestSeconds) / 60),
    driveExtraMinutes: Math.max(0, (durationSeconds - fastestSeconds) / 60),
    totalExtraMinutes: Math.max(0, (durationSeconds - fastestSeconds) / 60),
    points: routePoints(route),
    provider,
  };
}

function dedupeCandidates(candidates) {
  const seen = new Set();
  return candidates.filter(candidate => {
    const key = `${Math.round(candidate.distanceMeters / 100)}:${Math.round(candidate.durationSeconds / 30)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

async function enrichCandidate(candidate, enabledSceneKinds, preferences, autoSuggestStops) {
  let enrichment = { observations: [], source: "geometry-only" };
  if (process.env.OSM_ENRICHMENT_URL) {
    try {
      enrichment = await enrichRouteFromOsm({
        points: candidate.points,
        endpoint: process.env.OSM_ENRICHMENT_URL,
      });
    } catch (error) {
      console.warn("corridor enrichment degraded:", error.message);
      enrichment = { observations: [], source: "degraded", reason: error.message };
    }
  }

  const topFood = candidate.character === "DIRECT" ? [] : await enrichTopFoodAlongRoute({
    points: candidate.points,
    apiKey: process.env.GOOGLE_PLACES_API_KEY,
    preferences,
    enabledSceneKinds,
  });

  const observations = [...enrichment.observations, ...topFood];
  const analysis = analyzeCorridor({
    points: candidate.points,
    observations,
    enabledSceneKinds,
  });
  const scenePoints = autoSuggestStops === false ? [] : selectSceneSuggestions({
    points: candidate.points,
    observations,
    enabledSceneKinds,
    maxStops: preferences.maxStops ?? 5,
  });

  return {
    ...candidate,
    factors: analysis.factors,
    motorwayShare: analysis.motorwayShare,
    industrialShare: analysis.industrialShare,
    scenePoints,
    corridor: {
      source: enrichment.source,
      verifiedTopFoodCount: topFood.length,
      diagnostics: analysis.diagnostics,
      degradedReason: enrichment.reason,
    },
  };
}

function balancedPreferences(preferences) {
  return {
    ...preferences,
    windingness: Math.min(55, Math.max(35, preferences.windingness ?? 50)),
    hilliness: Math.min(45, Math.max(25, preferences.hilliness ?? 40)),
  };
}

function beautifulPreferences(preferences) {
  return {
    ...preferences,
    windingness: Math.max(65, preferences.windingness ?? 70),
    hilliness: Math.max(45, preferences.hilliness ?? 50),
    avoidMotorways: preferences.avoidMotorways !== false,
  };
}

async function searchPlaces(query, lat, lon) {
  if (process.env.PHOTON_URL) {
    const osmResults = await photonSearch({
      endpoint: process.env.PHOTON_URL,
      query,
      lat,
      lon,
      limit: 8,
      language: "de",
    });
    if (osmResults.length) return osmResults;
  }

  return tomTomSearch({
    apiKey: process.env.TOMTOM_API_KEY,
    query,
    lat,
    lon,
    limit: 8
  });
}

async function applyAutomaticStops({
  ranked,
  origin,
  destination,
  fixedWaypoints,
  fixedDwellMinutes,
  fastestSeconds,
  preferences,
  enabledSceneKinds,
  autoSuggestStops,
}) {
  if (autoSuggestStops === false || (preferences.maxExtraMinutes ?? 0) < 30) return ranked;

  // Exact insertion is intentionally bounded: at most the two strongest non-direct scenic
  // variants are trial-routed. This keeps production latency predictable while still giving
  // users more than one complete experience to compare.
  let plannedCount = 0;
  const planned = [];
  for (const candidate of ranked) {
    if (candidate.character === "DIRECT" || plannedCount >= 2 || !candidate.scenePoints?.length) {
      planned.push(candidate);
      continue;
    }
    plannedCount += 1;
    try {
      planned.push(await insertAutomaticStops({
        candidate,
        origin,
        destination,
        fixedWaypoints,
        fixedDwellMinutes,
        fastestSeconds,
        preferences,
        enabledSceneKinds,
        apiKey: process.env.TOMTOM_API_KEY,
      }));
    } catch (error) {
      console.warn("automatic Smart Stop insertion degraded:", error.message);
      planned.push(candidate);
    }
  }
  return planned;
}

const server = http.createServer(async (req, res) => {
  const requestUrl = new URL(req.url, `http://${req.headers.host || "localhost"}`);

  if (req.method === "GET" && requestUrl.pathname === "/health") {
    return json(res, 200, {
      ok: true,
      service: "scenic-path-backend",
      version: "0.6.1",
      apiVersion: 1,
      placeSearch: process.env.PHOTON_URL ? "OpenStreetMap/Photon" : "TomTom fallback",
      corridorEnrichment: process.env.OSM_ENRICHMENT_URL ? "configured" : "geometry-only",
      verifiedFood: process.env.GOOGLE_PLACES_API_KEY ? "configured" : "disabled",
      popupRatings: process.env.GOOGLE_PLACES_API_KEY ? "Google Places" : "disabled",
      automaticSmartStops: true,
      vehicleAwareRouting: true,
      routeTimeBudgetValidation: true,
    });
  }

  if (req.method === "GET" && requestUrl.pathname === "/v1/search") {
    try {
      const query = requestUrl.searchParams.get("q")?.trim();
      if (!query || query.length < 2) return json(res, 200, { results: [] });
      const lat = Number(requestUrl.searchParams.get("lat"));
      const lon = Number(requestUrl.searchParams.get("lon"));
      const results = await searchPlaces(query, lat, lon);
      return json(res, 200, { results });
    } catch (error) {
      console.error(error);
      return json(res, 502, { error: error.message });
    }
  }

  if (req.method === "GET" && requestUrl.pathname === "/v1/poi-details") {
    try {
      const name = requestUrl.searchParams.get("name")?.trim();
      const lat = Number(requestUrl.searchParams.get("lat"));
      const lon = Number(requestUrl.searchParams.get("lon"));
      if (!name || !Number.isFinite(lat) || !Number.isFinite(lon)) {
        return json(res, 400, { error: "name, lat and lon are required" });
      }
      if (!process.env.GOOGLE_PLACES_API_KEY) {
        return json(res, 200, { ratingSource: null, providerConfigured: false });
      }
      const details = await findPlaceDetails({
        apiKey: process.env.GOOGLE_PLACES_API_KEY,
        name,
        center: { lat, lon },
      });
      return json(res, 200, details ? { ...details, providerConfigured: true } : { providerConfigured: true });
    } catch (error) {
      console.error("poi-details:", error);
      return json(res, 502, { error: error.message });
    }
  }

  if (req.method === "POST" && requestUrl.pathname === "/v1/plan") {
    try {
      const body = await readJson(req);
      if (!body.origin || !body.destination || !body.preferences) {
        return json(res, 400, { error: "origin, destination and preferences are required" });
      }

      const orderedStops = (body.stops ?? [])
        .filter((stop) => stop.position && Number.isFinite(stop.position.lat) && Number.isFinite(stop.position.lon));
      const waypoints = orderedStops.map((stop) => stop.position);
      const fixedDwellMinutes = orderedStops.reduce(
        (sum, stop) => sum + Math.max(0, Number(stop.dwellMinutes) || 0),
        0,
      );
      const enabledSceneKinds = Array.isArray(body.enabledSceneKinds) ? body.enabledSceneKinds : [];
      const requestedCharacter = ["BEAUTIFUL", "BALANCED", "DIRECT", "CUSTOM"].includes(body.routeCharacter)
        ? body.routeCharacter
        : "BEAUTIFUL";

      const fastestRaw = await tomTomRoute({
        apiKey: process.env.TOMTOM_API_KEY,
        origin: body.origin,
        destination: body.destination,
        waypoints,
        preferences: body.preferences,
        routeType: "fastest"
      });
      const fastestRoute = fastestRaw.routes?.[0];
      const fastestSeconds = fastestRoute?.summary?.travelTimeInSeconds;
      if (!fastestSeconds) throw new Error("No fastest baseline route returned");

      const [beautifulRaw, balancedRaw] = await Promise.all([
        tomTomRoute({
          apiKey: process.env.TOMTOM_API_KEY,
          origin: body.origin,
          destination: body.destination,
          waypoints,
          preferences: beautifulPreferences(body.preferences),
          routeType: "thrilling"
        }),
        tomTomRoute({
          apiKey: process.env.TOMTOM_API_KEY,
          origin: body.origin,
          destination: body.destination,
          waypoints,
          preferences: balancedPreferences(body.preferences),
          routeType: "thrilling"
        })
      ]);

      const rawCandidates = [
        normalizeRoute(fastestRoute, 0, fastestSeconds, "DIRECT"),
        ...(balancedRaw.routes ?? []).map((route, index) => normalizeRoute(route, index, fastestSeconds, "BALANCED")),
        ...(beautifulRaw.routes ?? []).map((route, index) => normalizeRoute(route, index, fastestSeconds, requestedCharacter === "CUSTOM" ? "CUSTOM" : "BEAUTIFUL")),
      ];

      const enriched = await Promise.all(
        dedupeCandidates(rawCandidates).map(candidate =>
          enrichCandidate(candidate, enabledSceneKinds, body.preferences, body.autoSuggestStops)
        )
      );
      const scenicRanked = rankRoutes(enriched, body.preferences);
      const initiallyRanked = orderRoutesForCharacter(
        scenicRanked,
        requestedCharacter,
        body.preferences,
        fastestSeconds,
      );
      const withAutoStops = await applyAutomaticStops({
        ranked: initiallyRanked,
        origin: body.origin,
        destination: body.destination,
        fixedWaypoints: waypoints,
        fixedDwellMinutes,
        fastestSeconds,
        preferences: body.preferences,
        enabledSceneKinds,
        autoSuggestStops: body.autoSuggestStops,
      });
      const ranked = orderRoutesForCharacter(
        withAutoStops,
        requestedCharacter,
        body.preferences,
        fastestSeconds,
      );

      return json(res, 200, {
        baseline: {
          durationSeconds: fastestSeconds,
          distanceMeters: fastestRoute.summary.lengthInMeters,
          points: routePoints(fastestRoute)
        },
        candidates: ranked,
        stops: orderedStops,
        plan: {
          mode: body.mode ?? "QUICK",
          routeCharacter: requestedCharacter,
          enabledSceneKinds,
          autoSuggestStops: body.autoSuggestStops !== false,
          preserveScenicIntentOnReroute: body.preserveScenicIntentOnReroute !== false
        },
        note: buildPlanNote(ranked, process.env.OSM_ENRICHMENT_URL),
      });
    } catch (error) {
      console.error(error);
      return json(res, 500, { error: error.message });
    }
  }

  return json(res, 404, { error: "not found" });
});

function buildPlanNote(candidates, enrichmentUrl) {
  const included = candidates.reduce((max, candidate) => Math.max(max, candidate.autoStopIds?.length ?? 0), 0);
  const base = enrichmentUrl
    ? "Routes ranked with corridor geometry plus configured landscape/scene enrichment."
    : "Routes ranked with road geometry; configure OSM_ENRICHMENT_URL for full landscape/scene enrichment.";
  if (included > 0) return `${base} ${included} automatic Smart Stop${included === 1 ? "" : "s"} validated inside the selected time budget.`;
  return `${base} Automatic Smart Stop candidates are shown when available.`;
}

server.listen(port, () => console.log(`Scenic Path backend listening on :${port}`));
