# Scenic Path — The Beautiful Way

Native Android experience-first scenic route planner.

Scenic Path does not only ask “how do I get from A to B?”. It turns the user's available extra time into a more beautiful journey: alternative road corridors, automatically selected scenic stops, heritage, views, nature, architecture, culture and food.

## Current development milestone — v0.5.1

v0.5.1 is a physical-device regression fix for long journeys where the route was valid but the first visible map markers were still dominated by trees, water and mountains.

### What changed in v0.5.1

- the marker renderer was verified not to be the root problem: it already renders each `ScenicCategoryLane` with its own emoji
- the bottleneck was upstream: a long route could reach the map with Photon reverse results before the expensive full corridor scan had returned, and those reverse results naturally over-represent large nearby natural features
- new `RapidRoutePoiDiscovery` splits the real route into bounded ~65 km windows
- all windows run in parallel with hard per-window timeouts so a ~290 km route no longer waits on many serial Overpass corridor calls
- each window explicitly requests restaurants/cafés, museums, viewpoints, heritage, art, worship, architecture and attractions
- downloaded POIs are post-filtered against the actual road geometry, so a fast bounding-box search does not mean accepting places far from the route
- `FastRoutePoiDiscovery` now merges this category-first rapid pass with Photon; the deeper continuous corridor remains an enrichment layer instead of the only path to non-nature categories
- automatic long-route planning therefore receives human-interest candidates before its existing 12-second discovery guard expires
- versionCode `21`, versionName `0.5.1`

## Prototype-parity+ core retained from v0.5.0

- Kotlin + Jetpack Compose native Android app
- MapLibre + OpenStreetMap/OpenFreeMap map stack
- OSM-native Valhalla development routing with long-route segmentation
- time-budget-aware Journey Optimizer with several journey variants
- calculated routes remain visible while the user edits filters, weights or stops; a valid route is replaced only after a successful rebuild
- planner rebuild waits until edited plan/preferences are committed, preventing stale first-build settings
- route optimizer, Smart Stops and map consume one committed Scenic category selection
- stable full category catalogue remains available even when categories are disabled
- quick Scenic-mix presets plus individually selectable scene families
- Scenic DNA sliders for roads, views, water, forest, relief, culture, monuments, museums, art, worship, parks, architecture, food and scenic attractions
- manually fixed stops can be reordered without deleting and re-adding them
- Precision POI discovery follows actual route geometry and balances results across 23 user-facing Scenic lanes
- display caps and stronger deduplication keep repeated rivers, forests and reserves from becoming marker wallpaper

## Product principle

The WebSim version remains a useful interaction prototype, but the Android app is the product target. A feature is not counted as parity merely because a switch exists: filters, route character, time budget, stops and ranking controls must change the actual journey and map result.

## Development provider note

Public Photon, Overpass and Valhalla endpoints are used only for development/testing. A production release must use controlled/self-hosted or contracted infrastructure with appropriate capacity, policies and attribution.

Provider secrets such as Google Places or routing keys belong in backend/deployment secret storage and must never be embedded in the Android APK.

## Next product steps

- progressive on-map enrichment/status instead of waiting silently for the deep scan
- persistent shared journey/session state across process recreation
- richer POI information from Wikipedia/Wikidata/Wikimedia
- ETA-aware opening hours and stronger restaurant-quality provider integration
- marker clustering / zoom-dependent decluttering for dense cities
- direct map-to-itinerary interaction and richer stop ordering
- segment-level ScenicScore with elevation/relief data
- turn-by-turn navigation, TTS, upcoming-stop warnings and scenic rerouting
- offline/degraded-network behavior
- signed Play Store AAB and final privacy/data-safety review
