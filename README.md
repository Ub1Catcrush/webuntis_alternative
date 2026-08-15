# WebUntis Dashboard – Android (Kotlin)

> **[Deutsch](#deutsch)** | **[English](#english)**

---

<a name="deutsch"></a>
## Deutsch

Native Kotlin Android-App für WebUntis-Schüler und Eltern mit Stundenplan, Hausaufgaben, Nachrichten, Abwesenheiten, Klassenbuch und Terminen.

### Voraussetzungen

| Tool | Version |
|---|---|
| Android Studio | Ladybug+ |
| JDK | 17+ |
| Android SDK | API 26+ (minSdk) / API 35 (compileSdk) |
| Kotlin | 2.1.20 |
| Gradle | 8.9+ |

### Projekt öffnen

1. Repository klonen oder ZIP entpacken
2. Android Studio → **Open** → Projektordner wählen
3. Gradle-Sync abwarten
4. Gerät / Emulator auswählen → **▶ Run**

### Anmeldung

| Feld | Beispiel |
|---|---|
| Server | `meine-schule.webuntis.com` |
| Schul-Kurzname | steht in der WebUntis-URL nach `/WebUntis/` |
| Benutzername | dein WebUntis-Login |
| Passwort | dein WebUntis-Passwort |

> **Hinweis:** Falls deine Schule SSO (Microsoft, Google, Schulportal) nutzt, werden trotzdem direkte WebUntis-Zugangsdaten benötigt. Diese sind separat über die WebUntis-Webseite einzurichten.

Zugangsdaten werden mit **EncryptedSharedPreferences** (AES-256) lokal gespeichert und verlassen das Gerät nicht.

#### Multi-Account

In den Einstellungen kann ein zweiter Account (z. B. für ein zweites Kind oder als Elternteil zusätzlich zum Schüler-Account) hinterlegt werden. Die Nachrichten beider Accounts werden in einem gemeinsamen Posteingang zusammengeführt.

### Screens & Funktionen

| Screen | Beschreibung |
|---|---|
| **Stundenplan** | Konfigurierbare Anzahl Schultage (1–20) mit Vertretungs-Info, Unterrichtsinhalt, Lehrer-Notizen und farbigen Status-Badges. Umschaltbar zwischen **eigenem Plan**, **Klassenstundenplan** (kompletter Plan der eigenen Klasse) und **kombiniertem Stundenplan** (eigener Plan, in dem freie Stunden mit frei wählbaren Klassenfächern aufgefüllt werden). |
| **Hausaufgaben** | Liste mit Abhak-Funktion, Fälligkeits-Ampel, Fachfarben und Anhang-Download. |
| **Nachrichten** | Posteingang inkl. Anhang-Download, Anzeige des Nachrichtenverlaufs und Account-Labeling. |
| **Abwesenheiten** | Übersicht aller Fehlzeiten des aktuellen Schuljahres mit Entschuldigungs-Status und **Filter nach Status**. |
| **Klassenbuch** | Einträge der letzten 30 Tage (Lob, Tadel, Hausaufgaben-Vergessen etc.) inkl. Typ-Kategorisierung. |
| **Termine** | Prüfungen und Schulereignisse – standardmäßig nächste 90 Tage, optional **inkl. vergangene Termine**. |

### Features

- ✅ **Material 3 Design:** Volle Unterstützung für Light + Dark Mode.
- ✅ **Zweisprachig:** Vollständige Lokalisierung auf Deutsch und Englisch.
- ✅ **Sicherheit:** AES-256 verschlüsselte Speicherung der Zugangsdaten.
- ✅ **Multi-Account:** Nachrichten-Aggregation von zwei verschiedenen WebUntis-Profilen.
- ✅ **Intelligenter Stundenplan:** Einstellbare Tagesanzahl, Vertretungsvisualisierung, Status-Badges (Ausfall, Vertretung, Zusatz, Prüfung).
- ✅ **Klassenstundenplan:** Umschalten auf den vollständigen Plan der eigenen Klasse statt nur der eigenen Fächer.
- ✅ **Kombinierter Stundenplan:** Frei wählbare Klassenfächer (mit ausgeschriebenem Namen + Kürzel zur besseren Unterscheidung) werden nur in freie Stunden des persönlichen Plans eingeblendet.
- ✅ **Hausaufgaben:** Lokaler Abhak-Status, Anhang-Download.
- ✅ **Nachrichten:** Anhang-Download (inkl. S3-URL Handling), Verlaufsanzeige, Entwürfe.
- ✅ **Abwesenheiten:** Filter nach Entschuldigungs-Status (alle / einzelne Stati).
- ✅ **Termine:** Toggle zum Einblenden vergangener Termine (bis 365 Tage zurück).
- ✅ **Eltern-Support:** Automatische Ermittlung der Schüler-ID bei Eltern-Accounts.
- ✅ **Performance:** In-Memory-Caching mit konfigurierbareMTTL (0–60 Minuten).
- ✅ **Robustheit:** Automatischer Silent-Re-Login bei abgelaufenen Sessions.
- ✅ **Auto-Update:** In-App Update-Check und Installation per GitHub Releases.

### Architektur

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
    ├── absences/                # Abwesenheiten mit Status-Filter
    ├── events/                  # Termine & Prüfungen (inkl. vergangene)
    ├── classbook/               # Klassenbuch-Einträge
    └── settings/                # Account-Verwaltung & App-Konfiguration
```

**Stack:** MVVM · Hilt DI · Retrofit2 · OkHttp3 · Coroutines/Flow · Navigation Component · Material 3 · ViewBinding · DataStore

### Bekannte Einschränkungen

- Hausaufgaben-Abhakstatus ist nicht persistent (wird bei App-Neustart zurückgesetzt).
- Die WebUntis-API ist inoffiziell; serverseitige Änderungen können Funktionen beeinträchtigen.

### Kompatibilität

Optimiert für moderne Android-Versionen (getestet bis Android 15/API 35). Enthält spezifische Fixes für das Cookie-Handling unter Android 15 (`Secure`-Flag Problem).

### Build

```bash
./gradlew assembleDebug
```

---

<a name="english"></a>
## English

Native Kotlin Android app for WebUntis students and parents with timetable, homework, messages, absences, class register and events.

### Requirements

| Tool | Version |
|---|---|
| Android Studio | Ladybug+ |
| JDK | 17+ |
| Android SDK | API 26+ (minSdk) / API 35 (compileSdk) |
| Kotlin | 2.1.20 |
| Gradle | 8.9+ |

### Opening the project

1. Clone the repository or extract the ZIP
2. Android Studio → **Open** → select project folder
3. Wait for Gradle sync
4. Select device / emulator → **▶ Run**

### Sign-in

| Field | Example |
|---|---|
| Server | `my-school.webuntis.com` |
| School short name | found in the WebUntis URL after `/WebUntis/` |
| Username | your WebUntis login |
| Password | your WebUntis password |

> **Note:** If your school uses SSO (Microsoft, Google, school portal), you still need separate direct WebUntis credentials, set up via the WebUntis website.

Credentials are stored locally with **EncryptedSharedPreferences** (AES-256) and never leave the device.

#### Multi-account

A second account (e.g. for a second child, or a parent account alongside a student account) can be configured in Settings. Messages from both accounts are merged into a single inbox.

### Screens & Features

| Screen | Description |
|---|---|
| **Timetable** | Configurable number of school days (1–20) with substitution info, lesson content, teacher notes and colour-coded status badges. Switchable between **personal plan**, **class timetable** (the full plan of your own class) and **combined timetable** (your personal plan with freely selectable class subjects filled into free periods). |
| **Homework** | List with check-off function, due-date traffic light, subject colours and attachment download. |
| **Messages** | Inbox incl. attachment download, message history display and account labelling. |
| **Absences** | Overview of all absences for the current school year with excuse status and **filter by status**. |
| **Class register** | Entries from the last 30 days (praise, reprimands, forgotten homework, etc.) incl. type categorisation. |
| **Events** | Exams and school events – default next 90 days, optionally **including past events**. |

### Features

- ✅ **Material 3 Design:** Full Light + Dark mode support.
- ✅ **Bilingual:** Full localisation in German and English.
- ✅ **Security:** AES-256 encrypted storage of credentials.
- ✅ **Multi-account:** Message aggregation from two WebUntis profiles.
- ✅ **Smart timetable:** Configurable day count, substitution visualisation, status badges (cancelled, substitution, extra, exam).
- ✅ **Class timetable:** Switch to the whole class's schedule instead of just your own subjects.
- ✅ **Combined timetable:** Freely selectable class subjects (shown with their full name + abbreviation to avoid ambiguity) are filled into free periods of your personal plan.
- ✅ **Homework:** Local check-off state, attachment download.
- ✅ **Messages:** Attachment download (incl. S3 URL handling), history view, drafts.
- ✅ **Absences:** Filter by excuse status (all / individual statuses).
- ✅ **Events:** Toggle to include past events (up to 365 days back).
- ✅ **Parent support:** Automatic student ID resolution for parent accounts.
- ✅ **Performance:** In-memory caching with configurable TTL (0–60 minutes).
- ✅ **Resilience:** Automatic silent re-login on expired sessions.
- ✅ **Auto-update:** In-app update check and installation via GitHub Releases.

### Architecture

```
app/
├── api/
│   ├── WebUntisService.kt       # Retrofit interface (JSON-RPC, REST v1/v2, S3 download)
│   ├── WebUntisRepository.kt    # Central data logic, caching & multi-account merging
│   ├── SessionManager.kt        # Encrypted session & preferences
│   ├── RetrofitFactory.kt       # Dynamic base URL & interceptor setup
│   └── NetworkModule.kt         # Hilt DI, cookie handling (Android 15 fix)
├── model/
│   └── Models.kt                # GSON-compatible data classes for all API versions
└── ui/
    ├── login/                   # Login flow & validation
    ├── timetable/               # Timetable (ViewPager2 + detail enrichment)
    ├── homework/                # Homework incl. file handling
    ├── messages/                # Messages, attachments & history
    ├── absences/                # Absences with status filter
    ├── events/                  # Events & exams (incl. past)
    ├── classbook/               # Class register entries
    └── settings/                # Account management & app configuration
```

**Stack:** MVVM · Hilt DI · Retrofit2 · OkHttp3 · Coroutines/Flow · Navigation Component · Material 3 · ViewBinding · DataStore

### Known limitations

- Homework check-off state is not persistent (resets on app restart).
- The WebUntis API is unofficial; server-side changes may affect functionality.

### Compatibility

Optimised for modern Android versions (tested up to Android 15 / API 35). Includes specific fixes for cookie handling on Android 15 (`Secure` flag issue).

### Build

```bash
./gradlew assembleDebug
```
