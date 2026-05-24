// R08 Ring Remote — multi-module skeleton (see ../R08-Remote-Design.md §18.6 / §19)
//
// :core        pure Kotlin/JVM — device-agnostic logic (BLE protocol parsing, gesture
//              synthesizer, modes/mapping, action routing, executor & device-strategy
//              interfaces). No Android deps → fully unit-testable on the JVM.
// :app         Android app, Jetpack Compose. Two product flavors: `rokid` and `rayneo`
//              → two APKs from one codebase. Hosts R08BleClient (BluetoothGatt), the
//              foreground service, the Compose UI, the ADB/Shizuku/Accessibility
//              executor backends, and the per-flavor device strategies.
// :agent       The app_process injection agent (tiny dex; runs with shell uid, opens a
//              LocalSocket, calls InputManager.injectInputEvent directly). Shared by both
//              flavors. Built separately and bundled as an asset of :app.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // RayNeo ARSDK ("Mercury SDK") — apply for access at https://open.rayneo.com/
        // maven { url = uri("https://maven.rokid.com/repository/maven-public/") } // Rokid CXR-* (not needed for this project)
    }
}

rootProject.name = "r08-ring-remote"
include(":core", ":app", ":agent", ":test-plugin")
