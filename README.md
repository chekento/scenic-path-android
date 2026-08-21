# Scenic Path — The Beautiful Way Finder

**Scenic Path** is a native Android navigation project that optimizes for the *quality of the journey* rather than only time or distance.

The supplied WebSim prototype is preserved as product inspiration. This repository starts a clean Play-Store-oriented implementation for Android 16.

## Product idea
Choose what “beautiful” means for this trip:

- beautiful / quiet / winding roads
- forests and protected landscapes
- lakes, rivers and coastline
- mountains, relief and viewpoints
- historic sights and monuments
- museums, galleries and architecture
- parks and gardens
- carefully selected food stops

The user also defines a detour budget. Scenic Path then ranks candidate routes by a **ScenicScore** and refuses routes that exceed the configured extra time/percentage.

## Why this is different
The old prototype adds scenic POIs to an otherwise conventional route. The new architecture scores the **route corridor itself** and treats POIs as optional experience anchors.

## Status
**M0 / foundation**

- Android package `cloud.kosch.scenicpath`
- API 36 target
- Kotlin + Jetpack Compose UI foundation
- MapLibre Native map foundation
- scenic preference model
- provider-neutral ScenicScore with tests
- TomTom thrilling-route adapter on the backend
- backend boundary so routing/Places secrets never need to live in the APK
- CI workflows for Android build and backend tests
- prototype audit and Play Store checklist

Next milestone M1 connects address search, live location, route rendering, landscape/culture enrichment and strict food ranking.

## Development
### Android
1. Copy `local.properties.example` to `local.properties`.
2. Start the backend (below).
3. Open the project in Android Studio and run the `app` configuration.

The default map style is MapLibre's demo style **for development only**. Configure a production map provider before distribution.

### Backend
```bash
cd backend
cp .env.example .env
# Export values from .env in your preferred runtime/deployment environment.
TOMTOM_API_KEY=... npm start
```

Health check: `GET /health`

Route endpoint: `POST /v1/plan`

## Security
Do not put reusable TomTom/Google Places/server keys in the Android repository. The app calls the Scenic Path backend; provider credentials remain server-side.

## Repository naming
Recommended GitHub repository: **`scenic-path-android`**.

## License
No open-source license has been selected yet. Until the owner chooses one, normal copyright rules apply.
