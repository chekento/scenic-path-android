import test from "node:test";
import assert from "node:assert/strict";
import { vehicleConfig } from "../tomtom.js";

test("heavy vehicles never use thrilling routing", () => {
  for (const kind of ["CAMPER", "TRUCK", "COACH"]) {
    const config = vehicleConfig({ vehicle: { kind } }, "thrilling");
    assert.equal(config.routeType, "fastest");
  }
});

test("vehicle kinds map to their actual TomTom travel modes", () => {
  assert.equal(vehicleConfig({ vehicle: { kind: "CAR" } }).travelMode, "car");
  assert.equal(vehicleConfig({ vehicle: { kind: "MOTORCYCLE" } }).travelMode, "motorcycle");
  assert.equal(vehicleConfig({ vehicle: { kind: "TRUCK" } }).travelMode, "truck");
  assert.equal(vehicleConfig({ vehicle: { kind: "COACH" } }).travelMode, "bus");
  assert.equal(vehicleConfig({ vehicle: { kind: "BICYCLE" } }).travelMode, "bicycle");
});

test("bicycle type has a deterministic production route strategy", () => {
  assert.equal(vehicleConfig({ vehicle: { kind: "BICYCLE", bicycleType: "road" } }, "thrilling").routeType, "fastest");
  assert.equal(vehicleConfig({ vehicle: { kind: "BICYCLE", bicycleType: "city" } }, "thrilling").routeType, "fastest");
  assert.equal(vehicleConfig({ vehicle: { kind: "BICYCLE", bicycleType: "hybrid" } }, "thrilling").routeType, "shortest");
  assert.equal(vehicleConfig({ vehicle: { kind: "BICYCLE", bicycleType: "cross" } }, "thrilling").routeType, "shortest");
  assert.equal(vehicleConfig({ vehicle: { kind: "BICYCLE", bicycleType: "mountain" } }, "thrilling").routeType, "shortest");
});
