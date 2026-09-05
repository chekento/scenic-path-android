import test from "node:test";
import assert from "node:assert/strict";
import { geometryOverlap, orderDiverseRoutes, routeDiversity } from "../route-diversity.js";

const primary = {
  id: "primary",
  points: [
    { lat: 53.60, lon: 10.00 },
    { lat: 53.61, lon: 10.05 },
    { lat: 53.62, lon: 10.10 },
  ],
  autoStopIds: ["castle", "cafe"],
  profileScore: 95,
  experienceScore: 95,
};

const nearCopy = {
  id: "near-copy",
  points: [
    { lat: 53.6005, lon: 10.0005 },
    { lat: 53.6105, lon: 10.0505 },
    { lat: 53.6205, lon: 10.1005 },
  ],
  autoStopIds: ["castle", "cafe"],
  profileScore: 93,
  experienceScore: 93,
};

const different = {
  id: "different",
  points: [
    { lat: 53.60, lon: 10.00 },
    { lat: 53.67, lon: 9.98 },
    { lat: 53.70, lon: 10.08 },
    { lat: 53.62, lon: 10.10 },
  ],
  autoStopIds: ["viewpoint", "museum"],
  profileScore: 82,
  experienceScore: 82,
};

test("near-copy geometry has much more overlap than a different corridor", () => {
  assert.ok(geometryOverlap(primary.points, nearCopy.points) > geometryOverlap(primary.points, different.points));
});

test("route diversity rewards different roads and different stops", () => {
  assert.ok(routeDiversity(primary, different) > routeDiversity(primary, nearCopy));
});

test("Alternative 2 picks the different corridor even when a near-copy scores higher", () => {
  const ordered = orderDiverseRoutes([primary, nearCopy, different], 2);
  assert.equal(ordered[0].id, "primary");
  assert.equal(ordered[1].id, "different");
  assert.match(ordered[1].variantLabel, /Alternative 2/);
});

test("plus route count is bounded and can expand beyond two", () => {
  const extra = { ...different, id: "extra", points: different.points.map(p => ({ lat: p.lat + 0.03, lon: p.lon + 0.03 })) };
  const ordered = orderDiverseRoutes([primary, nearCopy, different, extra], 4);
  assert.equal(ordered.length, 4);
});
