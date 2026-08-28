# WebUntis Dashboard — ProGuard Rules

# ── Kotlin & coroutines ───────────────────────────────────────────────────────
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions, *Annotation*

# ── OkHttp / Okio ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
# Keep service method annotations (Retrofit reads them at runtime via reflection)
-keepclassmembers class * {
    @okhttp3.* <methods>;
}

# ── Retrofit ─────────────────────────────────────────────────────────────────
-dontwarn retrofit2.**
# Retrofit uses reflection on interface methods; keep interface declarations
-keep,allowobfuscation interface com.webuntis.dashboard.api.WebUntisService
-keepclassmembers,allowobfuscation interface com.webuntis.dashboard.api.WebUntisService {
    <methods>;
}

# ── Gson / JSON models ────────────────────────────────────────────────────────
# All data classes used for JSON (de)serialization must keep their fields.
# IMPORTANT: field names must NOT be obfuscated, since Gson maps JSON keys to
# fields by NAME via reflection — renaming a field silently breaks parsing
# (fields quietly stay null instead of throwing), which is very hard to notice.
-keep class com.webuntis.dashboard.model.** { *; }
-keepclassmembers class com.webuntis.dashboard.model.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers enum com.webuntis.dashboard.model.** { *; }
# Gson's reflective TypeAdapterFactory / TypeToken machinery
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-dontwarn com.google.gson.**

# ── Hilt ─────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# ── Navigation Safe Args ──────────────────────────────────────────────────────
-keep class * extends androidx.navigation.NavArgs

# ── Security: strip logging from release builds ───────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
