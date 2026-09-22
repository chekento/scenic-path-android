# Scenic Path 0.7.1-rc1 — complete long-route POI coverage

Version code: 43. Based on main / 0.7.0-rc3.

## Fixed

- Rapid Overpass discovery discarded every window after the seventh (~455 km).
  Photon corridor discovery discarded every window after the sixth (~540 km).
  Every distance window is now scanned, including the destination.
- Queries start at the beginning, destination and middle before progressively
  filling the gaps. Shared limits of three Overpass and three Photon requests
  bound network pressure across the map and Smart Stops. A failed window does
  not prevent later windows from being attempted.
- Each finished window publishes its results while other windows are still
  loading. Planner discovery keeps its short deadline and completed partials;
  the map continues the full scan without a whole-route seven-second cutoff.
- All route scanners share distance-based segmentation and sampling. Sparse
  geometry and densely sampled cities no longer bias samples toward the start.
  Corridor filtering measures distance to route segments instead of isolated
  sampled vertices, preventing valid POIs between vertices from disappearing.
- Display selection balances occupied route sections and categories together.
  High-scoring POIs near the departure city cannot consume the entire marker
  budget. The map, shared POI memory and Smart Stops use the same selection.
- Cancellation propagates through the window workers; completed results are
  checked for cancellation before publication after a route change.

## Verification

`RoutePoiCoverageTest` covers a ~1,700 km journey, sparse long edges, repeated
vertices, non-uniform point density, winding routes, distance-to-segment
filtering, marker caps, category filters, cross-provider deduplication,
destination retention during repeated updates, shared POI balance, bounded
concurrency, a failed departure query, progressive arrival results and route
cancellation. CI runs the full JVM suite, APK build/signature verification,
release lint and an unsigned release-bundle smoke build.

Physical-device acceptance: plan a route longer than 600 km with several
categories, inspect start/middle/destination while loading, then select a POI
near the destination, recalculate and reopen Smart Stops. Repeat with only one
category and with a briefly unavailable network. No physical-device or live
end-to-end long-route test is claimed by the deterministic regression suite.

Coverage means every route section is queried and available results are kept
geographically balanced. Public provider availability and mapped OSM content
still determine whether a particular section contains matching POIs. Rendering
remains capped at 420 markers; this is not an exhaustive download of every OSM
object. The existing original search providers and brand assets are preserved.
