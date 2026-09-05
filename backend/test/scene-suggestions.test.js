import test from "node:test";
import assert from "node:assert/strict";
import { selectSceneSuggestions } from "../scene-suggestions.js";

const route = [
  { lat: 53.60, lon: 10.00 },
  { lat: 53.60, lon: 10.12 },
];

test("empty scene selection produces no suggestions", () => {
  const observations = [
    { id: "view", point: { lat: 53.6002, lon: 10.06 }, kind: "VIEWPOINT", name: "View", relevance: 0.8 },
    { id: "scenic", point: { lat: 53.6002, lon: 10.08 }, kind: "SCENIC", name: "Attraction", relevance: 0.9 },
  ];
  const result = selectSceneSuggestions({ points: route, observations, enabledSceneKinds: [], maxStops: 5 });
  assert.deepEqual(result, []);
});

test("disabled scene kinds never become suggestions", () => {
  const observations = [
    { id: "museum", point: { lat: 53.6002, lon: 10.03 }, kind: "MUSEUM", name: "Museum", relevance: 0.95 },
    { id: "view", point: { lat: 53.6002, lon: 10.06 }, kind: "VIEWPOINT", name: "View", relevance: 0.8 },
    { id: "scenic", point: { lat: 53.6002, lon: 10.08 }, kind: "SCENIC", name: "Attraction", relevance: 1.0 },
  ];
  const result = selectSceneSuggestions({
    points: route,
    observations,
    enabledSceneKinds: ["VIEWPOINT"],
    maxStops: 5,
  });
  assert.equal(result.length, 1);
  assert.equal(result[0].kind, "VIEWPOINT");
});

test("SCENIC suggestions require explicit SCENIC selection", () => {
  const observations = [
    { id: "scenic", point: { lat: 53.6002, lon: 10.05 }, kind: "SCENIC", name: "Attraction", relevance: 0.95 },
  ];
  assert.equal(selectSceneSuggestions({ points: route, observations, enabledSceneKinds: ["VIEWPOINT"] }).length, 0);
  assert.equal(selectSceneSuggestions({ points: route, observations, enabledSceneKinds: ["SCENIC"] }).length, 1);
});

test("near-duplicate highlights of the same kind are spatially decluttered", () => {
  const observations = [
    { id: "a", point: { lat: 53.6001, lon: 10.0300 }, kind: "VIEWPOINT", name: "A", relevance: 0.95 },
    { id: "b", point: { lat: 53.6001, lon: 10.0303 }, kind: "VIEWPOINT", name: "B", relevance: 0.90 },
    { id: "c", point: { lat: 53.6001, lon: 10.0800 }, kind: "VIEWPOINT", name: "C", relevance: 0.85 },
  ];
  const result = selectSceneSuggestions({
    points: route,
    observations,
    enabledSceneKinds: ["VIEWPOINT"],
    maxStops: 5,
  });
  assert.ok(result.some(item => item.id === "a"));
  assert.ok(!result.some(item => item.id === "b"));
  assert.ok(result.some(item => item.id === "c"));
});

test("verified food suggestion carries useful dwell metadata", () => {
  const observations = [
    {
      id: "food",
      point: { lat: 53.6001, lon: 10.05 },
      kind: "FOOD",
      name: "Excellent Cafe",
      relevance: 0.96,
      rating: 4.8,
      ratingCount: 800,
    },
  ];
  const result = selectSceneSuggestions({
    points: route,
    observations,
    enabledSceneKinds: ["FOOD"],
    maxStops: 3,
  });
  assert.equal(result[0].suggestedDwellMinutes, 45);
  assert.equal(result[0].rating, 4.8);
  assert.equal(result[0].ratingCount, 800);
});