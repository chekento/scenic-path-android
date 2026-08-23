# Scenic Path Android

Native Android implementation of **Scenic Path** — a map-first journey planner for intentionally beautiful routes, scenic categories and Smart Stops.

## Current development build: v0.5.8

### Reliable Scenic POI rendering

v0.5.8 removes the full Scenic marker population from MapLibre's deprecated legacy annotation lifecycle. The base map, current-position puck and route remain native MapLibre layers, while Scenic POIs are rendered as a Compose overlay projected through the live MapLibre camera.

This fixes the regression where the blue route remained visible but all clickable Scenic location markers disappeared. Markers now survive route replacement, candidate switching, Smart Stop changes and waypoint recalculation independently from `removeAnnotations()` / `addMarkers()` churn.

The marker overlay follows pan and zoom continuously and keeps the established white/green category-symbol design. Fixed route waypoints retain their original category symbol and receive a larger luminous frame instead of becoming a generic yellow circle.

### Restored multi-provider POI discovery

The map now races three independent discovery paths for every valid route:

- `RapidRoutePoiDiscovery` — bounded Overpass windows for fast human-interest coverage
- `FastRoutePoiDiscovery` — Photon/category-first route discovery
- `PrecisionRoutePoiDiscovery` — deeper balanced coverage for the complete Scenic taxonomy

Any successful path can populate the map immediately; later results are merged into the committed journey reservoir rather than replacing it. This restores the mixed marker landscape with museums, restaurants, castles, viewpoints, art, worship, architecture, nature, parks, water and the other enabled Scenic categories.

### Hard route waypoints

User-added `mustVisit` waypoints remain hard routing breaks. Recalculated routes are stitched through those coordinates and validated against the resulting polyline.

### Start and destination address search

Start and destination search supports towns, landmarks, streets and exact house numbers. Type-ahead combines the device geocoder with Photon/OpenStreetMap. Pressing Search additionally performs an explicit OpenStreetMap Nominatim address lookup.

### Rich POI interaction

Scenic markers use the same category symbols as Smart Stops. Popups can expose official links, contact information, opening hours and provider-backed ratings where available. Suggested locations can be added to or removed from the route directly from the popup and the route can then be recalculated.

### Validation

Validated v0.5.8 APK source head: `7707596c157a5392bada9b396362da042341e9d8`.

- versionCode: `28`
- Android CI #218: passed
- Backend tests #218: passed
- POI provider smoke #44: passed
- workflow artifact ZIP SHA-256: `ed85ceda7a9ee6d5cb3df28308044ce04be19c4bcf82dd2d6a1a72104155fe6f`
- direct APK SHA-256: `10a0fa73ffed1d4d22f3b0ccbb0709bba70d6b63c5814ee42a8f26530759ca1b`

Subsequent documentation-only commits do not alter the validated APK source.

## Development infrastructure

Public OpenStreetMap, Photon, Nominatim, Overpass and routing services are used only as development/test infrastructure. Production deployment should use controlled/self-hosted or contracted providers and comply with each provider's usage, attribution and branding requirements.
