import { samplePolyline, routeLengthMeters } from "./corridor-analyzer.js";
import { searchTopFood } from "./google-places.js";

const clamp = (value, min = 0, max = 1) => Math.max(min, Math.min(max, value));

function relevanceFor(place, minRating, minReviews) {
  const rating = place.rating ?? 0;
  const reviews = place.userRatingCount ?? 0;
  const ratingScore = clamp((rating - minRating) / Math.max(0.1, 5 - minRating));
  const reviewScore = clamp(Math.log10(Math.max(1, reviews)) / Math.log10(Math.max(10, minReviews * 20)));
  const providerScore = clamp(((place.scenicFoodScore ?? rating) - 4.2) / 0.9);
  return clamp(0.52 + ratingScore * 0.22 + reviewScore * 0.12 + providerScore * 0.14);
}

/**
 * Turn route-adjacent restaurant/cafe search results into verified FOOD observations.
 * Search density scales with route length so a 200+ km journey is not represented by
 * only four restaurant search circles.
 */
export async function enrichTopFoodAlongRoute({
  points,
  apiKey,
  preferences,
  enabledSceneKinds = [],
}) {
  if (!apiKey || !enabledSceneKinds.includes("FOOD")) return [];

  const routeKm = Math.max(1, routeLengthMeters(points) / 1000);
  const maxSamples = routeKm > 180 ? 8 : routeKm > 100 ? 6 : routeKm > 45 ? 5 : 4;
  const spacingMeters = routeKm > 180 ? 32_000 : routeKm > 100 ? 26_000 : 20_000;
  const anchors = samplePolyline(points, { spacingMeters, maxSamples });
  const minRating = preferences.minimumFoodRating ?? 4.6;
  const minReviews = preferences.minimumFoodReviewCount ?? 100;
  const onlyOpen = preferences.onlyOpenFood === true;

  const batches = await Promise.all(
    anchors.map(center => searchTopFood({
      apiKey,
      center,
      radiusMeters: 7_500,
      minRating,
      minReviews,
      openNow: onlyOpen,
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
      id: `google:${place.id}`,
      point: { lat, lon },
      kind: "FOOD",
      subtype: place.primaryType === "cafe" ? "cafe" : "restaurant",
      name: place.displayName?.text || "Top food",
      relevance: relevanceFor(place, minRating, minReviews),
      signals: { food: 1 },
      rating: place.rating,
      ratingCount: place.userRatingCount,
      foodScore: place.scenicFoodScore,
      openNow: place.currentOpeningHours?.openNow,
      url: place.googleMapsUri,
      attribution: "Google",
    });
  }

  return observations
    .sort((a, b) => (b.foodScore ?? 0) - (a.foodScore ?? 0))
    .slice(0, 16);
}
