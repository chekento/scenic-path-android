# Scenic Path 0.7.0-rc3 — Play Store release gate

**Package:** `cloud.kosch.scenicpath`  
**versionName:** `0.7.0-rc3`  
**versionCode:** `42`  
**Target SDK:** `36`  
**Minimum SDK:** `26`

## Repository-ready

- [x] Original multi-lane Scenic Path search stack retained.
- [x] Explicit exact street + house-number search retained.
- [x] Rapid / Fast / Precision / RoutePoiCoverage POI discovery retained.
- [x] Foreground coarse + precise location only; no background-location permission.
- [x] Android backup disabled.
- [x] Release cleartext traffic disabled.
- [x] Canonical Scenic Path launcher branding enforced by CI.
- [x] Play release workflow builds a signed AAB only with production configuration + upload signing.
- [x] Play workflow verifies policy surface, tests, lint, signature, dependency inventory and SHA-256.
- [x] English + German Play listing copy prepared.
- [x] Data Safety worksheet updated for the exact 0.7 search architecture.
- [x] Privacy-policy draft updated for direct Photon/Nominatim/Overpass provider lanes.
- [x] 512 × 512 Play icon prepared.
- [x] 1024 × 500 feature graphic prepared.

## Required before the signed Play AAB can be generated

The protected GitHub environment `play-production` needs these values:

- [ ] `SCENIC_API_BASE_URL` — stable production HTTPS backend.
- [ ] `SCENIC_MAP_STYLE_URL` — production HTTPS map style/tile endpoint.
- [ ] `SCENIC_PRIVACY_POLICY_URL` — final public HTTPS privacy-policy page.
- [ ] `PLAY_UPLOAD_KEYSTORE_BASE64` — dedicated Play upload keystore encoded as base64.
- [ ] `PLAY_UPLOAD_STORE_PASSWORD`
- [ ] `PLAY_UPLOAD_KEY_ALIAS`
- [ ] `PLAY_UPLOAD_KEY_PASSWORD`

Do not commit any of those secrets to the repository.

## Required privacy / provider decisions

- [ ] Insert legal publisher/controller name, postal address and privacy email in the final policy.
- [ ] Confirm Scenic Path backend log retention + deletion procedure.
- [ ] Confirm production provider register and contractual/privacy status.
- [ ] Decide whether direct Android Photon/Nominatim/Overpass lanes stay in the production binary or are routed through controlled infrastructure while preserving the algorithms.
- [ ] Complete Google Play Data Safety against the exact signed AAB.

## Required Play Console work

- [ ] Create/confirm app record for `cloud.kosch.scenicpath`.
- [ ] Enable Play App Signing and register the dedicated upload key.
- [ ] Confirm developer verification / package ownership requirements.
- [ ] Complete App content: privacy policy, ads, app access, target audience/content, IARC content rating, Data Safety.
- [ ] Upload 512 × 512 icon and 1024 × 500 feature graphic.
- [ ] Capture/upload real phone screenshots from this RC.
- [ ] Upload signed AAB to Internal testing first.
- [ ] If the developer account is subject to the personal-account testing rule, run Closed testing with at least 12 testers continuously opted in for 14 days before requesting production access.

## Closed-test functional matrix

- [ ] clean install from Play test track
- [ ] update from previous internal build
- [ ] precise location granted
- [ ] approximate location granted
- [ ] location denied
- [ ] GPS unavailable / restored
- [ ] offline launch / network recovery
- [ ] exact address search
- [ ] type-ahead / typo-tolerant place search
- [ ] short route (<50 km)
- [ ] long route (>200 km)
- [ ] +30 / +120 / +240 minute budgets
- [ ] Smart Stops add/remove/recalculate
- [ ] POIs remain usable after route recalculation
- [ ] all Scenic Categories
- [ ] car / motorcycle / bicycle / camper / truck / coach profiles
- [ ] foreground navigation start/stop/reroute
- [ ] route deviation
- [ ] screen rotation / activity recreation
- [ ] low-memory return
- [ ] provider timeout/degraded behavior
- [ ] battery/thermal long-session check

## Release artifact expected from CI

After the production URLs and signing secrets are configured, run the **Play Release AAB** workflow. Expected artifact:

`Scenic-Path-v0.7.0-rc3-play.aab`

The artifact bundle also includes its SHA-256, release metadata and release dependency inventory.
