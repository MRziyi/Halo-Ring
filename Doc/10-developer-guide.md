# 10 — Developer Guide

How to build, test, and extend the codebase. For end users see
[09-user-manual.md](09-user-manual.md).

---

## 1. Repository layout

```
Halo-Ring/                         ← repo root (this is the canonical Halo Ring repo)
  README.md                        ← top-level public landing
  Doc/                             ← all design and developer docs (this)
  app-project/                     ← the Android multi-module Kotlin project
    settings.gradle.kts
    build.gradle.kts
    core/                          ← pure-JVM library; gesture state machine + protocol parser
                                     + interfaces; 250 JVM tests
    app/                           ← Android app, with rokid + rayneo flavors
      src/main/.../ui/             ← Compose UI: theme, components, screens (Vitals,
                                     Settings root + 5 groups, Profile editor / Action picker /
                                     System gestures / Gesture picker / Test Arena, Pairing,
                                     Ring, External Plugins), HUD overlay (the daily UX surface).
                                     v0.4 removed: InAppFocusController, TempleFocusBridge, tabs.
      src/main/.../service/        ← HaloRingService — foreground service host
      src/main/.../ble/            ← AndroidR08BleClient — Android BluetoothGatt impl
      src/main/.../inject/         ← AppProcessAgentBackend + AccessibilityBackend
      src/main/.../plugin/         ← Doc/18 plugin discovery + trigger + push/pop receiver
      src/main/.../runtime/        ← AndroidScheduler (HandlerThread for the gesture pipeline)
      src/rokid/ + src/rayneo/     ← flavor-specific device strategies
    agent/                         ← the app_process injection agent
    test-plugin/                   ← Doc/18 reference plugin (validation + plugin-author example)
  .github/                         ← CI: build-apks (release pipeline) + core-tests
  LICENSE / COPYRIGHT.md / COMMERCIAL-LICENSE.md / CONTRIBUTING.md
```

A separate **private research workspace** (`R08-dev/`, remote `git@github.com:MRziyi/R08-Dev.git`)
holds the BLE protocol validation tools (`phase0/`), vendor materials (`refs/`), and
third-party clones (`research/`). It's where reverse-engineering happens; verified bytes get
hand-published into `Doc/02-hardware-and-protocol.md` here. As a public contributor you don't
need R08-dev — everything you need to build, test, and extend the project is in this repo.

## 2. Prerequisites

- **Java 17** (e.g. via Homebrew: `brew install openjdk@17`; or any JDK 17 distribution)
- **Android Studio Iguana / Koala** or later, OR command-line Android SDK with API 34
  platform-tools + build-tools
- For ADB-on-device work (when hardware arrives): glasses-specific dev unlock (see
  [03](03-target-platforms.md))

## 3. Build & test (no hardware required)

The `:core` module is pure Kotlin/JVM and you can run its tests without Android Studio:

```bash
cd app-project
gradle wrapper                           # one-time, generates ./gradlew
./gradlew :core:test                     # ~25 unit tests, mostly the gesture state machine
```

Test output goes to `core/build/reports/tests/test/index.html`.

To assemble the Android APKs (needs Android SDK + `ANDROID_HOME`):

```bash
./gradlew :app:assembleRokidDebug        # → app/build/outputs/apk/rokid/debug/app-rokid-debug.apk
./gradlew :app:assembleRayneoDebug
./gradlew :app:assembleRokidRelease      # similar for release builds
```

To build the agent dex (for the AppProcessAgentBackend):

```bash
./gradlew :agent:jar
# then turn the jar into a dex for app_process:
$ANDROID_HOME/build-tools/<version>/d8 agent/build/libs/agent-*.jar \
    --output agent/build/halo-agent.dex
# bundle the dex as an :app asset (TODO: automate via a gradle task)
cp agent/build/halo-agent.dex app/src/main/assets/halo-agent.dex
```

## 4. Adding a new built-in profile

1. Define it in [`core/.../action/DefaultProfiles.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/action/DefaultProfiles.kt):
   ```kotlin
   val MY_PROFILE = KeyMapProfile(
       id = "my-profile",
       name = "My Profile",
       gestureConfig = GestureConfig(optimisticSingleTap = true, ...),
       map = systemSlots + mapOf(
           Gesture.TAP to GlassAction.Confirm,
           Gesture.DOUBLE_TAP to GlassAction.Back,
           // ... fill all 12 slots
       ),
       triggerPackages = listOf(/* package names that auto-activate this */),
   )
   ```
2. Add it to `DefaultProfiles.ALL`.
3. The settings UI lists it automatically. No other code changes needed.

## 5. Adding a new GlassAction

1. Add a new sealed-class entry in [`core/.../action/Action.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/action/Action.kt):
   ```kotlin
   data object MyAction : GlassAction { override val needs = Capability.KEY_EVENT }
   ```
   Pick the right `Capability`.
