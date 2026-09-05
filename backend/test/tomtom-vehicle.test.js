import test from "node:test";
import assert from "node:assert/strict";
import { routeConstraintConfig, vehicleConfig } from "../tomtom.js";

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

test("car motorway toll winding and hill controls become real TomTom parameters", () => {
  const config = routeConstraintConfig({
    vehicle: { kind: "CAR" },
    avoidMotorways: true,
    avoidTolls: true,
    windingness: 90,
    hilliness: 10,
  }, "thrilling");

  assert.deepEqual(config.avoid, ["motorways", "tollRoads"]);
  assert.equal(config.windingness, "high");
  assert.equal(config.hilliness, "low");
});

test("bicycle does not receive unsupported car-style motorway or thrilling controls", () => {
  const config = routeConstraintConfig({
    vehicle: { kind: "BICYCLE", bicycleType: "hybrid", allowUnpavedBikePaths: false },
    avoidMotorways: true,
    avoidTolls: true,
    windingness: 100,
    hilliness: 100,
  }, "thrilling");

  assert.deepEqual(config.avoid, ["unpavedRoads"]);
  assert.equal(config.windingness, null);
  assert.equal(config.hilliness, null);
  assert.equal(config.routeType, "shortest");
});
