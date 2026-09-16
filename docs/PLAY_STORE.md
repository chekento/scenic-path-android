# Scenic Path — Google Play release runbook

**Release-prep date:** 2026-09-16

## Current Play candidate

- Application ID: `cloud.kosch.scenicpath`
- App name: **Scenic Path**
- Tagline: **The beautiful way**
- Candidate: **0.7.0-rc3**
- versionCode: **42**
- Target SDK: **36 (Android 16)**
- Minimum SDK: 26
- Native Android / Jetpack Compose
- MapLibre map renderer
- Foreground location only
- No Scenic Path account required
- No advertising SDK
- Release manifest disables cleartext traffic and Android backup

## Current Google Play requirements relevant to this app

As of 2026-09-16:

- New mobile apps and app updates submitted after 2026-08-31 must target Android 16 / API 36 or higher. Scenic Path already targets 36.
- App Bundles / APKs must use a monotonically increasing `versionCode`.
- For personal developer accounts created after 2023-11-13, production access requires a **closed test with at least 12 testers continuously opted in for at least 14 days** before production access can be requested.
- Open testing is only available after production access for those affected personal accounts.
- Google Play package-name registration / Android developer verification requirements become effective 2026-09-30; make sure `cloud.kosch.scenicpath` is registered to the verified developer account.
- An IARC content rating is required.
- The Play Console App content section must include the privacy policy, ads declaration, app-access status, target audience/content and Data Safety information.

Official references:

- Target API: https://support.google.com/googleplay/android-developer/answer/11926878
- Testing requirements: https://support.google.com/googleplay/android-developer/answer/14151465
- Package registration: https://support.google.com/googleplay/android-developer/answer/16984799
- App content/review: https://support.google.com/googleplay/android-developer/answer/9859455
- Preview assets: https://support.google.com/googleplay/android-developer/answer/9866151

## Release architecture

### Debug / physical-device development

Debug builds may use local/public development endpoints and can allow cleartext traffic for the local backend.

### Release build

Release builds:

- are non-debuggable;
- disallow cleartext traffic;
- require an HTTPS production backend URL via `SCENIC_API_BASE_URL`;
- require a production map style via `SCENIC_MAP_STYLE_URL`;
- require a public HTTPS privacy-policy URL via `SCENIC_PRIVACY_POLICY_URL`;
- do not embed backend routing/place provider secrets;
- are signed only when the upload-key environment values are supplied.

### Original-search production note

0.7.0-rc3 intentionally preserves the original search/discovery stack. That stack still contains direct Android requests to public Photon, Nominatim and Overpass-compatible endpoints in addition to the Scenic Path backend.

This is acceptable for controlled development/closed testing only if provider terms and traffic remain appropriate. Before a broad production rollout, choose one of these paths:

1. move those lanes behind controlled/self-hosted/contracted infrastructure while preserving the original algorithms; or
2. explicitly retain the direct provider architecture, confirm production-use terms/capacity and disclose the transfers accurately in privacy/Data Safety.

Do not treat community demo infrastructure as an unbounded production SLA.

## Hard Play release gate

Run:

```bash
gradle :app:verifyPlayReleaseConfig
```

The task fails unless:

- the backend is a non-local HTTPS URL;
- a production HTTPS map style is configured;
- a public HTTPS privacy-policy URL is configured;
- all upload signing values are present;
- the configured upload keystore exists.

The dedicated Play workflow adds further checks for targetSdk 36, package identity, location-permission scope, branding, original-search files/call-sites, unit tests, backend tests, release lint, dependency inventory, AAB signature and SHA-256.

## GitHub Actions

### Normal CI — `Android CI`

PRs validate:

1. Scenic Path brand contract;
2. original-search algorithm contract;
3. unit tests;
4. debug APK compilation;
5. Android release lint;
6. unsigned release AAB compilation.

The unsigned AAB is build-smoke output only and must not be uploaded to Google Play.

### Signed Play bundle — `Play Release AAB`

The manual workflow uses the protected GitHub environment `play-production` and expects:

- `SCENIC_API_BASE_URL`
- `SCENIC_MAP_STYLE_URL`
- `SCENIC_PRIVACY_POLICY_URL`
- `PLAY_UPLOAD_KEYSTORE_BASE64`
- `PLAY_UPLOAD_STORE_PASSWORD`
- `PLAY_UPLOAD_KEY_ALIAS`
- `PLAY_UPLOAD_KEY_PASSWORD`

The workflow:

