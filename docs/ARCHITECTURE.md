# Scenic Path architecture

## Goal
Find the **best experience under a detour budget**, not the shortest path with random stops.

## Pipeline
1. Geocode origin/destination with a production geocoder.
2. Compute a fastest baseline.
3. Generate 2–4 candidate routes. The first production provider is TomTom Routing with `routeType=thrilling`, parameterized by windingness and hilliness.
4. Enrich each route corridor with landscape and culture signals:
   - forest / protected landscape
   - lake / river / coast proximity
   - elevation, relief, viewpoints
   - historic sites, architecture, monuments
   - museums, galleries, gardens, parks
   - road class, motorway share, tunnels, industrial land use
5. Query food separately. A food stop is eligible only if it exceeds the configured rating **and** review-count threshold. If a rating provider is unavailable, Scenic Path should show no “top-rated” food rather than pretend OSM tags are reviews.
6. Calculate `ScenicScore` for each candidate.
7. Discard routes beyond both extra-minutes and extra-percent limits.
8. Return a Pareto-style set: Beautiful / Balanced / Direct.
9. During navigation, reroute from live GPS while preserving the remaining scenic intent and stop budget.

## ScenicScore v1
All inputs normalized to 0..1:

`beauty = weighted_mean(road, forest, water, mountain, viewpoint, culture, museum, architecture, park, food)`

Then subtract:
- motorway penalty when avoidance is enabled
- industrial-land penalty
- detour penalty as the route approaches the user's budget

The algorithm intentionally remains provider-neutral and unit-tested in `backend/scenic-score.js`.

## Provider strategy
- Map rendering: MapLibre Native. Production style/tile URL is configurable.
- Candidate routing: TomTom “thrilling” first; GraphHopper custom models and self-hosted Valhalla are viable alternatives/fallbacks.
- Cultural/natural data: OSM + Wikidata/Wikipedia through a self-hosted or contracted service; no public Overpass dependency for scaled production.
- Food ratings: provider abstraction; Google Places is a practical first provider, but its content must remain visually attributed and cannot be indiscriminately cached.
- Secrets: backend environment only.

## Offline roadmap
M3 introduces downloadable regional map packages and cached route/POI metadata. Full offline rerouting can later use regional Valhalla/GraphHopper packages or a dedicated on-device engine.
