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

function foodScore(observation) {
  if (Number.isFinite(observation.foodScore)) return observation.foodScore * 20;
  const rating = observation.rating ?? 0;
  const reviews = Math.max(0, observation.ratingCount ?? 0);
  return rating * 20 + Math.log10(reviews + 10) * 3;
}

function toScenePoint(item) {
  const observation = item.observation;
  return {
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
  };
}

/**
 * Turn raw corridor observations into a small, diverse set of useful optional stops.
 * Exact detour validation happens before insertion.
 */
export function selectSceneSuggestions({ points, observations, enabledSceneKinds = [], maxStops = 5 }) {
  const enabled = new Set(enabledSceneKinds);
  const samples = samplePolyline(points, { spacingMeters: 700, maxSamples: 100 });
  const routeKm = Math.max(0.1, routeLengthMeters(points) / 1000);
  const desired = Math.max(3, Math.min(20, Math.max(maxStops * 2, Math.round(routeKm / 20) + 3)));
  const minSpacingMeters = routeKm < 20 ? 700 : routeKm < 80 ? 1_200 : 2_000;

  const ranked = observations
    .filter(observation => observation.kind && observation.point)
    .filter(observation => enabled.size === 0 || enabled.has(observation.kind) || observation.kind === "SCENIC")
    .map(observation => {
      const distanceFromRouteMeters = nearestDistance(observation.point, samples);
      const proximity = clamp(1 - distanceFromRouteMeters / 2_800);
      const relevance = clamp(observation.relevance ?? 0.65);
      const verifiedFoodBonus = observation.kind === "FOOD" ? clamp(foodScore(observation) / 120) * 0.08 : 0;
      const score = relevance * 0.72 + proximity * 0.20 + verifiedFoodBonus;
      return { observation, distanceFromRouteMeters, score };
    })
    .filter(item => item.distanceFromRouteMeters <= 2_800)
    .sort((a, b) => b.score - a.score);

  const selected = [];
  const selectedIds = new Set();

  const addIfUseful = item => {
    if (!item || selected.length >= desired || selectedIds.has(item.observation.id)) return false;
    const tooClose = selected.some(existing =>
      haversineMeters(existing.point, item.observation.point) < minSpacingMeters &&
      existing.kind === item.observation.kind
    );
    if (tooClose) return false;
    const point = toScenePoint(item);
    selected.push(point);
    selectedIds.add(point.id);
    return true;
  };

  // FOOD is a product promise, not an incidental category. If enabled and verified food
  // data exists, reserve one slot for the strongest rating/review-confidence candidate.
  if (enabled.has("FOOD")) {
    const topFood = ranked
      .filter(item => item.observation.kind === "FOOD")
      .sort((a, b) => foodScore(b.observation) - foodScore(a.observation))[0];
    addIfUseful(topFood);
  }

  // Then reserve one candidate for each other enabled category that has data.
  for (const kind of enabled) {
    if (kind === "FOOD") continue;
    addIfUseful(ranked.find(item => item.observation.kind === kind));
    if (selected.length >= desired) break;
  }

  // Fill remaining space by overall utility.
  for (const item of ranked) {
    if (selected.length >= desired) break;
    addIfUseful(item);
  }

  return selected;
}
