# R08 Ring Remote — skeleton project

Scaffolding for the Android app(s) described in `../R08-Remote-Design.md` (currently v0.6).

## Structure

```
app-project/
  settings.gradle.kts          ← :core, :app, :agent
  build.gradle.kts             ← plugin versions
  gradle.properties

  core/                        ← pure Kotlin/JVM library. Device-agnostic. Trivially unit-testable.
    src/main/kotlin/com/r08remote/core/
      DeviceProfile.kt
      ble/  R08Protocol.kt  R08Frame.kt  RingEvent.kt  R08BleClient.kt
      gesture/  Gestures.kt  Scheduler.kt  GestureSynthesizer.kt   ← THE state machine
      action/   Action.kt  KeyMapProfile.kt  DefaultProfiles.kt  ModeManager.kt  ActionRouter.kt
      inject/   ExecutorBackend.kt
      device/   DeviceStrategy.kt        ← DisplayAdapter / GlassActionMapper / WearStateProvider / FeatureIntents
    src/test/kotlin/com/r08remote/core/
      gesture/  ManualScheduler.kt  GestureSynthesizerTest.kt
      ble/      R08FrameTest.kt

  app/                         ← the Android app. Two product flavors: rokid / rayneo (= two APKs).
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/res/xml/r08_accessibility_config.xml
    src/main/kotlin/com/r08remote/app/
      R08RemoteApplication.kt        MainActivity.kt
      di/  AppGraph.kt   (Bindings)
      runtime/  AndroidScheduler.kt
      ble/  AndroidR08BleClient.kt   ← TODO
      service/  R08RemoteService.kt  ← TODO
      receiver/ BootReceiver.kt
      inject/   AppProcessAgentBackend.kt   ← TODO (talks to :agent)
                AccessibilityBackend.kt     ← TODO
    src/rokid/kotlin/com/r08remote/app/
      di/DeviceFlavorBindings.kt           (wires the Rokid strategies)
      device/rokid/RokidStrategies.kt      (DPAD-key mapper, FeatureIntents from rokid-docs)
    src/rayneo/kotlin/com/r08remote/app/
      di/DeviceFlavorBindings.kt           (wires the RayNeo strategies)
      device/rayneo/RayNeoStrategies.kt    (swipe-MotionEvent mapper, FeatureIntents TBD)

  agent/                       ← the app_process injection agent (shell-uid; LocalSocket API).
    build.gradle.kts
    src/main/kotlin/com/r08remote/agent/Main.kt   ← TODO
```

## Build & test (right now — works without any hardware)

`:core` is a plain JVM module — you can run the gesture-synthesizer and frame-parser tests with
no Android SDK installed:

```bash
cd app-project
./gradlew :core:test                          # the test report tells you the state machine works
```

(You'll need `./gradlew` — generate it once with `gradle wrapper` if your project doesn't have a
wrapper jar yet.)

## Build the Android apps

After installing the Android SDK + setting `ANDROID_HOME`:

```bash
./gradlew :app:assembleRokidDebug             # → app/build/outputs/apk/rokid/debug/app-rokid-debug.apk
./gradlew :app:assembleRayneoDebug            # → app/build/outputs/apk/rayneo/debug/app-rayneo-debug.apk
```

Each APK is independent — install the one matching your glasses. Inside the app, `AppGraph`
double-checks `DeviceProfile` at runtime as a sanity check (and falls back to GENERIC_ANDROID on
a regular phone so you can dev on phone hardware).

## What's actually written vs. what's a TODO

Fleshed-out (you can read & test these now):

- `core/.../gesture/GestureSynthesizer.kt` — the whole state machine, ~150 lines + heavy
  doc comments.
- `core/.../gesture/Gestures.kt`, `Scheduler.kt` — types and the timer abstraction.
- `core/.../ble/R08Protocol.kt`, `R08Frame.kt`, `RingEvent.kt` — protocol constants, command
  builder with checksum, pure-Kotlin notify-frame decoder.
- `core/.../action/*.kt`, `device/DeviceStrategy.kt`, `inject/ExecutorBackend.kt` — types and the
  contracts the rest of the app plugs into.
- `core/.../action/DefaultProfiles.kt` — the four shipping profiles (Navigation / Media / Reader
  / Fast) per §15.2.
- `app/src/rokid/.../RokidStrategies.kt` — fully wired DPAD-key mapper + Rokid Sprite Launcher
  Intent map from rokid-docs.
- `app/src/rayneo/.../RayNeoStrategies.kt` — swipe-MotionEvent mapper; FeatureIntents are placeholders
  pending §17.5 / §18.7 on-device discovery.
- `core/src/test/.../GestureSynthesizerTest.kt`, `R08FrameTest.kt` — exhaustive JVM tests of the
  state machine and the frame parser. Run them.

Stubbed (intentional placeholders, every one referenced in the design doc with a section number):

- `app/.../service/R08RemoteService.kt` — the resident foreground service.
- `app/.../ble/AndroidR08BleClient.kt` — `BluetoothGatt` impl of `R08BleClient`.
- `app/.../inject/AppProcessAgentBackend.kt` — talks to the `:agent` over LocalSocket.
- `app/.../inject/AccessibilityBackend.kt` — global-action helper.
- `agent/.../Main.kt` — the app_process agent body.
- `MainActivity.kt` — the Compose UI (per §22 screen list).

## Where the design lives

`../R08-Remote-Design.md` — every TODO points to the section that explains *what* and *why*.
