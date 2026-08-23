# Scenic Path — The Beautiful Way

Native Android experience-first scenic route planner.

Scenic Path does not only ask “how do I get from A to B?”. It turns the user's available extra time into a more beautiful journey: alternative road corridors, automatically selected scenic stops, heritage, views, nature, architecture, culture and food.

## Current development milestone — v0.5.2

v0.5.2 fixes the long-route marker population at the provider-query level after physical-device tests showed that v0.5.1 could still display mostly parks, water and natural features even though all Scenic marker symbols were implemented.

### Category-first POI pipeline

- Photon reverse discovery is no longer allowed to use the broad `layer=other` request that biased results toward large nearby natural features.
- Every Photon reverse request is now explicitly constrained to the currently enabled OSM categories.
- A new `PhotonCorridorPoiDiscovery` searches the actual route in bounded windows using Photon's indexed `bbox` + `include` category filters.
- Food has an independent result pack, so restaurants and cafés cannot be crowded out by nature, heritage or other categories.
- Culture has its own pack for museums, artwork, galleries, arts centres/theatres, historic worship and attractions.
- Heritage/architecture has its own pack for viewpoints, castles, palaces/manors, forts, ruins, monuments, memorials, archaeology, towers, lighthouses, mills and bridges.
- If a textless bbox/category search returns nothing, the same category pack is retried through documented category-filtered Photon reverse search around the route window.
- Returned places are post-filtered against the real route geometry before display.
- Fast category results are published into the shared map POI state immediately, so restaurants/museums/heritage can appear while slower Overpass precision enrichment is still running.
- Overpass remains a secondary enrichment source for the richer 23-lane taxonomy; it is no longer the only route to critical human-interest POIs.

### Provider regression smoke

The branch contains a small provider smoke workflow for this regression. On 2026-08-23 it confirmed against the same public Photon service used by the APK:

- 10 `amenity=restaurant` results around Ahrensburg from category-filtered reverse search (sample: `7Fuji Sushi & Ramen`).
- 19 `tourism=museum` results in the Detmold test box from textless `bbox` + `include` search (sample returned by Photon: `Fürstliches Residenzschloss Detmold`).

This verifies the actual provider queries independently of MapLibre rendering.

## Prototype-parity+ foundation retained

- Kotlin + Jetpack Compose native Android app
- MapLibre + OpenStreetMap/OpenFreeMap map stack
- OSM-native Valhalla development routing with long-route segmentation
- time-budget-aware Journey Optimizer with several journey variants
- calculated routes remain visible while the user edits filters, weights or stops; a valid route is replaced only after a successful rebuild
- planner rebuild waits until the edited plan/preferences are committed, preventing stale first-build behavior
- route optimizer, Smart Stops and map consume one committed Scenic category selection
- stable full category catalogue remains available in the planner even when categories are disabled
- Quick / Day Trip / Road Trip modes and Beautiful / Balanced / Direct / Custom route character
- 0–360 minute exploration budget
- full Scenic DNA controls for roads, views, water, forest, relief, culture, monuments, museums, art, worship, parks, architecture, food and attractions
- manually fixed stops can be reordered
- Smart Stops expose 23 human-facing Scenic lanes with manual Deep Refresh
- map POIs use the same category symbols as Smart Stops; automatically included route stops remain numbered
- display selection is balanced across Scenic lanes and repeated river/forest/reserve representations are deduplicated

## Product principle

The WebSim version remains an interaction prototype, but the Android app is the product target. A feature is not counted as parity merely because a switch exists: filters, route character, time budget, stops and ranking controls must change the actual journey and map result.

## Development provider note

Public Photon, Overpass and Valhalla endpoints are used only for development/testing. Photon explicitly allows project use under a fair-use expectation and does not guarantee availability. A production release must use controlled/self-hosted or contracted infrastructure with appropriate capacity, policies and attribution.

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
