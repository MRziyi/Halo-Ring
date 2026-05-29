# 03 — Architecture

How the codebase is organised, how the layers interact, what plugs where. Read this once and you
should be able to navigate [`../app-project/`](../app-project/) without surprises.

Absorbs the content of the former Doc/03 (target platforms), Doc/06 (performance & power), and
Doc/07 (sensor utilisation + modules) — those were merged in 2026-05-27 as part of the v0.4 doc
pass. See `_archive/` for the pre-merge snapshots.

---

## 1. The product shape

**Goal**: one ring should work as a remote for **two different AR glasses** (Rokid + RayNeo X3
Pro) with **identical operations, UI content, and behaviour**. The implementation differences
(DPAD key injection on Rokid vs swipe-MotionEvent injection on X3 Pro) must be invisible.

We satisfy this with: **one Git repo, one `:core` Kotlin/JVM module (device-agnostic), and two
Android product flavors (`rokid`, `rayneo`)** that pull device-specific strategies from
flavor-specific source sets. Each flavor ships as a separate APK.

In v0.4 the **HaloRingService (ForegroundService) is the system's main entry point**, not the
Activity. The Activity is for configuration only and may be unopened for days at a time. Mirrors
Constellation-Glass's "Service spine + HUD-first" model (`~/Code/Projects/Constellation-Glass/Doc/GLASS-CLIENT-DESIGN.md`).

## 2. Module graph

```
:core      ─── pure Kotlin/JVM library (no Android deps; 282 tests) ───
   R08Protocol, R08Frame, RingEvent, R08BleClient (interface),
   GestureSynthesizer (the 12-gesture state machine), Scheduler,
   SystemGestures, InteractionRouter, Modal interface,
   GlassAction (sealed) + Capability + ModalSentinel + PluginAction codec,
   KeyMapProfile, DefaultProfiles, ModeManager, ActionRouter,
   ExecutorBackend (interface), DeviceStrategy interfaces
   (DisplayAdapter / GlassActionMapper / WearStateProvider / FeatureIntents),
   AccelProcessor (posture / free-fall / impact / wrist-shake),
   PowerPolicy (3-band IntervalMode), DeviceProfile,
   AdbMessage (wire packet for the agent bootstrap),
   AgentWireProtocol (KEY/TAP/SWIPE/AM/BC/SH line protocol).

:agent     ─── small Java/Kotlin dex; runs as shell uid ───
   LocalServerSocket("halo.agent"); reflects InputManager.injectInputEvent
   for ~1-3 ms per gesture. Built once, bundled as a :app asset.

:app       ─── Android application. Compose. ForegroundService.
   main/      HaloRingApplication, MainActivity (PairingActivity-shaped in v0.4),
              AppGraph, HaloRingService, BootReceiver,
              AndroidR08BleClient (BluetoothGatt impl of the core interface),
              AndroidScheduler (HandlerThread-backed Scheduler),
              HaloRingAccessibilityService,
              ExecutorBackend impls: AppProcessAgentBackend (LocalSocket to :agent),
              InotifydScriptBackend (fallback), AccessibilityBackend.
              v0.4-added: SystemBroadcastReceiver (Rokid temple system broadcasts).
              v0.4-removed: InAppFocusController, TempleFocusBridge, GuidedTour.
   src/rokid/ Rokid strategies + DeviceFlavorBindings.
   src/rayneo/ RayNeo strategies + DeviceFlavorBindings + Mercury AAR.

:test-plugin  ─── reference Doc/10 plugin app for integration testing ───

→ ./gradlew :app:assembleRokidDebug   ⇒  app-rokid-debug.apk
→ ./gradlew :app:assembleRayneoDebug  ⇒  app-rayneo-debug.apk
```

## 3. Runtime data flow

