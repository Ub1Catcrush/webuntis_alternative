# WebUntis Dashboard – Android (Kotlin)

Native Kotlin Android App für WebUntis-Schüler und Eltern mit Stundenplan, Hausaufgaben, Nachrichten, Abwesenheiten, Klassenbuch und Terminen.

---

## Voraussetzungen

| Tool | Version |
|---|---|
| Android Studio | Hedgehog 2023.1.1+ |
| JDK | 17+ |
| Android SDK | API 26+ (minSdk) / API 34 (compileSdk) |
| Kotlin | 1.9.23 |

---

## Projekt öffnen

1. ZIP entpacken
2. Android Studio → **Open** → Projektordner wählen
3. Gradle sync abwarten (erste Ausführung lädt ~300 MB)
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

### Zweiter Account

In den Einstellungen kann ein zweiter Account (z. B. für ein Kind) hinterlegt werden. Nachrichten beider Accounts werden zusammengeführt angezeigt.

---

## Screens

| Screen | Beschreibung |
|---|---|
| **Stundenplan** | Konfigurierbare Anzahl Schultage (1–20, Standard 5) mit Vertretungs-Info, Unterrichtsinhalt und Notizen für alle |
| **Hausaufgaben** | Liste mit Abhak-Funktion, Fälligkeits-Ampel und Fachfarben |
| **Nachrichten** | Posteingang inkl. Anhang-Download (beide Accounts zusammengeführt) |
| **Abwesenheiten** | Entschuldigungs-Status mit Übersicht offener Einträge |
| **Klassenbuch** | Einträge der letzten 30 Tage kategorisiert nach Typ |
| **Termine** | Prüfungen, Ferien und Veranstaltungen der nächsten 90 Tage |

---

## Architektur

```
app/
├── api/
│   ├── WebUntisService.kt       # Retrofit Interface (JSON-RPC + REST v1/v2)
│   ├── WebUntisRepository.kt    # Datenzugriff & Anreicherung
│   ├── SessionManager.kt        # Verschlüsselte Session & Einstellungen
│   ├── RetrofitFactory.kt       # Dynamische Base-URL
│   └── NetworkModule.kt         # Hilt DI, CookieJar, Interceptors
├── model/
│   └── Models.kt                # Alle Datenklassen
└── ui/
    ├── login/                   # Login & LoginViewModel
    ├── timetable/               # Stundenplan (ViewPager2)
    ├── homework/                # Hausaufgaben
    ├── messages/                # Nachrichten & Anhänge
    ├── absences/                # Abwesenheiten
    ├── events/                  # Termine & Prüfungen
    ├── classbook/               # Klassenbuch
    └── settings/                # Einstellungen
```

**Stack:** MVVM · Hilt DI · Retrofit2 · OkHttp3 · Coroutines/Flow · Navigation Component · Material 3 · ViewBinding

---

## Features

- ✅ Material 3 Design (Light + Dark Mode)
- ✅ Verschlüsselte Credential-Speicherung (AES-256)
- ✅ Zweiter Account (z. B. Kind) mit zusammengeführten Nachrichten
- ✅ Stundenplan: einstellbare Tagesanzahl (1–20), Standard 5
- ✅ Stundenplan: Unterrichtsinhalt (`📖`) und Notizen für alle (`📌`) direkt in der Stundenkarte
- ✅ Stundenplan: Vertretungsinfo mit durchgestrichenem Original-Lehrer
- ✅ Klickbare URLs in Lehrer-Notizen (Linkify)
- ✅ Status-Badges: Ausfall / Vertretung / Zusatz / Klassenarbeit
- ✅ Pull-to-Refresh auf allen Screens
- ✅ Hausaufgaben abhaken (Session-lokal)
- ✅ Fälligkeitsdatum-Ampel bei Hausaufgaben
- ✅ Farbkodierung nach Fach
- ✅ Anhang-Download aus Nachrichten
- ✅ Login ohne Neustart nach Einstellungsänderung (Integration Reload)

---

## Bekannte Einschränkungen

- WebUntis-API ist nicht offiziell dokumentiert – Endpunkte können sich ändern
- Session-Token läuft nach Serverinaktivität ab → App meldet sich automatisch neu an
- Hausaufgaben-Abhakstatus ist nicht persistent (wird bei App-Neustart zurückgesetzt)

---

## Kompatibilität

Getestet auf Android 10 (API 29), Android 15 (API 35) und Android 17 (API 37).  
Der Login-Fix für **Android 15** behebt ein Cookie-Handling-Problem (`Secure`-Flag) das zu einem 403 auf dem zweiten Login-Endpunkt führte.

---

## Build Release APK

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

Für Google Play: Keystore erstellen und in `build.gradle.kts` eintragen.
