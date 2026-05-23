# Project Context: WebUntis Dashboard

## Overview
Native Android application (Kotlin) for student/parent WebUntis dashboards.

## General Rules (MANDATORY)
1. **Localization:** ALL user-facing strings MUST be in `app/src/main/res/values/strings.xml`. Hardcoded strings in code or layouts are strictly prohibited.
2. **Context Management:** This file (`context.md`) must be updated whenever new global rules, major architectural shifts, or important technical insights are identified.

## Architecture
- **Pattern:** MVVM + Hilt DI.
- **Networking:** Retrofit2/OkHttp (JSON-RPC, REST v1/v2). Android 15 requires strict `Secure` cookie handling and modern User-Agents.
- **UI:** Material 3, ViewBinding, Navigation Component.
- **State:** `UiState` wrapper (Loading, Success, Error) with Kotlin Flow.

## Feature: Timetable
- **Classic View:** ViewPager2 (one day per page).
- **Compact View:** Horizontal RecyclerView showing days as columns.
- **Overlaps:** Overlapping lessons are merged. If an active lesson replaces a cancelled one, it is marked as a substitution and displays "statt [Old Subject]".
- **Interactions:** Tapping a lesson (especially in Compact View) opens a `MaterialAlertDialog` with details (Subject, Time, Room, Teacher, Teaching Content, Notes).

## Key Components
- `WebUntisRepository`: Singleton, handles parallel fetching, caching, and logical merging of lessons.
- `SessionManager`: Encrypted storage for credentials and plain storage for UI preferences.
- `NetworkModule`: Configures OkHttpClient with a custom `CookieJar` and `jsonSanitizer` to handle WebUntis session expiry (HTML-to-JSON conversion).

## Current Status
- **Version:** v0.0.12 (defined in `dependencies.gradle`).
- **Target SDK:** 35 (Android 15).
