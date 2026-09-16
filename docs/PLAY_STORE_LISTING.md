# Scenic Path — Google Play listing draft

**Prepared for:** 0.7.0-rc3 / versionCode 42

## App identity

- **App name:** Scenic Path
- **Tagline:** The beautiful way
- **Package:** `cloud.kosch.scenicpath`
- **Play category:** Maps & Navigation
- **App or game:** App
- **Ads:** No
- **Account required:** No
- **Background location:** No

## English listing

### Short description

Scenic routes, smart stops and beautiful detours built around your time.

### Full description

**Scenic Path turns extra time into a better journey.**

Instead of asking only for the fastest way from A to B, Scenic Path searches for a route that fits the experience you want: beautiful roads, viewpoints, water, nature, castles, monuments, museums, parks, architecture, culture and worthwhile food stops.

Choose your destination, decide how much extra time you have and let Scenic Path build the journey around it.

**Plan around your time**
- Set an extra-time budget for the trip.
- Larger budgets expand the available scenic search space.
- Compare alternative journey styles instead of a single fixed route.

**Original multi-lane place search**
- Fast type-ahead and typo-tolerant place discovery.
- Explicit exact street + house-number lookup.
- Combined results are ranked and de-duplicated rather than depending on one provider response.

**Smart Stops**
- Discover scenic locations automatically along the journey.
- See included stops and optional alternatives directly on the map.
- Explore viewpoints, heritage, museums, nature, water, parks, art, historic places of worship, architecture and food.
- Add or remove stops before rebuilding the route.

**Scenic DNA**
Tell Scenic Path what matters to you. Adjust the balance between nature, water, mountains, viewpoints, culture, monuments, museums, art, architecture, parks, food and beautiful roads.

**Vehicle-aware routing**
Choose car, motorcycle, bicycle, camper, truck or coach. Where supported by available routing/map data, Scenic Path uses the selected vehicle profile and physical restrictions instead of treating every vehicle like a normal car.

**Map-first trip preview**
Routes and scenic locations stay visible while you adjust the next plan, so you can understand the journey before starting navigation.

**Privacy-minded by design**
- No Scenic Path account required.
- No advertising SDK.
- Foreground location only in this release.
- Location permission can be denied and is used for route/map/search/navigation functionality when granted.

Scenic Path is under active development. Route quality and POI completeness depend on available map/provider data. Always follow road signs, legal restrictions and real-world conditions over app guidance.

## German listing

### Kurzbeschreibung

Schöne Routen, Smart Stops und Umwege passend zu deinem Zeitbudget.

### Vollständige Beschreibung

**Scenic Path macht aus zusätzlicher Zeit eine bessere Reise.**

Statt nur nach dem schnellsten Weg von A nach B zu suchen, plant Scenic Path eine Route passend zum gewünschten Erlebnis: schöne Straßen, Aussichtspunkte, Wasser, Natur, Schlösser, Denkmäler, Museen, Parks, Architektur, Kultur und lohnenswerte Food-Stops.

Ziel wählen, zusätzliches Zeitbudget festlegen und Scenic Path baut die Reise darum herum.

**Planung nach Zeitbudget**
- Lege fest, wie viel zusätzliche Zeit die Fahrt kosten darf.
- Mit größerem Zeitbudget wächst auch der mögliche Scenic-Suchraum.
- Vergleiche unterschiedliche Reisevarianten statt nur einer Standardroute.

**Originale mehrstufige Suche**
- Schnelle Vorschläge und fehlertolerante Ortssuche.
- Explizite Suche nach Straße + Hausnummer.
- Ergebnisse verschiedener Suchwege werden zusammengeführt, gewichtet und von Dubletten bereinigt.

**Smart Stops**
- Sehenswürdigkeiten und besondere Orte werden automatisch entlang der Reise gesucht.
- Eingeplante Stopps und Alternativen erscheinen direkt auf der Karte.
- Kategorien umfassen Aussicht, Geschichte, Museen, Natur, Wasser, Parks, Kunst, historische Gotteshäuser, Architektur und Food.
- Stopps lassen sich vor der Neuberechnung ergänzen oder verändern.

**Scenic DNA**
Bestimme selbst, was eine schöne Fahrt für dich bedeutet: Natur, Wasser, Berge, Aussichtspunkte, Kultur, Denkmäler, Museen, Kunst, Architektur, Parks, Food und schöne Straßen können unterschiedlich gewichtet werden.