```
[R08 ring]
   ↓  BLE notify (e.g. `73 2D 03` for a single touch)
[AndroidR08BleClient]
   ↓  byte-level dedup (phase-0-calibrated window, ~40-60 ms)
   ↓  R08Frame.parse → RingEvent (GestureEvent / Health / Activity / Battery / Capability / ...)
   ↓  post onto AndroidScheduler.handler (the pipeline's single thread)
[InteractionRouter]
   ↓  screen-off fast path? wake gesture → ScreenWake → ActionRouter → done; else drop
   ↓  otherwise → GestureSynthesizer
[GestureSynthesizer]
   ↓  state machine: 4 raw → 12 vocab (TAP / DOUBLE_TAP / ... / DOUBLE_LONG_PRESS)
   ↓  timing windows: multi-tap (280 ms), combo (300 ms), long-press follow-up (400 ms)
[InteractionRouter — second pass]
   ↓  step 1: if base gesture (TAP/DOUBLE_TAP/SWIPE_UP/SWIPE_DOWN) AND useSystemKeyEvents=true
   ↓          → SystemKeyDispatcher (KEYCODE_DPAD_CENTER/BACK/DPAD_RIGHT/DPAD_LEFT) → done
   ↓  step 2: system slots (sleep / profileCycle / peekHud / AI_assistant)
   ↓  step 3: active modal — modal.handle(gesture)
   ↓  step 4: profile layer — modeManager.active().actionFor(gesture) → GlassAction
[ActionRouter]
   ↓  per-flavor GlassActionMapper.capabilityFor(action) → Capability
   ↓  pick highest-priority ExecutorBackend that has Capability + isReady()
[ExecutorBackend]
   ↓  AppProcessAgentBackend → mapper.primitives(action) → LocalSocket commands → agent
[:agent (shell uid)]
   ↓  parse line protocol → InputManager.injectInputEvent via reflection
[Android InputDispatcher]
   ↓  KeyEvent or MotionEvent goes to focused window
[Glasses system UI / app reacts → frame renders]
```

**v0.4 addition** — a second event source flows into the same `InteractionRouter`: temple
touchpad **system ordered broadcasts** received by `HaloRingService.SystemBroadcastReceiver`
(see §8.1). Ring and temple are unified at the routing layer.

## 4. The four device strategies

