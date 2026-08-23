# Scenic Path Android

Native Android implementation of Scenic Path — a scenic-route planner that prioritizes beautiful, interesting and customizable journeys over simple fastest-path navigation.

## Current milestone

The active development branch is `feat/m0-foundation` and remains behind Draft PR #1.

### v0.5.4 reliability + popup route editing

- physical-device destination search no longer depends on the emulator-only `10.0.2.2` backend;
- Android Geocoder is tried first for cities/addresses in debug builds, with Photon/OSM as a landmark/POI fallback;
- calculated routes remain visible while replacement routes are built;
- Scenic POI markers are no longer cleared when a waypoint causes a new route geometry;
- the last valid clickable marker set stays on-screen until discovery for the replacement corridor returns useful data;
- rich map popups can add a Scenic POI as a locked route waypoint or remove it again;
- popup changes mark the route dirty and expose a direct `Recalculate route` action;
- official website, contact data, opening hours, OSM references and provider-backed ratings remain part of rich POI popups;
- ratings are never fabricated: verified values are shown only when a configured ratings provider supplies them.

### Scenic discovery

The current physical-device discovery stack combines category-first route-window lookup with deeper OSM/Overpass enrichment and balanced human-facing Scenic lanes. The long-route regression case is Current Location/Ahrensburg → Detmold.

Human-interest categories include restaurants/cafés, museums, viewpoints, castles and fortresses, palaces/manor houses, ruins/archaeology, monuments/memorials, art/galleries, worship, towers/architecture, bridges, attractions, parks, water, forests and natural landmarks.

## Development infrastructure

Public Photon, Overpass, OpenStreetMap and OpenFreeMap endpoints are used only as development/testing infrastructure. A production Play Store release should use controlled/self-hosted or contracted providers and follow each provider's attribution, caching, quota and branding requirements.

Google Places support belongs on the server side. API keys must never be embedded in the APK.

## Build

Android CI produces a debug APK artifact on pull-request changes.

```bash
./gradlew assembleDebug
```

## Backend

The optional Node backend supports production-oriented provider composition, search, routing and verified food/rating enrichment. See `backend/.env.example` for configuration placeholders.

## Status

Draft / unmerged. The project is under active iteration and is not yet a production release.
