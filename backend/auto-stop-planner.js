import { haversineMeters, samplePolyline } from "./corridor-analyzer.js";
import { tomTomRoute } from "./tomtom.js";
import { combinedDwellMinutes } from "./route-time.js";

const clamp = (value, min, max) => Math.max(min, Math.min(max, value));

export function automaticStopLimit(maxExtraMinutes = 0, configuredMaxStops = 5) {
  const budgetLimit = maxExtraMinutes >= 210 ? 3
    : maxExtraMinutes >= 100 ? 2
      : maxExtraMinutes >= 30 ? 1
        : 0;
  return Math.max(0, Math.min(configuredMaxStops ?? 5, budgetLimit));
}

export function autoStopDistanceLimitMeters(maxExtraMinutes = 0) {
  if (maxExtraMinutes < 45) return 6_000;
  if (maxExtraMinutes < 90) return 10_000;
  if (maxExtraMinutes < 150) return 14_000;
  if (maxExtraMinutes < 210) return 18_000;
  if (maxExtraMinutes < 300) return 23_000;
  return 27_000;
}

function dnaWeight(point, preferences = {}) {
  const w = preferences.weights ?? {};
  switch (point.kind) {
    case "VIEWPOINT": return w.viewpoints ?? 1;
    case "MUSEUM": return w.museums ?? 0.65;
    case "NATURE": return ((w.forest ?? 0.85) + (w.mountains ?? 0.75)) / 2;
    case "MONUMENT": return ((w.monuments ?? 0.78) + (w.culture ?? 0.8)) / 2;
    case "PARK": return w.parks ?? 0.6;
    case "ART": return w.art ?? 0.58;
    case "WORSHIP": return w.worship ?? 0.48;
    case "WATER": return w.water ?? 0.9;
    case "FOOD": return w.food ?? 0.35;
    case "ARCHITECTURE": return w.architecture ?? 0.65;
    default: return w.scenicHighlights ?? 0.7;
  }
}

function heritageBonus(point) {
  switch ((point.subtype ?? "").toLowerCase()) {
    case "castle":
    case "defensive_castle":
    case "stately":
    case "palace":
    case "manor":
      return 26;
    case "fort":
    case "ruins":
    case "archaeological_site":
      return 17;
    case "waterfall":
    case "viewpoint":
    case "lighthouse":
      return 12;
    default:
      return 0;
  }
}

function foodQualityBonus(point) {
  if (point.kind !== "FOOD") return 0;
  const rating = Number.isFinite(point.rating) ? point.rating : 0;
  const reviews = Number.isFinite(point.ratingCount) ? point.ratingCount : 0;
  const verified = rating > 0 ? rating * 10 + Math.log10(reviews + 10) * 6 : 0;
  const restaurant = point.subtype === "restaurant" ? 8 : 2;
  return verified + restaurant;
}

export function stopUtility(point, preferences = {}) {
  const relevance = clamp(point.relevance ?? point.suggestionScore ?? 0.5, 0, 1.3);
  const dna = clamp(dnaWeight(point, preferences), 0, 1);
  const distancePenalty = Math.max(0, point.distanceFromRouteMeters ?? 0) / 430;
  const dwellPenalty = Math.max(0, point.suggestedDwellMinutes ?? 20) * 0.18;
  return relevance * 60 + dna * 42 + heritageBonus(point) + foodQualityBonus(point) - distancePenalty - dwellPenalty;
}

export function chooseInitialAutoStops(scenePoints = [], preferences = {}, enabledSceneKinds = []) {
  const budgetMinutes = preferences.maxExtraMinutes ?? 0;
  const maxStops = automaticStopLimit(budgetMinutes, preferences.maxStops ?? 5);
  if (maxStops <= 0) return [];

  const enabled = new Set(enabledSceneKinds);
  if (enabled.size === 0) return [];

  const maxDistanceMeters = autoStopDistanceLimitMeters(budgetMinutes);
  const eligible = scenePoints
    .filter(point => point?.point && Number.isFinite(point.point.lat) && Number.isFinite(point.point.lon))
    .filter(point => enabled.has(point.kind))
    .filter(point => (point.distanceFromRouteMeters ?? 0) <= maxDistanceMeters)
    .sort((a, b) => stopUtility(b, preferences) - stopUtility(a, preferences));

  const selected = [];
  const add = point => {
    if (!point || selected.length >= maxStops || selected.some(existing => existing.id === point.id)) return false;
    selected.push(point);
    return true;
  };

  if (budgetMinutes >= 60 && enabled.has("FOOD")) {
    const food = eligible
      .filter(point => point.kind === "FOOD")
      .sort((a, b) => foodQualityBonus(b) + stopUtility(b, preferences) - foodQualityBonus(a) - stopUtility(a, preferences))[0];
    add(food);
  }

  for (const point of eligible) {
    if (selected.length >= maxStops) break;
    if (selected.some(existing => existing.id === point.id)) continue;
    if (selected.some(existing => existing.kind === point.kind) && eligible.some(other => !selected.some(s => s.kind === other.kind))) {
      continue;
    }
    add(point);
  }

  return selected;
}

function routePoints(route) {
  return (route.legs ?? []).flatMap(leg =>
    (leg.points ?? []).map(point => ({ lat: point.latitude, lon: point.longitude }))
  );
}

