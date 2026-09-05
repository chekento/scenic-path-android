import test from "node:test";
import assert from "node:assert/strict";
import { orderRoutesForCharacter } from "../route-selection.js";

const preferences = { maxExtraPercent: 40 };
const routes = [
  { id: "direct", character: "DIRECT", durationSeconds: 3600, scenicScore: 45 },
  { id: "balanced", character: "BALANCED", durationSeconds: 4050, scenicScore: 76 },
  { id: "beautiful", character: "BEAUTIFUL", durationSeconds: 4380, scenicScore: 94 },
];

test("Direct recommends the fastest candidate", () => {
  const result = orderRoutesForCharacter(routes, "DIRECT", preferences, 3600);
  assert.equal(result[0].id, "direct");
  assert.equal(result[0].recommended, true);
});

test("Beautiful recommends the high-scenic candidate inside budget", () => {
  const result = orderRoutesForCharacter(routes, "BEAUTIFUL", preferences, 3600);
  assert.equal(result[0].id, "beautiful");
});

test("Balanced rewards beauty without ignoring time", () => {
  const extremeDetour = [
    { id: "fast", character: "DIRECT", durationSeconds: 3600, scenicScore: 52 },
    { id: "middle", character: "BALANCED", durationSeconds: 3960, scenicScore: 80 },
    { id: "slow", character: "BEAUTIFUL", durationSeconds: 4980, scenicScore: 96 },
  ];
  const result = orderRoutesForCharacter(extremeDetour, "BALANCED", preferences, 3600);
  assert.equal(result[0].id, "middle");
});
