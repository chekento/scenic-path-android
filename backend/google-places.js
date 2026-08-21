/**
 * Production food provider. Google Places data must be attributed according to
 * Google Maps Platform policy. Do not cache content beyond the allowed fields.
 */
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
      "X-Goog-FieldMask": "places.id,places.displayName,places.location,places.rating,places.userRatingCount,places.currentOpeningHours.openNow,places.googleMapsUri"
    },
    body: JSON.stringify(body)
  });
  if (!res.ok) throw new Error(`Google Places failed: ${res.status}`);
  const data = await res.json();
  return (data.places ?? [])
    .filter(p => (p.rating ?? 0) >= minRating)
    .filter(p => (p.userRatingCount ?? 0) >= minReviews)
    .filter(p => !openNow || p.currentOpeningHours?.openNow === true)
    .sort((a, b) => (b.rating - a.rating) || (b.userRatingCount - a.userRatingCount));
}
