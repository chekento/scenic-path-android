# Changelog

## 0.7.3-rc2 — Build 47

- Bind the shared POI pool to the active route. Starting a new journey clears old markers immediately and late results from the previous route are rejected.
- Make progressive POI discovery bounded and cancellable. Provider waves now drain completed partials safely, expose the real loaded-candidate count and cannot appear permanently stuck at the 520-place render pool.
- Keep route POIs visible at city zoom levels by letting the native clustered map layer render valid candidate icons instead of hiding them through symbol collisions.
- Add route-wide automatic Smart Stops: longer exploration budgets promote well-spaced candidates into real route waypoints, so multi-day and multi-week plans do not require manual POI selection.
- Extend exploration time from hours to 30 days with presets and free values such as `18h`, `3d` and `2w`; the scenic corridor and automatic itinerary capacity expand with the selected time.

## 0.7.3-rc1 — Build 46

- Make long-distance vehicle routing resilient at automatically generated road-corridor anchors. Endpoint matching no longer applies contradictory hard motorway/toll filters to those anchors; the selected vehicle costing remains authoritative for the final route.
- Commit a valid road route without waiting for the public POI enrichment wave. Progressive Rapid/Fast/Precision scans continue after the route appears and retain the complete-route coverage model.
- Add a visible animated Scenic status indicator for route calculation and ongoing POI discovery, including the number of candidates already available after scanning completes.
- Add a direct current-GPS action to the start picker, with explicit permission, waiting-for-fix and accuracy states.
- Keep the previous committed route when a replacement request fails; a slow or empty POI provider cannot turn a valid road route into a planning error.

## 0.7.2-rc2 — Build 45

- Render POIs in native clustered map layers, retaining separately selectable fixed stops. Camera motion no longer recomposes hundreds of marker views.
- Upload route geometry only when it changes; GPS updates only the location marker. Navigation calculations run off the UI thread and reuse route distances.
- Batch POI updates with bounded backpressure, a 520-place shared pool, a segment index and bounded projection cache. Full-route scanning and destination coverage remain intact.
- Cancel HTTP requests and background scans promptly. Bound provider response size and worker queues; reserve separate capacity for route/search/detail requests.
- Smart Stops reuses live discoveries; explicit refresh performs a deeper search. Forward Android memory-pressure events to the map renderer.

- Long-distance routes use a full road-network corridor and bounded, vehicle-specific route sections; smaller sections are retried for provider distance errors. Stops, endpoint coverage and section continuity are preserved. No partial journey or straight-line substitute is offered as a route.
- Reuse the direct baseline, preserve cancellation through provider failures, and bound optional scenic detours.
- Address suggestions appear as each provider finishes. GPS changes no longer restart active input; exact searches remain available while other results load. House numbers appear in suggestion titles.
- Remove duplicate Photon requests; use cancellable HTTP and asynchronous Android geocoding on Android 13 and newer.
- One current route request, a visible Cancel action, readable service errors, and retained journey/search drafts through rotation.
- New landscape/path icon in the launcher, app header and repository; Android themed icon support. Replace the undecodable old WebP icon and verify image decoding in CI.
- Stable, public test-only APK signing identity from this version onward. Older CI test installations may need one uninstall before installing this build; uninstalling removes local settings. Play signing is unchanged.

## 0.7.1-rc1 — Build 43

- POI searches cover the entire route instead of truncating after the first windows.
- Progressive results and route-section balancing retain POIs near the destination.
- Segment-based distance calculations support sparse route geometry.