The single source of truth for "what's different between glasses" — interfaces in
[`core/.../device/DeviceStrategy.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/device/DeviceStrategy.kt):

### 4.1 `DisplayAdapter`

```kotlin
interface DisplayAdapter {
    val isBinocular: Boolean
    val contentWidthPx: Int
    val contentHeightPx: Int
}
```

- **Rokid Glasses**: `isBinocular=false`, content area **480×640 portrait** (per Rokid bare-metal
  docs §00 — corrects pre-v0.4 docs that said 480×480).
- **RayNeo X3 Pro**: `isBinocular=true`, content area 640×480 per eye. With the Mercury SDK AAR
  the Activity extends `BaseMirrorActivity` and gets free left/right mirroring; without it, draw
  content twice into a 1280×480 surface with parallax offset.

### 4.2 `GlassActionMapper`

```kotlin
interface GlassActionMapper {
    fun capabilityFor(action: GlassAction): Capability?
    fun primitives(action: GlassAction): List<InjectionPrimitive>
    fun supports(action: GlassAction): Boolean
}
```

`InjectionPrimitive` IR: `Key(keycode)` / `Tap(x,y)` / `Swipe(x1,y1,x2,y2,ms)` /
`StartActivity(comp, extras)` / `Broadcast(action, extras)` / `Shell(cmd)` / `A11yGlobal(action)`.

- Rokid `NavPrev` → `Key(KEYCODE_DPAD_UP)` (capability `NAVIGATE` via `KEY_EVENT`)
- RayNeo `NavPrev` → `Swipe(400, 240, 240, 240, 60ms)` (capability `NAVIGATE` via `TAP_SWIPE`)

### 4.3 `WearStateProvider`

Drives **TOUCH_DISABLE on the ring** when not worn (power saving) + ring hand-over between
glasses. Drives the `PowerPolicy.IntervalMode` decision.

- Rokid: `vendor.rkd.glasses.is_take_on` sysprop + `RokidDoorReceiver` broadcast + proximity
- RayNeo: Mercury SDK 佩戴检测 module (`MobileState.isWearing()` via reflection)
- Fallback: `ACTION_SCREEN_ON/OFF` proxy

### 4.4 `FeatureIntents`

Abstract feature → device-specific `am start` / `am broadcast` primitives. Both flavors implement
`openCamera / takePhoto / askVisualAI / openTranslate / openChat / openMusic / openSettings /
openGallery / launchApp(pkg)`. Rokid is fully populated; RayNeo has placeholders pending on-device
discovery via `pm list packages` / `dumpsys activity top`.

## 5. Executor backends

`ActionRouter` picks the highest-priority ready backend that has the action's required
`Capability`. Multiple backends can be alive simultaneously.

| # | Backend | Priority | Capabilities | Latency |
|---|---|---|---|---|
| 1 | **AppProcessAgentBackend** | 100 | NAVIGATE, KEY_EVENT, TAP_SWIPE, LAUNCH_INTENT, SHELL, + A11y globals | ~1-3 ms inject + ~3-7 ms socket overhead |
| 2 | **ShizukuBackend** (optional) | 90 | Same as agent | ~5-10 ms |
| 3 | **InotifydScriptBackend** | 60 | NAVIGATE, KEY_EVENT, TAP_SWIPE, LAUNCH_INTENT, SHELL | ~50-150 ms (`input` JVM spawn) |
| 4 | **PollScriptBackend** | 40 | Same | ~100-200 ms |
| 5 | **AccessibilityBackend** | 80 | BACK, HOME, RECENTS, NOTIFICATIONS (+ reads foreground pkg) | ~10-20 ms |

The agent is the performance win — persistent process, persistent connection, reflects
`InputManager.injectInputEvent(KeyEvent | MotionEvent, MODE_ASYNC)`. Line protocol over
`LocalSocket("halo.agent")`: `KEY <keycode>` / `TAP <x> <y>` / `SWIPE x1 y1 x2 y2 ms` / `AM <args>` /
`SH <raw>` / `PING`.

Accessibility cannot inject DPAD on Android 12 (API 33+). It's a helper for BACK/HOME/RECENTS and
the foreground-package signal, never a replacement for the agent.

## 6. The InteractionRouter (4-layer routing pipeline)

[`core/.../gesture/InteractionRouter.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/gesture/InteractionRouter.kt)
runs the layers top to bottom:

```
1. Screen-state gateway
   ↓ screen off: raw == wakeGesture → ScreenWake → done; else drop
2. Base-gesture system-KeyEvent passthrough (v0.4)
   ↓ if useSystemKeyEvents AND gesture ∈ {TAP, DOUBLE_TAP, SWIPE_UP, SWIPE_DOWN}
   ↓    SystemKeyDispatcher.dispatch(keycode) → done
3. System-level gestures (5 slots)
   ↓ sleep / profileCycle / peekHud / AI_assistant / capability-gated extras → done
4. Modal layer
   ↓ activeModal.handle(gesture) → GlassAction; sentinel Exit/Cancel/FireAndExit semantics
5. Profile layer
   ↓ modeManager.active().actionFor(gesture) → GlassAction → ActionRouter
```

Layer 2 is the v0.4 addition (hard-locked base passthrough). See [Doc/11 §3](11-v0.4-design.md)
for why and [Doc/04 §3.8](04-interaction-design.md) for behaviour detail.

## 7. Performance & power

### 7.1 End-to-end latency budget

```
①  Ring firmware: touch IC → RF03 → BLE frame  ─── ~5-20 ms (fixed)
②  Frame waits for next BLE connection event ──── 0 to conn_interval (lever A)
③  BLE radio → glasses BT controller → HAL ────── ~5-15 ms (system)
④  GestureSynthesizer window ──────────────────── 0 ms (optimistic / swipe / wake) or 280-400 ms (multi-tap / combo)
⑤  ActionRouter → ExecutorBackend → injection ── ~5-10 ms (agent) / 50-150 ms (input cmd)
⑥  System InputDispatcher → focused window ───── ~16-50 ms (1-2 frames)
```

