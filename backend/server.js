import http from "node:http";
import { rankRoutes } from "./scenic-score.js";
import { analyzeCorridor } from "./corridor-analyzer.js";
import { enrichRouteFromOsm } from "./osm-enrichment.js";
import { enrichTopFoodAlongRoute } from "./food-enrichment.js";
import { findPlaceDetails } from "./foursquare-places.js";
import { selectSceneSuggestions } from "./scene-suggestions.js";
import { orderRoutesForCharacter } from "./route-selection.js";
import { insertAutomaticStops } from "./auto-stop-planner.js";
import { photonSearch } from "./photon-search.js";
import { tomTomRoute } from "./tomtom.js";
import { tomTomSearch } from "./tomtom-search.js";
import { orderFlexibleStops } from "./waypoint-order.js";
import {
  isRoundTripRequest,
  roundTripWaypointSets,
  utilizationScore,
} from "./round-trip.js";
import { orderDiverseRoutes } from "./route-diversity.js";
import { withManualDwell } from "./route-time.js";
import { balancedPreferences, beautifulPreferences, executionPreferences } from "./route-preferences.js";

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

function normalizeRoundRoute(route, index, character, waypoints, budgetMinutes, fixedDwellMinutes) {
  const durationSeconds = route.summary?.travelTimeInSeconds ?? 0;
  const outingMinutes = durationSeconds / 60 + fixedDwellMinutes;
  return {
    id: `tomtom-round-${character.toLowerCase()}-${index}`,
    character,
    durationSeconds,
    fastestDurationSeconds: durationSeconds,
    distanceMeters: route.summary?.lengthInMeters ?? 0,
    extraMinutes: durationSeconds / 60,
    driveExtraMinutes: durationSeconds / 60,
    dwellMinutes: fixedDwellMinutes,
    totalExtraMinutes: outingMinutes,
    budgetUsedMinutes: outingMinutes,
    budgetMinutes,
    isRoundTrip: true,
    points: routePoints(route),
    provider: "TomTom · Scenic round trip",
    roundTripWaypoints: waypoints,
    variantLabel: `Round tour ${index + 1}`,
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
        maxExtraMinutes: preferences.maxExtraMinutes ?? 0,
      });
    } catch (error) {
      console.warn("corridor enrichment degraded:", error.message);
      enrichment = { observations: [], source: "degraded", reason: error.message };
    }
  }

  const topFood = candidate.character === "DIRECT" ? [] : await enrichTopFoodAlongRoute({
    points: candidate.points,
    apiKey: process.env.FOURSQUARE_SERVICE_KEY,
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
    maxExtraMinutes: preferences.maxExtraMinutes ?? 0,
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
      topFoodProvider: topFood.length ? "Foursquare" : null,
      diagnostics: analysis.diagnostics,
      degradedReason: enrichment.reason,
      lateralOffsetKm: enrichment.lateralOffsetKm ?? 0,
    },
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

function bearingDegrees(origin, point) {
  const lat1 = origin.lat * Math.PI / 180;
  const lat2 = point.lat * Math.PI / 180;
  const dLon = (point.lon - origin.lon) * Math.PI / 180;
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}

function orderLoopWaypoints(origin, shaping, fixedStops) {
  if (!shaping.length) return fixedStops.map(stop => stop.position);
  const startBearing = bearingDegrees(origin, shaping[0]);
  return [
    ...shaping.map((position, index) => ({ position, shape: true, index, bearing: bearingDegrees(origin, position) })),
    ...fixedStops.map((stop, index) => ({ position: stop.position, shape: false, index, bearing: bearingDegrees(origin, stop.position) })),
  ]
    .map(item => ({ ...item, progress: (item.bearing - startBearing + 360) % 360 }))
    .sort((a, b) => a.progress - b.progress || Number(b.shape) - Number(a.shape) || a.index - b.index)
    .map(item => item.position);
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
  roundTrip = false,
}) {
  const withoutAutomaticStops = candidate => roundTrip ? {
    ...candidate,
    budgetUsedMinutes: candidate.durationSeconds / 60 + fixedDwellMinutes,
    budgetMinutes: preferences.maxExtraMinutes,
    isRoundTrip: true,
  } : withManualDwell(candidate, fixedDwellMinutes);

  if (autoSuggestStops === false || (preferences.maxExtraMinutes ?? 0) < 30) {
    return ranked.map(withoutAutomaticStops);
  }

  const usedStopIds = new Set();
  let plannedCount = 0;
  const planned = [];
  for (const candidate of ranked) {
    if (candidate.character === "DIRECT" || plannedCount >= 5 || !candidate.scenePoints?.length) {
      planned.push(withoutAutomaticStops(candidate));
      continue;
    }
    plannedCount += 1;
    try {
      const unused = candidate.scenePoints.filter(point => !usedStopIds.has(point.id));
      const candidateForStops = {
        ...candidate,
        scenePoints: unused.length ? unused : candidate.scenePoints,
      };
      const candidateFixed = roundTrip
        ? [...(candidate.roundTripWaypoints ?? []), ...fixedWaypoints]
        : fixedWaypoints;
      let next = await insertAutomaticStops({
        candidate: candidateForStops,
        origin,
        destination,
        fixedWaypoints: candidateFixed,
        fixedDwellMinutes,
        fastestSeconds: roundTrip ? 0 : fastestSeconds,
        preferences,
        enabledSceneKinds,
        apiKey: process.env.TOMTOM_API_KEY,
      });
      if (roundTrip) {
        const outing = next.totalExtraMinutes ?? (next.durationSeconds / 60 + next.dwellMinutes);
        next = {
          ...next,
          budgetUsedMinutes: outing,
          budgetMinutes: preferences.maxExtraMinutes,
          isRoundTrip: true,
        };
      }
      (next.autoStopIds ?? []).forEach(id => usedStopIds.add(id));
      planned.push(next);
    } catch (error) {
      console.warn("automatic Smart Stop insertion degraded:", error.message);
      planned.push(withoutAutomaticStops(candidate));
    }
  }
  return planned;
}

