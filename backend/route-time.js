export function withManualDwell(candidate, fixedDwellMinutes = 0) {
  const fixed = Math.max(0, Number(fixedDwellMinutes) || 0);
  const driveExtra = Math.max(0, Number(candidate?.driveExtraMinutes ?? candidate?.extraMinutes) || 0);
  return {
    ...candidate,
    dwellMinutes: fixed,
    totalExtraMinutes: driveExtra + fixed,
  };
}

export function combinedDwellMinutes(fixedDwellMinutes = 0, automaticDwellMinutes = 0) {
  return Math.max(0, Number(fixedDwellMinutes) || 0) + Math.max(0, Number(automaticDwellMinutes) || 0);
}
