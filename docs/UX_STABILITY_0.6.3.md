# Scenic Path 0.6.3-rc1 — UX and session stability

Based on `feat/ux-routing-modes` at `46bf22a9cb7155837ce08d88b9ba43ce6832073a`.

## Problems addressed

- Requests previously outlived the endpoint or draft that started them. A late result could restore the wrong route and overwrite newer preferences.
- Activity recreation discarded the entire journey. Search and planner minimization also discarded unfinished input.
- Independent fixed overlays still competed for space, including the Activity's GPS switch. Small route summaries squeezed text behind five navigation controls.
- Quick priorities changed day/road trips into point-to-point journeys and reset their budgets.
- Search waited for its slowest provider and restarted on every changing GPS bias. Duplicate provider IDs could break keyed search rows.
- The map started a second high-accuracy GPS subscription and could retain an old location after location access changed.

## Resulting behavior

- A ViewModel owns the journey, committed route and retained searches across configuration changes. Explicit cancellation and request identity checks protect against stale results; provider cancellation does not start a fallback. Failed replacements retain the previous route.
- Status and corrective actions have a dedicated top region, including during navigation. Portrait controls are bounded and scrollable; landscape/wide windows use a side column. The map keeps its composition when panels change. Route title and alternative controls use separate rows with 48 dp icon targets.
- The planner retains draft changes when minimized; searches retain independent input for start, destination and manual stops. Results appear progressively. Android 13+ uses bounded asynchronous geocoding, and moving GPS does not restart a search.
- Priority changes preserve day/road-trip scope, budget and fixed stops. Round trips pin both endpoints. Adding an alternative advances its exploration generation and checks the actual loaded count; duplicate results leave existing alternatives usable.
- One foreground lifecycle-aware GPS stream supplies both map and guidance. Old cached fixes are rejected and disposed callbacks cannot re-enable GPS. System permission state is refreshed on resume.
- Navigation can be minimized, uses committed manual stops, and requires pending edits to be rebuilt before starting. Back closes location details or ends navigation. Map-load failures expose a retry action.
- App surfaces follow the system light/dark setting. Map imagery keeps the configured provider style.

## Verification

CI runs the Android JVM regression suite, debug APK build, release lint and unsigned release AAB smoke build. JVM XML reports are retained with the build artifacts. Backend regression tests include repeated round-trip exploration with an unchanged alternative count.

Focused new tests cover missing endpoints, duplicate taps, noncooperative stale requests, editing/cancellation, failed replacement retention, alternative retries, retained searches, priority/scope preservation, cancellation propagation and duplicate search IDs.

## Physical-device acceptance checks

1. On a fresh session, plan with missing endpoints; verify corrective actions appear at the top.
2. Calculate a route, rotate the phone, open/minimize/restore searches and the planner; verify route and draft retention.
3. Change destination or budget during a slow request, or cancel it; verify the obsolete result never appears.
4. Exercise portrait, landscape, split screen, large font, keyboard and light/dark mode; verify map controls and summary remain reachable.
5. Try the different priorities in day-trip and road-trip mode; verify budgets and fixed stops remain unchanged.
6. Switch alternatives, request another repeatedly, and check retained POIs and camera behavior.
7. Toggle/revoke GPS, leave and return to the app, start/minimize/end guidance and retry a failed map load.

Configuration-change retention is implemented; restoring full journeys after OS process death is not included. This work does not establish real-device navigation accuracy, screen-off/background navigation, live-provider uptime or Play Store release readiness. The existing production service/signing requirements remain applicable.

Architecture references: [Android ViewModel](https://developer.android.com/topic/libraries/architecture/viewmodel), [Android geocoding API](https://developer.android.com/reference/android/location/Geocoder), [MapLibre map failure callback](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-view/add-on-did-fail-loading-map-listener.html).