Targets (p95): SWIPE / optimistic-TAP / LONG_PRESS = **50-80 ms**; ScreenWake = **<150 ms**;
multi-tap = synthesis cost + ~50-80 ms; LP+combo = +400 ms.

### 7.2 The two latency levers

**Lever A — BLE connection interval** (step ②). On connect call
`requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` → ~15-30 ms. Idle-relax timer drops to
`BALANCED` (~75-100 ms) after 10 s; screen-off + worn drops to `LOW_POWER` (~200-500 ms). Next
gesture snaps back to HIGH.

**Lever B — Injection path** (step ⑤). Agent (~1-3 ms) > Shizuku (~5-10 ms) > inotifyd (~50-150 ms)
> shell polling (~100-200 ms). Default agent; fallback ladder when unavailable.

### 7.3 Power state machine

`PowerPolicy` (`:core`, 12 tests) decides `(touchEnabled, intervalMode, disconnect)` from
`(worn, screenOn, lastActivityMs, lastWornMs, nowMs)`:

| Wear state | Screen | Touch IC | BLE interval | Reason |
|---|---|---|---|---|
| Not worn for ≥5 min | (n/a) | DISABLE | (disconnect) | Save everything |
| Not worn <5 min | (n/a) | DISABLE | BALANCED | Quick re-arm if user puts ring back on |
| Worn | OFF | **ENABLE** | **SLOW** (~200-500 ms) | Wake-gesture must remain listenable |
| Worn | ON, active (<10 s ago) | ENABLE | HIGH (~15-30 ms) | Low-latency stream |
| Worn | ON, idle (≥10 s) | ENABLE | BALANCED (~75-100 ms) | Save while not actively interacting |

Touch IC stays on when worn + screen-off so the wake-gesture works.

### 7.4 The three power wastes we avoid (vs a naïve ring-remote implementation)

- **No persistent CPU wakelock**. BLE controller IRQ wakes the CPU on every notify; that's enough.
- **No app-level scan loop**. `connectGatt(autoConnect=true)` — BT stack handles low-duty-cycle
  background scanning. App-level scan only on explicit "find ring" / `ForceReconnect`.
- **Event-driven injection**, not polled. Agent LocalSocket OR inotifyd. Zero CPU when idle.

Plus our foreground notification is `IMPORTANCE_LOW` + silent.

### 7.5 Reliability

- **De-duplication**: BLE may re-deliver identical notify within ms. Drop byte-identical packets
  in `AndroidR08BleClient` before parsing. Window calibrated per phase-0 (~40-60 ms).
- **Threading**: pipeline runs on one HandlerThread (`AndroidScheduler`). BT callbacks post via
  `scheduler.post`. No shared mutable state → no races, no locks.
- **Connection robustness**: `connectGatt(autoConnect=true)` + 30 s scan timeout + MAC whitelist
  via `RingPairingPrefsStore` + `armWakeSwallow()` on every reconnect.
- **Idempotence trackers**: `lastTouchEnabledRequested` / `lastIntervalModeRequested` prevent BLE
  write storms; reset on disconnect/stop.

## 8. Target platforms

### 8.1 Rokid Glasses (YodaOS-Sprite, Android 12 Go)

Authoritative source: `~/Code/Projects/Constellation/reference/rokid-glass/bare-metal-docs/`
(captured 2026-05-26 from custom.rokid.com).

| Property | Value |
|---|---|
| Model | RG-glasses |
| OS | YodaOS-Sprite (Android 12 Go, API 32) |
| Display | JBD JBD4020 Micro-LED, **right eye only**, **480×640 portrait** |
| IMU | InvenSense ICM-4x6xx (accel + gyro + freefall) via I3C |
| Speech co-proc | NXP RT600 (iFlytek + Rokid KWS) |
| Default locale | zh-CN |

**Input** — Rokid publishes temple touchpad actions as **system ordered broadcasts** AND
standard Android KeyEvents. Halo Ring's `SystemBroadcastReceiver` (on `HaloRingService`)
registers these:

