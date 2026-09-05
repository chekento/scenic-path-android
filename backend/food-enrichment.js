import { samplePolyline, routeLengthMeters } from "./corridor-analyzer.js";
import { searchTopFood } from "./foursquare-places.js";

const clamp = (value, min = 0, max = 1) => Math.max(min, Math.min(max, value));

function relevanceFor(place, minRating, minRatings) {
  const rating = place.rating ?? 0;
  const ratings = place.ratingCount ?? 0;
  const ratingScore = rating > 0
    ? clamp((rating - Math.min(4.0, minRating)) / Math.max(0.2, 5 - Math.min(4.0, minRating)))
    : 0;
  const volumeScore = ratings > 0
    ? clamp(Math.log10(ratings + 1) / Math.log10(Math.max(100, minRatings * 20)))
    : 0;
  const providerScore = clamp((place.scenicFoodScore ?? 0) / 5);
  return clamp(0.48 + ratingScore * 0.22 + volumeScore * 0.10 + providerScore * 0.20);
}

/**
 * Turn route-adjacent Foursquare restaurant results into verified FOOD observations.
 * Search density scales with route length while remaining bounded for predictable latency/cost.
 */
export async function enrichTopFoodAlongRoute({
  points,
  apiKey,
  preferences,
  enabledSceneKinds = [],
}) {
  if (!apiKey || !enabledSceneKinds.includes("FOOD")) return [];

  const routeKm = Math.max(1, routeLengthMeters(points) / 1000);
  const maxSamples = routeKm > 220 ? 6 : routeKm > 120 ? 5 : routeKm > 55 ? 4 : 3;
  const spacingMeters = routeKm > 220 ? 42_000 : routeKm > 120 ? 34_000 : 24_000;
  const anchors = samplePolyline(points, { spacingMeters, maxSamples });
  const minRating = preferences.minimumFoodRating ?? 4.6;
  const minRatings = preferences.minimumFoodReviewCount ?? 100;
  const onlyOpen = preferences.onlyOpenFood === true;

  const batches = await Promise.all(
    anchors.map(center => searchTopFood({
      apiKey,
      center,
      radiusMeters: routeKm > 160 ? 12_000 : 9_000,
      minRating,
      minRatings,
      openNow: onlyOpen,
      limit: 20,
    }).catch(() => []))
  );

  const seen = new Set();
  const observations = [];
  for (const place of batches.flat()) {
    if (!place?.id || seen.has(place.id) || !place.location) continue;
    seen.add(place.id);
    const lat = place.location.latitude;
    const lon = place.location.longitude;
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) continue;
    observations.push({
      id: `foursquare:${place.id}`,
      point: { lat, lon },
      kind: "FOOD",
      subtype: "restaurant",
      name: place.name || "Top Food",
      relevance: relevanceFor(place, minRating, minRatings),
      signals: { food: 1 },
      rating: place.rating,
      ratingCount: place.ratingCount,
      foodScore: place.scenicFoodScore,
      popularity: place.popularity,
      openNow: place.openNow,
      url: place.website,
      providerUrl: place.providerUrl,
      attribution: "Powered by Foursquare",
    });
  }

  return observations
    .sort((a, b) => (b.foodScore ?? 0) - (a.foodScore ?? 0))
    .slice(0, 16);
}
