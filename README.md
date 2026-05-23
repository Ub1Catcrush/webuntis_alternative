# WebUntis Dashboard – Android (Kotlin)

Native Kotlin Android App für WebUntis-Schüler und Eltern mit Stundenplan, Hausaufgaben, Nachrichten, Abwesenheiten, Klassenbuch und Terminen.

---

## Voraussetzungen

| Tool | Version |
|---|---|
| Android Studio | Ladybug+ |
| JDK | 17+ |
| Android SDK | API 26+ (minSdk) / API 35 (compileSdk) |
| Kotlin | 2.1.20 |
| Gradle | 8.9+ |

---

## Projekt öffnen

1. Repository klonen oder ZIP entpacken
2. Android Studio → **Open** → Projektordner wählen
3. Gradle sync abwarten
4. Gerät / Emulator auswählen → **▶ Run**

---

## Anmeldung

| Feld | Beispiel |
|---|---|
| Server | `meine-schule.webuntis.com` |
| Schul-Kurzname | steht in der WebUntis-URL nach `/WebUntis/` |
| Benutzername | dein WebUntis-Login |
| Passwort | dein WebUntis-Passwort |

> **Hinweis:** Falls deine Schule SSO (Microsoft, Google, Schulportal) nutzt, werden trotzdem direkte WebUntis-Zugangsdaten benötigt. Diese sind separat über die WebUntis-Webseite einzurichten.

Zugangsdaten werden mit **EncryptedSharedPreferences** (AES-256) lokal gespeichert und verlassen das Gerät nicht.

### Multi-Account Support

In den Einstellungen kann ein zweiter Account (z. B. für ein zweites Kind oder als Elternteil zusätzlich zum Schüler-Account) hinterlegt werden. Die Nachrichten beider Accounts werden in einem gemeinsamen Posteingang zusammengeführt.

---

## Screens & Funktionen

| Screen | Beschreibung |
|---|---|
| **Stundenplan** | Konfigurierbare Anzahl Schultage (1–20) mit Vertretungs-Info, Unterrichtsinhalt, Lehrer-Notizen und farbigen Status-Badges. |
| **Hausaufgaben** | Liste mit Abhak-Funktion, Fälligkeits-Ampel, Fachfarben und **Anhang-Download**. |
| **Nachrichten** | Posteingang inkl. **Anhang-Download**, Anzeige des **Nachrichtenverlaufs (Reply History)** und Account-Labeling. |
| **Abwesenheiten** | Übersicht aller Fehlzeiten des aktuellen Schuljahres mit Entschuldigungs-Status. |
| **Klassenbuch** | Einträge der letzten 30 Tage (Lob, Tadel, Hausaufgaben-Vergessen etc.) inkl. Typ-Kategorisierung. |
| **Termine** | Kombinierte Ansicht aus Prüfungen (Klassenarbeiten/Tests) und allgemeinen Schulereignissen der nächsten 90 Tage. |

---

## Architektur

```
app/
├── api/
│   ├── WebUntisService.kt       # Retrofit Interface (JSON-RPC, REST v1/v2, S3-Download)
│   ├── WebUntisRepository.kt    # Zentrale Datenlogik, Caching & Multi-Account-Merging
│   ├── SessionManager.kt        # Verschlüsselte Session & Präferenzen
│   ├── RetrofitFactory.kt       # Dynamische Base-URL & Interceptor-Setup
│   └── NetworkModule.kt         # Hilt DI, Cookie-Handling (Android 15 Fix)
├── model/
│   └── Models.kt                # GSON-kompatible Datenklassen für alle API-Versionen
└── ui/
    ├── login/                   # Login-Flow & Validierung
    ├── timetable/               # Stundenplan (ViewPager2 + Detail-Enrichment)
    ├── homework/                # Hausaufgaben inkl. Datei-Handling
    ├── messages/                # Nachrichten, Anhänge & History
    ├── absences/                # Abwesenheiten-Liste
    ├── events/                  # Termine & Prüfungen
    ├── classbook/               # Klassenbuch-Einträge
    └── settings/                # Account-Verwaltung & App-Konfiguration
```

**Stack:** MVVM · Hilt DI · Retrofit2 · OkHttp3 · Coroutines/Flow · Navigation Component · Material 3 · ViewBinding · DataStore

---

## Features

- ✅ **Material 3 Design:** Volle Unterstützung für Light + Dark Mode.
- ✅ **Sicherheit:** AES-256 verschlüsselte Speicherung der Zugangsdaten.
- ✅ **Multi-Account:** Nachrichten-Aggregation von zwei verschiedenen WebUntis-Profilen.
- ✅ **Intelligenter Stundenplan:**
    - Einstellbare Tagesanzahl (1–20).
    - Automatische Anreicherung mit Detail-Infos (Lehrstoff, Notizen).
    - Visualisierung von Vertretungen (durchgestrichene Lehrer, Fachwechsel).
    - Status-Badges: Ausfall, Vertretung, Zusatz, Prüfung.
- ✅ **Hausaufgaben-Management:**
    - Lokaler Abhak-Status (Session-basiert).
    - **Neu:** Download von Anhängen direkt aus der Hausaufgabe.
- ✅ **Verbesserte Nachrichten:**
    - Download von Anhängen (inkl. Presigned S3-URL Handling).
    - Anzeige von vorherigen Nachrichten im Verlauf.
- ✅ **Eltern-Support:** Automatische Ermittlung der Schüler-ID (Priming) bei Eltern-Accounts.
- ✅ **Performance:** In-Memory Caching mit intelligentem TTL-Management (Time-to-Live).
- ✅ **Robustheit:** Automatischer Silent-Re-Login bei abgelaufenen Sessions.

---

## Bekannte Einschränkungen

- Hausaufgaben-Abhakstatus ist zurzeit nicht persistent (wird bei App-Neustart zurückgesetzt).
- Die WebUntis-API ist inoffiziell; Änderungen serverseitig können Funktionen beeinträchtigen.

---

## Kompatibilität

Optimiert für moderne Android-Versionen (getestet bis Android 15/17). Enthält spezifische Fixes für das Cookie-Handling unter Android 15 (`Secure`-Flag Problem), um 403-Fehler bei Cross-API Aufrufen zu vermeiden.

---

## Build

```bash
./gradlew assembleDebug
```
Die aktuelle Version ist **v0.0.9** (definiert in `dependencies.gradle`).
