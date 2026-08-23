# Scenic Path — The Beautiful Way

Native Android experience-first scenic route planner.

Scenic Path does not only ask “how do I get from A to B?”. It turns the user's available extra time into a more beautiful journey: alternative road corridors, automatically selected scenic stops, heritage, views, nature, architecture, culture and food.

## Current development milestone — v0.5.0

v0.5.0 is the first deliberate **prototype-parity+** pass: controls that matter are not merely reproduced from the WebSim prototype; they are wired into the native route, Smart Stops and map pipeline.

- Kotlin + Jetpack Compose native Android app
- MapLibre + OpenStreetMap/OpenFreeMap map stack
- OSM-native Valhalla development routing with long-route segmentation
- time-budget-aware Journey Optimizer with several journey variants
- calculated routes remain visible while the user edits filters, weights or stops; a valid route is replaced only after a successful rebuild
- planner rebuild waits until the edited plan/preferences are committed, preventing stale “first build used old settings” behavior
- route optimizer, Smart Stops and map now consume one committed Scenic category selection instead of independent hard-coded category sets
- stable full category catalogue remains available in the planner even when categories are disabled
- quick Scenic-mix presets plus individually selectable scene families
- Scenic DNA now exposes roads, views, water, forest, relief, culture, monuments, museums, art, worship, parks, architecture, food and scenic-attraction weights
- winding-road and hill/relief controls are explicitly adjustable
- manually fixed stops can be reordered without deleting and re-adding them
- automatic itinerary discovery no longer uses the old sparse prototype-era scanner: it shares the richer Fast + continuous route-corridor Precision POI pipeline with the rest of the app
- Precision POI discovery follows simplified pieces of the actual route geometry instead of only probing isolated sample circles
- independent search families cover restaurants/cafés, museums, viewpoints, heritage, castles/palaces/manors, ruins, monuments, art, worship, architecture, nature and attractions
- Smart Stops expose 23 human-facing Scenic lanes and Deep Refresh performs a wider opt-in corridor scan
- display selection is balanced across Scenic lanes before common nature/water data may consume spare result capacity
- map POIs use the same category symbols as Smart Stops; automatically included route stops remain numbered
- Top Food participates in automatic stop selection; development fallback uses OSM metadata without inventing ratings
- production backend can use Google Places Top Food with real rating/review confidence when `GOOGLE_PLACES_API_KEY` is configured

## Product principle

The WebSim version remains a useful interaction prototype, but the Android app is the product target. A feature is not counted as parity merely because a switch exists: filters, route character, time budget, stops and ranking controls must change the actual journey and map result.

## Development provider note

Public Photon, Overpass and Valhalla endpoints are used only for development/testing. A production release must use controlled/self-hosted or contracted infrastructure with appropriate capacity, policies and attribution.

Provider secrets such as Google Places or routing keys belong in backend/deployment secret storage and must never be embedded in the Android APK.

## Next product steps

- persistent shared journey/session state across process recreation
- richer POI information from Wikipedia/Wikidata/Wikimedia
- ETA-aware opening hours and stronger restaurant-quality provider integration
- marker clustering / zoom-dependent decluttering for dense cities
- direct map-to-itinerary interaction and richer stop ordering
- segment-level ScenicScore with elevation/relief data
- turn-by-turn navigation, TTS, upcoming-stop warnings and scenic rerouting
- offline/degraded-network behavior
- signed Play Store AAB and final privacy/data-safety review
