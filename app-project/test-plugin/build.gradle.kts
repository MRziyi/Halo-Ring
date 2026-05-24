// Doc/18 reference plugin app. Self-contained — depends on nothing in :core / :app.
// This module exists ONLY to validate the Halo Ring plugin protocol on a real device. It is
// not shipped in any production release; the build outputs are debug-only.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.halo.ring.testplugin"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.halo.ring.testplugin"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        // No release build — this is a developer-only artifact.
        getByName("debug") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
