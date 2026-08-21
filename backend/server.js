import http from "node:http";
import { rankRoutes } from "./scenic-score.js";
import { tomTomRoute } from "./tomtom.js";

const port = Number(process.env.PORT || 8787);
const json = (res, status, body) => {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(JSON.stringify(body));
};
const readJson = req => new Promise((resolve, reject) => {
  let data = "";
  req.on("data", chunk => data += chunk);
  req.on("end", () => { try { resolve(JSON.parse(data || "{}")); } catch (e) { reject(e); } });
  req.on("error", reject);
});

function normalizeTomTom(raw, fastestSeconds) {
  return (raw.routes ?? []).map((r, index) => ({
    id: `tomtom-${index}`,
    durationSeconds: r.summary?.travelTimeInSeconds ?? 0,
    fastestDurationSeconds: fastestSeconds,
    distanceMeters: r.summary?.lengthInMeters ?? 0,
    // Until landscape enrichment is connected these values are deliberately conservative.
    factors: {
      beautifulRoads: 0.72 - index * 0.04,
      forest: 0.0, water: 0.0, mountains: 0.0, viewpoints: 0.0,
      culture: 0.0, museums: 0.0, architecture: 0.0, parks: 0.0, food: 0.0
    },
    motorwayShare: 0,
    industrialShare: 0,
    provider: "TomTom",
    raw: r
  }));
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/health") return json(res, 200, { ok: true, service: "scenic-path-backend", version: "0.1.0" });
  if (req.method === "POST" && req.url === "/v1/plan") {
    try {
      const body = await readJson(req);
      if (!body.origin || !body.destination || !body.preferences) return json(res, 400, { error: "origin, destination and preferences are required" });

      const fastestRaw = await tomTomRoute({
        apiKey: process.env.TOMTOM_API_KEY,
        origin: body.origin,
        destination: body.destination,
        preferences: body.preferences,
        routeType: "fastest"
      });
      const fastestSeconds = fastestRaw.routes?.[0]?.summary?.travelTimeInSeconds;
      if (!fastestSeconds) throw new Error("No fastest baseline route returned");

      const scenicRaw = await tomTomRoute({
        apiKey: process.env.TOMTOM_API_KEY,
        origin: body.origin,
        destination: body.destination,
        preferences: body.preferences,
        routeType: "thrilling"
      });
      const ranked = rankRoutes(normalizeTomTom(scenicRaw, fastestSeconds), body.preferences);
      return json(res, 200, {
        baseline: { durationSeconds: fastestSeconds, distanceMeters: fastestRaw.routes[0].summary.lengthInMeters },
        candidates: ranked,
        note: "M0 route core: thrilling-road candidate generation is active; landscape/culture corridor enrichment is M1."
      });
    } catch (error) {
      console.error(error);
      return json(res, 500, { error: error.message });
    }
  }
  json(res, 404, { error: "not found" });
});

server.listen(port, () => console.log(`Scenic Path backend listening on :${port}`));
