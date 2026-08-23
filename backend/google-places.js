/**
 * Production place provider. Google Places data must be attributed according to
 * Google Maps Platform policy. Do not cache content beyond the allowed fields.
 */
function foodQualityScore(place) {
  const rating = Number(place.rating ?? 0);
  const reviews = Math.max(0, Number(place.userRatingCount ?? 0));
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

function distanceMeters(a, b) {
  const earth = 6371000;
  const dLat = (b.lat - a.lat) * Math.PI / 180;
  const dLon = (b.lon - a.lon) * Math.PI / 180;
  const lat1 = a.lat * Math.PI / 180;
  const lat2 = b.lat * Math.PI / 180;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * earth * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
}

/**
 * Resolve one selected Scenic marker to provider-backed popup details.
 * The API key stays server-side. Location bias plus a strict nearest-place sanity check keeps
 * same-name venues in other cities from contaminating the popup.
 */
export async function findPlaceDetails({ apiKey, name, center }) {
  if (!apiKey || !name || !Number.isFinite(center?.lat) || !Number.isFinite(center?.lon)) return null;

  const body = {
    textQuery: name,
    languageCode: "de",
    maxResultCount: 5,
    locationBias: {
      circle: {
        center: { latitude: center.lat, longitude: center.lon },
        radius: 5000
      }
    }
  };
  const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": apiKey,
      "X-Goog-FieldMask": [
        "places.id",
        "places.displayName",
        "places.location",
        "places.rating",
        "places.userRatingCount",
        "places.googleMapsUri",
        "places.websiteUri",
        "places.nationalPhoneNumber",
        "places.internationalPhoneNumber",
        "places.formattedAddress",
        "places.currentOpeningHours.openNow",
        "places.currentOpeningHours.weekdayDescriptions",
        "places.regularOpeningHours.weekdayDescriptions"
      ].join(",")
    },
    body: JSON.stringify(body)
  });
  if (!res.ok) throw new Error(`Google Places details failed: ${res.status}`);
  const data = await res.json();
  const ranked = (data.places ?? [])
    .filter(place => Number.isFinite(place.location?.latitude) && Number.isFinite(place.location?.longitude))
    .map(place => ({
      place,
      distance: distanceMeters(center, { lat: place.location.latitude, lon: place.location.longitude })
    }))
    .sort((a, b) => a.distance - b.distance);

  const best = ranked[0];
  if (!best || best.distance > 6500) return null;
  const place = best.place;
  const opening = place.currentOpeningHours?.weekdayDescriptions ?? place.regularOpeningHours?.weekdayDescriptions ?? [];

  return {
    providerPlaceId: place.id,
    matchedName: place.displayName?.text,
    rating: Number.isFinite(place.rating) ? place.rating : null,
    ratingCount: Number.isFinite(place.userRatingCount) ? place.userRatingCount : null,
    ratingSource: "Google Places",
    googleMapsUrl: place.googleMapsUri ?? null,
    website: place.websiteUri ?? null,
    phone: place.internationalPhoneNumber ?? place.nationalPhoneNumber ?? null,
    address: place.formattedAddress ?? null,
    openNow: typeof place.currentOpeningHours?.openNow === "boolean" ? place.currentOpeningHours.openNow : null,
    openingHours: opening.length ? opening.join(" · ") : null
  };
}