function applyDayTripBudgetIntent(candidates, mode, preferences, roundTrip) {
  if (mode !== "DAY_TRIP") return candidates;
  const budget = Math.max(1, preferences.maxExtraMinutes ?? 0);
  return candidates
    .map(candidate => {
      const used = roundTrip
        ? candidate.budgetUsedMinutes ?? candidate.durationSeconds / 60 + (candidate.dwellMinutes ?? 0)
        : candidate.totalExtraMinutes ?? candidate.extraMinutes ?? 0;
      const useScore = utilizationScore(used, budget);
      const quality = Math.max(0, Math.min(100, candidate.profileScore ?? candidate.experienceScore ?? candidate.scenicScore ?? 0));
      return {
        ...candidate,
        budgetUsedMinutes: used,
        budgetMinutes: budget,
        profileScore: quality * 0.64 + useScore * 36,
        experienceScore: Math.max(candidate.experienceScore ?? 0, (candidate.scenicScore ?? 0) * 0.72 + useScore * 28),
      };
    })
    .filter(candidate => !roundTrip || candidate.budgetUsedMinutes <= budget * 1.03)
    .sort((a, b) => b.profileScore - a.profileScore || b.experienceScore - a.experienceScore);
}

async function planRoundTrip(body, fixedStops, enabledSceneKinds, requestedCharacter, requestedCount) {
  const preferences = executionPreferences(body.preferences, "DAY_TRIP");
  const fixedDwellMinutes = fixedStops.reduce((sum, stop) => sum + Math.max(0, Number(stop.dwellMinutes) || 0), 0);
  const sets = roundTripWaypointSets({
    origin: body.origin,
    preferences,
    autoSuggestStops: body.autoSuggestStops !== false,
    fixedDwellMinutes,
    count: Math.max(requestedCount + 2, 4),
  });

  const raw = await Promise.all(sets.map(async (shape, index) => {
    try {
      const waypoints = orderLoopWaypoints(body.origin, shape, fixedStops);
      const response = await tomTomRoute({
        apiKey: process.env.TOMTOM_API_KEY,
        origin: body.origin,
        destination: body.origin,
        waypoints,
        preferences,
        routeType: requestedCharacter === "DIRECT" ? "fastest" : "thrilling",
      });
      const route = response.routes?.[0];
      return route ? normalizeRoundRoute(
        route,
        index,
        requestedCharacter,
        shape,
        preferences.maxExtraMinutes,
        fixedDwellMinutes,
      ) : null;
    } catch (error) {
      console.warn(`round-trip variant ${index + 1} degraded:`, error.message);
      return null;
    }
  }));

  const candidates = dedupeCandidates(raw.filter(Boolean));
  if (!candidates.length) throw new Error("No routable round trip matched the selected day-trip area");

  const enriched = await Promise.all(candidates.map(candidate =>
    enrichCandidate(candidate, enabledSceneKinds, preferences, body.autoSuggestStops)
  ));
  const scenicRanked = rankRoutes(enriched, preferences);
  const withStops = await applyAutomaticStops({
    ranked: scenicRanked,
    origin: body.origin,
    destination: body.origin,
    fixedWaypoints: fixedStops.map(stop => stop.position),
    fixedDwellMinutes,
    fastestSeconds: 0,
    preferences,
    enabledSceneKinds,
    autoSuggestStops: body.autoSuggestStops,
    roundTrip: true,
  });
  const budgetRanked = applyDayTripBudgetIntent(withStops, "DAY_TRIP", preferences, true);
  const fallback = budgetRanked.length ? budgetRanked : withStops.sort((a, b) => a.totalExtraMinutes - b.totalExtraMinutes);
  return orderDiverseRoutes(fallback, requestedCount);
}

