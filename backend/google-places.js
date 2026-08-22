/**
 * Production food provider. Google Places data must be attributed according to
 * Google Maps Platform policy. Do not cache content beyond the allowed fields.
 */
function foodQualityScore(place) {
  const rating = Number(place.rating ?? 0);
  const reviews = Math.max(0, Number(place.userRatingCount ?? 0));
  // Bayesian shrinkage prevents a tiny-review 5.0 from automatically beating a
  // heavily reviewed 4.8 restaurant. The mild log review term rewards confidence.
  const priorMean = 4.25;
  const priorWeight = 180;
  const bayesian = (rating * reviews + priorMean * priorWeight) / Math.max(1, reviews + priorWeight);
  const confidence = Math.log10(reviews + 10) * 0.045;
  const restaurantBonus = place.primaryType === "restaurant" ? 0.035 : 0;
  return bayesian + confidence + restaurantBonus;
}

export async function searchTopFood({ apiKey, center, radiusMeters, minRating = 4.6, minReviews = 100, openNow = false }) {
  if (!apiKey) return [];
  const body = {
    includedTypes: ["restaurant", "cafe"],
    maxResultCount: 20,
    rankPreference: "POPULARITY",
    locationRestriction: { circle: { center: { latitude: center.lat, longitude: center.lon }, radius: radiusMeters } }
  };
  const res = await fetch("https://places.googleapis.com/v1/places:searchNearby", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": apiKey,
      "X-Goog-FieldMask": "places.id,places.displayName,places.location,places.primaryType,places.rating,places.userRatingCount,places.currentOpeningHours.openNow,places.googleMapsUri"
    },
    body: JSON.stringify(body)
  });
  if (!res.ok) throw new Error(`Google Places failed: ${res.status}`);
  const data = await res.json();
  return (data.places ?? [])
    .filter(p => (p.rating ?? 0) >= minRating)
    .filter(p => (p.userRatingCount ?? 0) >= minReviews)
    .filter(p => !openNow || p.currentOpeningHours?.openNow === true)
    .map(place => ({ ...place, scenicFoodScore: foodQualityScore(place) }))
    .sort((a, b) => b.scenicFoodScore - a.scenicFoodScore);
}
