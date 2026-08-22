import http from "node:http";
import { rankRoutes } from "./scenic-score.js";
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

function normalizeTomTom(raw, fastestSeconds) {
  return (raw.routes ?? []).map((r, index) => ({
    id: `tomtom-${index}`,
    durationSeconds: r.summary?.travelTimeInSeconds ?? 0,
    fastestDurationSeconds: fastestSeconds,
    distanceMeters: r.summary?.lengthInMeters ?? 0,
    extraMinutes: Math.max(0, ((r.summary?.travelTimeInSeconds ?? fastestSeconds) - fastestSeconds) / 60),
    points: routePoints(r),
    // Corridor enrichment will replace conservative zeros as M1 evolves.
    factors: {
      beautifulRoads: Math.max(0, 0.72 - index * 0.04),
      forest: 0.0,
      water: 0.0,
      mountains: 0.0,
      viewpoints: 0.0,
      culture: 0.0,
      museums: 0.0,
      architecture: 0.0,
      parks: 0.0,
      food: 0.0
    },
    motorwayShare: 0,
    industrialShare: 0,
    provider: "TomTom"
  }));
}

const server = http.createServer(async (req, res) => {
  const requestUrl = new URL(req.url, `http://${req.headers.host || "localhost"}`);

  if (req.method === "GET" && requestUrl.pathname === "/health") {
    return json(res, 200, { ok: true, service: "scenic-path-backend", version: "0.2.0" });
  }

  if (req.method === "GET" && requestUrl.pathname === "/v1/search") {
    try {
      const query = requestUrl.searchParams.get("q")?.trim();
      if (!query || query.length < 2) return json(res, 200, { results: [] });
      const lat = Number(requestUrl.searchParams.get("lat"));
      const lon = Number(requestUrl.searchParams.get("lon"));
      const results = await tomTomSearch({
        apiKey: process.env.TOMTOM_API_KEY,
        query,
        lat,
        lon,
        limit: 8
      });
      return json(res, 200, { results });
    } catch (error) {
      console.error(error);
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

      const fastestRaw = await tomTomRoute({
        apiKey: process.env.TOMTOM_API_KEY,
        origin: body.origin,
        destination: body.destination,
        waypoints,
        preferences: body.preferences,
        routeType: "fastest"
      });
      const fastestSeconds = fastestRaw.routes?.[0]?.summary?.travelTimeInSeconds;
      if (!fastestSeconds) throw new Error("No fastest baseline route returned");

      const scenicRaw = await tomTomRoute({
        apiKey: process.env.TOMTOM_API_KEY,
        origin: body.origin,
        destination: body.destination,
        waypoints,
        preferences: body.preferences,
        routeType: body.routeCharacter === "DIRECT" ? "fastest" : "thrilling"
      });
      const ranked = rankRoutes(normalizeTomTom(scenicRaw, fastestSeconds), body.preferences);

      return json(res, 200, {
        baseline: {
          durationSeconds: fastestSeconds,
          distanceMeters: fastestRaw.routes[0].summary.lengthInMeters,
          points: routePoints(fastestRaw.routes[0])
        },
        candidates: ranked,
        stops: orderedStops,
        plan: {
          mode: body.mode ?? "QUICK",
          routeCharacter: body.routeCharacter ?? "BEAUTIFUL",
          preserveScenicIntentOnReroute: body.preserveScenicIntentOnReroute !== false
        },
        note: "M1 routing now respects ordered resolved stops; corridor enrichment is the next scoring layer."
      });
    } catch (error) {
      console.error(error);
      return json(res, 500, { error: error.message });
    }
  }

  return json(res, 404, { error: "not found" });
});

server.listen(port, () => console.log(`Scenic Path backend listening on :${port}`));
