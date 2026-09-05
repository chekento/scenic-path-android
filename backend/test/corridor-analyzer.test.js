import test from "node:test";
import assert from "node:assert/strict";
import { analyzeCorridor, geometrySignals, samplePolyline } from "../corridor-analyzer.js";

const straight = [
  { lat: 53.60, lon: 10.00 },
  { lat: 53.60, lon: 10.20 },
  { lat: 53.60, lon: 10.40 },
];

const winding = [
  { lat: 53.60, lon: 10.00 },
  { lat: 53.62, lon: 10.05 },
  { lat: 53.58, lon: 10.10 },
  { lat: 53.63, lon: 10.15 },
  { lat: 53.59, lon: 10.20 },
];

test("winding geometry scores above a straight corridor", () => {
  const a = geometrySignals(straight);
  const b = geometrySignals(winding);
  assert.ok(b.windingness > a.windingness);
  assert.ok(b.beautifulRoads > a.beautifulRoads);
});

test("polyline sampling caps output and includes route endpoints", () => {
  const samples = samplePolyline(winding, { spacingMeters: 100, maxSamples: 8 });
  assert.ok(samples.length <= 8);
  assert.equal(samples[0].lat, winding[0].lat);
  assert.equal(samples.at(-1).lon, winding.at(-1).lon);
  assert.equal(samples.at(-1).routeProgress, 1);
});

test("corridor enrichment surfaces landscape and selected scene points", () => {
  const points = [
    { lat: 53.60, lon: 10.00 },
    { lat: 53.60, lon: 10.04 },
  ];
  const observations = [
    { id: "forest", point: { lat: 53.6005, lon: 10.01 }, signals: { forest: 1 } },
    { id: "lake", point: { lat: 53.6005, lon: 10.02 }, kind: "WATER", name: "Lake", relevance: 0.9, signals: { water: 1 } },
    { id: "museum", point: { lat: 53.6005, lon: 10.03 }, kind: "MUSEUM", name: "Museum", relevance: 0.9, signals: { museums: 1, culture: 0.8 } },
  ];

  const result = analyzeCorridor({ points, observations, enabledSceneKinds: ["WATER"] });
  assert.ok(result.factors.forest > 0);
  assert.ok(result.factors.water > 0);
  assert.equal(result.scenePoints.length, 1);
  assert.equal(result.scenePoints[0].kind, "WATER");
});
