# Scenic Path — The Beautiful Way

Native Android prototype for an experience-first scenic route planner.

Scenic Path does not only ask “how do I get from A to B?”. It asks how to turn the user's available extra time into a more beautiful journey: alternative road corridors, automatically selected scenic stops, heritage, views, water, parks, architecture, culture and food.

## Current development milestone — v0.4.7

- Kotlin + Jetpack Compose native Android app
- MapLibre + OpenStreetMap/OpenFreeMap map stack
- OSM-native Valhalla development routing
- time-budget-aware Journey Optimizer
- long-route segmentation below public development-provider distance limits
- automatic Smart Stops with exact detour/budget validation
- stable non-draggable Smart Stops dialog
- Smart Stops now expose 18 richer human-facing scene lanes instead of collapsing all results into ten broad buckets
- route POIs found by Smart Stops are immediately shared with the map, so the exact same discovered locations can be seen geographically
- map POIs use the same category emojis as Smart Stops; automatically included route stops remain clearly numbered
- manual `Refresh Smart Stops` performs an opt-in deeper route-corridor scan with a wider search radius and more route samples
- while POI enrichment is running, Smart Stops show a searching state instead of misleading intermediate coverage such as `2/18` followed by `3/18`
- targeted discovery covers viewpoints, museums, natural landmarks, reserves, castles, palaces/manors, ruins/archaeology, monuments, parks/gardens, art, worship, waterfalls, beaches, lakes/rivers, food, towers/lighthouses, bridges/aqueducts and scenic attractions
- Top Food participates in long-route automatic stop selection; the development-device fallback uses OSM metadata without inventing ratings
- production backend supports Google Places Top Food with real ratings/review counts and review-confidence ranking when `GOOGLE_PLACES_API_KEY` is configured

## Development provider note

Public Photon, Overpass and Valhalla endpoints are used only for development/testing. A production release must use controlled/self-hosted or contracted infrastructure with appropriate capacity, policies and attribution.

Provider secrets such as Google Places or routing keys belong in backend/deployment secret storage and must never be embedded in the Android APK.

## Still planned

- replace the temporary in-memory POI bridge with one persistent shared journey state used by planner, map, Smart Stops and navigation
- richer POI information from Wikipedia/Wikidata/Wikimedia
- ETA-aware opening hours
- marker clustering for dense areas
- segment-level ScenicScore with elevation/relief data
- turn-by-turn navigation, TTS, upcoming-stop warnings and scenic rerouting
- offline/degraded-network behavior
- signed Play Store AAB and final privacy/data-safety review
