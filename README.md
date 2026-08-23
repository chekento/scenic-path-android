# Scenic Path Android

Native Android implementation of **Scenic Path** — a map-first journey planner for intentionally beautiful routes, scenic categories, Smart Stops and live guidance.

## Current development build: v0.5.9

### Forward-flow waypoint routing

v0.5.9 fixes the multi-hour backtracking/loop regression that could appear after adding a Scenic POI as a fixed waypoint.

The waypoint planner now establishes a clean roads-only A→B baseline first. When flexible stop order is enabled, fixed POIs are ordered by their natural progress along that corridor rather than by the order in which the user happened to tap them. The actual travel detour required to visit those POIs is measured against the original A→B baseline and charged to the same global exploration-time budget as dwell time and optional scenic-road upgrades.

Scenic route variants are no longer chosen independently for every leg. The direct route through all mandatory POIs is the guaranteed base journey; individual scenic leg upgrades compete by experience gain per extra minute and are accepted only while the remaining global budget fits. A final guard prevents a looping provider candidate from being published as Best match when it exceeds that budget.

A user-selected `mustVisit` POI remains a hard routing break and is validated against the resulting route geometry. If a fixed POI itself requires more time than the selected budget, Scenic Path keeps the POI mandatory but does not add further scenic loops and reports the budget conflict.

### Live Navigation — first native driver mode

The route map now has a **Navigate** action and a driver-focused live navigation HUD powered by the phone's GPS updates.

Current navigation features include live route progress, remaining distance, ETA, current speed, route/GPS heading, a tilted follow camera, route overview, off-route detection, reroute action, next fixed Scenic POI, arrival detection and Android TTS alerts for approaching POIs/off-route/arrival. The complete clickable Scenic POI overlay remains visible during navigation.

The current route data model does not yet contain provider maneuver instructions, so v0.5.9 deliberately does not invent street-name/turn commands. Valhalla maneuver decoding, lane/roundabout instructions, maneuver arrows, automatic rerouting thresholds, background navigation/service behavior and navigation-specific POI arrival handling are the next navigation layer.

### Reliable Scenic POI rendering

The v0.5.8 Compose POI overlay remains in place. The base map, current-position indicator and route are native MapLibre layers, while Scenic POIs are projected as a durable Compose overlay through the live MapLibre camera. Route replacement, Smart Stop changes and waypoint recalculation therefore do not depend on the legacy annotation lifecycle.

The map continues to combine Rapid Overpass, Photon/category-first and Precision Overpass discovery. Museums, restaurants, castles, viewpoints, art, worship, architecture, nature, parks, water and the other enabled Scenic categories share the same marker taxonomy as Smart Stops. Fixed waypoints keep their category symbol and receive a luminous emphasis frame.

### Search and POI details

Start and destination search supports towns, landmarks, streets and exact house numbers. POI popups can expose official links, contact information, opening hours and provider-backed ratings where available, and locations can be added to or removed from the route directly from their popup.

### Validation

Validated v0.5.9 APK source head: `35afcbe234697011a28660afff75f9ffa96b8f77`.

- versionCode: `29`
- Android CI #226: passed
- Backend tests #226: passed
- POI provider smoke #52: passed
- workflow artifact ZIP SHA-256: `bdf04d75aed913a486188c502699e4552ee1aa36208974ebb96b59884192397a`
- direct APK SHA-256: `205b8e359f47131ad0e4670fd642b98fc30269b00c52f59fb76f141241642d18`

Subsequent documentation-only commits do not alter the validated APK source.

## Development infrastructure

Public OpenStreetMap, Photon, Nominatim, Overpass and Valhalla services are used only as development/test infrastructure. Production deployment should use controlled/self-hosted or contracted providers and comply with each provider's usage, attribution and branding requirements.
