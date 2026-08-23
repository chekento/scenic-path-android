# Scenic Path Android

Native Android implementation of **Scenic Path** — a map-first journey planner for intentionally beautiful routes, scenic categories and Smart Stops.

## Current development build: v0.5.5

### Marker reliability

The map treats its Scenic POI population as committed display state. A valid marker set is retained while a waypoint reroute or deeper corridor enrichment is running. A transient empty provider result can no longer remove all clickable icons. The shared POI bridge now recognizes equivalent routes by stable start/destination endpoints even when the replacement polyline geometry changes.

### Start and destination address search

Start and destination search supports towns, landmarks, streets and exact house numbers. Type-ahead combines the device geocoder with Photon/OpenStreetMap. Pressing Search additionally performs an explicit OpenStreetMap Nominatim address lookup, so inputs such as `Hamburger Straße 12, Ahrensburg` can resolve to an exact point instead of only the containing town.

### Map interaction

Scenic markers use the same category symbols as Smart Stops. Rich popups can expose official links, contact information, opening hours and provider-backed ratings where available. Suggested locations can be added to or removed from the route directly from the popup and the route can then be recalculated without intentionally discarding the current valid map state.

### Validation

The v0.5.5 APK is built from app-code head `1ed7ae17660b49f12c3b09731997122f166f78e5`. Android CI #201, Backend tests #201 and POI provider smoke #27 passed for that build. Later README-only commits do not alter the validated APK source.

## Development infrastructure

Public OpenStreetMap, Photon, Nominatim, Overpass and routing services are used only as development/test infrastructure. Production deployment should use controlled/self-hosted or contracted providers and comply with each provider's usage, attribution and branding requirements.
