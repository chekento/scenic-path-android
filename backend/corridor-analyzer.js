const EARTH_RADIUS_M = 6_371_000;
const clamp = (value, min = 0, max = 1) => Math.max(min, Math.min(max, value));
const toRad = deg => deg * Math.PI / 180;

export function haversineMeters(a, b) {
  const dLat = toRad(b.lat - a.lat);
  const dLon = toRad(b.lon - a.lon);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

function bearingDegrees(a, b) {
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const dLon = toRad(b.lon - a.lon);
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}

function angularDifference(a, b) {
  const diff = Math.abs(a - b) % 360;
  return diff > 180 ? 360 - diff : diff;
}

export function routeLengthMeters(points) {
  let total = 0;
  for (let i = 1; i < points.length; i += 1) total += haversineMeters(points[i - 1], points[i]);
  return total;
}

export function samplePolyline(points, { spacingMeters = 1_200, maxSamples = 72 } = {}) {
  if (!Array.isArray(points) || points.length === 0) return [];
  if (points.length === 1) return [{ ...points[0], routeProgress: 0 }];

  const total = routeLengthMeters(points);
  if (total <= 1) return [{ ...points[0], routeProgress: 0 }];
  const targetSpacing = Math.max(spacingMeters, total / Math.max(2, maxSamples - 1));
  const samples = [{ ...points[0], routeProgress: 0 }];
  let nextDistance = targetSpacing;
  let travelled = 0;

  for (let i = 1; i < points.length; i += 1) {
    const a = points[i - 1];
    const b = points[i];
    const segment = haversineMeters(a, b);
    if (segment <= 0) continue;
    while (travelled + segment >= nextDistance && samples.length < maxSamples - 1) {
      const t = (nextDistance - travelled) / segment;
      samples.push({
        lat: a.lat + (b.lat - a.lat) * t,
        lon: a.lon + (b.lon - a.lon) * t,
        routeProgress: nextDistance / total,
      });
      nextDistance += targetSpacing;
    }
    travelled += segment;
  }

  samples.push({ ...points.at(-1), routeProgress: 1 });
  return samples;
}

export function geometrySignals(points) {
  const lengthMeters = routeLengthMeters(points);
  if (points.length < 3 || lengthMeters < 250) {
    return { beautifulRoads: 0.35, windingness: 0, lengthMeters };
  }

  let turnEnergy = 0;
  let measured = 0;
  for (let i = 1; i < points.length - 1; i += 1) {
    const prevLen = haversineMeters(points[i - 1], points[i]);
    const nextLen = haversineMeters(points[i], points[i + 1]);
    if (prevLen < 20 || nextLen < 20) continue;
    const angle = angularDifference(
      bearingDegrees(points[i - 1], points[i]),
      bearingDegrees(points[i], points[i + 1]),
    );
    const usefulTurn = Math.max(0, Math.min(95, angle) - 8) / 87;
    const segmentWeight = Math.min(1, Math.min(prevLen, nextLen) / 220);
    turnEnergy += usefulTurn * segmentWeight;
    measured += segmentWeight;
  }

  const km = Math.max(0.25, lengthMeters / 1000);
  const turnsPerKm = turnEnergy / km;
  const windingness = clamp(turnsPerKm / 2.4);
  // Gentle variety scores best; straight motorway-like geometry stays low without
  // rewarding extremely jagged geometry indefinitely.
  const beautifulRoads = clamp(0.28 + windingness * 0.72);
  return { beautifulRoads, windingness, lengthMeters, measuredTurns: measured };
}

function observationStrengthAt(sample, observations, signal, radiusMeters) {
  let best = 0;
  for (const observation of observations) {
    const strength = observation.signals?.[signal];
    if (!Number.isFinite(strength) || strength <= 0 || !observation.point) continue;
    const distance = haversineMeters(sample, observation.point);
    if (distance > radiusMeters) continue;
    best = Math.max(best, clamp(strength) * (1 - distance / radiusMeters));
  }
  return best;
}

function coverage(samples, observations, signal, radiusMeters) {
  if (!samples.length || !observations.length) return undefined;
  const values = samples.map(sample => observationStrengthAt(sample, observations, signal, radiusMeters));
  if (!values.some(value => value > 0)) return undefined;
  return clamp(values.reduce((sum, value) => sum + value, 0) / values.length);
}

function densityFactor(observations, kind, routeLengthKm) {
  const matching = observations.filter(observation => observation.kind === kind && observation.point);
  if (!matching.length) return undefined;
  const quality = matching.reduce((sum, observation) => sum + clamp(observation.relevance ?? 0.65), 0);
  const expectedPerTenKm = Math.max(1, routeLengthKm / 10);
  return clamp(1 - Math.exp(-quality / expectedPerTenKm));
}

function elevationReliefFactor(observations) {
  const elevations = observations
    .map(observation => observation.elevationMeters)
    .filter(Number.isFinite);
  if (elevations.length < 2) return undefined;
  const relief = Math.max(...elevations) - Math.min(...elevations);
  return clamp(relief / 650);
}

function scenePointsAlongRoute(observations, enabledSceneKinds, routeLengthKm) {
  const enabled = new Set(enabledSceneKinds ?? []);
  const maxHighlights = Math.max(4, Math.min(18, Math.round(routeLengthKm / 12) + 4));
  return observations
    .filter(observation => observation.kind && observation.point)
    .filter(observation => enabled.size === 0 || enabled.has(observation.kind) || observation.kind === "SCENIC")
    .sort((a, b) => (b.relevance ?? 0) - (a.relevance ?? 0))
    .filter((observation, index, array) => {
      const key = `${observation.kind}:${observation.name ?? ""}:${observation.point.lat.toFixed(4)}:${observation.point.lon.toFixed(4)}`;
      return array.findIndex(other => `${other.kind}:${other.name ?? ""}:${other.point.lat.toFixed(4)}:${other.point.lon.toFixed(4)}` === key) === index;
    })
    .slice(0, maxHighlights)
    .map(observation => ({
      id: observation.id,
      name: observation.name ?? observation.subtype ?? observation.kind,
      kind: observation.kind,
      subtype: observation.subtype,
      point: observation.point,
      relevance: clamp(observation.relevance ?? 0.65),
      tags: observation.tags,
    }));
}

export function analyzeCorridor({ points, observations = [], enabledSceneKinds = [] }) {
  const geometry = geometrySignals(points ?? []);
  const samples = samplePolyline(points ?? []);
  const routeLengthKm = Math.max(0.1, geometry.lengthMeters / 1000);

  const factors = {
    beautifulRoads: geometry.beautifulRoads,
    forest: coverage(samples, observations, "forest", 1_300),
    water: coverage(samples, observations, "water", 1_000),
    mountains: elevationReliefFactor(observations) ?? coverage(samples, observations, "mountains", 2_300),
    viewpoints: densityFactor(observations, "VIEWPOINT", routeLengthKm),
    culture: coverage(samples, observations, "culture", 1_200),
    monuments: densityFactor(observations, "MONUMENT", routeLengthKm),
    museums: densityFactor(observations, "MUSEUM", routeLengthKm),
    art: densityFactor(observations, "ART", routeLengthKm),
    worship: densityFactor(observations, "WORSHIP", routeLengthKm),
    architecture: densityFactor(observations, "ARCHITECTURE", routeLengthKm),
    parks: densityFactor(observations, "PARK", routeLengthKm),
    food: densityFactor(observations, "FOOD", routeLengthKm),
    scenicHighlights: densityFactor(observations, "SCENIC", routeLengthKm),
  };

  Object.keys(factors).forEach(key => factors[key] === undefined && delete factors[key]);

  const motorwayShare = coverage(samples, observations, "motorway", 260) ?? 0;
  const industrialShare = coverage(samples, observations, "industrial", 900) ?? 0;
  const scenePoints = scenePointsAlongRoute(observations, enabledSceneKinds, routeLengthKm);

  const rankedFactorNames = Object.entries(factors)
    .filter(([name]) => name !== "beautifulRoads")
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(([name]) => name);

  return {
    factors,
    motorwayShare,
    industrialShare,
    scenePoints,
    diagnostics: {
      routeLengthMeters: geometry.lengthMeters,
      sampleCount: samples.length,
      observationCount: observations.length,
      windingness: geometry.windingness,
      strongestSignals: rankedFactorNames,
    },
  };
}
