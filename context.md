# Project Context: WebUntis Dashboard

## Overview
Native Android application (Kotlin) for student/parent WebUntis dashboards.

## General Rules (MANDATORY)
1. **Localization:** ALL user-facing strings MUST be in `app/src/main/res/values/strings.xml`. Hardcoded strings in code or layouts are strictly prohibited.
2. **Context Management:** This file (`context.md`) must be updated whenever new global rules, major architectural shifts, or important technical insights are identified.

## Architecture
- **Pattern:** MVVM + Hilt DI.
- **Networking:** Retrofit2/OkHttp (JSON-RPC, REST v1/v2).
- **UI:** Material 3, ViewBinding, Navigation Component.
- **State:** `UiState` wrapper (Loading, Success, Error) with Kotlin Flow.

## Feature: Timetable
- **Classic View:** ViewPager2 (one day per page).
- **Compact View:** Horizontal RecyclerView showing days as columns.
- **Interactions:** In Compact View, tapping a lesson opens a `MaterialAlertDialog` with details (Subject, Time, Room, Teacher, Teaching Content, Notes).

## Key Components
- `WebUntisRepository`: Singleton, handles parallel fetching and caching.
- `SessionManager`: Encrypted storage for credentials and plain storage for UI preferences.
- `CompactWeekAdapter`: Handles the horizontal day columns.
- `CompactLessonAdapter`: Handles the individual lesson cards within columns.