| KeyType | Action constant | Notes |
|---|---|---|
| `CLICK` | `com.android.action.ACTION_SPRITE_BUTTON_CLICK` | Side-key single click |
| `DOUBLE_CLICK` | `com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK` | **System-occupied = back; can't abortBroadcast()** |
| `LONG_PRESS` | `com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS` | Side-key long press |
| `ACTION_TWO_FINGER_SINGLE_TAP` | (same as constant) | |
| `ACTION_TWO_FINGER_DOUBLE_TAP` | (same) | |
| `ACTION_TWO_FINGER_SWIPE_FORWARD` | (same) | |
| `ACTION_TWO_FINGER_SWIPE_BACK` | (same) | |
| `ACTION_SETTINGS_KEY` | (same — 二指长按) | Used to launch Halo Ring config |
| `AI_START` | `com.android.action.ACTION_AI_START` | **System-occupied; can't intercept** |

`priority=100` + `abortBroadcast()` available for non-system-occupied entries. KeyEvents on the
foreground Activity are still consumed by Compose's `FocusManager` for navigation when the
config Activity is visible.

**System launcher**: Sprite Launcher (`com.rokid.os.sprite.launcher`) is focus-based with DPAD
key transport. 21 launchable page Activities including:
- Camera: `am start -n com.rokid.os.sprite.launcher/.page.camera.CameraPageActivity`
- AI chat (everyday): `…/.page.chat.ChatPageActivity`
- Translate: `…/.page.translate.TranslatePageActivity`
- Word tips (teleprompter): `…/.page.wordtips.WordTipsPageActivity`
- Music / Navigation / Payment / Settings (full list in `bare-metal-docs/`)
- "Open any installed app": `am broadcast -a com.rokid.os.sprite.launcher.cmd --es cmd open_app --es pkg <pkg>`
- Visual AI: `am broadcast -a com.rokid.visualaidemo.ACTION_START`

These are wired into `RokidFeatureIntents` (`app/src/rokid/.../RokidStrategies.kt`).

**No touchscreen on Rokid.** Any `Modifier.pointerInput { }` / `detectTapGestures` / drag in
shared code is dead. Stay focus-driven; `Modifier.clickable()` (DPAD_CENTER triggers it) is fine.

**Wear detection**: `RokidDoorReceiver` broadcast + proximity sensor + hinge state.

**ADB bootstrap**: 5-pin development cable + companion phone app (one-time). Then `pm grant
WRITE_SECURE_SETTINGS` lets us toggle wireless debugging on subsequent reboots.

### 8.2 RayNeo X3 Pro (RayNeo AIOS 2.0)

Source: official docs at https://rayneo.gitbook.io/rayneo-devdoc/.

| Property | Value |
|---|---|
| Model code | ARGF20 |
| OS | RayNeo AIOS 2.0 (Android 12+, API ≥ 31) |
| SoC | Snapdragon AR1 Gen 1 |
| RAM/ROM | 4 GB / 32 GB |
| Display | Full-colour MicroLED + diffractive waveguide; **dual-eye 1280×480 (640×480/eye)**; ~30° FOV |
| Cameras | 12 MP RGB + VGA spatial |
| Sensors | `TYPE_GAME_ROTATION_VECTOR` @ 219 Hz; Mercury SDK 佩戴检测 |
| Weight | 76 g |

**Input** — temple touchpad delivers raw Android `MotionEvent`s to the foreground Activity. The
ARSDK (Mercury) `TouchDispatcher` recognises gestures and dispatches `TempleAction`s:
`Click`, `DoubleClick`, `TripleClick`, `LongClick`, `SlideForward/Backward/Upwards/Downwards`,
`TpSlideContinuous`, X3-only `onTPDoubleFingerClick/LongClick`.

To drive the system UI we inject **swipe MotionEvents** (the launcher's focus controller expects
them). DPAD keys *might* also work — verify on first hardware.

**Mercury SDK** (`com.ffalcon.mercury.android.sdk`, AAR from open.rayneo.com): 合目处理
(`BaseMirrorActivity`), 焦点管理 (`FocusHolder` + `FocusInfo`), 触控板 (`BaseTouchActivity`),
3D 效果, audio, camera, IMU, phone link, 佩戴检测. We base the rayneo Activity on
`BaseMirrorActivity` for free binocular mirroring.

