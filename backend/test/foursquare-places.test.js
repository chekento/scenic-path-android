import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import {
  foursquareAttribution,
  foursquareFoodScore,
  matchesFoodThresholds,
  normalizeFoursquarePlace,
  toAppRating,
} from "../foursquare-places.js";

const here = dirname(fileURLToPath(import.meta.url));
const backend = join(here, "..");

test("Foursquare 0-10 ratings normalize to Scenic Path 0-5 ratings", () => {
  assert.equal(toAppRating(9.4), 4.7);
  assert.equal(toAppRating(10), 5);
  assert.equal(toAppRating(null), null);
});

test("Top Food score rewards sustained quality and popularity", () => {
  const strong = foursquareFoodScore({ rating: 9.2, popularity: 0.86, stats: { total_ratings: 850 } });
  const sparse = foursquareFoodScore({ rating: 9.3, popularity: 0.18, stats: { total_ratings: 3 } });
  assert.ok(strong > sparse);
});

test("Foursquare place normalization preserves rating count and visible attribution", () => {
  const place = normalizeFoursquarePlace({
    fsq_place_id: "abc123",
    name: "Route Restaurant",
    latitude: 53.6,
    longitude: 10.1,
    rating: 9.0,
    popularity: 0.75,
    stats: { total_ratings: 240 },
    website: "https://example.test",
    hours: { open_now: true, display: "Open until 22:00" },
    location: { address: "Main Street 1", locality: "Example" },
  });
  assert.equal(place.rating, 4.5);
  assert.equal(place.ratingCount, 240);
  assert.equal(place.attribution, "Powered by Foursquare");
  assert.equal(place.openNow, true);
  assert.match(place.address, /Main Street 1/);
  assert.equal(foursquareAttribution(), "Powered by Foursquare");
});

test("Top Food minimum rating and review count are hard constraints", () => {
  const strong = { rating: 4.8, ratingCount: 850, openNow: true };
  const lowRating = { rating: 4.5, ratingCount: 2000, openNow: true };
  const tooFewReviews = { rating: 4.9, ratingCount: 25, openNow: true };
  const unknownReviews = { rating: 4.9, ratingCount: null, openNow: true };

  const settings = { minRating: 4.6, minRatings: 100, openNow: false };
  assert.equal(matchesFoodThresholds(strong, settings), true);
  assert.equal(matchesFoodThresholds(lowRating, settings), false);
  assert.equal(matchesFoodThresholds(tooFewReviews, settings), false);
  assert.equal(matchesFoodThresholds(unknownReviews, settings), false);
});

test("Top Food open-now constraint excludes closed and unknown opening state", () => {
  const settings = { minRating: 4.6, minRatings: 100, openNow: true };
  assert.equal(matchesFoodThresholds({ rating: 4.8, ratingCount: 500, openNow: true }, settings), true);
  assert.equal(matchesFoodThresholds({ rating: 4.8, ratingCount: 500, openNow: false }, settings), false);
  assert.equal(matchesFoodThresholds({ rating: 4.8, ratingCount: 500, openNow: null }, settings), false);
});

test("active production backend contains no Google Places provider path", () => {
  const server = readFileSync(join(backend, "server.js"), "utf8");
  const food = readFileSync(join(backend, "food-enrichment.js"), "utf8");
  const env = readFileSync(join(backend, ".env.example"), "utf8");
  assert.equal(existsSync(join(backend, "google-places.js")), false);
  assert.doesNotMatch(server, /GOOGLE_PLACES_API_KEY|google-places\.js|places\.googleapis\.com/);
  assert.doesNotMatch(food, /GOOGLE_PLACES_API_KEY|google-places\.js|places\.googleapis\.com/);
  assert.doesNotMatch(env, /GOOGLE_PLACES_API_KEY/);
  assert.match(server, /FOURSQUARE_SERVICE_KEY/);
  assert.match(food, /foursquare-places\.js/);
});