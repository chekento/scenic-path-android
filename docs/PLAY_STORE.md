# Play Store release checklist

## Fixed now
- Application ID: `cloud.kosch.scenicpath`
- App name: **Scenic Path**
- Target SDK: **36 (Android 16)**
- Minimum SDK: 26
- Native Android project; no WebView wrapper

## Before closed/open testing
- Replace demo map style with a production map/tile provider and verify attribution/license.
- Deploy backend and set `SCENIC_API_BASE_URL`.
- Configure routing and Places secrets only on backend.
- Add real location tracking + navigation foreground service.
- Add in-app privacy page and public privacy-policy URL.
- Complete Play Data Safety declarations for precise/approximate location and network providers.
- Add Terms/attributions for OSM/MapLibre/TomTom/Google Places as applicable.
- Add release signing through encrypted GitHub/Play secrets; never commit the keystore.
- Test Android 16 edge-to-edge, permission denial, GPS loss, offline/no-service, route deviation and battery use.
- Generate AAB and upload to internal testing first.
