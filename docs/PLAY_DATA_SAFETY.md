# Google Play Data Safety — Scenic Path release matrix

This document is an implementation-grounded worksheet for the Play Console Data Safety form. It is **not** legal advice. Re-check it against the final production deployment, provider contracts and SDK versions immediately before submission.

## Current app facts

- Package: `cloud.kosch.scenicpath`
- Target SDK: 36
- No Scenic Path account required
- No advertising SDK in the app module
- Foreground location permissions only: `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`
- No `ACCESS_BACKGROUND_LOCATION`
- Release traffic to the Scenic Path backend is HTTPS-only
- Vehicle profile is stored locally on device
- Release manifest disables Android backup

## Recommended Play Console answers to verify

### Location — Approximate location

**Does the app access/transmit it?** Yes, when the user grants location permission and uses Current Location / navigation.

**Purpose:** App functionality (route planning, map position, on-screen navigation).

**Required or optional:** Optional. Users can deny location permission; workflows should allow manually selected route locations.

**Ephemeral processing:** Mark as ephemeral only if the production backend/provider path uses the location solely in memory for the specific request and does not retain it beyond what is necessary for that request. Confirm production logging before submission.

### Location — Precise location

Same handling as approximate location. The app requests `ACCESS_FINE_LOCATION`, but Android can allow users to provide approximate location instead.

**Purpose:** App functionality.

**Required or optional:** Optional.

**Ephemeral:** Confirm against production logging/provider behavior.

### App activity — In-app search history

Place/address text entered by the user can leave the device for search/geocoding.

**Purpose:** App functionality.

**Ephemeral:** Intended to be request-oriented. Confirm that Scenic Path backend logs do not retain query contents before marking ephemeral.

### App activity — App interactions / route preferences

Scenic Categories, route character, detour budget and vehicle route parameters can be sent with a route request.

These values are transmitted to provide the requested routing result. Decide with the final Play Console wording whether they meet Google's `App interactions` definition for the production implementation.

### Device or other IDs

Scenic Path application code does not intentionally create an advertising/device identifier. Re-check Google Play Services / map / provider SDK declarations before submission.

### Crash logs / diagnostics

No crash-reporting or analytics SDK is intentionally bundled in the current Android module. If Firebase Crashlytics, Sentry, Play SDK diagnostics or another telemetry product is added later, update this section and the Play declaration.

## Sharing vs. service-provider processing

The production backend can call routing/place/map-data providers. Google Play's Data Safety definition treats transfers differently depending on whether the third party is acting as a service provider on the publisher's behalf.

Before submission:

- [ ] identify every production routing/search/place/tile provider;
- [ ] verify the applicable data-processing/contract terms;
- [ ] decide which transfers qualify as service-provider processing;
- [ ] declare any transfer that does not qualify for a sharing exception.

## Security practices

Recommended answers, provided production matches the repository release gate:

- Data in transit encrypted: **Yes** (HTTPS release gate).
- Users can request deletion: there is no Scenic Path account/route-history database in the current design; final answer depends on operational logs/support records.
- Independent security review: **Do not claim one unless actually completed.**

## Permissions declaration

Current manifest does not request background location. Therefore the special Play background-location declaration/video path should not apply to this release candidate.

If background navigation is added later with background/foreground-service location behavior, redo the permissions and policy review before shipping that update.

## Final pre-submit confirmation

- [ ] Production backend URL configured and HTTPS.
- [ ] Production map style configured and HTTPS.
- [ ] Privacy policy published at stable public HTTPS URL.
- [ ] Production backend log retention documented.
- [ ] Provider list and service-provider status confirmed.
- [ ] No new SDK has introduced analytics/ads/device-ID collection.
- [ ] Play Console Data Safety responses match the exact uploaded AAB, not an older debug build.
