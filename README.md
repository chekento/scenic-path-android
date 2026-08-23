# Scenic Path Android

Native Android implementation of **Scenic Path** — a map-first scenic-route planner that optimizes for beautiful, interesting and customizable journeys instead of only the fastest path.

The active development branch is `feat/m0-foundation`; work is kept behind Draft PR #1 until the native experience is ready to merge.

## v0.5.4 — destination-search recovery, persistent POIs and popup route editing

The physical-device regression route is **Current Location / Ahrensburg → Detmold**. v0.5.4 addresses two issues exposed during that test:

- Destination entry in debug builds no longer waits on the emulator-only `10.0.2.2` backend. Android Geocoder is used first for ordinary cities/addresses, with Photon/OpenStreetMap as an independent landmark/POI fallback.
- Scenic POI markers are no longer reset to an empty list when adding a waypoint produces a replacement route. The last valid clickable POI population stays visible while the replacement corridor is enriched, and is replaced only when the new discovery produces useful results.

Rich marker popups can now:

- add a suggested Scenic location as a locked route waypoint;
- remove a previously selected waypoint;
- mark the current route as changed;
- recalculate the route directly from the popup while the previous valid route remains visible until the replacement succeeds;
- show mapped official website/contact links, address, phone, email, opening hours, OSM/reference links and provider-backed ratings when available.

Ratings are never fabricated. OpenStreetMap does not provide a general user-star-rating system; verified scores appear only when supplied by a configured rating provider, otherwise the popup links to live ratings.

## Scenic discovery stack

The current native discovery pipeline combines fast and deep route-corridor searches so long journeys do not collapse into only parks, mountains and water. Human-facing Scenic lanes include restaurants/cafés, museums, viewpoints, castles/fortresses, palaces/manor houses, ruins/archaeology, monuments/memorials, art/galleries, worship, towers/architecture, bridges, attractions, parks, water, forests and natural landmarks.

Smart Stops and the map share discovered POIs, use category balancing and deduplication, and preserve the last valid route while settings or stops are edited.

## Planner

Current planner capabilities include:

- Quick route / Day trip / Road trip
- Beautiful / Balanced / Direct / Custom route character
- configurable extra-time budget
- Smart Stops
- broad Scenic category filters
- Scenic DNA weighting for roads, views, water, forest, relief, culture, monuments, museums, art, worship, parks, architecture, food and attractions
- manual locked waypoints
- motorway/toll preferences
- route persistence during replacement calculations

## Development infrastructure

Public Photon, Overpass, OpenStreetMap and OpenFreeMap endpoints are development/testing infrastructure. A production Play Store release should use controlled/self-hosted or contracted providers and follow each provider's attribution, caching, quota and branding requirements.

Google Places / verified rating integrations belong server-side. Provider API keys must never be embedded in the APK.

## Build

Android CI produces a debug APK artifact on pull-request changes.

```bash
./gradlew assembleDebug
```

## Backend

The optional Node backend supports production-oriented provider composition, search, routing and verified food/rating enrichment. See `backend/.env.example` for configuration placeholders.

## Status

Draft / unmerged. Scenic Path is under active iteration and is not yet a production release.
