# Datenschutz, KI- & Drittanbietertransparenz — Scenic Path

**Stand:** 1. Oktober 2026  
**App:** Scenic Path 0.7.3-rc4 (`cloud.kosch.scenicpath`)  
**Projekt:** https://github.com/chekento/scenic-path-android  
**Kontakt / Anbieterinformationen:** https://kosch.cloud

Diese Seite beschreibt den aktuellen technischen Datenschutz- und Drittanbieterstand der Scenic-Path-RC. Sie ersetzt die noch zu finalisierende Play-Store-Datenschutzerklärung für eine spätere Produktionsveröffentlichung.

## 1. Kurzfassung

Scenic Path ist eine Karten-, Routen- und POI-App. Für diese Funktionen müssen je nach Aktion Suchtexte, Kartenbereiche und Koordinaten an externe Karten-/Routingdienste oder einen konfigurierten Scenic-Path-Backenddienst übertragen werden.

- Kein Scenic-Path-Benutzerkonto in der aktuellen RC.
- Kein Werbe-SDK.
- Kein eigenes Analytics-/Tracking-SDK.
- Standort ist optional und wird nur im Vordergrund angefordert.
- Keine `ACCESS_BACKGROUND_LOCATION`-Berechtigung.
- Produktionskonfigurationen sollen HTTPS verwenden.
- Routen- und Scenic-Bewertungen sind algorithmisch; die aktuelle App führt **keine generative KI und kein ML-Modell** aus.

## 2. Android-Berechtigungen

Die aktuelle App deklariert:

- `INTERNET` — Karten, Suche, Routing, POIs und Backend-Kommunikation.
- `ACCESS_NETWORK_STATE` — Prüfung des Netzwerkstatus.
- `ACCESS_COARSE_LOCATION` — optionaler ungefährer Standort.
- `ACCESS_FINE_LOCATION` — optionaler genauer Vordergrundstandort.

Die App fordert keine Hintergrundstandortberechtigung.

## 3. Standort- und Routendaten

Je nach Funktion können verarbeitet oder übertragen werden:

- aktueller ungefährer oder genauer Standort,
- Start- und Zielkoordinaten,
- Zwischenstopps,
- Suchtexte,
- kartensichtbarer Ausschnitt / Bounding Box,
- Routenkorridore,
- Scenic-Kategorien und Präferenzen,
- Fahrzeugprofil bzw. relevante Maße für Straßeneignung.

Diese Daten sind erforderlich, damit Karten-, Such-, Routing- und POI-Dienste Ergebnisse liefern können. Externe Anbieter erhalten außerdem technisch notwendige Verbindungsdaten wie IP-Adresse, Zeitpunkt und Clientinformationen.

## 4. KI-Transparenz

### Keine generative KI in der aktuellen App

Scenic Path verwendet derzeit **kein LLM, keinen Chatbot, kein generatives Modell und kein lokales neuronales Netz** zur Routenentscheidung.

Begriffe wie **ScenicScore**, POI-Relevanz, Route Character, Smart Stops oder Experience Score bezeichnen algorithmische Bewertungs- und Heuristiklogik. Diese Werte werden aus Routen-, POI-, Entfernungs-, Kategorie- und Präferenzdaten berechnet. Sie sind **keine KI-generierten Tatsachenbehauptungen**.

Daraus folgt insbesondere:

- keine Prompts an OpenAI/ChatGPT;
- keine Übertragung von Routen an ein LLM;
- kein automatisiertes Persönlichkeitsprofil;
- keine biometrische Verarbeitung;
- keine KI-Trainingsnutzung von Standortdaten durch Scenic Path selbst.

## 5. Externe Dienste und Datenquellen

Je nach Build, Modus und Konfiguration können folgende Drittanbieter beteiligt sein:

| Dienst / Komponente | Zweck | Mögliche übertragene Daten |
|---|---|---|
| **OpenStreetMap** | Karten-/POI-Datenbasis | Kartenbereich, Objekt-/POI-Abfragen |
| **Photon / komoot public instance** | Type-ahead und Ortssuche in Entwicklungs-/RC-Pfaden | Suchtext, optional Standortbias |
| **Nominatim** | ausdrücklich ausgelöste Adresssuche | Suchtext, Sprache, optional Viewbox/Bias |
| **Overpass-kompatible Dienste** | POI- und Routenkorridor-Suche | geografische BBox / Korridor |
| **OpenFreeMap** bzw. konfigurierte Kartenquelle | Kartenstil/Kacheln | sichtbarer Kartenbereich, technische Verbindungsdaten |
| **Valhalla / FOSSGIS OSRM** | Entwicklungs-/Fallback-Routing | Start, Ziel, Zwischenpunkte |
| **Scenic Path Backend** | Produktionssuche und Routenplanung, sofern konfiguriert | Suchtext, Koordinaten, Stops, Präferenzen |
| **TomTom** | Backend-Routing/Suche, sofern konfiguriert | routing-/suchrelevante Daten |
| **Foursquare** | optionale Food-/Place-Details, sofern konfiguriert | Orts-/POI-Abfragen |
| **Android Geocoder** | geräteabhängige Ortssuche | Verhalten hängt vom Geräte-/Systemanbieter ab |
| **Google Play Services Location** | Android-Standortbereitstellung | Standortverarbeitung nach Android-/Google-Systemkonfiguration |

