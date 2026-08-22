import { samplePolyline } from "./corridor-analyzer.js";
import { searchTopFood } from "./google-places.js";

const clamp = (value, min = 0, max = 1) => Math.max(min, Math.min(max, value));

function relevanceFor(place, minRating, minReviews) {
  const rating = place.rating ?? 0;
  const reviews = place.userRatingCount ?? 0;
  const ratingScore = clamp((rating - minRating) / Math.max(0.1, 5 - minRating));
  const reviewScore = clamp(Math.log10(Math.max(1, reviews)) / Math.log10(Math.max(10, minReviews * 20)));
  return clamp(0.58 + ratingScore * 0.26 + reviewScore * 0.16);
}

/**
 * Turn route-adjacent restaurant/cafe search results into verified FOOD observations.
 * The expensive provider call is intentionally sparse: at most four route anchors.
 */
export async function enrichTopFoodAlongRoute({
  points,
  apiKey,
  preferences,
  enabledSceneKinds = [],
}) {
  if (!apiKey || !enabledSceneKinds.includes("FOOD")) return [];

  const anchors = samplePolyline(points, { spacingMeters: 20_000, maxSamples: 4 });
  const minRating = preferences.minimumFoodRating ?? 4.6;
  const minReviews = preferences.minimumFoodReviewCount ?? 100;
  const onlyOpen = preferences.onlyOpenFood === true;

  const batches = await Promise.all(
    anchors.map(center => searchTopFood({
      apiKey,
      center,
      radiusMeters: 5_000,
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
      subtype: "top_food",
      name: place.displayName?.text || "Top food",
      relevance: relevanceFor(place, minRating, minReviews),
      signals: { food: 1 },
      rating: place.rating,
      ratingCount: place.userRatingCount,
      openNow: place.currentOpeningHours?.openNow,
      url: place.googleMapsUri,
      attribution: "Google",
    });
  }

  return observations
    .sort((a, b) => (b.rating - a.rating) || (b.ratingCount - a.ratingCount))
    .slice(0, 12);
}
