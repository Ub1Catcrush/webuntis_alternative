import org.jetbrains.kotlin.gradle.dsl.JvmTarget

apply(from = rootProject.file("dependencies.gradle"))

val appVersions by extra(rootProject.extra["app_versions"] as Map<String, Any>)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.navigation.safeargs)
}

android {
    namespace = "com.webuntis.dashboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.webuntis.dashboard"
        minSdk = 26
        targetSdk = 36

        val major = (appVersions["versionMajor"] as Number).toInt()
        val minor = (appVersions["versionMinor"] as Number).toInt()
        val patch = (appVersions["versionPatch"] as Number).toInt()

        versionCode = major * 1000000 + minor * 10000 + patch

        // 4. Kotlin-konforme Überprüfung für optionale Properties und korrekte Strings
        versionName = if (project.hasProperty("versionName")) {
            project.property("versionName") as String
        } else {
            "$major.$minor.$patch"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            // Disable unnecessary shrinking in debug for faster builds,
            // but enable dex optimization to reduce bytecode verification lag on emulator
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Separate Konfiguration für Kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.datastore)
    implementation(libs.security.crypto)
    implementation(libs.swiperefresh)
    implementation(libs.viewpager2)
    implementation(libs.fragment.ktx)
    implementation(libs.activity.ktx)
}
