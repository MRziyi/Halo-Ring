plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.halo.ring"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.halo.ring"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Two product flavors → two APKs, sharing 95% of code via :core and the main source set.
    flavorDimensions += "device"
    productFlavors {
        create("rokid") {
            dimension = "device"
            applicationIdSuffix = ".rokid"
            versionNameSuffix = "-rokid"
            buildConfigField("String", "DEVICE_FLAVOR", "\"rokid\"")
        }
        create("rayneo") {
            dimension = "device"
            applicationIdSuffix = ".rayneo"
            versionNameSuffix = "-rayneo"
            buildConfigField("String", "DEVICE_FLAVOR", "\"rayneo\"")
            // Mercury SDK is wired in the dependencies block below via "rayneoImplementation".
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // BouncyCastle's bcpkix / bcprov / bcutil jars each ship their own copy of these META-INF
    // resources — pick the first to avoid the merge collision.
    packaging {
        resources {
            pickFirsts += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // RayNeo Mercury SDK (binocular mirroring + temple gestures + 佩戴检测).
    // AAR lives in app/libs/. Only included in the rayneo flavor.
    "rayneoImplementation"(files("libs/mercury-release.aar"))

    // Optional: Shizuku — uncomment if you want the ShizukuBackend to compile.
    // implementation("dev.rikka.shizuku:api:13.1.5")
    // implementation("dev.rikka.shizuku:provider:13.1.5")

    // BouncyCastle — needed by :app/.../adb for X.509 cert generation in the ADB pairing flow.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}

// Make sure the agent dex is up-to-date before any APK is packaged. The :agent:packageDex task
// d8's the agent JVM jar and drops halo-agent.dex into app/src/main/assets/.
tasks.matching { it.name == "preBuild" || it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(":agent:packageDex") }
