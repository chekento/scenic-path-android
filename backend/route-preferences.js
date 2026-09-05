export function balancedPreferences(preferences = {}) {
  return {
    ...preferences,
    maxExtraMinutes: Math.max(30, Number(preferences.maxExtraMinutes) || 0),
    maxExtraPercent: Math.max(25, Number(preferences.maxExtraPercent) || 0),
    avoidMotorways: false,
    windingness: 50,
    hilliness: 40,
  };
}

export function beautifulPreferences(preferences = {}) {
  return {
    ...preferences,
    maxExtraMinutes: Math.max(45, Number(preferences.maxExtraMinutes) || 0),
    maxExtraPercent: Math.max(35, Number(preferences.maxExtraPercent) || 0),
    avoidMotorways: true,
    windingness: 75,
    hilliness: 60,
  };
}

export function directPreferences(preferences = {}) {
  return {
    ...preferences,
    avoidMotorways: false,
    windingness: 20,
    hilliness: 20,
  };
}

/**
 * Apply only the selected routing-priority preset. Day-trip scope keeps the user's chosen
 * time budget and enforces only the structural minimum needed for a useful outing.
 */
export function preferencesForCharacter(preferences = {}, character = "BEAUTIFUL", mode = "QUICK") {
  const characterized = character === "DIRECT"
    ? directPreferences(preferences)
    : character === "BALANCED"
      ? balancedPreferences(preferences)
      : character === "CUSTOM"
        ? { ...preferences }
        : beautifulPreferences(preferences);

  return mode === "DAY_TRIP"
    ? { ...characterized, maxExtraMinutes: Math.max(30, Number(characterized.maxExtraMinutes) || 0) }
    : characterized;
}
