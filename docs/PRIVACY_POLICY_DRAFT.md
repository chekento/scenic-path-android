# Scenic Path — Privacy Policy (Play release draft)

**Last updated:** 2026-09-16

> This is the source draft for the public Google Play privacy-policy page.
> It is **not yet publication-ready** because legal publisher/contact details, production provider contracts and retention values still have to be confirmed.

## 1. Publisher / controller

Scenic Path is provided by:

- **Publisher:** `[legal publisher name matching the Play Console developer identity]`
- **Address:** `[publisher postal address]`
- **Privacy contact:** `[privacy email address]`

## 2. What Scenic Path does

Scenic Path is a route-planning and on-screen navigation app that uses the user's route, available extra time and selected Scenic Categories to propose alternative routes, scenic locations and optional stops.

The current Play release candidate does **not** require a Scenic Path account and does **not** include an advertising SDK.

## 3. Location data

Scenic Path can request Android's approximate and precise foreground-location permissions.

Location can be used for:

- Current Location as a route start;
- route planning and rerouting;
- showing the user's position on the map;
- location-biased place/address search;
- discovering scenic locations around the route corridor;
- on-screen navigation while Scenic Path is visible.

The current Play release candidate does **not** request `ACCESS_BACKGROUND_LOCATION` and does not claim always-on/background navigation.

Users can deny location permission and use manually selected route locations where the relevant workflow supports it.

## 4. Search, route and place requests

To provide search and routing functionality, Scenic Path can transmit information needed for the requested operation. Depending on the action, this can include:

- search/address text entered by the user;
- approximate or precise current-location coordinates used as search bias;
- start and destination coordinates;
- selected intermediate stops;
- route-derived geographic bounding boxes used to find points of interest;
- Scenic Categories and route preferences;
- vehicle profile/dimensions when needed for road-access restrictions.

Production route planning sends the necessary request data over HTTPS to the configured Scenic Path backend.

The original 0.7 search/discovery stack also contains direct Android requests to OpenStreetMap-based services:

- Photon for type-ahead and category/corridor discovery;
- Nominatim for explicit street + house-number search;
- Overpass-compatible endpoints for route-corridor POI discovery;
- the configured map-style/tile provider for map rendering.

For production distribution, these direct provider paths must either be deliberately retained with appropriate provider terms/capacity and disclosed here, or be moved behind controlled/self-hosted/contracted infrastructure. The final published policy must describe the architecture actually shipped in the signed AAB.

## 5. On-device data

Vehicle profile and related route-access/settings data can be stored locally on the device so they can be reused for later route calculations.

Scenic Path does not currently create a cloud user profile or require a Scenic Path account.

Android backup is disabled for the release manifest.

## 6. Service providers and data sources

Depending on the final production configuration, Scenic Path can use services/data from providers including:

- OpenStreetMap contributors and OSM-compatible services;
- Photon for search/discovery;
- Nominatim for explicit address search;
- Overpass-compatible POI/corridor services;
- MapLibre for Android map rendering;
- a configured map-style/tile provider;
- TomTom for backend routing/search when configured;
- Foursquare for food/place details or ratings when configured;
- the Scenic Path backend/hosting provider.

Provider processing is governed by the applicable provider terms and privacy arrangements. The final public policy must list the providers actually used by the production AAB/backend and describe relevant data transfers.

## 7. Advertising and profiling

The current Scenic Path Android release contains no advertising SDK. Scenic Path application code is not designed to use location, route or search requests to build advertising profiles.

## 8. Security

The Play release requires HTTPS for the configured Scenic Path backend and production map-style URL. Cleartext traffic is disabled for release builds.

Provider credentials and upload-signing keys are not intended to be embedded in the Android application. They must remain in backend/deployment or protected CI secret storage.

## 9. Retention

Scenic Path does not intentionally maintain a user-account route-history database in the current architecture.

Before publication, insert final retention information for:

- Scenic Path production backend operational/access logs;
- routing/search/place/tile provider logs where applicable;
- any retained support communications;
- deletion/erasure procedures for data that can be associated with a user.

Do not describe processing as ephemeral unless the production backend and relevant providers actually meet that definition.

## 10. User rights

Depending on the user's jurisdiction, users may have rights regarding access, correction, deletion, restriction, portability or objection to processing of personal data.

Requests should be sent to the privacy contact listed above. The final policy should include the applicable controller identity and contact route required by the publisher's jurisdiction.

## 11. Children / target audience

Scenic Path is a general route/navigation utility and is not designed to solicit personal information from children. The final Play Console target-audience declaration and store listing must match the intended audience and the app's actual content.

## 12. Changes

This policy may be updated when Scenic Path functionality, providers or legal requirements change. The public page should always show the current revision date.

---

## Release-owner checks before publishing this policy

- [ ] Insert legal publisher name, postal address and privacy contact matching Play Console identity.
- [ ] Confirm production backend logging/retention and deletion procedure.
- [ ] Confirm every production routing/search/tile/POI/food provider.
- [ ] Decide whether direct Photon/Nominatim/Overpass requests remain in the production AAB.
- [ ] Confirm provider relationships for Google Play Data Safety (service provider vs. sharing).
- [ ] Publish as a normal read-only HTTPS webpage (not a PDF).
- [ ] Set the same URL as `SCENIC_PRIVACY_POLICY_URL` for the signed Play build and in Play Console.
- [ ] Verify the in-app privacy link opens the final public page.
