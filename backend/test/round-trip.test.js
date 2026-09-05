import test from "node:test";
import assert from "node:assert/strict";
import {
  isRoundTripRequest,
  roundTripWaypointSets,
  targetDriveMinutes,
  utilizationScore,
} from "../round-trip.js";

const origin = { lat: 53.675, lon: 10.24 };

const preferences = {
  maxExtraMinutes: 240,
  vehicle: { kind: "CAR" },
};

test("day trip with same endpoint is recognized as a round trip", () => {
  assert.equal(isRoundTripRequest({ mode: "DAY_TRIP", origin, destination: { ...origin } }), true);
  assert.equal(isRoundTripRequest({ mode: "QUICK", origin, destination: { ...origin } }), false);
  assert.equal(isRoundTripRequest({ mode: "DAY_TRIP", origin, destination: { lat: 53.72, lon: 10.31 } }), false);
});

test("round-trip seeds create several genuinely different directions", () => {
  const sets = roundTripWaypointSets({ origin, preferences, autoSuggestStops: true, count: 5 });
  assert.equal(sets.length, 5);
  assert.ok(sets.every(set => set.length === 3));
  const firstPoints = sets.map(set => `${set[0].lat.toFixed(4)}:${set[0].lon.toFixed(4)}`);
  assert.equal(new Set(firstPoints).size, 5);
});

test("Smart Stops reserve outing time instead of consuming the entire drive budget", () => {
  const withStops = targetDriveMinutes(preferences, true, 0);
  const roadsOnly = targetDriveMinutes(preferences, false, 0);
  assert.ok(withStops < roadsOnly);
  assert.ok(withStops > preferences.maxExtraMinutes * 0.5);
});

test("budget utilization strongly prefers using most of the selected day", () => {
  assert.ok(utilizationScore(220, 240) > utilizationScore(120, 240));
  assert.ok(utilizationScore(220, 240) > utilizationScore(260, 240));
});
