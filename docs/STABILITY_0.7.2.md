# Scenic Path 0.7.2-rc1 / 44

## Routing

`VehicleAwareJourneyPlanner` now invokes `LongDistanceRouting` directly. Above 600 km (120 km for bicycle), it obtains a real OSRM road corridor and divides it on existing network vertices. Each section is recalculated by Valhalla with the selected vehicle costing and dimensions. OSRM guide geometry is never used as the final vehicle route. Public OSRM profiles rejected motorway-exclusion parameters in live checks, so exclusions are applied in Valhalla costing and intermediate-anchor road filters, without sending unsupported guide parameters. Short-route distance-limit errors enter the same path; bounded recursive subdivision handles stricter service limits. All mandatory stops use this route adapter.

Failed or disconnected sections fail the complete new calculation. The UI retains the previous committed route. Coroutine cancellation disconnects HTTP requests and cannot start a fallback. Optional scenic failure retains the direct route. The duplicate no-stop baseline request is removed.

Providers remain public development services. A usable mapped corridor, vehicle-compatible roads, working providers and sufficient response time are required. A device test is still needed for navigation/GPS. Process death persistence is not included; the ViewModel covers configuration changes.

## Search and state

Independent Photon, device/backend and explicit exact-address lanes publish merged results as they complete. Nominatim is still called only on explicit Search. A query uses a stable location bias. Changing input cancels the previous job. The picker remains actionable while additional providers are loading. Original ranking and deduplication are retained; invalid coordinates and duplicate row IDs are excluded.

A retained journey ViewModel owns the route job and committed state. Endpoint, vehicle and planner changes invalidate in-flight results; cancellation and older cleanup cannot overwrite the latest request. Search text survives closing and reopening the picker.

## Brand

One generated PNG is shared by the in-app header and repository. The adaptive Android icon uses the same image with safe insets and a deep-teal background; themed launchers have a monochrome vector. CI checks byte identity and image decoding, because the previous equal-byte check accepted two identically corrupted WebP files.

Image prompt: polished square Scenic Path travel icon, ivory winding S-shaped path, layered emerald/teal hills and turquoise mountains, golden sun, no text, readable at launcher size. Created with built-in image generation.

## Verification

Regression cases cover complete long-route coverage, on-network splits, stricter provider limits, failed sections, discontinuities, cancellation, old request rejection, progressive search, provider failure isolation and invalid suggestions. Existing full-route POI and search ranking tests remain in place.

`SCENIC_LIVE_ROUTING=1` enables a real Hamburg–Lisbon test through the actual vehicle planner; its result is tracked separately from deterministic tests. Android CI builds the APK, verifies its signature/version and runs lint plus an unsigned release AAB smoke build.

API references: [Valhalla route API](https://valhalla.github.io/valhalla/api/route/api-reference/), [OSRM route API](https://project-osrm.org/docs/v5.24.0/api/), [FOSSGIS service policy](https://routing.openstreetmap.de/about.html). Public requests are paced to at most one per second per routing host.
