const clamp = (value, min = 0, max = 1) => Math.max(min, Math.min(max, value));

function timeEfficiency(candidate, fastestDurationSeconds, maxExtraPercent = 35) {
  if (!fastestDurationSeconds) return 0;
  const extraRatio = Math.max(0, candidate.durationSeconds / fastestDurationSeconds - 1);
  const budget = Math.max(0.05, maxExtraPercent / 100);
  return clamp(1 - extraRatio / budget);
}

export function routeProfileScore(candidate, requestedCharacter, preferences, fastestDurationSeconds) {
  const scenic = clamp((candidate.scenicScore ?? 0) / 100);
  const efficient = timeEfficiency(candidate, fastestDurationSeconds, preferences.maxExtraPercent);
  switch (requestedCharacter) {
    case "DIRECT":
      return efficient;
    case "BALANCED":
      return scenic * 0.58 + efficient * 0.42;
    case "CUSTOM":
    case "BEAUTIFUL":
    default:
      return scenic * 0.86 + efficient * 0.14;
  }
}

export function orderRoutesForCharacter(candidates, requestedCharacter, preferences, fastestDurationSeconds) {
  return [...candidates]
    .map(candidate => ({
      ...candidate,
      profileScore: routeProfileScore(candidate, requestedCharacter, preferences, fastestDurationSeconds) * 100,
      recommendedFor: requestedCharacter,
    }))
    .sort((a, b) => {
      if (requestedCharacter === "DIRECT") {
        return a.durationSeconds - b.durationSeconds || b.scenicScore - a.scenicScore;
      }
      const scoreDiff = b.profileScore - a.profileScore;
      if (Math.abs(scoreDiff) > 0.001) return scoreDiff;
      return a.durationSeconds - b.durationSeconds;
    })
    .map((candidate, index) => ({ ...candidate, recommended: index === 0 }));
}
