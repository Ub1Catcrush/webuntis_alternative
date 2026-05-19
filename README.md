# WebUntis Dashboard – Android (Kotlin)

Native Kotlin Android App mit drei Haupt-Screens:
- **Stundenplan** – Heute & Morgen per Tab (ViewPager2)
- **Hausaufgaben** – Liste mit Abhak-Funktion und Farbkodierung
- **Klassenbuch** – Einträge der letzten 30 Tage mit Kategorien

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

Zugangsdaten werden mit **EncryptedSharedPreferences** (AES-256) lokal gespeichert.

---

## Architektur

```
app/
├── api/
│   ├── WebUntisService.kt       # Retrofit Interface
│   ├── WebUntisRepository.kt    # Datenzugriff
│   ├── SessionManager.kt        # Verschlüsselte Session
│   ├── RetrofitFactory.kt       # Dynamische Base-URL
│   └── NetworkModule.kt         # Hilt DI
├── model/
│   └── Models.kt                # Alle Datenklassen
└── ui/
    ├── login/                   # Login Screen
    ├── timetable/               # Stundenplan (ViewPager2)
    ├── homework/                # Hausaufgaben
    └── classbook/               # Klassenbuch
```

**Stack:** MVVM · Hilt DI · Retrofit2 · Coroutines/Flow · Navigation Component · Material 3 · ViewBinding

---

## Features

- ✅ Material 3 Design (Light + Dark Mode)
- ✅ Verschlüsselte Credential-Speicherung
- ✅ Pull-to-Refresh auf allen Screens
- ✅ Hausaufgaben abhaken (Session-lokal)
- ✅ Farbkodierung nach Fach
- ✅ Status-Badges: Ausfall / Vertretung / Zusatz
- ✅ Fälligkeitsdatum-Ampel bei Hausaufgaben

---

## Bekannte Einschränkungen

- WebUntis-API ist nicht offiziell dokumentiert – Endpunkte können sich ändern
- Session-Token läuft nach Serverinaktivität ab → erneut anmelden
- CORS betrifft nur Browser, nicht die App

---

## Build Release APK

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

Für Google Play: Keystore erstellen und in `build.gradle.kts` eintragen.
