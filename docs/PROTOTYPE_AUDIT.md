# Prototype audit

The supplied WebSim prototype is a strong product demo, but it is not yet a production navigation engine.

## What is already good
- OSM/Leaflet map foundation.
- Start/destination + custom intermediate stops.
- Live browser geolocation and rerouting concepts.
- Scenic POIs from Overpass.
- Multiple route variants, itinerary UI, stop metadata and spoken guidance concepts.
- Category toggles and an explicit exploration-time budget.

## What must change for production
1. **Scenic routing is currently POI insertion, not road-level beauty optimization.** The code ranks nearby attractions and sends them as OSRM waypoints. The road graph itself is not scored for forests, water, mountains, quietness, views or cultural density.
2. **Public Nominatim is used for autocomplete.** The current OSMF public Nominatim usage policy explicitly disallows client-side autocomplete.
3. **Public OSRM demo routing is not a production SLA.** A Play Store app needs a contracted or self-hosted routing service with monitoring and a switchable provider.
4. **Food quality is not actually verified.** OSM data does not provide dependable consumer ratings. Production food recommendations need a review/rating provider and strict rating-count thresholds.
5. **The app must become native Android.** GPS lifecycle, permissions, background/foreground navigation, audio focus, notifications, offline behavior, power management and Android 16 edge-to-edge all need first-class handling.
6. **Provider keys must not be shipped as reusable secrets.** Route orchestration and Places queries belong behind a backend/proxy.

## Product decision
The WebSim prototype is preserved in `/prototype` as UX/reference material. The Android app is a clean implementation, not a wrapper.
