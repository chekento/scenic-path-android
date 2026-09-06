import test from "node:test";
import assert from "node:assert/strict";
import { rankRoutes, scenicScore, withinDetourBudget } from "../scenic-score.js";

const prefs = {
  weights: { beautifulRoads: .9, forest: .85, water: .9, mountains: .75, viewpoints: 1, culture: .8, museums: .65, architecture: .65, parks: .6, food: .35 },
  maxExtraMinutes: 45,
  maxExtraPercent: 40,
  avoidMotorways: true
};

test("ranks richer scenery above dull route", () => {
  const base = { fastestDurationSeconds: 3600, durationSeconds: 4200, motorwayShare: 0.02, industrialShare: 0.01 };
  const dull = { ...base, id: "dull", factors: { beautifulRoads:.3, forest:.1, water:.1, mountains:0, viewpoints:.1, culture:.1, museums:0, architecture:.1, parks:.1, food:.1 } };
  const scenic = { ...base, id: "scenic", factors: { beautifulRoads:.9, forest:.8, water:.9, mountains:.5, viewpoints:.8, culture:.7, museums:.4, architecture:.6, parks:.7, food:.4 } };
  assert.equal(rankRoutes([dull, scenic], prefs)[0].id, "scenic");
  assert.ok(scenicScore(scenic, prefs) > scenicScore(dull, prefs));
});

test("rejects excessive detour", () => {
  const c = { fastestDurationSeconds: 3600, durationSeconds: 7200 };
  assert.equal(withinDetourBudget(c, prefs), false);
});

test("Scenic DNA can reverse production preference between museum-rich and viewpoint-rich routes", () => {
  const base = {
    fastestDurationSeconds: 3600,
    durationSeconds: 3900,
    motorwayShare: 0,
    industrialShare: 0,
  };
  const museumRoute = { ...base, id: "museum", factors: { museums: 1, viewpoints: 0.1 } };
  const viewRoute = { ...base, id: "view", factors: { museums: 0.1, viewpoints: 1 } };

  const museumPrefs = {
    ...prefs,
    avoidMotorways: false,
    weights: { museums: 1, viewpoints: 0 },
  };
  const viewPrefs = {
    ...prefs,
    avoidMotorways: false,
    weights: { museums: 0, viewpoints: 1 },
  };

  assert.equal(rankRoutes([viewRoute, museumRoute], museumPrefs)[0].id, "museum");
  assert.equal(rankRoutes([museumRoute, viewRoute], viewPrefs)[0].id, "view");
});
