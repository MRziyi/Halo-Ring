# 04 — Architecture

How the codebase is organised, how the layers interact, what plugs where. Read this once and you
should be able to navigate `app-project/` without surprises.

The full implementation skeleton is at [`../app-project/`](../app-project/); this doc tells you
*why* it's shaped the way it is.

## 1. Top-level constraint: same product, two platforms

The user requirement: one ring should work as a remote for **two different AR glasses** (Rokid +
RayNeo X3 Pro), with **identical operations, UI content, and behaviour**. The implementation
differences (DPAD-key injection on Rokid vs swipe-MotionEvent injection on X3 Pro) must be
invisible to the end user.

We satisfy this with: **one Git repo, one `:core` Kotlin/JVM module (device-agnostic), and two
Android product flavors (`rokid`, `rayneo`) that pull device-specific strategies from
flavor-specific source sets**. Each flavor ships as a separate APK. The 90% shared / 10%
different split lives along strategy-pattern interfaces ([§4 below](#4-the-four-device-strategies)).

We considered "one APK with runtime device detection" — it works, but it's strictly more complex
than two APKs for no real benefit beyond "fewer downloads", which doesn't matter when each user
only has one or two pairs of glasses. The strategies are still resolved at runtime via
`DeviceProfile` for sanity-checking + GENERIC_ANDROID dev fallback (so you can run a stub on a
regular phone).

## 2. Module graph

```
:core      ─────────────►  pure Kotlin/JVM library (no Android deps)
                            R08BleClient (interface), R08Protocol, R08Frame, RingEvent,
                            GestureSynthesizer (the 12-gesture state machine),
                            SystemGestures, InteractionRouter, Modal interface,
                            GlassAction (sealed) + Capability, KeyMapProfile,
                            DefaultProfiles, ModeManager, ActionRouter,
                            ExecutorBackend (interface), DeviceStrategy interfaces
                            (DisplayAdapter / GlassActionMapper / WearStateProvider /
                            FeatureIntents), DeviceProfile.
                            ── trivially unit-testable on JVM ──
                            (GestureSynthesizerTest, R08FrameTest, ManualScheduler)

:agent     ─────────────►  the app_process injection agent (small Java/Kotlin dex; runs as
                            shell uid). LocalSocket server; reflects InputManager.injectInputEvent
                            for ~1-3ms per gesture. Built once, bundled as a :app asset.

:app       ─────────────►  Android application. Compose. Foreground service.
   main/                    HaloRingApplication, MainActivity, AppGraph,
                            HaloRingService, BootReceiver,
                            AndroidR08BleClient (BluetoothGatt impl of the core interface),
                            AndroidScheduler (HandlerThread-backed Scheduler),
                            HaloRingAccessibilityService,
                            ExecutorBackend impls: AppProcessAgentBackend (LocalSocket to :agent),
                            InotifydScriptBackend (fallback),
                            AccessibilityBackend.
   src/rokid/               RokidDisplayAdapter, RokidActionMapper, RokidFeatureIntents,
                            RokidWearStateProvider; DeviceFlavorBindings wires them.
   src/rayneo/              RayNeoDisplayAdapter, RayNeoActionMapper, RayNeoFeatureIntents,
                            RayNeoWearStateProvider; depends on the Mercury AAR (optional).

→ ./gradlew :app:assembleRokidDebug  ⇒  app-rokid-debug.apk
→ ./gradlew :app:assembleRayneoDebug ⇒  app-rayneo-debug.apk
```

## 3. Runtime data flow

What happens when the user does a gesture, end to end:

```
[R08 ring]
   ↓  BLE notify (`73 2D 03` for a single touch)
[AndroidR08BleClient]
   ↓  100ms byte-identical dedup (carefully tuned per phase-0 measurement)
   ↓  R08Frame.parse → RingEvent.GestureEvent(raw = TOUCH)
   ↓  post onto AndroidScheduler.handler (the pipeline's single thread)
[InteractionRouter]
   ↓  screen-off fast path?
   ↓     if screen off AND raw == wakeGesture → emit ScreenWake → ActionRouter → done
   ↓     if screen off AND not wake → drop (no false positives)
   ↓  otherwise: continue to GestureSynthesizer
[GestureSynthesizer]
   ↓  state-machine combines raw events into Gesture (TAP / DOUBLE_TAP / SWIPE_UP / ...)
   ↓  timing windows: multi-tap (280ms), combo (300ms), long-press follow-up (400ms)
[InteractionRouter (back to it)]
   ↓  system-level layer:  systemGestures.sleep → ScreenSleep
   ↓                        systemGestures.profileCycle → ModeManager.cycleNext
   ↓                        systemGestures.peekHud → onPeekHud()  (UI callback)
   ↓                        systemGestures.forceReconnect → onForceReconnect()
   ↓  modal layer:          if a modal active, modal.handle(gesture) takes over
   ↓  profile layer:        modeManager.active().actionFor(gesture) → GlassAction
[ActionRouter]
   ↓  per-flavor GlassActionMapper.capabilityFor(action) → Capability
   ↓  pick highest-priority ExecutorBackend that has the Capability and isReady()
[ExecutorBackend]
   ↓  AppProcessAgentBackend → mapper.primitives(action) → LocalSocket command list to agent
[:agent (shell uid)]
   ↓  parse line protocol, reflect InputManager.injectInputEvent
[Android InputDispatcher]
   ↓  KeyEvent or MotionEvent goes to focused window
[Glasses system UI / app reacts → frame renders]
```

## 4. The four device strategies

The single source of truth for "what's different between glasses" is these four interfaces in
[`core/.../device/DeviceStrategy.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/device/DeviceStrategy.kt):

### 4.1 `DisplayAdapter` — how our app's own UI is rendered

```kotlin
interface DisplayAdapter {
    val isBinocular: Boolean
    val contentWidthPx: Int
    val contentHeightPx: Int
}
```

- **Rokid**: `isBinocular = false`, content area 480×480 (right-eye mono).
- **RayNeo X3 Pro**: `isBinocular = true`, content area 640×480 per eye. With the Mercury SDK
  AAR present, our Activity extends `BaseMirrorActivity` and gets free left/right mirroring.
  Without the AAR (DIY fallback): we draw content twice into a 1280×480 surface with a small
  parallax offset.

### 4.2 `GlassActionMapper` — abstract action → device-specific injection

```kotlin
interface GlassActionMapper {
    fun capabilityFor(action: GlassAction): Capability?      // override what the action's needs are on this device
    fun primitives(action: GlassAction): List<InjectionPrimitive>
}
```

`InjectionPrimitive` is the IR the executor backend executes:

```kotlin
sealed interface InjectionPrimitive {
    data class Key(val keycode: Int)
    data class Tap(val x: Int, val y: Int)
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int)
    data class StartActivity(val component: String, val extras: Map<String, String> = ...)
    data class Broadcast(val action: String, val extras: ...)
    data class Shell(val cmd: String)
    data class A11yGlobal(val action: A11yGlobalAction)      // BACK / HOME / RECENTS / NOTIFICATIONS / QUICK_SETTINGS / LOCK_SCREEN / TAKE_SCREENSHOT / POWER_DIALOG
}
```

- **Rokid `NavPrev`** → `Key(KEYCODE_DPAD_UP)` — capability `NAVIGATE` satisfied by KEY_EVENT.
- **RayNeo `NavPrev`** → `Swipe(400, 240, 240, 240, 60ms)` — capability `NAVIGATE` satisfied by
  TAP_SWIPE.

Same `GlassAction.NavPrev`, totally different injection. The user has no idea.

### 4.3 `WearStateProvider` — is the user wearing these glasses?

```kotlin
interface WearStateProvider {
    fun isWorn(): Boolean
    fun observe(onChange: (worn: Boolean) -> Unit): () -> Unit
}
```

Drives **TOUCH_DISABLE on the ring** when not worn (power saving) and the **ring hand-over**
between glasses (see [05](05-interaction-design.md) §5).

- **Rokid**: `RokidDoorReceiver` broadcast + proximity sensor + hinge state (see
  [03](03-target-platforms.md) §1.7). Fallback: `ACTION_SCREEN_ON/OFF` as a proxy.
- **RayNeo X3 Pro**: Mercury SDK 佩戴检测 API. Fallback: same screen on/off proxy.

### 4.4 `FeatureIntents` — abstract feature → device-specific Intent

```kotlin
interface FeatureIntents {
    fun openCamera(): List<InjectionPrimitive>
    fun takePhoto(): List<InjectionPrimitive>
    fun askVisualAI(): List<InjectionPrimitive>
    fun openTranslate(): List<InjectionPrimitive>
    fun openChat(): List<InjectionPrimitive>
    fun openMusic(): List<InjectionPrimitive>
    fun openSettings(): List<InjectionPrimitive>
    fun openGallery(): List<InjectionPrimitive>
    fun launchApp(pkg: String): List<InjectionPrimitive>
}
```

Each returns a sequence of primitives to invoke the feature on that device.

- **Rokid**: fully populated from `rokid-docs/yodaos/docs/apps/sprite-launcher.md` — all 21
  page Activities documented (see [03](03-target-platforms.md) §1.3).
- **RayNeo**: mostly TODO until on-device discovery via `pm list packages` / `dumpsys activity
  top` (see [11](11-verification-checklists.md) §B6). For now `launchApp` falls back to
  `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`.

### 4.5 (Optional, dev-only) `DeviceProfile`

Enum `{ ROKID_GLASSES, RAYNEO_X3PRO, GENERIC_ANDROID }`, resolved at startup from `Build.*`. The
flavor build pins the right strategies; runtime detection is just a sanity check (catches "wrong
APK installed") and lets us run a stubbed GENERIC build on a regular Android phone for development.

## 5. Executor backends

The `ExecutorBackend` interface — see [`core/.../inject/ExecutorBackend.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/inject/ExecutorBackend.kt):

```kotlin
interface ExecutorBackend {
    val id: String
    val priority: Int                              // higher = preferred
    fun capabilities(): Set<Capability>            // probed at runtime
    fun isReady(): Boolean
    suspend fun perform(action: GlassAction): Boolean
}
```

The `ActionRouter` picks the highest-priority ready backend that has the action's required
`Capability`. Multiple backends can be alive simultaneously; the router negotiates.

### 5.1 The four backends, in priority order

| # | Backend | Priority | Capabilities | When available | Cost / latency |
|---|---|---|---|---|---|
| 1 | **AppProcessAgentBackend** | 100 | NAVIGATE, KEY_EVENT, TAP_SWIPE, LAUNCH_INTENT, SHELL, BACK, HOME, RECENTS, NOTIFICATIONS | After bootstrap wizard + agent running | ~1–3 ms / event |
| 2 | **ShizukuBackend** (optional) | 90 | Same as agent | User has Shizuku/Sui installed | ~5–10 ms |
| 3 | **InotifydScriptBackend** | 60 | Same as agent, minus pure shell speed | After bootstrap wizard | ~50–150 ms (`input keyevent` JVM spawn) |
| 4 | **PollScriptBackend** | 40 | Same | Last-resort, `小猪`-style 50ms polling | ~100–200 ms |
| 5 | **AccessibilityBackend** | 80 | BACK, HOME, RECENTS, NOTIFICATIONS (and reads foreground package for auto-switch) | User enabled accessibility service | ~10–20 ms |

The agent is the performance win. It runs as a shell-uid process started by `app_process` (with
classpath = our dex pushed to `/data/local/tmp/`). Once running, it serves a LocalSocket with a
trivial line protocol (`KEY <keycode>` / `TAP <x> <y>` / `SWIPE x1 y1 x2 y2 ms` / `AM <args>` /
`SH <raw>` / `PING`); the backend connects once at startup, then pipelines commands. The agent
internally reflects `InputManager.injectInputEvent(KeyEvent | MotionEvent, MODE_ASYNC)` — same as
scrcpy, same as Shizuku, ~1–3 ms per call.

See [10-developer-guide.md](10-developer-guide.md) §6 for the agent's build + bootstrap flow.

### 5.2 The Accessibility role

We use AccessibilityService for *two* things, both optional:

1. **Cheap Back/Home/Recents/Notifications/etc.** — even without ADB. `performGlobalAction`
   covers these on Android 12.
2. **Reading the foreground package** — `onAccessibilityEvent(TYPE_WINDOW_STATE_CHANGED)` →
   feed the new package to `ModeManager.onForegroundPackage()` → auto-switch profile.

Crucially, on Android 12 AccessibilityService **cannot inject DPAD key events**
(`GLOBAL_ACTION_DPAD_*` is API 33+). It also can't inject MotionEvents reliably to the focus-based
system UI. So it's a *helper*, never a replacement for the agent.

## 6. The InteractionRouter (the top-level routing pipeline)

[`core/.../gesture/InteractionRouter.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/gesture/InteractionRouter.kt)
implements the 4-layer hierarchy. Layers, in order, top to bottom:

```
1. Screen-state gateway
   ↓ if screen off:
   ↓   raw == wakeGesture → ScreenWake → done
   ↓   else                → drop
   ↓ if screen on: continue
2. System-level gestures
   ↓ gesture == sleepGesture          → ScreenSleep → done
   ↓ gesture == profileCycle (3-tap)  → ModeManager.cycleNext + LED ack → done
   ↓ gesture == peekHud (4-tap)       → show HUD 2s → done
   ↓ gesture == forceReconnect (2-LP) → R08BleClient.reconnect → done
   ↓ else: continue
3. Modal layer (when active)
   ↓ activeModal.handle(gesture) → GlassAction
   ↓ if handled, dispatch; if sentinel Exit/Cancel, exit modal
4. Profile layer
   ↓ modeManager.active().actionFor(gesture) → GlassAction → ActionRouter
```

This separation is what lets:
- Sleep/wake gestures be **system-wide** regardless of the active profile
- The 5-second manual-lock on profile switching not interfere with screen on/off
- A modal own the user's attention (e.g. "Volume modal" — swipes change volume) without
  permanently rebinding gestures
- The screen-off fast path **bypass the gesture synthesiser entirely** so wake-via-long-press has
  zero synthesis latency (since `LONG_PRESS` is a raw event from the ring, not synthesised)

## 7. The pipeline thread

The whole pipeline (BLE callback dedup → frame parse → InteractionRouter → synthesiser → modal /
profile → ActionRouter → executor) runs on **one HandlerThread**, the `AndroidScheduler`. BLE
callbacks land on the binder thread; the BLE client immediately posts onto the scheduler. This
gives the gesture state machine **race-free single-threaded execution** without any locks.

The agent socket I/O is on its own thread (sending; receiving is non-blocking ACKs). The
foreground service notification + Compose UI live on the main thread.

Wakelock: **none**. The BT controller's interrupt wakes the CPU on every notify; no explicit
wakelock needed. (`com.ring.r08remote`'s persistent `PARTIAL_WAKE_LOCK` is one of its biggest
power waste — see [06](06-performance-and-power.md) §3.)

## 8. Lifecycle & resident running

- **`HaloRingService`** — foreground service, type `connectedDevice`. Quiet low-priority
  notification.
- **`BootReceiver`** — restarts the service on boot / unlock / package replace.
- **Battery optimisation exemption** — `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` requested in the
  first-run wizard; without it Android Doze kills our service after some hours.
- **Agent process** — also persists across our service restarts (started via `nohup`); has a
  heartbeat file `/data/local/tmp/halo.agent.heartbeat`. If the heartbeat is stale, the service
  re-spawns it.

See [06-performance-and-power.md](06-performance-and-power.md) for the low-power state machine
that gates `TOUCH_ENABLE/DISABLE` and BLE connection interval.

## 9. Where things live

| Concern | File(s) |
|---|---|
| BLE protocol constants | `core/.../ble/R08Protocol.kt` |
| Notify-frame parsing | `core/.../ble/R08Frame.kt` |
| Gesture state machine | `core/.../gesture/GestureSynthesizer.kt` (+ `Gestures.kt`, `Scheduler.kt`) |
| Top-level routing | `core/.../gesture/InteractionRouter.kt` (+ `SystemGestures.kt`) |
| Action vocabulary | `core/.../action/Action.kt` |
| Default profiles | `core/.../action/DefaultProfiles.kt` |
| Profile + auto-switch | `core/.../action/KeyMapProfile.kt`, `ModeManager.kt` |
| Action routing | `core/.../action/ActionRouter.kt` |
| Executor interface | `core/.../inject/ExecutorBackend.kt` |
| Device strategy interfaces | `core/.../device/DeviceStrategy.kt` |
| Android BLE client | `app/src/main/.../ble/AndroidR08BleClient.kt` |
| Production scheduler | `app/src/main/.../runtime/AndroidScheduler.kt` |
| Foreground service | `app/src/main/.../service/HaloRingService.kt` |
| Agent backend | `app/src/main/.../inject/AppProcessAgentBackend.kt` |
| Accessibility backend | `app/src/main/.../inject/AccessibilityBackend.kt` |
| Rokid strategies | `app/src/rokid/.../device/rokid/RokidStrategies.kt` |
| RayNeo strategies | `app/src/rayneo/.../device/rayneo/RayNeoStrategies.kt` |
| Agent body | `agent/src/main/.../Main.kt` |
