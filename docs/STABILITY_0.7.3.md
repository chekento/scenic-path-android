# Scenic Path 0.7.3-rc4 / Build 49

## POI waypoint recalculation

When a discovered POI is added as a mandatory route waypoint, the incoming and outgoing Valhalla legs are still routed independently. If the provider snaps the two legs to nearby different access roads, Build 49 requests a real vehicle-aware connector between those road points and includes it in the committed route metrics. The strict corridor stitcher remains unchanged for genuinely disconnected sections; no straight-line geometry is inserted.

## Immediate POI marker rendering

Every retained discovery batch now feeds a route-aware Compose marker overlay immediately. Marker positions are projected from the current MapLibre camera, follow camera movement, and remain clickable. Provider raw categories are normalized before the active scene filter is applied. Native clustered GeoJSON layers remain active as a complementary renderer, with a native point fallback beneath the category icons.

## Route-owned POI sessions

The shared POI pool is now keyed to a compact fingerprint of the active route. Starting a new route clears the previous marker pool before discovery begins; a generation token rejects late results from cancelled provider waves. The UI count tracks unique candidates received separately from the bounded 520-place render pool, so reaching the safe render capacity is not presented as a stalled search.

## Bounded progressive discovery

Rapid, Fast and Precision discovery still publish completed route sections incrementally, but the map now drains all queued partial batches before closing a pass and applies a 75-second/120-second wall-clock deadline depending on route size. Provider cancellation disconnects the underlying HTTP requests. Candidate icons allow native rendering at city zoom while the clustered layer protects the overview map.

## Automatic long-form itinerary

Exploration budgets accept free minute/hour/day/week values up to 30 days. Larger budgets expand the corridor and the automatic Smart Stop capacity. The map promotes a route-wide, category-diverse, well-spaced subset into real route waypoints; the existing vehicle-aware router then recalculates the journey through those stops.

## Strengthened route commit

Long-distance planning still obtains a real road-network corridor and recalculates bounded sections with the selected vehicle profile. Automatically generated corridor anchors are now matched as network anchors without applying contradictory hard endpoint filters. Vehicle costing continues to enforce the selected motorway, toll and vehicle constraints on the final Valhalla route.

The road route is committed independently from public POI enrichment. A slow or empty Photon/Overpass response therefore cannot delay the first valid route render or convert the route into an error. The map starts the full-route progressive discovery pass after the route is committed.

## User-visible progress

The top planning panel shows an animated Scenic status indicator while the road route is calculated and while the map continues scanning the complete route corridor for POIs. The status remains visible after the route appears, so a temporarily empty first provider wave is distinguishable from a completed search.

## GPS start selection

The start picker offers the current fused-GPS position directly. It shows an accuracy estimate when available, requests location permission when required, and clearly indicates when Android is still waiting for a fix. Choosing this action returns the app to live-GPS start mode rather than freezing a manually typed coordinate.

## Validation scope

The repository CI workflow is the authoritative validation path for Build 49. It runs deterministic JVM tests, the Android debug build and APK verification, release lint, the unsigned AAB smoke build, the live Hamburg–Lisbon routing check and the API 35 native-map stress test. The installable APK and SHA-256 asset are published only by the successful main-branch release workflow.
