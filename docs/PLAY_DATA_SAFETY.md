# Google Play Data Safety — Scenic Path 0.7.0-rc3

**Release-prep date:** 2026-09-16

This is an implementation-grounded worksheet for the Play Console Data Safety form. It is not legal advice. Re-check every answer against the exact signed AAB, production deployment, provider contracts and Google Play wording immediately before submission.

## Current app facts

- Package: `cloud.kosch.scenicpath`
- Target SDK: 36 / Android 16
- Minimum SDK: 26
- No Scenic Path account required
- No advertising SDK in the Android app module
- Foreground location permissions only: `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`
- No `ACCESS_BACKGROUND_LOCATION`
- Release backend traffic is HTTPS-only
- Android backup is disabled
- Vehicle profile/preferences are stored locally on device
- Original Scenic Path place/POI discovery is still present: Photon, explicit Nominatim exact-address lookup, Rapid/Fast/Precision/RoutePoiCoverage discovery

## Important 0.7.0-rc3 network fact

The original search/discovery stack currently contains direct Android calls to third-party/community OSM services in addition to the Scenic Path backend:

- Photon search/type-ahead can send the search text and optional location bias directly to `photon.komoot.io`.
- Explicit exact-address search can send the entered address text and a location-bias viewbox directly to `nominatim.openstreetmap.org`.
- Route POI discovery can send route-derived bounding boxes/category queries to public Overpass endpoints.
- Map rendering contacts the configured map-style/tile provider.
- Production route planning sends route inputs to the configured Scenic Path backend; that backend can in turn use configured routing/search/place providers such as TomTom, Photon/OSM-compatible services and Foursquare.

This matters for Data Safety. Do not describe all location/search processing as backend-only until the Android direct-provider lanes have either been moved behind the production backend or intentionally retained and fully disclosed.

## Data types to review in Play Console

### Location — approximate location

**Access/transmission:** Yes when the user grants location permission and uses Current Location, location-biased search or navigation.

**Purpose:** App functionality: current-position display, route planning, rerouting, location-biased place search and on-screen navigation.

**Required or optional:** Optional. The app can be used with manually selected locations where the relevant workflow supports it.

**Collection/sharing:** Review conservatively. Location can be transmitted to Scenic Path backend services and, in this RC, route/search-derived location data can also be sent directly to Photon/Nominatim/Overpass/map providers. Whether a transfer qualifies for a service-provider exception depends on the final provider relationship and Play's current definitions.

**Ephemeral processing:** Do not mark ephemeral until backend and provider logging/retention is confirmed.

### Location — precise location

Same policy position as approximate location. The app requests `ACCESS_FINE_LOCATION`; Android can allow approximate location instead.

**Purpose:** App functionality.

**Required or optional:** Optional.

**Ephemeral:** Not yet confirmed.

### App activity — in-app search history / search queries

Place/address text entered by the user can leave the device:

- type-ahead: Photon lane;
- explicit exact-address search: Nominatim lane;
- configured Scenic Path backend search lane.

**Purpose:** App functionality.

**Collection/sharing:** Confirm against the final production architecture and provider agreements. Because direct third-party endpoints are currently present, do not assume a service-provider exception without evidence.

**Ephemeral:** Not yet confirmed.

### App activity — app interactions / route preferences

Scenic Categories, route character, detour budget, fixed stops and vehicle route parameters can be sent with route requests.

**Purpose:** App functionality.

These values are transmitted to calculate the requested journey. Confirm the exact Play Console subcategory wording against the final AAB.

### Device or other IDs

Scenic Path application code does not intentionally create an advertising identifier or account identifier. Re-check the current Google Play Services Location / MapLibre dependency guidance and the Play SDK Index before submission.

### Crash logs / diagnostics

No Crashlytics, Sentry or first-party analytics SDK is intentionally bundled in the current Android module. If telemetry is added later, update the privacy policy and Data Safety declaration before shipping.

## Third parties / processors to confirm before production

Create a final provider register with legal/contractual status and retention terms for every provider used by the production AAB and backend. At minimum review:

- Scenic Path production backend/host
- TomTom routing/search if configured
- production Photon/OSM-compatible search provider
- production OSM/Overpass-compatible corridor enrichment provider
- map-style/tile provider
- Foursquare if Top Food / ratings are enabled
- any public Photon, Nominatim or Overpass endpoint still reachable from the release binary

For each provider record:

1. data fields transmitted;
2. purpose;
3. retention/logging;
4. whether it acts on the publisher's behalf as a service provider;
5. applicable privacy/DPA terms;
6. whether Play Data Safety requires the transfer to be declared as sharing.

## Security practices

Provided the production release gate passes:

- Data in transit encrypted: **Yes** for the configured Scenic Path backend and map-style URL.
- Cleartext Android release traffic: **Disabled** by manifest placeholder.
- Android backup: **Disabled**.
- Users can request deletion: no Scenic Path account/route-history database is designed into this RC, but the final answer depends on operational logs/support records.
- Independent security review: **Do not claim one unless one has actually been completed.**

Note: direct public OSM/Photon/Overpass endpoints use HTTPS in the current implementation, but production use/capacity and privacy terms still need to be validated separately.

## Permissions declaration

Current manifest requests:

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_COARSE_LOCATION`
- `ACCESS_FINE_LOCATION`

It does **not** request `ACCESS_BACKGROUND_LOCATION`. The special Play background-location declaration/video path should therefore not apply to this release candidate.

If background navigation is added later, redo the permissions, prominent-disclosure and policy review before shipping that update.

## Conservative Play Console starting point

Until provider retention/contracts are finalized, use the following as a review checklist rather than blindly copying answers:

- Location / approximate: **collected or transmitted for app functionality — Yes**
- Location / precise: **collected or transmitted for app functionality — Yes**
- Search activity/search queries: **transmitted for app functionality — Yes**
- Route/preferences/app interactions: **review as app interaction data — likely Yes**
- Ads/advertising profiling: **No**
- Account data: **No Scenic Path account**
- Background location: **No**
- Data in transit encrypted: **Yes**, provided final production configuration passes the release gate
- Data deletion: answer only after log/support retention and deletion process are finalized

## Final pre-submit confirmation

- [ ] Exact signed AAB version/versionCode recorded.
- [ ] Production backend URL configured and HTTPS.
- [ ] Production map style/tile endpoint configured and HTTPS.
- [ ] Public privacy policy URL active and linked both in Play Console and in-app.
- [ ] Legal publisher/contact details inserted in privacy policy.
- [ ] Production backend log retention documented.
- [ ] Direct Photon/Nominatim/Overpass release behavior intentionally accepted or moved behind controlled production infrastructure.
- [ ] Provider register + service-provider/sharing classification completed.
- [ ] No new SDK introduced analytics, ads, device-ID or other data collection.
- [ ] Data Safety responses match the exact uploaded AAB, not an older debug APK.