const server = http.createServer(async (req, res) => {
  const requestUrl = new URL(req.url, `http://${req.headers.host || "localhost"}`);

  if (req.method === "GET" && requestUrl.pathname === "/health") {
    return json(res, 200, {
      ok: true,
      service: "scenic-path-backend",
      version: "0.6.2-rc1",
      apiVersion: 1,
      placeSearch: process.env.PHOTON_URL ? "OpenStreetMap/Photon" : "TomTom fallback",
      corridorEnrichment: process.env.OSM_ENRICHMENT_URL ? "configured" : "geometry-only",
      verifiedFood: process.env.FOURSQUARE_SERVICE_KEY ? "configured" : "disabled",
      popupRatings: process.env.FOURSQUARE_SERVICE_KEY ? "Foursquare" : "disabled",
      foodAttribution: process.env.FOURSQUARE_SERVICE_KEY ? "Powered by Foursquare" : null,
      automaticSmartStops: true,
      vehicleAwareRouting: true,
      routeTimeBudgetValidation: true,
      budgetDrivenRoundTrips: true,
      diverseAlternatives: true,
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
      if (!process.env.FOURSQUARE_SERVICE_KEY) {
        return json(res, 200, { ratingSource: null, providerAttribution: null, providerConfigured: false });
      }
      const details = await findPlaceDetails({
        apiKey: process.env.FOURSQUARE_SERVICE_KEY,
        name,
        center: { lat, lon },
      });
      return json(res, 200, details ? { ...details, providerConfigured: true } : {
        providerConfigured: true,
        ratingSource: "Foursquare",
        providerAttribution: "Powered by Foursquare",
      });
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

      const validStops = (body.stops ?? [])
        .filter(stop => stop.position && Number.isFinite(stop.position.lat) && Number.isFinite(stop.position.lon));
      const roundTrip = isRoundTripRequest(body);
      const orderedStops = body.flexibleStopOrder && !roundTrip
        ? orderFlexibleStops(validStops, body.origin, body.destination)
        : validStops;
      const waypoints = orderedStops.map(stop => stop.position);
      const fixedDwellMinutes = orderedStops.reduce(
        (sum, stop) => sum + Math.max(0, Number(stop.dwellMinutes) || 0),
        0,
      );
      const enabledSceneKinds = Array.isArray(body.enabledSceneKinds) ? body.enabledSceneKinds : [];
      const requestedCharacter = ["BEAUTIFUL", "BALANCED", "DIRECT", "CUSTOM"].includes(body.routeCharacter)
        ? body.routeCharacter
        : "BEAUTIFUL";
      const requestedCount = Math.max(1, Math.min(5, Number(body.requestedAlternatives) || 2));
      const mode = body.mode ?? "QUICK";
      const preferences = executionPreferences(body.preferences, mode);

      if (roundTrip) {
        const ranked = await planRoundTrip({ ...body, preferences }, orderedStops, enabledSceneKinds, requestedCharacter, requestedCount);
        const clean = ranked.map(({ roundTripWaypoints, ...candidate }) => candidate);
        return json(res, 200, {
          baseline: null,
          candidates: clean,
          stops: orderedStops,
          plan: {
            mode: "DAY_TRIP",
            routeCharacter: requestedCharacter,
            enabledSceneKinds,
            autoSuggestStops: body.autoSuggestStops !== false,
            roundTrip: true,
            requestedAlternatives: requestedCount,
          },
          note: `Day-trip round route · returns to start · targets ${preferences.maxExtraMinutes} min total outing time · ${clean.length} deliberately different route variant${clean.length === 1 ? "" : "s"}.`,
        });
      }

      const fastestRaw = await tomTomRoute({
        apiKey: process.env.TOMTOM_API_KEY,
        origin: body.origin,
        destination: body.destination,
        waypoints,
        preferences,
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
          preferences: beautifulPreferences(preferences),
          routeType: "thrilling"
        }),
        tomTomRoute({
          apiKey: process.env.TOMTOM_API_KEY,
          origin: body.origin,
          destination: body.destination,
          waypoints,
          preferences: balancedPreferences(preferences),
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
          enrichCandidate(candidate, enabledSceneKinds, preferences, body.autoSuggestStops)
        )
      );
      const scenicRanked = rankRoutes(enriched, preferences);
      const initiallyRanked = orderRoutesForCharacter(
        scenicRanked,
        requestedCharacter,
        preferences,
        fastestSeconds,
      );
      const withAutoStops = await applyAutomaticStops({
        ranked: initiallyRanked,
        origin: body.origin,
        destination: body.destination,
        fixedWaypoints: waypoints,
        fixedDwellMinutes,
        fastestSeconds,
        preferences,
        enabledSceneKinds,
        autoSuggestStops: body.autoSuggestStops,
        roundTrip: false,
      });
      let ranked = orderRoutesForCharacter(
        withAutoStops,
        requestedCharacter,
        preferences,
        fastestSeconds,
      );
      ranked = applyDayTripBudgetIntent(ranked, mode, preferences, false);
      ranked = orderDiverseRoutes(ranked, requestedCount);

      return json(res, 200, {
        baseline: {
          durationSeconds: fastestSeconds,
          distanceMeters: fastestRoute.summary.lengthInMeters,
          points: routePoints(fastestRoute)
        },
        candidates: ranked,
        stops: orderedStops,
        plan: {
          mode,
          routeCharacter: requestedCharacter,
          enabledSceneKinds,
          autoSuggestStops: body.autoSuggestStops !== false,
          preserveScenicIntentOnReroute: body.preserveScenicIntentOnReroute !== false,
          flexibleStopOrder: body.flexibleStopOrder !== false,
          requestedAlternatives: requestedCount,
        },
        note: buildPlanNote(ranked, process.env.OSM_ENRICHMENT_URL, mode, preferences.maxExtraMinutes),
      });
    } catch (error) {
      console.error(error);
      return json(res, 500, { error: error.message });
    }
  }

  return json(res, 404, { error: "not found" });
});

function buildPlanNote(candidates, enrichmentUrl, mode, budgetMinutes) {
  const included = candidates.reduce((max, candidate) => Math.max(max, candidate.autoStopIds?.length ?? 0), 0);
  const base = enrichmentUrl
    ? "Routes ranked with corridor geometry plus configured landscape/scene enrichment."
    : "Routes ranked with road geometry; configure OSM_ENRICHMENT_URL for full landscape/scene enrichment.";
  const budget = mode === "DAY_TRIP" ? ` Day-trip ranking actively targets the selected ${budgetMinutes} min exploration budget.` : "";
  const alternatives = candidates.length > 1 ? ` Alternative 2 is selected for corridor/stop diversity; ${candidates.length} variants currently loaded.` : "";
  if (included > 0) return `${base}${budget} ${included} automatic Smart Stop${included === 1 ? "" : "s"} validated inside the selected time budget.${alternatives}`;
  return `${base}${budget} Automatic Smart Stop candidates are shown when available.${alternatives}`;
}

server.listen(port, () => console.log(`Scenic Path backend listening on :${port}`));
