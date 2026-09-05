const EARTH = 6_371_000;
const toRad = degrees => degrees * Math.PI / 180;
const toDeg = radians => radians * 180 / Math.PI;

export function haversineMeters(a, b) {
  const dLat = toRad(b.lat - a.lat);
  const dLon = toRad(b.lon - a.lon);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
}

export function isRoundTripRequest(body) {
  return body?.mode === "DAY_TRIP" && body?.origin && body?.destination &&
    haversineMeters(body.origin, body.destination) <= 350;
}

function project(origin, bearingDegrees, distanceMeters) {
  const angular = distanceMeters / EARTH;
  const bearing = toRad(bearingDegrees);
  const lat1 = toRad(origin.lat);
  const lon1 = toRad(origin.lon);
  const lat2 = Math.asin(
    Math.sin(lat1) * Math.cos(angular) +
    Math.cos(lat1) * Math.sin(angular) * Math.cos(bearing)
  );
  const lon2 = lon1 + Math.atan2(
    Math.sin(bearing) * Math.sin(angular) * Math.cos(lat1),
    Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2)
  );
  return { lat: toDeg(lat2), lon: toDeg(lon2) };
}

function speedKmh(preferences = {}) {
  switch (preferences.vehicle?.kind) {
    case "BICYCLE": return 18;
    case "TRUCK": return 42;
    case "COACH": return 46;
    case "CAMPER": return 48;
    case "MOTORCYCLE": return 52;
    default: return 55;
  }
}

export function targetDriveMinutes(preferences = {}, autoSuggestStops = true, fixedDwellMinutes = 0) {
  const budget = Math.max(30, Number(preferences.maxExtraMinutes) || 0);
  const reserveRatio = autoSuggestStops ? 0.24 : 0.07;
  const reserve = Math.min(budget * 0.42, budget * reserveRatio + fixedDwellMinutes);
  return Math.max(budget * 0.55, budget - reserve);
}

export function roundTripWaypointSets({
  origin,
  preferences,
  autoSuggestStops = true,
  fixedDwellMinutes = 0,
  count = 3,
}) {
  const targetKm = speedKmh(preferences) * targetDriveMinutes(preferences, autoSuggestStops, fixedDwellMinutes) / 60;
  const radius = Math.max(2_500, Math.min(70_000, targetKm * 1000 / 5.46));
  const scales = [0.86, 1.0, 1.12, 0.94, 1.06, 0.8];
  const desired = Math.max(2, Math.min(6, count));
  return Array.from({ length: desired }, (_, variant) => {
    const orientation = (variant * 57 + (variant % 2 === 0 ? 12 : 31)) % 120;
    const r = radius * scales[variant % scales.length];
    return [
      project(origin, orientation, r),
      project(origin, orientation + 120, r),
      project(origin, orientation + 240, r),
    ];
  });
}

export function budgetUtilization(outingMinutes, budgetMinutes) {
  if (!(budgetMinutes > 0)) return 0;
  return Math.max(0, Math.min(1.25, outingMinutes / budgetMinutes));
}

export function utilizationScore(outingMinutes, budgetMinutes) {
  const utilization = budgetUtilization(outingMinutes, budgetMinutes);
  if (utilization > 1.03) return 0;
  return Math.max(0, Math.min(1, 1 - Math.abs(0.93 - utilization) / 0.93));
}
