# Scenic Path — Privacy Policy (release draft)

**Last updated:** 2026-09-05

> This file is the source draft for the public privacy-policy page required by Google Play.
> Replace the publisher/contact placeholders and confirm production log retention/provider contracts before publishing the final public URL.

## 1. Publisher / controller

Scenic Path is provided by:

- **Publisher:** `[legal publisher name]`
- **Address:** `[publisher postal address]`
- **Privacy contact:** `[privacy email address]`

## 2. What Scenic Path does

Scenic Path is a route-planning and on-screen navigation app that uses the user's available extra time and selected Scenic Categories to propose alternative routes, scenic locations and optional stops.

The current Play release candidate does **not** require a user account and does **not** include an advertising SDK.

## 3. Location data

Scenic Path can request Android's approximate and precise foreground-location permissions.

Location is used for:

- **Current Location** as a route start;
- route planning and rerouting;
- showing the user's position on the map;
- on-screen navigation while Scenic Path is visible.

The current Play release candidate does **not** request `ACCESS_BACKGROUND_LOCATION`. Scenic Path therefore does not claim always-on/background navigation in this release.

Users can deny location permission and use manually selected route locations instead where the relevant workflow supports it.

## 4. Route and place requests

For production route planning, Scenic Path sends the information required for the requested operation over HTTPS to the Scenic Path backend. Depending on the action, this can include:

- start and destination coordinates;
- selected intermediate stops;
- place-search text;
- Scenic Categories and route preferences;
- vehicle profile/dimensions when needed for road-access restrictions.

The backend can use contracted/configured routing, mapping and place-data providers to answer the request.

The intended production design is request-oriented processing rather than creation of a personal route-history profile. Before publication, the publisher must confirm and document the actual production server/log retention period.

## 5. On-device data

The selected vehicle profile and related route-access settings are stored locally on the device so they can be reused for the next route calculation.

Scenic Path does not currently create a cloud user profile or require a Scenic Path account.

Android backup is disabled for the app release manifest.

## 6. Map, routing and POI providers

Scenic Path can use data/services from providers including:

- OpenStreetMap contributors;
- MapLibre for Android map rendering;
- a configured map-style/tile provider;
- configured routing/search providers on the Scenic Path backend;
- Google Places for verified food/place information when enabled on the production backend;
- other providers explicitly identified in the app or store documentation when configured.

Provider processing is governed by the applicable provider contracts and privacy terms. Production configuration must be reviewed before release so the final policy lists the providers actually used.

## 7. Advertising and profiling

The current Scenic Path release contains no advertising SDK and the Scenic Path application code does not use location or route requests to build advertising profiles.

## 8. Security

The Play release is configured to use HTTPS for the Scenic Path backend and production map style. Cleartext HTTP is disabled for release builds.

Provider credentials and upload-signing keys are not embedded in the Android application and must remain in backend/deployment or GitHub secret storage.

## 9. Retention

Scenic Path does not intentionally maintain a user-account route-history database in the current architecture.

Before the privacy policy is published, the publisher must insert the final retention statement for:

- production backend operational logs;
- routing/place-provider logs where contractually relevant;
- support communications, if any.

## 10. User rights

Depending on the user's jurisdiction, users may have rights regarding access, correction, deletion, restriction, portability or objection to processing of personal data.

Requests should be sent to the privacy contact listed above.

## 11. Children

Scenic Path is a general route/navigation utility and is not designed to solicit personal information from children. The final store target-audience declaration must match the Play Console configuration.

## 12. Changes

This policy may be updated when Scenic Path functionality, providers or legal requirements change. The public page should always show the current revision date.

---

## Release-owner checks before publishing this policy

- [ ] Insert legal publisher name, postal address and privacy contact.
- [ ] Confirm production backend logging/retention.
- [ ] Confirm production routing, search, tile and food providers.
- [ ] Confirm provider relationships for Google Play Data Safety (service provider vs. sharing).
- [ ] Publish this text at a stable public HTTPS URL.
- [ ] Set that URL as `SCENIC_PRIVACY_POLICY_URL` for the signed Play build and in Play Console.