**Fahrzeugabhängige Routen**
Wähle Auto, Motorrad, Fahrrad, Camper, Lkw oder Reisebus. Wo Routing- und Kartendaten es erlauben, berücksichtigt Scenic Path das gewählte Fahrzeug und physische Beschränkungen, statt jedes Fahrzeug wie einen normalen Pkw zu behandeln.

**Kartenbasierte Reisevorschau**
Route und Scenic Locations bleiben sichtbar, während du die nächste Planung anpasst. So lässt sich die Fahrt vor dem Start visuell nachvollziehen.

**Datenschutzorientiert**
- Kein Scenic-Path-Konto erforderlich.
- Kein Werbe-SDK.
- In dieser Version nur Standortzugriff im Vordergrund.
- Die Standortberechtigung kann verweigert werden und wird bei Freigabe für Karten-, Such-, Routen- und Navigationsfunktionen genutzt.

Scenic Path wird aktiv weiterentwickelt. Qualität und Vollständigkeit von Routen und POIs hängen von verfügbaren Karten- und Providerdaten ab. Verkehrszeichen, gesetzliche Einschränkungen und reale Straßenbedingungen haben immer Vorrang vor App-Hinweisen.

## Play Console declarations — starting point

These must be confirmed in the actual Play Console against the final signed AAB:

- **App or game:** App
- **Category:** Maps & Navigation
- **Contains ads:** No
- **App access:** No login/restricted account required
- **Target audience:** General navigation audience; do not include children unless the final product/marketing intentionally targets them
- **Content rating:** Complete the IARC questionnaire; do not self-assign a rating
- **Government app:** No
- **News app:** No
- **Health app:** No
- **Background location:** No
- **Data Safety:** use `PLAY_DATA_SAFETY.md`; location/search data is transmitted for app functionality and current RC still contains direct third-party OSM provider lanes

## Core graphics

Google Play current core requirements:

### Store icon

- 512 × 512 px
- 32-bit PNG with alpha
- <= 1,024 KB

Prepared asset:

- `scenic-path-play-icon-512.png`
- 512 × 512 RGBA
- derived from the canonical Scenic Path launcher emblem

### Feature graphic

- 1,024 × 500 px
- JPEG or 24-bit PNG without alpha

Prepared asset:

- `scenic-path-feature-1024x500.png`
- 1024 × 500 RGB PNG
- based on the corrected Scenic Path wordmark

## Screenshots still required

Use **real screenshots from 0.7.0-rc3 or the exact signed Play RC**. Do not use mockups that show UI/features not present in the uploaded bundle.

Recommended phone screenshot sequence:

1. Start + destination search over the map, with the time-budget control visible.
2. Generated Scenic Path route with diverse POI markers.
3. Smart Stops / multiple categories around the route.
4. Scenic DNA / category preferences.
5. Vehicle profile selection.
6. Live foreground navigation with route progress / next scenic stop.
7. Exact street + house-number search result.
8. Route alternative comparison / ScenicScore if visually available.

Use localized screenshots for German and English if practical.

## Reviewer notes draft

Scenic Path is a map/navigation utility. No account or login is required. The app requests foreground coarse/precise location only. Location can be denied; manually selected start/destination points remain available. The app does not request background location and this release only offers on-screen navigation while the app is visible. No advertising SDK is included.

For functional review, grant foreground location when prompted, or manually choose a start and destination. Set an extra-time budget and calculate a route. Scenic POIs/Smart Stops appear around the route. Exact address search is triggered by the explicit Search action rather than every keystroke.

## Release notes draft — 0.7.0-rc3

**English**

Full-search Play release candidate: original multi-lane place/address search, exact street + house-number lookup, improved POI discovery, ScenicScore route planning, Smart Stops, vehicle-aware routing, foreground navigation and corrected Scenic Path branding.

**Deutsch**

Play-Release-Candidate mit vollständiger Suche: mehrstufige Orts-/Adresssuche, exakte Straße-und-Hausnummer-Suche, erweiterte POI-Erkennung, ScenicScore-Routenplanung, Smart Stops, fahrzeugabhängiges Routing, Vordergrundnavigation und korrigiertes Scenic-Path-Branding.

## Final listing blockers

- [ ] final public privacy-policy HTTPS URL
- [ ] final support/contact email/website shown in Play Console
- [ ] real phone screenshots from the exact RC
- [ ] IARC questionnaire completed
- [ ] target-audience declaration completed
- [ ] Data Safety reviewed against production provider architecture
- [ ] developer identity / package registration confirmed in Play Console
