import test from "node:test";
import assert from "node:assert/strict";
import {
  automaticStopLimit,
  autoStopDistanceLimitMeters,
  chooseInitialAutoStops,
  stopUtility,
} from "../auto-stop-planner.js";

const preferences = {
  maxExtraMinutes: 120,
  maxStops: 5,
  weights: {
    viewpoints: 1,
    museums: 0.65,
    forest: 0.85,
    mountains: 0.75,
    monuments: 0.78,
    culture: 0.8,
    parks: 0.6,
    art: 0.58,
    worship: 0.48,
    water: 0.9,
    food: 0.35,
    architecture: 0.65,
    scenicHighlights: 0.7,
  },
};

function poi(overrides = {}) {
  return {
    id: overrides.id ?? "poi",
    name: overrides.name ?? "POI",
    kind: overrides.kind ?? "SCENIC",
    subtype: overrides.subtype ?? "scenic",
    point: overrides.point ?? { lat: 53.6, lon: 10.0 },
    relevance: overrides.relevance ?? 0.8,
    suggestionScore: overrides.suggestionScore ?? 0.8,
    distanceFromRouteMeters: overrides.distanceFromRouteMeters ?? 800,
    suggestedDwellMinutes: overrides.suggestedDwellMinutes ?? 20,
    rating: overrides.rating,
    ratingCount: overrides.ratingCount,
  };
}

test("automatic stop limit grows materially with available exploration time", () => {
  assert.equal(automaticStopLimit(0, 8), 0);
  assert.equal(automaticStopLimit(29, 8), 0);
  assert.equal(automaticStopLimit(30, 8), 1);
  assert.equal(automaticStopLimit(60, 8), 2);
  assert.equal(automaticStopLimit(100, 8), 3);
  assert.equal(automaticStopLimit(150, 8), 4);
  assert.equal(automaticStopLimit(210, 8), 5);
  assert.equal(automaticStopLimit(300, 8), 6);
  assert.equal(automaticStopLimit(360, 2), 2);
});

test("large budgets widen the production POI search envelope", () => {
  assert.ok(autoStopDistanceLimitMeters(360) > autoStopDistanceLimitMeters(45) * 3);
});

test("empty enabled category set means no automatic stops", () => {
  const selected = chooseInitialAutoStops(
    [poi({ id: "view", kind: "VIEWPOINT" }), poi({ id: "scenic", kind: "SCENIC" })],
    preferences,
    [],
  );
  assert.deepEqual(selected, []);
});

test("SCENIC candidates are excluded unless SCENIC is explicitly enabled", () => {
  const scenic = poi({ id: "scenic", kind: "SCENIC", relevance: 1.0 });
  const view = poi({ id: "view", kind: "VIEWPOINT", relevance: 0.8 });
  const selected = chooseInitialAutoStops([scenic, view], preferences, ["VIEWPOINT"]);
  assert.ok(selected.every(item => item.kind !== "SCENIC"));
});

test("Top Food receives a reserved slot when enabled and budget is sufficient", () => {
  const food = poi({
    id: "food",
    kind: "FOOD",
    subtype: "restaurant",
    relevance: 0.78,
    rating: 4.8,
    ratingCount: 1800,
  });
  const castle = poi({ id: "castle", kind: "MONUMENT", subtype: "castle", relevance: 1.0 });
  const viewpoint = poi({ id: "view", kind: "VIEWPOINT", subtype: "viewpoint", relevance: 1.0 });

  const selected = chooseInitialAutoStops(
    [castle, viewpoint, food],
    preferences,
    ["FOOD", "MONUMENT", "VIEWPOINT"],
  );

  assert.equal(selected.length, 3);
  assert.ok(selected.some(item => item.id === "food"));
});

test("Food is not forced when FOOD category is disabled", () => {
  const food = poi({ id: "food", kind: "FOOD", subtype: "restaurant", rating: 4.9, ratingCount: 5000 });
  const castle = poi({ id: "castle", kind: "MONUMENT", subtype: "castle", relevance: 1.0 });

  const selected = chooseInitialAutoStops(
    [food, castle],
    preferences,
    ["MONUMENT"],
  );

  assert.ok(selected.every(item => item.kind !== "FOOD"));
});

test("heritage bonus makes a castle more useful than an otherwise similar generic scenic POI", () => {
  const castle = poi({ id: "castle", kind: "MONUMENT", subtype: "castle", relevance: 0.8 });
  const generic = poi({ id: "generic", kind: "SCENIC", subtype: "scenic", relevance: 0.8 });

  assert.ok(stopUtility(castle, preferences) > stopUtility(generic, preferences));
});

test("category diversity is preferred before repeating the same kind", () => {
  const nature1 = poi({ id: "n1", kind: "NATURE", subtype: "peak", relevance: 1.0 });
  const nature2 = poi({ id: "n2", kind: "NATURE", subtype: "peak", relevance: 0.99 });
  const water = poi({ id: "water", kind: "WATER", subtype: "lake", relevance: 0.78 });

  const selected = chooseInitialAutoStops(
    [nature1, nature2, water],
    preferences,
    ["NATURE", "WATER"],
  );

  assert.ok(selected.length >= 2);
  assert.ok(selected.some(item => item.kind === "NATURE"));
  assert.ok(selected.some(item => item.kind === "WATER"));
});