function routeProgress(points, target) {
  const samples = samplePolyline(points, { spacingMeters: 1_500, maxSamples: 160 });
  let bestIndex = 0;
  let bestDistance = Infinity;
  samples.forEach((sample, index) => {
    const distance = haversineMeters(sample, target);
    if (distance < bestDistance) {
      bestDistance = distance;
      bestIndex = index;
    }
  });
  return bestIndex;
}

function orderWaypoints(route, fixedWaypoints, autoStops) {
  return [
    ...fixedWaypoints.map((point, index) => ({ point, fixed: true, index })),
    ...autoStops.map((stop, index) => ({ point: stop.point, fixed: false, index })),
  ]
    .sort((a, b) => routeProgress(route.points, a.point) - routeProgress(route.points, b.point))
    .map(item => item.point);
}

function dropWeakestPreservingFood(stops, preferences, enabledSceneKinds) {
  if (stops.length <= 1) return [];
  const foodEnabled = enabledSceneKinds.includes("FOOD") && (preferences.maxExtraMinutes ?? 0) >= 60;
  const nonFood = stops.filter(stop => stop.kind !== "FOOD");
  const pool = foodEnabled && nonFood.length ? nonFood : stops;
  const weakest = [...pool].sort((a, b) => stopUtility(a, preferences) - stopUtility(b, preferences))[0];
  return stops.filter(stop => stop.id !== weakest.id);
}

export async function insertAutomaticStops({
  candidate,
  origin,
  destination,
  fixedWaypoints = [],
  fixedDwellMinutes = 0,
  fastestSeconds,
  preferences,
  enabledSceneKinds = [],
  apiKey,
}) {
  if (!candidate || candidate.character === "DIRECT" || !candidate.scenePoints?.length) return candidate;

  const budgetMinutes = Math.max(0, preferences.maxExtraMinutes ?? 0);
  let selected = chooseInitialAutoStops(candidate.scenePoints, preferences, enabledSceneKinds);
  if (!selected.length) return candidate;

  const maxExtraPercent = Math.max(0, preferences.maxExtraPercent ?? 1000);
  const maxDurationByPercent = fastestSeconds > 0
    ? fastestSeconds * (1 + maxExtraPercent / 100)
    : Infinity;

  while (selected.length) {
    const waypoints = orderWaypoints(candidate, fixedWaypoints, selected);
    let raw;
    try {
      raw = await tomTomRoute({
        apiKey,
        origin,
        destination,
        waypoints,
        preferences,
        routeType: candidate.character === "DIRECT" ? "fastest" : "thrilling",
      });
    } catch {
      selected = dropWeakestPreservingFood(selected, preferences, enabledSceneKinds);
      continue;
    }

    const route = raw.routes?.[0];
    const durationSeconds = route?.summary?.travelTimeInSeconds;
    if (!durationSeconds) {
      selected = dropWeakestPreservingFood(selected, preferences, enabledSceneKinds);
      continue;
    }

    const driveExtraMinutes = Math.max(0, (durationSeconds - fastestSeconds) / 60);
    const autoDwellMinutes = selected.reduce((sum, stop) => sum + (stop.suggestedDwellMinutes ?? 20), 0);
    const dwellMinutes = combinedDwellMinutes(fixedDwellMinutes, autoDwellMinutes);
    const totalExtraMinutes = driveExtraMinutes + dwellMinutes;
    const withinTime = totalExtraMinutes <= budgetMinutes + 1;
    const withinPercent = durationSeconds <= maxDurationByPercent + 1;

    if (!withinTime || !withinPercent) {
      selected = dropWeakestPreservingFood(selected, preferences, enabledSceneKinds);
      continue;
    }

    const includedIds = new Set(selected.map(stop => stop.id));
    const scenePoints = candidate.scenePoints.map(point => ({
      ...point,
      includedInRoute: includedIds.has(point.id),
      personalMatch: Math.round(clamp((point.relevance ?? 0.5) * 55 + dnaWeight(point, preferences) * 45, 0, 100)),
      estimatedDetourMinutes: Math.max(0, point.distanceFromRouteMeters ?? 0) / 500,
      rationale: point.rationale ?? (
        heritageBonus(point) >= 17 ? "Standout heritage · selected inside the time budget"
          : point.kind === "FOOD" ? "Top Food candidate · selected inside the time budget"
            : "Strong Scenic DNA fit · selected inside the time budget"
      ),
    }));

    const experienceBonus = selected.reduce((sum, stop) => sum + Math.max(0, stopUtility(stop, preferences)), 0) / Math.max(1, selected.length) * 0.12;

    return {
      ...candidate,
      distanceMeters: route.summary?.lengthInMeters ?? candidate.distanceMeters,
      durationSeconds,
      points: routePoints(route),
      extraMinutes: driveExtraMinutes,
      driveExtraMinutes,
      dwellMinutes,
      totalExtraMinutes,
      autoStopIds: selected.map(stop => stop.id),
      scenePoints,
      variantLabel: candidate.variantLabel ?? "Best match",
      experienceScore: clamp((candidate.scenicScore ?? 0) + experienceBonus, 0, 100),
      strongestSignals: [
        ...(candidate.strongestSignals ?? []),
        "automaticSmartStops",
        selected.some(stop => stop.kind === "FOOD") ? "topFood" : null,
        selected.some(stop => stop.kind === "MONUMENT") ? "heritage" : null,
      ].filter(Boolean).slice(0, 6),
    };
  }

  return candidate;
}
