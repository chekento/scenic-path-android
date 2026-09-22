# Scenic Path 0.7.3-rc1 / Build 46

## Strengthened route commit

Long-distance planning still obtains a real road-network corridor and recalculates bounded sections with the selected vehicle profile. Automatically generated corridor anchors are now matched as network anchors without applying contradictory hard endpoint filters. Vehicle costing continues to enforce the selected motorway, toll and vehicle constraints on the final Valhalla route.

The road route is committed independently from public POI enrichment. A slow or empty Photon/Overpass response therefore cannot delay the first valid route render or convert the route into an error. The map starts the full-route progressive discovery pass after the route is committed.

## User-visible progress

The top planning panel shows an animated Scenic status indicator while the road route is calculated and while the map continues scanning the complete route corridor for POIs. The status remains visible after the route appears, so a temporarily empty first provider wave is distinguishable from a completed search.

## GPS start selection

The start picker offers the current fused-GPS position directly. It shows an accuracy estimate when available, requests location permission when required, and clearly indicates when Android is still waiting for a fix. Choosing this action returns the app to live-GPS start mode rather than freezing a manually typed coordinate.

## Validation scope

The repository CI workflow is the authoritative validation path for Build 46. It runs deterministic JVM tests, the Android debug build and APK verification, release lint, the unsigned AAB smoke build, the live Hamburg–Lisbon routing check and the API 35 native-map stress test. The installable APK and SHA-256 asset are published only by the successful main-branch release workflow.
