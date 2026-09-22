# Changelog

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
