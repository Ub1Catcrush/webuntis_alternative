# WebUntis Dashboard ProGuard Rules

# Allgemeine Attribute für Reflection & Annotationen
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions, *Annotation*, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keep class okhttp3.** { *; }

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
# WICHTIG: Ersetzen Sie dies mit dem echten Pfad zu Ihren Retrofit-Interfaces!
-keep interface com.webuntis.dashboard.api.** { *; }

# Gson & WebUntis Models
-keep class com.webuntis.dashboard.model.** { *; }
#-keep class com.webuntis.dashboard.model.** { <fields>; <methods>; }
-keepclassmembers class com.webuntis.dashboard.model.** { *; }
#-keepclassmembers class * {
#    @com.google.gson.annotations.SerializedName <fields>;
#}

# Hilt (Sicherheitshalber)
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# Navigation Safe Args
-keep class * extends androidx.navigation.NavArgs