Nicht jeder Anbieter wird in jedem Lauf oder jedem Build verwendet. Der ausgelieferte RC enthält mehrere Such-/Fallback-Pfade; der jeweils gewählte Pfad bestimmt die konkrete Datenübertragung.

## 6. Drittanbieter-Bibliotheken und Werkzeuge

### App-Laufzeit

- **Jetpack Compose / AndroidX** — Benutzeroberfläche und Lifecycle.
- **MapLibre Android SDK** — Kartenrendering.
- **Google Play Services Location** — Standortzugriff.
- **Kotlin Coroutines** — asynchrone Verarbeitung.

### Build und Distribution

- **Gradle**
- **Android SDK 36**
- **JDK 17**
- **GitHub / GitHub Actions / GitHub Releases**

Diese Build-Werkzeuge sind keine Tracking-SDKs in der App.

## 7. Lokale Daten

Scenic Path kann Einstellungen und für die Routenfunktion relevante Konfigurationen lokal speichern, z. B. Scenic-Präferenzen oder Fahrzeugprofil. Die aktuelle RC erfordert kein Cloudkonto und führt kein Scenic-Path-Benutzerprofil.

Android-Backup ist im aktuellen Manifest deaktiviert.

## 8. Anbieterprotokolle und Aufbewahrung

Scenic Path kontrolliert nicht die Serverlogs externer Karten-, Routing-, Such- oder Hostinganbieter. Solche Anbieter können IP-Adresse, Zeitstempel, angeforderte URLs, Koordinaten oder Suchparameter nach ihren eigenen Datenschutz- und Missbrauchsschutzregeln protokollieren.

Für einen späteren Produktions-Play-Release müssen die tatsächlich verwendeten Produktionsanbieter, Verträge und Aufbewahrungsfristen final bestätigt werden.

## 9. Zertifikate, Signierung und HTTPS

Die aktuell verteilte GitHub-RC ist ein **Test-/Debug-Build**. Das Repository definiert eine öffentliche Test-Signieridentität für Debug-Builds. Eine spätere Play-/Produktionsversion soll mit einer separaten, außerhalb des öffentlichen Repositorys gehaltenen Upload-Signieridentität erstellt werden.

Die APK-Signatur ist keine unabhängige Sicherheits- oder Datenschutz-Zertifizierung. SHA-256-Prüfsummen dienen ausschließlich der Integritätsprüfung.

Für Produktions-Builds ist Klartextverkehr deaktiviert; Backend- und Kartenkonfiguration sollen HTTPS verwenden. Scenic Path installiert keine eigene Root-CA und verlangt keine Benutzerzertifikate.

## 10. Keine Werbung / kein Nutzerprofiling

Die aktuelle RC enthält kein Werbe-SDK und keine eigene Analytics-Plattform. Scenic Path ist nicht darauf ausgelegt, Standort- oder Routendaten für Werbeprofile zu verwenden.

## 11. Löschung und Kontrolle

Standortberechtigungen können jederzeit in Android entzogen werden. Lokale App-Daten lassen sich über Androids App-Speicherverwaltung oder durch Deinstallation entfernen. Daten, die bei externen Diensten in Serverlogs entstanden sind, unterliegen den Regeln des jeweiligen Anbieters.

## 12. Änderungen

Wenn Produktionsanbieter, KI/ML-Funktionen, Konten, Analytics, Cloud-Synchronisation oder Berechtigungen ergänzt werden, muss diese Datei vor Veröffentlichung aktualisiert werden.

---

**Weiterführender Play-Entwurf:** [docs/PRIVACY_POLICY_DRAFT.md](docs/PRIVACY_POLICY_DRAFT.md)  
**Repository:** https://github.com/chekento/scenic-path-android  
**Anbieter / Kontakt:** https://kosch.cloud
