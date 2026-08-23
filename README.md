# Scenic Path Android

Native Android implementation of **Scenic Path** — a map-first journey planner for intentionally beautiful routes, scenic categories and Smart Stops.

## Current development build: v0.5.7

### Durable Scenic marker population

The map keeps the full, category-balanced Scenic POI population visible while route geometry is recalculated. A sparse route response containing only one waypoint can no longer replace a rich corridor result with one or two markers. POI state is merged for the same start/destination journey and only reset when a genuinely different route arrives.

Fast corridor POIs are painted as soon as they are available while the deeper precision pass continues. Temporary empty route frames or provider failures do not clear an already useful map. Museums, restaurants, castles, viewpoints, art, architecture, nature, water and the other enabled Scenic categories therefore remain visible as in the established mixed-marker map.

### Hard route waypoints

User-added `mustVisit` waypoints remain hard routing breaks. Recalculated routes are stitched through those coordinates and validated against the resulting polyline. A route that bypasses a fixed waypoint is rejected.

### Waypoint marker design

A waypoint keeps its original Scenic category emoji and receives a luminous teal/green multi-ring frame instead of becoming a generic yellow circle. Removing the waypoint removes the emphasis while the location can remain on the map as a normal Scenic POI.

### Start and destination address search

Start and destination search supports towns, landmarks, streets and exact house numbers. Type-ahead combines the device geocoder with Photon/OpenStreetMap. Pressing Search additionally performs an explicit OpenStreetMap Nominatim address lookup.

### Map interaction

Scenic markers use the same category symbols as Smart Stops. Rich popups can expose official links, contact information, opening hours and provider-backed ratings where available. Suggested locations can be added to or removed from the route directly from the popup and the route can then be recalculated without discarding the current valid map state.

### Validation

Validated v0.5.7 APK source head: `e1e5298233e0e5d4245bc3a21174d2f0f6ba6098`.

- Android CI #214: passed
- Backend tests #214: passed
- POI provider smoke #40: passed
- direct APK SHA-256: `06c0b8a9877423b8af449cced971c50f5c6ea5c3c7abb751e1d145ace939c920`

## Development infrastructure

Public OpenStreetMap, Photon, Nominatim, Overpass and routing services are used only as development/test infrastructure. Production deployment should use controlled/self-hosted or contracted providers and comply with each provider's usage, attribution and branding requirements.
