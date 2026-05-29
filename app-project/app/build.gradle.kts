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
        versionCode = 11
        versionName = "0.7.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true   // Enables consuming Prefab AAR modules (e.g. boringssl below).
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

    // Reuse the debug keystore for release builds so developers can run
    // :app:assembleRokidRelease without keystore setup — purely for size /
    // R8-shrink measurement. Replace with a real release signingConfig before
    // any external distribution.
    signingConfigs {
        getByName("debug") {
            // AGP defaults to ~/.android/debug.keystore — leave as-is.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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
            // BouncyCastle ships ~1.2 MB of PQC (post-quantum) algorithm precomputed tables and
            // localized CertPath error messages we never use. R8 strips the unused *classes* but
            // can't strip resource files. Excluding them entirely saves ~1.2 MB in the release
            // APK with zero functional impact for our use case (we only use AdbCrypto.kt's
            // RSA-2048 + X.509 cert helpers).
            excludes += listOf(
                "org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties",
                "org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties",
                "org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties",
                "org/bouncycastle/x509/CertPathReviewerMessages*.properties",
                "org/bouncycastle/pkix/CertPathReviewerMessages*.properties",
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
    // AppCompat 1.6+ for per-app locale (`AppCompatDelegate.setApplicationLocales`) — backs the
    // language switch under Settings → Language. Android 13+ uses the system LocaleManager;
    // older versions get the same UX via AppCompat's emulation. Doesn't force us to use
    // AppCompatActivity; the static APIs are usable from any Activity.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // RayNeo Mercury SDK (binocular mirroring + temple gestures + 佩戴检测).
    // AAR is committed at [app/libs/mercury-release.aar] (Mercury is the openly-distributable
    // RayNeo ARDK — see https://rayneo.gitbook.io/rayneo-devdoc/x-xi-lie/android-kai-fa). Only
    // included in the rayneo flavor.
    "rayneoImplementation"(files("libs/mercury-release.aar"))

    // Optional: Shizuku — uncomment if you want the ShizukuBackend to compile.
    // implementation("dev.rikka.shizuku:api:13.1.5")
    // implementation("dev.rikka.shizuku:provider:13.1.5")

    // ZXing pure-Java QR decoder — used by QrCapture to read the ADB pairing QR code.
    // No Android dependencies; the :core library's ZXing import would work here too, but
    // keeping it in :app since QrCapture uses Android ImageReader / MediaProjection.
    implementation("com.google.zxing:core:3.5.3")

    // BouncyCastle — needed by :app/.../adb for X.509 cert generation in the ADB pairing flow.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // SPAKE2 / crypto: statically link a prebuilt BoringSSL via Prefab. Same SPAKE2 code
    // path as adbd → byte-for-byte compatible.
    //
    // Why not the simpler approaches:
    //   • spake2-java (pure Java) has an unresolved bug (issue #1, Alice/Bob keys disagree)
    //     traced to EdDSA-Java's group ops.
    //   • dlopen of system /apex .../libcrypto.so is blocked: apps can't link against
    //     `android_get_exported_namespace` (it's in libdl.so's LIBC_PLATFORM version map,
    //     reserved for platform code).
    //
    // io.github.vvb2060.ndk:boringssl is the Prefab AAR Shizuku and similar tools use.
    implementation("io.github.vvb2060.ndk:boringssl:20250114")
}

// Make sure the agent dex is up-to-date before any APK is packaged. The :agent:packageDex task
// d8's the agent JVM jar and drops halo-agent.dex into app/src/main/assets/.
tasks.matching { it.name == "preBuild" || it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(":agent:packageDex") }
