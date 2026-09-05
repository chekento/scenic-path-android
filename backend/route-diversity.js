import { haversineMeters } from "./round-trip.js";

function sample(points = [], maxSamples = 54) {
  if (!Array.isArray(points) || points.length <= maxSamples) return points ?? [];
  const step = (points.length - 1) / (maxSamples - 1);
  return Array.from({ length: maxSamples }, (_, index) => points[Math.min(points.length - 1, Math.round(index * step))]);
}

export function geometryOverlap(a = [], b = [], thresholdMeters = 1_250) {
  if (a.length < 2 || b.length < 2) return 0;
  const aa = sample(a, 54);
  const bb = sample(b, 76);
  let near = 0;
  for (const point of aa) {
    const distance = bb.reduce((best, other) => Math.min(best, haversineMeters(point, other)), Infinity);
    if (distance <= thresholdMeters) near += 1;
  }
  return near / Math.max(1, aa.length);
}

function stopOverlap(a = [], b = []) {
  const aa = new Set(a);
  const bb = new Set(b);
  if (!aa.size && !bb.size) return 0;
  const union = new Set([...aa, ...bb]).size;
  let intersection = 0;
  for (const id of aa) if (bb.has(id)) intersection += 1;
  return union ? intersection / union : 0;
}

export function routeDiversity(a, b) {
  const geometry = 1 - geometryOverlap(a?.points ?? [], b?.points ?? []);
  const stops = 1 - stopOverlap(a?.autoStopIds ?? [], b?.autoStopIds ?? []);
  return Math.max(0, Math.min(1, geometry * 0.82 + stops * 0.18));
}

/**
 * Keep the best route first, then deliberately prefer a different corridor for Alternative 2.
 * Extra + Route variants remain diverse while gradually giving quality more weight again.
 */
export function orderDiverseRoutes(candidates = [], requestedCount = 2) {
  if (!candidates.length) return [];
  const limit = Math.max(1, Math.min(5, requestedCount, candidates.length));
  const remaining = [...candidates];
  const selected = [remaining.shift()];

  while (selected.length < limit && remaining.length) {
    let bestIndex = 0;
    let bestScore = -Infinity;
    remaining.forEach((candidate, index) => {
      const diversity = Math.min(...selected.map(existing => routeDiversity(existing, candidate)));
      const quality = Math.max(0, Math.min(1, (candidate.profileScore ?? candidate.experienceScore ?? candidate.scenicScore ?? 0) / 100));
      const score = selected.length === 1
        ? diversity * 0.80 + quality * 0.20
        : diversity * 0.64 + quality * 0.36;
      if (score > bestScore) {
        bestScore = score;
        bestIndex = index;
      }
    });
    selected.push(remaining.splice(bestIndex, 1)[0]);
  }

  return selected.map((candidate, index) => ({
    ...candidate,
    variantLabel: index === 0
      ? candidate.variantLabel
      : index === 1
        ? "Alternative 2 · different corridor"
        : `Alternative ${index + 1}`,
    diversityFromPrimary: index === 0 ? 0 : routeDiversity(selected[0], candidate),
  }));
}
