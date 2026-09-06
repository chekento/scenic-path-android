import test from "node:test";
import assert from "node:assert/strict";
import { combinedDwellMinutes, withManualDwell } from "../route-time.js";

test("manual stop dwell is visible even when no automatic stops are inserted", () => {
  const candidate = withManualDwell({
    id: "route-1",
    driveExtraMinutes: 12.5,
    extraMinutes: 12.5,
    dwellMinutes: 0,
    totalExtraMinutes: 12.5,
  }, 45);

  assert.equal(candidate.dwellMinutes, 45);
  assert.equal(candidate.totalExtraMinutes, 57.5);
});

test("automatic and manual visit time are combined exactly once", () => {
  assert.equal(combinedDwellMinutes(35, 20), 55);
  assert.equal(combinedDwellMinutes(0, 20), 20);
  assert.equal(combinedDwellMinutes(35, 0), 35);
});
