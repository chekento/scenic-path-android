import { haversineMeters, routeLengthMeters, samplePolyline } from "./corridor-analyzer.js";

const clamp = (value, min = 0, max = 1) => Math.max(min, Math.min(max, value));

const dwellByKind = {
  VIEWPOINT: 12,
  MUSEUM: 50,
  NATURE: 20,
  MONUMENT: 25,
  PARK: 25,
  ART: 30,
  WORSHIP: 20,
  WATER: 25,
  FOOD: 45,
  ARCHITECTURE: 18,
  SCENIC: 15,
};

function nearestDistance(point, routeSamples) {
  let best = Infinity;
  for (const sample of routeSamples) best = Math.min(best, haversineMeters(point, sample));
  return best;
}

/**
 * Turn raw corridor observations into a small set of useful optional stops.
 * These are suggestions only; exact detour validation happens before insertion.
 */
export function selectSceneSuggestions({ points, observations, enabledSceneKinds = [], maxStops = 5 }) {
  const enabled = new Set(enabledSceneKinds);
  const samples = samplePolyline(points, { spacingMeters: 700, maxSamples: 100 });
  const routeKm = Math.max(0.1, routeLengthMeters(points) / 1000);
  const desired = Math.max(3, Math.min(18, Math.max(maxStops * 2, Math.round(routeKm / 20) + 3)));
  const minSpacingMeters = routeKm < 20 ? 700 : routeKm < 80 ? 1_200 : 2_000;

  const ranked = observations
    .filter(observation => observation.kind && observation.point)
    .filter(observation => enabled.size === 0 || enabled.has(observation.kind) || observation.kind === "SCENIC")
    .map(observation => {
      const distanceFromRouteMeters = nearestDistance(observation.point, samples);
      const proximity = clamp(1 - distanceFromRouteMeters / 2_200);
      const relevance = clamp(observation.relevance ?? 0.65);
      const score = relevance * 0.78 + proximity * 0.22;
      return { observation, distanceFromRouteMeters, score };
    })
    .filter(item => item.distanceFromRouteMeters <= 2_200)
    .sort((a, b) => b.score - a.score);

  const selected = [];
  for (const item of ranked) {
    if (selected.length >= desired) break;
    const tooClose = selected.some(existing =>
      haversineMeters(existing.point, item.observation.point) < minSpacingMeters &&
      existing.kind === item.observation.kind
    );
    if (tooClose) continue;

    const observation = item.observation;
    selected.push({
      id: observation.id,
      name: observation.name ?? observation.subtype ?? observation.kind,
      kind: observation.kind,
      subtype: observation.subtype,
      point: observation.point,
      relevance: clamp(observation.relevance ?? 0.65),
      suggestionScore: clamp(item.score),
      distanceFromRouteMeters: Math.round(item.distanceFromRouteMeters),
      suggestedDwellMinutes: dwellByKind[observation.kind] ?? 20,
      rating: observation.rating,
      ratingCount: observation.ratingCount,
      openNow: observation.openNow,
      url: observation.url,
      attribution: observation.attribution,
    });
  }

  return selected;
}
