import test from "node:test";
import assert from "node:assert/strict";
import { orderFlexibleStops } from "../waypoint-order.js";

const origin = { lat: 53.60, lon: 10.00 };
const destination = { lat: 53.60, lon: 10.30 };
const stop = (id, lon, lat = 53.60) => ({ id, position: { lat, lon } });

test("flexible stop ordering follows origin to destination progress", () => {
  const input = [stop("late", 10.24), stop("early", 10.06), stop("middle", 10.15)];
  assert.deepEqual(orderFlexibleStops(input, origin, destination).map(item => item.id), ["early", "middle", "late"]);
});

test("single stop order remains unchanged", () => {
  const input = [stop("only", 10.10)];
  assert.deepEqual(orderFlexibleStops(input, origin, destination), input);
});

test("same-progress stops use lateral distance then stable input order", () => {
  const near = stop("near", 10.15, 53.601);
  const far = stop("far", 10.15, 53.63);
  const ordered = orderFlexibleStops([far, near], origin, destination);
  assert.deepEqual(ordered.map(item => item.id), ["near", "far"]);
});