**Sideload**: Settings → swipe left ×10 → developer mode → USB-C data cable → adb.

**Intent map TBD** — RayNeo doesn't publicly document launcher Intent strings. Discover on-device
via `dumpsys activity top` / `pm list packages`. Until then `RayNeoFeatureIntents` falls back to
`monkey -p <pkg> -c LAUNCHER 1`.

### 8.3 Same / different cheat-sheet

| | Rokid | RayNeo X3 Pro |
|---|---|---|
| Android | 12 (API 32) | 12+ (API ≥ 31) |
| Display | mono 480×640 portrait | binocular 1280×480 |
| Temple input | **Ordered broadcasts** + DPAD KeyEvents | Mercury TouchDispatcher (MotionEvents) |
| Inject for nav | DPAD KeyEvents (system Sprite Launcher) | Swipe MotionEvents |
| Feature intents | Fully documented (Sprite Launcher 21 pages) | TBD via dumpsys |
| Wear detection | `RokidDoorReceiver` + sysprop | Mercury 佩戴检测 |
| Public system-app dump | Yes (rokid-docs) | No |

## 9. Sensor utilisation matrix (formerly Doc/07 §1.1)

Confidence: 🟢 R08-verified per SPEC v3 / 🟡 plausible / 🔵 still speculative / 🔴 conflicting.

| Source | Data | Direction | Power cost | Role | Confidence |
|---|---|---|---|---|---|
| Touch ring | 4 raw events (`73 2D 01..04`) | push | Touch IC always-on while enabled = dominant ring drain | **Control core** | 🟢 |
| Touch status echo | `73 2A` | push | — | Wear-state signal (charging dock detection) | 🟢 |
| Battery | `0x03 <level>` poll + `73 0C` push | both | trivial | Passive HUD + adaptive | 🟢 |
| Activity totals push | `73 12 <steps_BE24, kcal_BE24/1000, dist_BE24/1000>` | push | zero extra | Passive stats | 🟢 |
| Activity totals canonical | `0x48` 14-byte BE response | pull | trivial | Passive stats (richer than push) | 🟢 |
| HR (real-time) | `0x69 01 01` start / `0x6A` stop | start/stop stream | PPG LED on = **large** | On-demand snapshot (15-25 s convergence) | 🟢 |
| SpO2 (real-time) | `0x69 03 01` | same | same | On-demand | 🟢 |
| Stress / HRV / Temp | `0x69 <kind> 01` (8/10/11) | same | same | Progress-only on this fw per SPEC §4.5 | 🟡 |
| Wear via `0x69 errCode` | err byte in response | passive | — | Cheap wear-state hint | 🟢 |
| HR history / HRV / stress / step / sleep history | `0x15` / `0x39` / `0x37` / `0x43` / `0x44` multi-packet | pull | low | On-demand | 🟢 per SPEC §4.7 |
| Auto-monitor cadence | `0x16` / `0x2C` / `0x36` / `0x38` | r/w | low | Config (ring is cadence master) | 🟢 |
| Accelerometer | `0xA1` 16B frame, 3-axis int16 LE payload | push (subscription) | low (~64 B/s when ON) | **Spatial — AccelProcessor: posture/free-fall/impact/wrist-shake** | 🟢 |
| LED + vibration | `0x50 [0x55, 0xAA]` (Find Ring) | write | trivial | **Output channel** | 🟢 verified on burn-in |
| Soft reboot | `0x08` | write | trivial | (Avoid — `0x08` is OTA-mode entry; would brick) | 🟢 negative-verified |
| Capability bitmap | SetTime 14B response + `0x3C` 9B response | pull (once per connect) | trivial | Feature gating per fw | 🟢 verified |
| Time sync | `0x01` BCD UTC+8 (per SPEC §4.8) | write (once per connect) | trivial | Required for any history read | 🟢 |
| Sport session | `0x77 [01, sport_type]` start, `0x78` ticks (duration + HR), `0x77 [00]` stop | push during session | medium (~64 B/s) | Workout tracking | 🟢 verified |
| Firmware / HW revision | GATT `0x2A26` / `0x2A27` | pull | trivial | About panel + identity | 🟢 |
| Spontaneous reconnect quirk | SPEC §6.5 — 10-20 s cycle on this fw | — | — | Self-heals; ignore | 🟡 fw-specific |

