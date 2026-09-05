import test from "node:test";
import assert from "node:assert/strict";
import {
  balancedPreferences,
  beautifulPreferences,
  executionPreferences,
} from "../route-preferences.js";

test("final execution preserves explicit route constraints", () => {
  const input = {
    maxExtraMinutes: 180,
    maxExtraPercent: 72,
    avoidMotorways: false,
    avoidTolls: true,
    windingness: 31,
    hilliness: 19,
  };
  assert.deepEqual(executionPreferences(input, "DAY_TRIP"), input);
});

test("day trip enforces only its structural minimum time budget", () => {
  const result = executionPreferences({
    maxExtraMinutes: 5,
    avoidMotorways: true,
    windingness: 22,
  }, "DAY_TRIP");
  assert.equal(result.maxExtraMinutes, 30);
  assert.equal(result.avoidMotorways, true);
  assert.equal(result.windingness, 22);
});

test("balanced candidate generation never disables an explicit motorway avoidance constraint", () => {
  const result = balancedPreferences({
    avoidMotorways: true,
    windingness: 95,
    hilliness: 90,
  });
  assert.equal(result.avoidMotorways, true);
  assert.equal(result.windingness, 55);
  assert.equal(result.hilliness, 45);
});

test("scenic candidate generation keeps an explicit motorway-allowed choice", () => {
  const result = beautifulPreferences({
    avoidMotorways: false,
    windingness: 40,
    hilliness: 30,
  });
  assert.equal(result.avoidMotorways, false);
  assert.equal(result.windingness, 65);
  assert.equal(result.hilliness, 45);
});
