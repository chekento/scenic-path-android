import test from "node:test";
import assert from "node:assert/strict";
import { autoStopDistanceLimitMeters } from "../auto-stop-planner.js";
import { lateralSearchOffsetKm } from "../osm-enrichment.js";
import { sceneSearchDistanceLimitMeters } from "../scene-suggestions.js";

test("larger time budgets expand lateral discovery space", () => {
  assert.equal(lateralSearchOffsetKm(30), 0);
  assert.equal(lateralSearchOffsetKm(60), 4);
  assert.equal(lateralSearchOffsetKm(120), 7);
  assert.equal(lateralSearchOffsetKm(180), 11);
  assert.equal(lateralSearchOffsetKm(240), 16);
  assert.equal(lateralSearchOffsetKm(360), 20);
});

test("larger time budgets allow farther suggestions", () => {
  assert.equal(sceneSearchDistanceLimitMeters(30), 2_800);
  assert.equal(sceneSearchDistanceLimitMeters(60), 6_500);
  assert.equal(sceneSearchDistanceLimitMeters(120), 10_000);
  assert.equal(sceneSearchDistanceLimitMeters(180), 15_000);
  assert.equal(sceneSearchDistanceLimitMeters(240), 21_000);
  assert.equal(sceneSearchDistanceLimitMeters(360), 26_000);
});

test("larger time budgets allow much farther POIs to be trial-routed as automatic stops", () => {
  assert.equal(autoStopDistanceLimitMeters(30), 6_000);
  assert.equal(autoStopDistanceLimitMeters(60), 12_000);
  assert.equal(autoStopDistanceLimitMeters(120), 20_000);
  assert.equal(autoStopDistanceLimitMeters(180), 30_000);
  assert.equal(autoStopDistanceLimitMeters(240), 42_000);
  assert.equal(autoStopDistanceLimitMeters(360), 58_000);
  assert.ok(autoStopDistanceLimitMeters(360) > autoStopDistanceLimitMeters(60) * 4);
});
