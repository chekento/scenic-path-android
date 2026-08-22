import test from "node:test";
import assert from "node:assert/strict";
import { classifyTags } from "../scene-catalog.js";

test("generic attractions become Scenic Highlights instead of disappearing", () => {
  const result = classifyTags({ tourism: "attraction", name: "Old crane" });
  assert.equal(result.kind, "SCENIC");
  assert.equal(result.subtype, "attraction");
});

test("capes remain nature scene points", () => {
  const result = classifyTags({ natural: "cape", name: "Cape Example" });
  assert.equal(result.kind, "NATURE");
  assert.equal(result.subtype, "cape");
});

test("lighthouses map to architecture", () => {
  const result = classifyTags({ man_made: "lighthouse", name: "Light" });
  assert.equal(result.kind, "ARCHITECTURE");
  assert.equal(result.signals.architecture, 1);
});

test("historic worship requires historical context", () => {
  assert.equal(classifyTags({ amenity: "place_of_worship", name: "New church" }), null);
  const historic = classifyTags({ amenity: "place_of_worship", historic: "church", name: "Old church" });
  assert.equal(historic.kind, "WORSHIP");
});

test("unnamed forest contributes corridor signal without becoming a stop", () => {
  const result = classifyTags({ landuse: "forest" });
  assert.equal(result.kind, undefined);
  assert.equal(result.signals.forest, 1);
});

test("OSM restaurant is discovery only, never verified Top Food", () => {
  const result = classifyTags({ amenity: "restaurant", name: "Any Restaurant" });
  assert.equal(result.kind, undefined);
  assert.equal(result.signals.foodCandidate, 1);
});
