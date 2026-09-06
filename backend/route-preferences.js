export function balancedPreferences(preferences = {}) {
  return {
    ...preferences,
    windingness: Math.min(55, Math.max(35, Number(preferences.windingness) || 50)),
    hilliness: Math.min(45, Math.max(25, Number(preferences.hilliness) || 40)),
  };
}

export function beautifulPreferences(preferences = {}) {
  return {
    ...preferences,
    windingness: Math.max(65, Number(preferences.windingness) || 70),
    hilliness: Math.max(45, Number(preferences.hilliness) || 50),
    // `false` means motorways are allowed, not required. Keep an explicit false untouched;
    // a true hard constraint must also remain true.
    avoidMotorways: preferences.avoidMotorways !== false,
  };
}

/**
 * Final execution must preserve every explicit user choice. Presets are applied when the user
 * chooses them in the UI; they are not silently re-applied during route calculation.
 */
export function executionPreferences(preferences = {}, mode = "QUICK") {
  const result = { ...preferences };
  if (mode === "DAY_TRIP") {
    result.maxExtraMinutes = Math.max(30, Number(result.maxExtraMinutes) || 0);
  }
  return result;
}
