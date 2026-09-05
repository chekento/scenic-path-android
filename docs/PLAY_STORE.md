# Scenic Path — Play Store release runbook

## Current release-candidate baseline

- Application ID: `cloud.kosch.scenicpath`
- App name: **Scenic Path**
- Tagline: **The beautiful way**
- Target SDK: **36 (Android 16)**
- Minimum SDK: 26
- Native Android / Jetpack Compose
- MapLibre map renderer
- Foreground location only
- No Scenic Path account required
- No advertising SDK
- Release manifest disables cleartext traffic and Android backup

As of 2026-09-05, Google Play requires new mobile apps and updates to target Android 16 / API 36. This repository already does so.

## Release architecture

### Debug / physical-device development

Debug builds may use local/public development endpoints and can allow cleartext traffic for the local backend.

### Release build

Release builds:

- are non-debuggable;
- disallow cleartext traffic;
- use the production backend URL from `SCENIC_API_BASE_URL`;
- use the production map style from `SCENIC_MAP_STYLE_URL` (or explicit `MAP_STYLE_URL`);
- expose the public privacy-policy URL from `SCENIC_PRIVACY_POLICY_URL`;
- never contain routing/place provider secrets;
- are signed only when upload-key environment values are supplied.

### Hard release gate

Run:

```bash
gradle :app:verifyPlayReleaseConfig
```

The task fails unless:

- the backend is a non-local HTTPS URL;
- a production HTTPS map style is configured;
- a public HTTPS privacy-policy URL is configured;
- all upload signing values are present;
- the configured upload keystore file exists.

This prevents an accidental localhost/demo build from becoming a Play artifact.

## GitHub Actions

### Normal CI — `Android CI`

Every PR validates:

1. debug APK compilation;
2. Android release lint;
3. unsigned release AAB compilation.

The unsigned AAB is a build-smoke artifact only and must **not** be uploaded to Play.

### Signed Play bundle — `Play Release AAB`

The manual workflow uses the protected GitHub environment `play-production` and expects these encrypted secrets:

- `SCENIC_API_BASE_URL`
- `SCENIC_MAP_STYLE_URL`
- `SCENIC_PRIVACY_POLICY_URL`
- `PLAY_UPLOAD_KEYSTORE_BASE64`
- `PLAY_UPLOAD_STORE_PASSWORD`
- `PLAY_UPLOAD_KEY_ALIAS`
- `PLAY_UPLOAD_KEY_PASSWORD`

The workflow:

1. restores the upload keystore to the runner temp directory;
2. runs `verifyPlayReleaseConfig`;
3. runs release lint;
4. builds the signed AAB;
5. verifies the AAB signature;
6. emits SHA-256;
7. uploads the AAB as a workflow artifact.

No Play service-account auto-upload is configured yet. The first releases should be uploaded manually to the **Internal testing** track.

## Play App Signing

For a new Play app, enable **Play App Signing** and use a separate upload key for the CI/local upload bundle. Never commit the keystore or passwords.

The repository already ignores `*.jks`, `*.keystore`, `*.aab`, `*.apk`, `local.properties` and backend `.env` files.

## Privacy / Data Safety

Repository documents:

- `PRIVACY_POLICY_DRAFT.md` — source draft for the public policy
- `PLAY_DATA_SAFETY.md` — Play Console response worksheet
- `PLAY_STORE_LISTING.md` — English/German listing copy and marketing-asset checklist

Before the first Play upload:

- [ ] insert legal publisher/contact details in the privacy policy;
- [ ] confirm production backend log retention;
- [ ] confirm the exact production routing/search/map/food providers;
- [ ] confirm provider contracts/service-provider status;
- [ ] publish the privacy policy at a stable public HTTPS URL;
- [ ] complete the Play Data Safety form against the exact uploaded AAB.

## Location-policy position for the first release

The current manifest contains only:

- `ACCESS_COARSE_LOCATION`
- `ACCESS_FINE_LOCATION`

It does **not** request `ACCESS_BACKGROUND_LOCATION`.

The current release candidate must therefore describe navigation as **on-screen / while Scenic Path is visible**. Do not claim background or always-on navigation in the Play listing until a separately reviewed background-navigation implementation is shipped.

## Production infrastructure still required

Before external/production testing:

- [ ] deploy a stable Scenic Path HTTPS backend;
- [ ] configure production routing/search/place provider credentials only on that backend;
- [ ] configure a production map-style/tile endpoint with appropriate capacity and attribution;
- [ ] validate provider timeouts, rate limits and degraded-service behavior;
- [ ] confirm Google Places attribution/caching rules if Top Food uses Google Places.

Public Photon/Overpass/Valhalla endpoints used for development must not be treated as production infrastructure.

## Stability gate before closed testing

Test at minimum:

- [ ] Android 16 / API 36 physical device
- [ ] at least one Android 13–15 device/emulator
- [ ] permission accepted with precise location
- [ ] permission accepted with approximate location
- [ ] permission denied
- [ ] GPS temporarily unavailable
- [ ] no network at app start
- [ ] network loss while route is displayed
- [ ] backend HTTP 4xx/5xx/timeout
- [ ] route A→B under 50 km
- [ ] long route over 200 km
- [ ] +30 / +120 / +240 minute budgets
- [ ] all Scenic Categories enabled
- [ ] category changes while an existing route remains displayed
- [ ] repeated add/remove Smart Stop cycles
- [ ] map marker tap / POI details / external link
- [ ] car, bicycle, camper, truck and coach profiles
- [ ] screen rotation / activity recreation
- [ ] low-memory return to app
- [ ] navigation start/stop and route deviation
- [ ] battery/thermal behavior during a long on-screen navigation session

## Suggested Play rollout

1. **Internal testing** — owner + a few known testers
2. **Closed testing** — broader device/route matrix
3. Fix Play pre-launch-report findings
4. **Open testing** only if useful for POI/provider coverage feedback
5. Production staged rollout: 5% → 20% → 50% → 100%, pausing on crash/ANR/provider regressions

## Store assets

See `PLAY_STORE_LISTING.md` for listing copy and screenshot sequence.

Still required before publication:

- 512 × 512 store icon confirmation
- 1024 × 500 feature graphic
- phone screenshots showing diverse POIs / Smart Stops / Scenic DNA / navigation
- final public privacy-policy URL

## Final release command path

For local validation without signing:

```bash
gradle :app:lintRelease :app:bundleRelease
```

For an actual signed Play artifact, prefer the **Play Release AAB** GitHub workflow after configuring the protected `play-production` environment and secrets.