Glasses-side sensors (Doc/03 covers in §8.1-§8.2): IMU (head pose), wear detection, screen state,
battery, foreground app (drives auto-switch profile).

## 10. Threading + lifecycle

- **`HaloRingService`** — ForegroundService, type `connectedDevice`, low-priority silent
  notification.
- **`BootReceiver`** — restarts the service on boot / unlock / package replace.
- **Battery optimisation exemption** — `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` requested in the
  pairing flow; without it Android Doze kills the service after some hours.
- **Agent process** — persists across our service restarts (`nohup`); heartbeat file
  `/data/local/tmp/halo.agent.heartbeat`; service re-spawns if stale.
- **No persistent wakelock** (see §7.4).
- **Pipeline thread**: one HandlerThread (`AndroidScheduler`). All `:core` state machine
  mutations land here. BT callbacks post-onto. Agent socket I/O hops to `Dispatchers.IO`.
  HUD overlay `show/hide/setPosition` are `runOnMain`-wrapped internally.

## 11. v0.4 cuts and additions

| Status | Component |
|---|---|
| **Added** | `SystemBroadcastReceiver` (Rokid temple system broadcasts → InteractionRouter) |
| **Added** | `SystemKeyDispatcher` interface in `:core` + `ActivitySystemKeyDispatcher` in `:app` |
| **Added** | `GestureConfig.useSystemKeyEvents` (default true) + `reverseSwipeSemantics` (default false) |
| **Removed** | `InAppFocusController` — Compose `FocusManager` + system KeyEvents replace it |
| **Removed** | `TempleFocusBridge` scaffolding — system broadcast path replaces it |
| **Removed** | `GuidedTour` — Test Arena does the same job |
| **Shrunk** | `FirstRunWizardScreen` 5 → 1 step (pair ring only) |
| **Shrunk** | `AdvancedScreen`, `AboutScreen`, `PowerConnectionScreen` |
| **Converted** | `StatusScreen` full-screen panel → HUD-overlay trigger |
| **Reorganised** | Settings 10 flat items → 5 groups (Ring / Vitals / Gestures / Plugins / More) |

See [Doc/11 §4-§5](11-v0.4-design.md) for rationale. The deletes happen in code refactor C1
(Doc/11 §11).

## 12. Where things live

| Concern | File(s) |
|---|---|
| BLE protocol constants | `core/.../ble/R08Protocol.kt` |
| Notify-frame parsing | `core/.../ble/R08Frame.kt` |
| Gesture state machine | `core/.../gesture/GestureSynthesizer.kt` (+ `Gestures.kt`, `Scheduler.kt`) |
| System KeyEvent dispatch | `core/.../gesture/SystemKeyDispatcher.kt` + `app/.../ui/ActivitySystemKeyDispatcher.kt` |
| Top-level routing | `core/.../gesture/InteractionRouter.kt` (+ `SystemGestures.kt`) |
| Action vocabulary | `core/.../action/Action.kt` |
| Default profiles | `core/.../action/DefaultProfiles.kt` |
| Profile + auto-switch | `core/.../action/KeyMapProfile.kt`, `ModeManager.kt` |
| Action routing | `core/.../action/ActionRouter.kt` |
| Plugin protocol codec | `core/.../action/GlassActionCodec.kt` + `PluginAction` variant |
| Power policy | `core/.../power/PowerPolicy.kt` |
| Accelerometer processing | `core/.../sensor/AccelProcessor.kt` |
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
| HUD overlay | `app/src/main/.../ui/hud/HudOverlay.kt` + `HudEvent.kt` + `HudServiceHost.kt` |