2. Map it in each platform's strategy:
   - [`app/src/rokid/.../RokidStrategies.kt`](../app-project/app/src/rokid/kotlin/com/halo/ring/device/rokid/RokidStrategies.kt) `primitives()` `when` block
   - [`app/src/rayneo/.../RayNeoStrategies.kt`](../app-project/app/src/rayneo/kotlin/com/halo/ring/device/rayneo/RayNeoStrategies.kt) likewise
3. If it's a "feature-open" action that uses an Intent, add to the `FeatureIntents` interface in
   [`core/.../device/DeviceStrategy.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/device/DeviceStrategy.kt) and implement in both flavors.
4. (Optional) bind it in one of the default profiles.

## 6. Adding a new gesture

1. Extend the `Gesture` enum in [`core/.../gesture/Gestures.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/gesture/Gestures.kt).
2. Implement the synthesis path in [`GestureSynthesizer.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/gesture/GestureSynthesizer.kt). If it's
   a new combo, add a follow-up timer + state field; mirror the existing combo logic.
3. Add tests in [`GestureSynthesizerTest.kt`](../app-project/core/src/test/kotlin/com/halo/ring/core/gesture/GestureSynthesizerTest.kt) — at minimum:
   - "does it fire when the conditions are met"
   - "does it not fire when an interfering sequence happens"
   - "does it interact correctly with optimistic-tap / await-combos"
4. Add a default binding in profiles, or leave the slot unbound (`GlassAction.None`).
5. Update [05-interaction-design.md](05-interaction-design.md) §2 and [09](09-user-manual.md) §4.

## 7. Adding support for a new glasses platform

Suppose Glasses-C ships and we want to support it. The work:

1. **Platform research**: Android version? Display? Input model (focus + DPAD keys, or focus +
   MotionEvent gestures, or something else)? System launcher package + key Activities? ADB
   bootstrap process? Wear detection mechanism? Write up the findings analogous to
   [Doc/04 §8](04-architecture.md#8-target-platforms).
2. **Add a Gradle product flavor**: in [`app/build.gradle.kts`](../app-project/app/build.gradle.kts) add `glasses-c` flavor.
3. **Implement the four strategies** in `app/src/glassesC/...`:
   - `GlassesCDisplayAdapter`
   - `GlassesCActionMapper`
   - `GlassesCWearStateProvider`
   - `GlassesCFeatureIntents`
4. Wire them in `app/src/glassesC/.../DeviceFlavorBindings.kt`.
5. Extend [`DeviceProfile`](../app-project/core/src/main/kotlin/com/halo/ring/core/DeviceProfile.kt) and
   [`AppGraph.detectDeviceProfile()`](../app-project/app/src/main/kotlin/com/halo/ring/di/AppGraph.kt) with the new profile.
6. Verify per the smoke-check sequence in [Doc/13 §1.4](13-handoff.md) (former verification checklists archived under `_archive/11-verification-checklists.md`).

## 8. Adding a new executor backend

Implement [`ExecutorBackend`](../app-project/core/src/main/kotlin/com/halo/ring/core/inject/ExecutorBackend.kt). Example for adding e.g. an HID-bluetooth-keyboard backend
(for the future phone-as-bridge architecture):

```kotlin
class HidKeyboardBackend(private val hidDevice: BluetoothHidDevice) : ExecutorBackend {
    override val id = "hid-keyboard"
    override val priority = 70  // between Shizuku (90) and InotifydScript (60)
    override fun capabilities() = if (isReady())
        setOf(Capability.KEY_EVENT, Capability.LAUNCH_INTENT) else emptySet()
    override fun isReady() = hidDevice.connectedDevices.isNotEmpty()
    override suspend fun perform(action: GlassAction): Boolean { /* HID reports */ }
}
```

Then register it in the appropriate flavor's `DeviceFlavorBindings.create()`.

The `ActionRouter` picks it up automatically based on priority + capability + readiness.

## 9. The gesture state machine — extension points

The synthesiser is intentionally simple. Common extensions:

| Want | How |
|---|---|
| New tunable | Add field to `GestureConfig`, plumb through |
| New raw event (e.g. firmware update adds left/right swipe) | Add to `RawGesture` enum; add a branch in `onRaw`; update `R08Frame.parse` |
| New combo | Mirror an existing combo's pattern (new follow-up timer + state flag + cancel logic) |
| Stateful "modes" within a profile | This is what the **modal layer** is for — see [05](05-interaction-design.md) §6 |

Whatever you do, **add tests**. The state machine has subtle interactions (e.g. flush-pending-tap
ordering) that are hard to keep right without a comprehensive test suite. Better to over-test
than catch a bug in production.

## 10. The agent — build and bootstrap flow

The `:agent` module produces a small dex that runs as a shell-uid process. Build:

```bash
./gradlew :agent:jar
d8 --output agent/build/halo-agent.dex agent/build/libs/agent-*.jar
cp agent/build/halo-agent.dex app/src/main/assets/
```

App-side bootstrap (in the first-run wizard or after a `pm grant`):

```bash
# Push the dex to a location the shell can run from
adb push halo-agent.dex /data/local/tmp/

# Start it via app_process. CLASSPATH on the line is the convention.
adb shell "CLASSPATH=/data/local/tmp/halo-agent.dex nohup \
    app_process /system/bin --nice-name=halo.agent com.halo.ring.agent.Main >/dev/null 2>&1 &"

# Verify
adb shell ps -A | grep halo.agent
```

The agent opens an abstract LocalSocket `halo.agent` and serves the line protocol:

```
KEY <kc>                              → press-and-release single keycode
KEYDOWN <kc>                          → keydown only
KEYUP <kc>                            → keyup only
TAP <x> <y>                           → MotionEvent down/up
SWIPE <x1> <y1> <x2> <y2> <duration_ms> → MotionEvent down + N moves + up
AM <args...>                          → equivalent to `am <args>`
BC <action> [k=v...]                  → broadcast
SH <raw shell>                        → arbitrary shell
PING                                  → OK
QUIT                                  → close gracefully
```

Each command replies `OK` or `ERR <msg>`.

The agent uses **reflection** to call `android.hardware.input.InputManager.getInstance().injectInputEvent(KeyEvent | MotionEvent, INJECT_INPUT_EVENT_MODE_ASYNC)`,
which is hidden API but accessible to shell-uid processes via `app_process` (which bypasses the
app-startup hidden-API gate). Detail and reference: scrcpy's server code, Shizuku.

`AppProcessAgentBackend` (in `:app`) connects to the socket once at startup and pipelines
commands. It also watches a heartbeat file (`/data/local/tmp/halo.agent.heartbeat`) and re-spawns
the agent if it goes stale.

## 11. The phase-0 probes

The Python (bleak) BLE probes that produced [`R08-dev/phase0/SPEC v3.md`](../../R08-dev/phase0/SPEC%20v3.md)
live in `R08-dev/phase0/` (the private research workspace). They're not part of the Halo-Ring
public repo — different language, different tool, different runtime. Run them when you need to
re-verify a byte sequence against actual ring firmware.

## 12. Coding conventions

- Kotlin official style (4-space indent, no wildcard imports, expression bodies for trivial
  functions)
- `:core` is **pure JVM, no Android imports**. If you find yourself wanting `android.os.SystemClock`
  in `:core`, abstract behind an interface and inject the Android impl from `:app`.
- All public types in `:core` have KDoc that references the relevant Doc/ section number.
- `ExecutorBackend` and `Modal` implementations should NEVER block on the gesture thread — use
  coroutines / handlers.
- Tests: prefer JVM unit tests over instrumentation tests. The synthesiser, frame parser, and
  router should all be JVM-testable.

## 13. Performance & power expectations

When you change something hot-path (BLE callback, scheduler, agent), confirm you didn't regress:

- **Latency**: Enable Debug HUD → Latency measurement mode → do 20 of each gesture → check 95th
  percentile against the targets in [Doc/04 §7.1](04-architecture.md#71-end-to-end-latency-budget).
- **Power**: Run resident with ring connected, glasses worn, no interaction, for 30 min. Check
  glasses' battery stats and the ring's reported battery delta. Compare to baseline.
- **Reliability**: 100 of each gesture, 60 cm distance, count drops + false positives.

## 14. Memory & references kept by the project

- `/.claude/projects/.../memory/` (this Claude Code project's persistent memory) — current
  design state, key decisions, action items
- `Doc/` — this folder; the canonical design documentation
- `Doc/_archive/` — historical monolithic versions of the design doc
- `research/` — cloned reference repositories (don't modify; reference reading)

Major design changes should update both the relevant `Doc/` sections AND memory.

## 15. Cutting a release

(Documented after we ship 0.1 — currently no release process yet.)
