const API_BASE = "https://places-api.foursquare.com";
const API_VERSION = "2025-06-17";
const ATTRIBUTION = "Powered by Foursquare";

const finite = value => {
  if (value === null || value === undefined || value === "") return null;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
};
const clamp = (value, min = 0, max = 1) => Math.max(min, Math.min(max, value));

export function foursquareAttribution() {
  return ATTRIBUTION;
}

export function toAppRating(rating10) {
  const rating = finite(rating10);
  return rating == null ? null : clamp(rating / 10, 0, 1) * 5;
}

export function foursquareFoodScore(place) {
  const rating5 = toAppRating(place?.rating);
  const popularity = clamp(finite(place?.popularity) ?? 0);
  const totalRatings = Math.max(0, finite(place?.stats?.total_ratings) ?? 0);
  const confidence = clamp(Math.log10(totalRatings + 1) / 4);

  // Rating is the primary quality signal. Popularity and rating-volume stabilize the pick
  // so a scarcely-rated place does not automatically outrank a consistently strong venue.
  if (rating5 == null) return popularity * 3.6 + confidence * 0.6;
  return rating5 * 0.76 + popularity * 5 * 0.16 + confidence * 5 * 0.08;
}

function placeArray(payload) {
  if (Array.isArray(payload?.results)) return payload.results;
  if (Array.isArray(payload?.places)) return payload.places;
  return [];
}

function locationText(location) {
  if (!location || typeof location !== "object") return null;
  const parts = [
    location.address,
    location.postcode,
    location.locality,
    location.region,
    location.country,
  ].map(value => String(value ?? "").trim()).filter(Boolean);
  return [...new Set(parts)].join(", ") || null;
}

function openingText(hours) {
  if (!hours || typeof hours !== "object") return null;
  if (typeof hours.display === "string" && hours.display.trim()) return hours.display.trim();
  if (Array.isArray(hours.display)) return hours.display.filter(Boolean).join(" · ") || null;
  return null;
}

export function normalizeFoursquarePlace(place) {
  if (!place || typeof place !== "object") return null;
  const id = String(place.fsq_place_id ?? place.fsq_id ?? "").trim();
  const latitude = finite(place.latitude);
  const longitude = finite(place.longitude);
  if (!id || latitude == null || longitude == null) return null;

  const rating = toAppRating(place.rating);
  const ratingCount = Math.max(0, finite(place.stats?.total_ratings) ?? 0) || null;
  const popularity = clamp(finite(place.popularity) ?? 0);
  const categoryNames = Array.isArray(place.categories)
    ? place.categories.map(category => String(category?.name ?? "").trim()).filter(Boolean)
    : [];

  return {
    id,
    name: String(place.name ?? "Top Food").trim() || "Top Food",
    location: { latitude, longitude },
    rating,
    ratingCount,
    popularity,
    scenicFoodScore: foursquareFoodScore(place),
    website: typeof place.website === "string" && place.website.trim() ? place.website.trim() : null,
    phone: typeof place.tel === "string" && place.tel.trim() ? place.tel.trim() : null,
    address: locationText(place.location),
    openNow: typeof place.hours?.open_now === "boolean" ? place.hours.open_now : null,
    openingHours: openingText(place.hours),
    providerUrl: typeof place.link === "string" && place.link.trim() ? place.link.trim() : null,
    categoryNames,
    attribution: ATTRIBUTION,
  };
}

async function requestPlaces({ apiKey, params }) {
  if (!apiKey) return [];
  const url = new URL(`${API_BASE}/places/search`);
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") url.searchParams.set(key, String(value));
  });

  const response = await fetch(url, {
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${apiKey}`,
      "X-Places-Api-Version": API_VERSION,
    },
  });
  if (!response.ok) throw new Error(`Foursquare Places failed: ${response.status}`);
  return placeArray(await response.json()).map(normalizeFoursquarePlace).filter(Boolean);
}

export async function searchTopFood({
  apiKey,
  center,
  radiusMeters,
  minRating = 4.6,
  minRatings = 100,
  openNow = false,
  limit = 20,
}) {
  if (!apiKey || !Number.isFinite(center?.lat) || !Number.isFinite(center?.lon)) return [];
  const places = await requestPlaces({
    apiKey,
    params: {
      query: "restaurant",
      ll: `${center.lat},${center.lon}`,
      radius: Math.round(Math.max(500, Math.min(100_000, radiusMeters ?? 8_000))),
      sort: "RATING",
      limit: Math.max(1, Math.min(50, limit)),
      open_now: openNow ? "true" : undefined,
      fields: "fsq_place_id,name,categories,location,latitude,longitude,distance,tel,email,website,link,hours,popularity,rating,stats,price",
    },
  });

  const thresholdMatches = places.filter(place =>
    place.rating != null &&
    place.rating >= minRating &&
    (place.ratingCount == null || place.ratingCount >= minRatings)
  );
  const pool = thresholdMatches.length ? thresholdMatches : places;
  return pool.sort((a, b) => b.scenicFoodScore - a.scenicFoodScore);
}

function distanceMeters(a, b) {
  const earth = 6_371_000;
  const dLat = (b.lat - a.lat) * Math.PI / 180;
  const dLon = (b.lon - a.lon) * Math.PI / 180;
  const lat1 = a.lat * Math.PI / 180;
  const lat2 = b.lat * Math.PI / 180;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * earth * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
}

export async function findPlaceDetails({ apiKey, name, center }) {
  if (!apiKey || !name || !Number.isFinite(center?.lat) || !Number.isFinite(center?.lon)) return null;
  const places = await requestPlaces({
    apiKey,
    params: {
      query: name,
      ll: `${center.lat},${center.lon}`,
      radius: 5_000,
      sort: "RELEVANCE",
      limit: 5,
      fields: "fsq_place_id,name,categories,location,latitude,longitude,distance,tel,email,website,link,hours,popularity,rating,stats,price",
    },
  });

  const ranked = places
    .map(place => ({
      place,
      distance: distanceMeters(center, { lat: place.location.latitude, lon: place.location.longitude }),
    }))
    .sort((a, b) => a.distance - b.distance);
  const best = ranked[0];
  if (!best || best.distance > 6_500) return null;

  const place = best.place;
  return {
    providerPlaceId: place.id,
    matchedName: place.name,
    rating: place.rating,
    ratingCount: place.ratingCount,
    ratingSource: "Foursquare",
    providerAttribution: ATTRIBUTION,
    providerUrl: place.providerUrl,
    website: place.website,
    phone: place.phone,
    address: place.address,
    openNow: place.openNow,
    openingHours: place.openingHours,
  };
}