1. verifies Play policy surface, branding and original-search contract;
2. runs backend + Android unit tests;
3. restores and validates the upload keystore;
4. runs `verifyPlayReleaseConfig`;
5. runs release lint;
6. records the release dependency tree;
7. builds the signed AAB;
8. verifies the AAB signature;
9. writes SHA-256 and release metadata;
10. uploads a deterministic `Scenic-Path-v<version>-play.aab` workflow artifact.

No Play service-account auto-upload is configured. The first upload should be manual so Play App Signing and the app record can be verified interactively.

## Play App Signing / upload key

For a new Play app:

- enable **Play App Signing**;
- use a dedicated upload key, separate from debug signing;
- keep the upload keystore backed up outside GitHub;
- store only a base64 representation + passwords as protected GitHub environment secrets;
- never commit the keystore or passwords.

## Privacy / Data Safety

Repository documents:

- `PRIVACY_POLICY_DRAFT.md` — release-aligned policy draft
- `PLAY_DATA_SAFETY.md` — implementation-grounded Data Safety worksheet
- `PLAY_STORE_LISTING.md` — English/German store copy and asset checklist
- `PLAY_RC_0.7.0-rc3.md` — exact release-candidate gate/status

Before the first signed Play upload:

- [ ] insert legal publisher/contact details in the privacy policy;
- [ ] confirm production backend retention/deletion behavior;
- [ ] confirm exact production routing/search/map/POI/food providers;
- [ ] classify provider relationships for Data Safety;
- [ ] publish privacy policy at a stable public HTTPS URL;
- [ ] complete Data Safety against the exact signed AAB.

## Location-policy position

Current manifest requests only foreground location:

- `ACCESS_COARSE_LOCATION`
- `ACCESS_FINE_LOCATION`

It does **not** request `ACCESS_BACKGROUND_LOCATION`.

Store copy and reviewer notes must therefore describe navigation as on-screen/foreground navigation. Do not claim always-on/background navigation until a separately reviewed background-location implementation exists.

## Store listing assets

Required core assets:

- Play Store icon: **512 × 512, 32-bit PNG with alpha, <= 1,024 KB**
- Feature graphic: **1,024 × 500, JPEG or 24-bit PNG without alpha**
- Phone screenshots showing the real product experience
- Public privacy-policy HTTPS URL

Prepared for this release-prep pass:

- `scenic-path-play-icon-512.png` — 512 × 512 RGBA PNG
- `scenic-path-feature-1024x500.png` — 1024 × 500 RGB PNG

Phone screenshots still need to come from the real RC on a device/emulator. Recommended sequence is in `PLAY_STORE_LISTING.md`.

## Closed-test stability gate

Test at minimum:

- [ ] Android 16 / API 36 physical device
- [ ] Android 13–15 device/emulator coverage
- [ ] precise location granted
- [ ] approximate location only
- [ ] permission denied
- [ ] GPS temporarily unavailable
- [ ] no network at app start
- [ ] network loss while route is displayed
- [ ] backend 4xx/5xx/timeout
- [ ] provider timeout/degraded behavior
- [ ] A→B under 50 km
- [ ] route over 200 km
- [ ] +30 / +120 / +240 minute detour budgets
- [ ] all Scenic Categories enabled
- [ ] exact street + house-number search
- [ ] typo-tolerant/type-ahead search
- [ ] repeated add/remove Smart Stop cycles
- [ ] route recalculation while POIs remain visible
- [ ] map marker tap / POI details / external link
- [ ] car, motorcycle, bicycle, camper, truck and coach profiles
- [ ] screen rotation/activity recreation
- [ ] low-memory return to app
- [ ] navigation start/stop and route deviation
- [ ] battery/thermal behavior during long foreground navigation

## Recommended Play rollout

1. **Internal testing** — developer + trusted devices; verify AAB/install/update path.
2. **Closed testing** — if the account is subject to the 12-testers/14-days rule, start this immediately and keep at least 12 testers opted in continuously.
3. Fix Play pre-launch-report findings and policy warnings.
4. Request production access when eligibility appears in Play Console.
5. Optional open testing after production access if useful.
6. Production staged rollout: 5% → 20% → 50% → 100%, pausing on crash/ANR/provider regressions.

## What is still blocking the actual signed AAB

The repository side is prepared, but the signed Play artifact cannot be generated until the protected `play-production` environment contains:

- production backend URL;
- production map-style URL;
- final public privacy-policy URL;
- dedicated Play upload keystore + passwords.

Those values must not be invented or committed to the repository.
