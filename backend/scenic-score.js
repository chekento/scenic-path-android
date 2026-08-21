const clamp = (v, min = 0, max = 1) => Math.max(min, Math.min(max, v));

/**
 * Provider-neutral route score. Every factor is normalized to 0..1.
 * A route must first pass the detour budget; beauty then decides among valid candidates.
 */
export function scenicScore(candidate, preferences) {
  const w = preferences.weights;
  const f = candidate.factors;
  const weighted = [
    [w.beautifulRoads, f.beautifulRoads],
    [w.forest, f.forest],
    [w.water, f.water],
    [w.mountains, f.mountains],
    [w.viewpoints, f.viewpoints],
    [w.culture, f.culture],
    [w.museums, f.museums],
    [w.architecture, f.architecture],
    [w.parks, f.parks],
    [w.food, f.food]
  ];
  const denom = weighted.reduce((s, [weight]) => s + weight, 0) || 1;
  const beauty = weighted.reduce((s, [weight, value]) => s + weight * clamp(value ?? 0), 0) / denom;

  const motorwayPenalty = preferences.avoidMotorways ? clamp(candidate.motorwayShare ?? 0) * 0.22 : 0;
  const industrialPenalty = clamp(candidate.industrialShare ?? 0) * 0.12;
  const detourRatio = Math.max(0, candidate.durationSeconds / candidate.fastestDurationSeconds - 1);
  const detourPenalty = clamp(detourRatio / Math.max(0.01, preferences.maxExtraPercent / 100)) * 0.14;

  return clamp(beauty - motorwayPenalty - industrialPenalty - detourPenalty) * 100;
}

export function withinDetourBudget(candidate, preferences) {
  const extraMinutes = Math.max(0, candidate.durationSeconds - candidate.fastestDurationSeconds) / 60;
  const extraPercent = Math.max(0, candidate.durationSeconds / candidate.fastestDurationSeconds - 1) * 100;
  return extraMinutes <= preferences.maxExtraMinutes && extraPercent <= preferences.maxExtraPercent;
}

export function rankRoutes(candidates, preferences) {
  return candidates
    .filter(c => withinDetourBudget(c, preferences))
    .map(c => ({ ...c, scenicScore: scenicScore(c, preferences) }))
    .sort((a, b) => b.scenicScore - a.scenicScore);
}
