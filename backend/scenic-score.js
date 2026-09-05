const clamp = (v, min = 0, max = 1) => Math.max(min, Math.min(max, v));

/**
 * Provider-neutral route score. Every factor is normalized to 0..1.
 * A route must first pass the detour budget; beauty then decides among valid candidates.
 *
 * Factors that are not yet enriched are ignored rather than treated as zero. This lets
 * the corridor analyzer grow from road character into the full prototype taxonomy
 * (monuments, art, worship, etc.) without depressing scores during partial enrichment.
 */
export function scenicScore(candidate, preferences) {
  const w = preferences.weights ?? {};
  const f = candidate.factors ?? {};
  const weighted = [];
  const add = (weight, value) => {
    if (Number.isFinite(weight) && Number.isFinite(value) && weight > 0) weighted.push([weight, value]);
  };

  add(w.beautifulRoads, f.beautifulRoads);
  add(w.forest, f.forest);
  add(w.water, f.water);
  add(w.mountains, f.mountains);
  add(w.viewpoints, f.viewpoints);
  add(w.culture, f.culture);
  add(w.monuments, f.monuments);
  add(w.museums, f.museums);
  add(w.art, f.art);
  add(w.worship, f.worship);
  add(w.architecture, f.architecture);
  add(w.parks, f.parks);
  add(w.food, f.food);
  add(w.scenicHighlights, f.scenicHighlights);

  const denom = weighted.reduce((s, [weight]) => s + weight, 0) || 1;
  const beauty = weighted.reduce((s, [weight, value]) => s + weight * clamp(value), 0) / denom;

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
