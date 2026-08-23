# Scenic Path Android

Native Android implementation of **Scenic Path** — a map-first journey planner for intentionally beautiful routes, scenic categories and Smart Stops.

## Current development build: v0.5.6

### Fixed waypoints are real routing constraints

A location added from a Scenic popup is no longer merely displayed beside the recalculated route. Manual `mustVisit` stops are converted into ordered routing breaks:

`origin → waypoint 1 → waypoint 2 → … → destination`

Each leg still uses the existing Journey Optimizer and long-distance segmentation guard. The global Scenic time and automatic-stop budgets are distributed over the legs instead of being multiplied per leg. Both the scenic and direct route candidates respect the same fixed waypoints.

A replacement route is also validated after stitching: if the returned geometry bypasses a mandatory waypoint by an implausible distance, the replacement is rejected and the previous valid route remains on screen.

### Waypoint marker design

A selected waypoint keeps its original Scenic category symbol — for example 🍽️, 🏛️, 🏰, 👁️ or 🌳. It is no longer replaced by a generic yellow circle. Selected/in-route locations use a luminous multi-ring frame around the normal white/green category marker, so selection is obvious while the location type remains readable.

Removing the waypoint removes the glow; if the same location is still part of the discoverable POI population it remains on the map as a normal candidate.

### Marker reliability

The map treats its Scenic POI population as committed display state. A valid marker set is retained while a waypoint reroute or deeper corridor enrichment is running. A transient empty provider result can no longer remove all clickable icons. The shared POI bridge recognizes equivalent routes by stable start/destination endpoints even when the replacement polyline geometry changes.

### Start and destination address search

Start and destination search supports towns, landmarks, streets and exact house numbers. Type-ahead combines the device geocoder with Photon/OpenStreetMap. Pressing Search additionally performs an explicit OpenStreetMap Nominatim address lookup.

### Map interaction

Scenic markers use the same category symbols as Smart Stops. Rich popups can expose official links, contact information, opening hours and provider-backed ratings where available. Suggested locations can be added to or removed from the route directly from the popup and the route can then be recalculated without discarding the current valid map state.

### Validation

The v0.5.6 APK is built from app-code head `477520c888c1372d8e58c7da9e30667323a8576b`.

- Android CI #208: passed
- Backend tests #208: passed
- POI provider smoke #34: passed
- GitHub Actions artifact ZIP SHA-256: `429a8edfc9619aefe1c7edeca83c041c4d4b85a2cf9ca74f64b1f8708a1bba01`
- Direct APK SHA-256: `830742d4c2982220bf4691dc46c9a056c9fe01a32ecf163bbfa354a551aac6e1`

## Development infrastructure

Public OpenStreetMap, Photon, Nominatim, Overpass and routing services are used only as development/test infrastructure. Production deployment should use controlled/self-hosted or contracted providers and comply with each provider's usage, attribution and branding requirements.
