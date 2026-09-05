const toRad = degrees => degrees * Math.PI / 180;

function localXY(point, origin) {
  const meanLat = toRad((point.lat + origin.lat) / 2);
  return {
    x: (point.lon - origin.lon) * Math.cos(meanLat) * 111_320,
    y: (point.lat - origin.lat) * 110_540,
  };
}

function routeProgress(point, origin, destination) {
  const p = localXY(point, origin);
  const d = localXY(destination, origin);
  const denom = d.x * d.x + d.y * d.y;
  if (denom <= 1) return 0;
  return (p.x * d.x + p.y * d.y) / denom;
}

function lateralDistance(point, origin, destination) {
  const p = localXY(point, origin);
  const d = localXY(destination, origin);
  const denom = Math.hypot(d.x, d.y);
  if (denom <= 1) return Math.hypot(p.x, p.y);
  return Math.abs(p.x * d.y - p.y * d.x) / denom;
}

/**
 * Produce a stable forward journey order for user-selected waypoints.
 *
 * This does not pretend to be a travelling-salesperson solver. It follows the requested
 * origin→destination direction, then uses lateral distance and original order as deterministic
 * tie-breakers. The real routing provider still validates the resulting road route/time.
 */
export function orderFlexibleStops(stops = [], origin, destination) {
  if (!Array.isArray(stops) || stops.length < 2 || !origin || !destination) return [...(stops ?? [])];
  return stops
    .map((stop, index) => ({
      stop,
      index,
      progress: routeProgress(stop.position, origin, destination),
      lateral: lateralDistance(stop.position, origin, destination),
    }))
    .sort((a, b) =>
      a.progress - b.progress ||
      a.lateral - b.lateral ||
      a.index - b.index
    )
    .map(item => item.stop);
}
