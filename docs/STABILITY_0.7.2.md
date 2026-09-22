# Scenic Path 0.7.2-rc2 / 45

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

## POI load and map stability

Native GeoJSON/Symbol layers replace camera-driven Compose marker projection. Clusters expand on taps by advancing zoom, without native cluster-ID lookups that can race with replaced sources. Planned stops use a separate source. Complete road geometry is uploaded only on route changes, prepared off the UI thread; GPS updates touch only the user source. Navigation snapshots are calculated only while navigation is active, on Default, reusing cumulative route distances.

A bounded queue (6 batches of at most 256 points) applies backpressure and publishes at 350 ms intervals. POI discovery retains at most 1,024 candidates per pass and the shared planning pool at most 520. Selection uses full route segments via a bounding tree and a 2,048-entry projection cache. Only two route indexes are retained. Scanning still visits all windows, including the destination. Journey clearing invalidates old publishers. Smart Stops reads this shared pool rather than starting duplicate automatic scans.

Background POI HTTP has three dedicated workers; foreground operations have four. Both queues are bounded to 32 and cancellation removes queued tasks and disconnects active requests. Responses are size-limited (2 MiB characters for enrichment; 512 KiB for details). Map enrichment stops below STARTED, completed scans are not restarted on resume, and MapView receives memory-pressure callbacks. Interrupted scans can repeat completed windows on resume; bounded deduplication preserves existing discoveries.

Regression tests exercise 70,001 route vertices with 2,000 POIs, 4,000 batched updates, old-session rejection, foreground access during occupied POI workers, oversized responses, dateline/loop projection against exhaustive segment search, and planned-stop separation. These checks and Android CI do not establish crash-free behavior on every physical device.
