# 13 — Handoff & TODO

> **For the next agent picking up this project.** This document is the canonical "where we are,
> what's left, and where to look" — keep it current as you work. If a TODO here is wrong, fix it.

Last updated: 2026-05-13 (after the **Halo Ring rebrand h**: full rename of OUR app from internal
codename `R08-Remote` to the public product name **Halo Ring · 环意** by Zack 紫意. Package
`com.r08remote.app` → `com.halo.ring`; classes `R08RemoteApplication` → `HaloRingApplication`,
`R08RemoteService` → `HaloRingService`, `R08AccessibilityService` → `HaloRingAccessibilityService`,
`R08App` → `HaloRingApp`, `R08Theme/Colors/Type` → `HaloRingTheme/HaloColors/HaloType`; agent socket
`r08agent` → `halo.agent`, dex file `r08agent.dex` → `halo-agent.dex`. Hardware references to the
**QRing R08 ring** (`R08Protocol`, `R08Frame`, `R08BleClient`, `AndroidR08BleClient`,
`FakeR08BleClient`) intentionally **kept** — those name the device model, not our app. Adaptive
launcher icon at `app/src/main/res/drawable/ic_launcher_foreground.xml` (master at
`Doc/brand/v10a-aperture-arcs.svg`); bilingual `app_name` strings; slogan **"Where the ring goes,
the world moves." / 「环之所至，意之所达」** in About. Round-trip verified on a OnePlus 9 Pro /
Android 14: 172/172 unit tests green; both flavor APKs at 14 MB; foreground service starts as
`HaloRingService` type=connectedDevice; `HaloA11y` captures foreground-package events; agent
bootstraps under shell uid with median PING RTT 5.14 ms.

Earlier session — **audit-driven fix pass D1–D11**: PowerPolicy gains a three-band
`IntervalMode { HIGH, BALANCED, SLOW }` so worn+screen-off correctly relaxes BLE to ~200-500 ms
(Doc/06 §3.2); battery poll + vitals snapshot now self-recover on disconnect; agent dispatch keeps
CPU-bound mapper work off `Dispatchers.IO`; first-run wizard relabelled 5-of-5; unused WAKE_LOCK
permission removed. The only software work left is the SPAKE2 pairing handshake and TLS-wrapped
ADB connection, which need hardware-validated cryptographic implementation — see B12-real below.

---

## 1. State snapshot

### 1.1 What's the project

A QRing **R08 smart ring** used as a wireless remote for two pairs of AR glasses (**Rokid
Glasses** and **RayNeo X3 Pro**). One ring, one design, two flavor builds. Auto-hand-over
between the two glasses by wear state. See [01-overview.md](01-overview.md) for the elevator
pitch.

### 1.2 What hardware do we have

**None yet.** Both pairs of glasses + the ring are on order. Almost everything below is
implementable / verifiable without the hardware; the parts that aren't are explicitly tagged
🔌 (hardware-required).

### 1.3 What's solid

| Layer | Status | Where |
|---|---|---|
| BLE protocol spec | ✅ fully reverse-engineered; on-device verification still pending | [02-hardware-and-protocol.md](02-hardware-and-protocol.md) |
| Architecture (modules, runtime data flow, four device strategies, executor backends, threading) | ✅ designed; stable | [04-architecture.md](04-architecture.md) |
| Interaction design (12 gestures, 4 profiles, system gestures, modal layer, hand-over) | ✅ designed, with full mapping tables | [05-interaction-design.md](05-interaction-design.md) |
| Performance & power budget (targets, two big levers, state machine) | ✅ designed | [06-performance-and-power.md](06-performance-and-power.md) |
| Sensor matrix + functional modules | ✅ designed | [07-sensors-and-modules.md](07-sensors-and-modules.md) |
| UI design (3 tabs, screens, gesture-hint mode, in-app nav, mockup) | ✅ designed + HTML mockup | [08-ui-design.md](08-ui-design.md), [ui-mockup.html](ui-mockup.html) |
| User manual | ✅ written | [09-user-manual.md](09-user-manual.md) |
| Developer guide | ✅ written | [10-developer-guide.md](10-developer-guide.md) |
| Verification checklists | ✅ written | [11-verification-checklists.md](11-verification-checklists.md) |
| Research provenance + R08-Dev.md errata | ✅ written | [12-research-and-references.md](12-research-and-references.md) |

### 1.4 What code is in the repo

**`:core` (pure Kotlin/JVM, `./gradlew :core:test` — 172 unit tests across 15 suites, all passing):**

- `core/.../ble/R08Protocol.kt` — constants, command builder with checksum
- `core/.../ble/R08Frame.kt` — pure notify-frame parser
- `core/.../ble/RingEvent.kt` — sealed event types
- `core/.../ble/R08BleClient.kt` — interface (Android impl in :app is **done**, see §1.4 below)
- `core/.../ble/FakeR08BleClient.kt` — JVM stand-in for tests + dev-without-hardware ✅
- `core/.../gesture/Gestures.kt` — 12-gesture vocab + `GestureConfig`
- `core/.../gesture/Scheduler.kt` — testable scheduler abstraction
- `core/.../gesture/GestureSynthesizer.kt` — **fully implemented** state machine (tap counting,
  combo windows, long-press follow-up, wake-swallow)
- `core/.../gesture/SystemGestures.kt` — global gestures config (wake/sleep/cycle/peek/reconnect)
- `core/.../gesture/InteractionRouter.kt` — top-level routing (screen gateway → system → modal →
  profile); exposes `onGestureRecognized` / `onScreenOffGesture` / **`inAppShortCircuit`**
  callback hooks. The `inAppShortCircuit` slot is the new foreground-bypass entrypoint (Doc/08 §9.1).
- `core/.../action/Action.kt` — sealed `GlassAction` (~30 actions) + `Capability` enum +
  `ModalSentinel` (moved from `gesture/` to keep the sealed hierarchy in one package)
- `core/.../action/KeyMapProfile.kt` + `DefaultProfiles.kt` — 4 default profiles with all 12 slots
  filled
- `core/.../action/ModeManager.kt` — profile state, cycle, auto-switch, manual lock
- `core/.../action/ActionRouter.kt` — picks the highest-priority ready backend
- `core/.../inject/AgentWireProtocol.kt` — pure encoder for the agent's line protocol (KEY / TAP /
  SWIPE / AM / BC / SH); JVM-testable so the format is locked down without an Android device ✅
- `core/.../power/PowerPolicy.kt` — pure decision function for `touchEnabled` / `intervalMode` /
  `disconnect` given `(worn, screenOn, lastActivityMs, lastWornMs, nowMs)`. Implements the full
  three-band table from [06 §3.2](06-performance-and-power.md): worn+screen-on+active → HIGH,
  worn+screen-on+idle → BALANCED, **worn+screen-off → SLOW** (`CONNECTION_PRIORITY_LOW_POWER`,
  ~200-500 ms). Called from [R08RemoteService.reconcilePower] ✅
- `core/.../gesture/SystemGestures.kt` — now also exposes a `Slot` enum + `withSlot` +
  `gestureFor` + **`conflict`** (returns the slot already bound to a gesture, used by the system
  gestures settings UI to warn before a clash) ✅
- `core/.../action/KeyMapProfile.kt` — adds `withMapping(gesture, action)` for the profile editor ✅
- `core/.../action/GlassActionCodec.kt` — pure string encoder/decoder (`NavPrev` ↔ `"NavPrev"`,
  `Shell("…")` ↔ `"Shell:…"`); 10 round-trip tests. Used by [ProfilesPrefsStore] for DataStore
  persistence (B13) ✅
- `core/.../action/Action.kt` — `ModalSentinel` gains a `FireAndExit(payload)` variant so a modal
  can dispatch one final action + close itself in one gesture (used by [RecentsModal]) ✅
- `core/.../modal/{VolumeModal, BrightnessModal, RecentsModal, AIDictateModal}.kt` —
  4 modal-layer state machines (B10); each implements [Modal.handle] with the gesture vocabulary
  documented in Doc/05 §6. 17 cases in `ModalsTest.kt` ✅
- `core/.../adb/AdbMessage.kt` — ADB wire packet (24-byte header + payload), serialise/parse,
  command-code constants (CNXN/AUTH/OPEN/OKAY/CLSE/WRTE/STLS). Lives in `:core` so the wire
  format is JVM-testable; 5 round-trip cases in `AdbMessageTest.kt` ✅
- `core/.../ble/R08Protocol.kt` — adds `REAL_TIME_HR_START` / `REAL_TIME_SPO2_START` /
  `REAL_TIME_STRESS_START` / `REAL_TIME_STOP` for B6 on-demand vitals measurement ✅
- `core/.../inject/ExecutorBackend.kt` — interface for the 5 backends
- `core/.../device/DeviceStrategy.kt` — `DisplayAdapter` / `GlassActionMapper` /
  `WearStateProvider` / `FeatureIntents` / `InjectionPrimitive` / `A11yGlobalAction` interfaces
- `core/.../DeviceProfile.kt` — runtime detection enum
- Tests: `GestureSynthesizerTest.kt` (~25 cases), `R08FrameTest.kt`, `ManualScheduler.kt`

**`:app` (Android, Compose, rokid + rayneo flavors; both flavors build a 9.1 MB debug APK):**

UI (implemented + wired to runtime):
- `app/src/main/.../ui/R08Theme.kt` — 8 design tokens + type scale + `Modifier.r08Focus()`
- `app/src/main/.../ui/Components.kt` — `StatusBar`, `FocusableRow`, `ListRow`, `Cta`,
  `AccentBar`, `MetricCell`
- `app/src/main/.../ui/TabBar.kt` — 3-tab strip; reports focus state to
  `InAppFocusController.focusOnTabStrip`
- `app/src/main/.../ui/R08App.kt` — root composable; manages tab state + sub-screen stack;
  reads `FeedbackPrefs` via `collectAsState(graph.feedbackPrefs.flow)` ✅
- `app/src/main/.../ui/InAppFocusController.kt` — in-app fast path; remaps `NavPrev/Next` to
  tab cycling when focus is on the tab strip (since the ring has no L/R swipes). **All
  Compose-state mutations are now posted onto the main-thread Handler** so the scheduler-thread
  caller doesn't violate Compose's main-thread requirement ✅
- `app/src/main/.../ui/screens/VitalsScreen.kt` — biometrics dashboard
- `app/src/main/.../ui/screens/SettingsRootScreen.kt` — 8-item settings list
- `app/src/main/.../ui/screens/StatusScreen.kt` — connection + power + BLE quality
- `app/src/main/.../ui/screens/FeedbackScreen.kt` — gesture-hint toggle + feedback prefs
- `app/src/main/.../ui/screens/FeedbackPrefsStore.kt` — **DataStore-backed prefs persistence** ✅
  (with separate "user-has-explicitly-set" bit so the 5-min auto-hint-after-pairing override
  doesn't clobber user preference)
- `app/src/main/.../ui/Navigation.kt` — sealed `SubScreen` hierarchy + `SystemGestureSlot` enum;
  the typed navigation stack `R08App` uses for settings drilldown ✅
- `app/src/main/.../ui/SettingsCatalog.kt` — curated list of user-bindable `GlassAction`s grouped
  by category, used by `ActionPickerScreen` ✅
- `app/src/main/.../ui/screens/ProfilesListScreen.kt` — profile list with active-badge ✅
- `app/src/main/.../ui/screens/ProfileEditorScreen.kt` — 12-row gesture→action editor; system
  slots greyed out ✅
- `app/src/main/.../ui/screens/ActionPickerScreen.kt` — grouped ~35-entry action picker ✅
- `app/src/main/.../ui/screens/SystemGesturesScreen.kt` — 5 system-gesture slots with inline
  conflict warnings ✅
- `app/src/main/.../ui/screens/GesturePickerScreen.kt` — 12-gesture picker + "in use by Slot X"
  conflict markers + a "(disable this slot)" row ✅
- `app/src/main/.../ui/screens/RingScreen.kt` (B4) — MAC + firmware + RSSI + battery + advertised
  name; **Find ring** / **Shutdown** / **Forget** CTAs wired to the BLE client ✅
- `app/src/main/.../ui/screens/PowerConnectionScreen.kt` (B5) — 3 timing windows as tap-to-cycle
  presets (180/220/280/340/400 ms etc.) + 3 latency switches (optimisticSingleTap, awaitCombos,
  awaitLPcombos) + a "Connection (automatic)" explanation block. Edits flow into the active
  profile's [GestureConfig] ✅
- `app/src/main/.../ui/screens/AdvancedScreen.kt` (B7) — debug HUD / latency-measurement / spatial
  mode toggles + 4 actions (deep-link to A11y / battery exemption / re-run ADB wizard / export
  latency CSV). Toggle state is in-memory (Activity scope) ✅
- `app/src/main/.../ui/screens/AboutScreen.kt` (B8) — version, detected DeviceProfile, credits,
  docs pointer ✅
- `app/src/main/.../ui/screens/FirstRunWizardScreen.kt` (B9) — 5-step wizard (Welcome / ADB /
  A11y / Battery / Pair). Shown when `FirstRunPrefsStore.completedFlow == false`. Each step has
  appropriate deep-link / scan CTAs ✅
- `app/src/main/.../ui/screens/FirstRunPrefsStore.kt` — boolean DataStore flag (B9 support) ✅
- `app/src/main/.../ui/screens/ProfilesPrefsStore.kt` (B13) — JSON-over-DataStore persistence for
  the editable profile list + system gestures. Format: `org.json.JSONObject` + [GlassActionCodec].
  Corrupt input falls back to defaults; never crashes ✅
- `app/src/main/.../adb/` — embedded ADB-over-Wi-Fi support (B12-real partial):
  - `AdbBootstrap.kt` — top-level coordinator: `keyPair`, `discoverPairingEndpoint`, `pairWithCode`,
    `pushAgentDex`, `grantWriteSecureSettings`, `startAgent`. Wires the impl files below ✅
  - `AdbCrypto.kt` — RSA-2048 keypair + self-signed X.509 cert via BouncyCastle. Plus the
    legacy little-endian ADB-public-key encoding used for the pre-TLS AUTH handshake. **DONE.** ✅
  - `AdbMdnsDiscovery.kt` — `_adb-tls-pairing._tcp.` / `_adb-tls-connect._tcp.` via Android
    [`NsdManager`](https://developer.android.com/reference/android/net/nsd/NsdManager). **DONE.** ✅
  - Still **TODO** (need hardware to validate): the SPAKE2 pairing handshake and the TLS-wrapped
    ADB connection. The decompiled v2 reference at
    [`decompiled/v2/.../AdbPairingClient.java`](../decompiled/v2/sources/com/ring/r08remote/adb/AdbPairingClient.java)
    and [`AdbConnection.java`](../decompiled/v2/sources/com/ring/r08remote/adb/AdbConnection.java)
    is ~1600 lines combined of BigInteger SPAKE2 + BouncyCastle TLS — porting blind without a
    test vector is dangerous. The skeleton's `pairWithCode` / `pushAgentDex` return
    `Result.Failure("not yet implemented")` with specific pointers to the missing pieces.
- `app/src/main/.../ui/screens/AdvancedPrefsStore.kt` — DataStore wrapper for the 3 Advanced
  toggles (debug HUD / latency / spatial). Mirrors [`FeedbackPrefsStore`] ✅
- `app/src/main/.../ui/screens/VitalsPrefsStore.kt` + `VitalsPrefsScreen.kt` — 5-row Vitals prefs
  (HR-on-HUD / activity overlay / auto-snapshot interval / CSV export / wear-detection gate) with
  DataStore persistence ✅
- `app/src/main/.../ui/LocalAppGraph.kt` — `CompositionLocal` exposing [`AppGraph`] to deep
  composables (used today by no callers; a back-door for future screens that need flow access
  without parameter threading) ✅
- `app/src/main/.../ui/hud/HudEvent.kt` — 6 event types + friendly-name helpers
- `app/src/main/.../ui/hud/HudOverlay.kt` — `WindowManager` `TYPE_APPLICATION_OVERLAY`
  Compose-hosted view, 6 visual variants, auto-hide
- `app/src/main/.../ui/hud/HudServiceHost.kt` — **new**: LifecycleOwner + ViewModelStoreOwner +
  SavedStateRegistryOwner bundle so a plain `Service` can host the Compose-based HUD ✅

System/lifecycle (all implemented):
- `app/src/main/.../R08RemoteApplication.kt` — Application class; builds `AppGraph` ✅
- `app/src/main/.../MainActivity.kt` — Compose host; tracks `isInForeground`; starts the
  `R08RemoteService`; persists prefs via `graph.feedbackPrefs.updatePrefs` ✅
- `app/src/main/.../di/AppGraph.kt` — runtime detection + bindings; exposes `scheduler`,
  `bleClient`, `feedbackPrefs` ✅
- `app/src/main/.../service/R08RemoteService.kt` — **implemented** ✅
  - Assembles the full pipeline `Scheduler → BLE → Synth → Router → ActionRouter → Backend`
  - `serviceScope = CoroutineScope(SupervisorJob() + scheduler.coroutineDispatcher)` so all
    suspending pipeline work stays on the scheduler thread (no races)
  - Wires HUD callbacks (A5), foreground bypass (A6 via `inAppShortCircuit`), DataStore (A7)
  - Calls `reconcilePower()` (using [`PowerPolicy`](../app-project/core/src/main/kotlin/com/r08remote/core/power/PowerPolicy.kt))
    on every wear/screen/gesture event + has an idle-relax timer that drops to BALANCED after
    10 s of no activity (Doc/06 §3.5)
  - Quiet low-priority foreground notification; **no wakelock**
- `app/src/main/.../ble/AndroidR08BleClient.kt` — **implemented** ✅
  - BluetoothGatt scan + connect (autoConnect=true) + notify + CCCD
  - 800/500/1500 ms staggered TOUCH_ENABLE / TOUCH_MODE / first BATTERY_QUERY init
  - byte-level dedup (50 ms default); 30-min battery poll
  - **30 s scan timeout** so we don't sit at LOW_LATENCY indefinitely when no ring is in range
  - `setActiveMode(active)` toggles `CONNECTION_PRIORITY_HIGH` ↔ `BALANCED`
- `app/src/main/.../inject/AppProcessAgentBackend.kt` — **implemented** ✅
  - LocalSocket client to the agent; lazy + reconnect-on-IOException
  - Heartbeat freshness check on `isReady()`
  - `Mutex` serialises send/receive; `withContext(Dispatchers.IO)` keeps the scheduler thread free
- `app/src/main/.../inject/AccessibilityBackend.kt` — **implemented** ✅ (B11) — claims BACK / HOME /
  RECENTS / NOTIFICATIONS; walks the mapper's primitives, executes the first `A11yGlobal` we can
  via `service.performGlobalAction`. API-gates `LOCK_SCREEN` (28+) and `TAKE_SCREENSHOT` (30+).
- `app/src/main/.../accessibility/R08AccessibilityService.kt` — alive: on `TYPE_WINDOW_STATE_CHANGED`
  it calls `foregroundPackageListener` which `R08RemoteService` wires to `ModeManager.onForegroundPackage`
  via `scheduler.post` (auto-switch by foreground app) ✅
- `app/src/main/.../runtime/AndroidScheduler.kt` — HandlerThread-backed Scheduler; **also exposes
  `coroutineDispatcher` (via `Handler.asCoroutineDispatcher`)** for the pipeline coroutine scope ✅
- `app/src/main/.../receiver/BootReceiver.kt` — starts the service on boot ✅

Per-flavor strategies (mostly populated):
- `app/src/rokid/.../device/rokid/RokidStrategies.kt` — DPAD-key mapper + Sprite Launcher Intent
  map; ✅ done (placeholder values until on-device verification)
- `app/src/rayneo/.../device/rayneo/RayNeoStrategies.kt` — swipe-MotionEvent mapper; Intent map
  is mostly empty (X3 Pro's launcher activities aren't publicly documented — see §3.B6 below)
- `app/src/rokid/.../di/DeviceFlavorBindings.kt` + `app/src/rayneo/.../di/DeviceFlavorBindings.kt`
  — wire the per-device strategies into AppGraph

Manifest, theme XML:
- `app/src/main/AndroidManifest.xml` — permissions declared, AccessibilityService stub
- `app/src/main/res/xml/r08_accessibility_config.xml` — A11y config

**`:agent`** (compiles against `compileOnly(android.jar)`; built via `./gradlew :agent:jar`; the
jar must then be d8'd into a dex and bundled as an asset for distribution):
- `agent/src/main/.../Main.kt` — **implemented** ✅. LocalServerSocket(`r08agent`), full line
  protocol (KEY / KEYDOWN / KEYUP / TAP / SWIPE / AM / BC / SH / PING / QUIT), reflection to
  `InputManager.getInstance().injectInputEvent(event, MODE_ASYNC)`, KeyEvent + MotionEvent
  construction with the right `source` flag, swipe = DOWN+N×MOVE+UP at ~120 Hz, 5 s heartbeat
  file at `/data/local/tmp/r08agent.heartbeat`.

**`phase0/`:**
- `r08_probe.py` — fully implemented, includes `--tutorial` mode with a Python port of the
  GestureSynthesizer recognition logic
- `README.md` + `requirements.txt`

### 1.5 Build environment

- `app-project/gradle/wrapper/` generated (Gradle 8.10)
- `app-project/local.properties` points `sdk.dir` to the Android SDK
- Mercury SDK AAR at `app-project/app/libs/mercury-release.aar`; wired into the `rayneo` flavor
- `./gradlew :core:test` — **172 unit tests across 15 suites**, all passing
- `./gradlew :app:assembleRokidDebug :app:assembleRayneoDebug` — 13 MB debug APK per flavor
  (jumped from 9.2 MB after adding BouncyCastle 1.78.1 for B12-real cert generation)
- `./gradlew :agent:packageDex` — **new** Gradle task: builds the agent JAR (10 KB), then d8's
  it into `app/src/main/assets/r08agent.dex` (16 KB). Automatically runs before any APK build,
  so the agent dex stays current without a manual d8 step.
- `.github/workflows/core-tests.yml` — CI runs `./gradlew :core:test` on every push / PR. The
  agent SDK lookup is lazy so the workflow works in an environment without an Android SDK.

### 1.6 Tests

JVM tests in `:core` (`./gradlew :core:test`, **172 total across 15 suites, all passing**):

- `GestureSynthesizerTest.kt` — 28 cases covering every documented behaviour (multi-tap windows,
  optimistic mode, combos, wake-swallow, dedup, ordering preservation)
- `GestureSynthesizerBoundaryTest.kt` — **9 D11 boundary cases**: exact-280 ms multi-tap edge,
  exact-300 ms combo edge, exact-400 ms LP-followup edge, 5+ tap cap, wake-swallow + immediate
  TOUCH interaction
- `R08FrameTest.kt` — **19 cases (was 8)**: every frame type + command builder checksums + 11
  defensive D10 cases for malformed frames (empty / truncated / unknown subcodes / zero health
  value)
- `InteractionRouterTest.kt` — 9 cases: screen-off fast path, system layer, profile layer, and
  the `inAppShortCircuit` foreground-bypass slot
- `InteractionRouterModalTest.kt` — **10 D6 cases**: modal swallows gestures, 3 sentinel exits
  (Exit/Cancel/FireAndExit), `Enter*Modal` calls `onEnterModal` only, system gestures preempt
  active modal, modal timeoutMs contract simulated via ManualScheduler
- `FakeR08BleClientTest.kt` — 6 cases for the in-core test driver
- `AgentWireProtocolTest.kt` — 9 cases for the pure agent-line-protocol encoder
- `PowerPolicyTest.kt` — **12 cases (was 10)**: every row of the table including the new SLOW
  band, exact 5-min not-worn boundary, screen-on-after-wake re-promotes to HIGH
- `SystemGesturesTest.kt` — 8 cases for the `Slot`/`withSlot`/`gestureFor`/`conflict` helpers
- `KeyMapProfileTest.kt` — 5 cases for `withMapping`
- `ModeManagerTest.kt` — **15 D2 cases (new)**: cycle, manualLockMs, switchTo, onForegroundPackage
  respects lock, auto-switch after lock expires, observe replay, upsert/remove CRUD
- `ActionRouterTest.kt` — **10 D3 cases (new)**: priority ordering, capability filtering, fallback
  on failure, mapper override (RayNeo TAP_SWIPE for NavPrev), dynamic backend provider
- `GlassActionCodecTest.kt` — 10 cases for the JSON-codec round-trip
- `ModalsTest.kt` — 17 cases across `VolumeModal` / `BrightnessModal` / `RecentsModal` / `AIDictateModal`
- `AdbMessageTest.kt` — 5 cases for the ADB wire packet round-trip + magic / max-payload
  validation + the 7 command-code constants
- `ManualScheduler.kt` — virtual-time scheduler for the synthesizer tests

No Compose / UI tests yet (visual; spec'd by the mockup HTML).
No instrumentation tests yet.

### 1.7 Pre-existing bugs that were uncovered + fixed during the A-priority pass

The handoff doc claimed the core was JVM-testable, but it had **never been compiled** — surface
bugs that materialised on the first build:

1. `InteractionRouter.onRawWhileScreenOff` was non-suspend but called the suspend `dispatch()` —
   fixed: make `onRawWhileScreenOff` suspend.
2. `ModalSentinel : GlassAction` was declared in the `gesture` package but the sealed parent
   `GlassAction` is in `action` — Kotlin requires same-package — fixed: moved `ModalSentinel`
   into `Action.kt`.
3. Several `when (action: GlassAction)` expressions (`HudEvent.GlassAction.friendly()`,
   `RokidStrategies.primitives`, `RayNeoStrategies.primitives`) became non-exhaustive when
   `ModalSentinel` joined the hierarchy — fixed: added explicit `is ModalSentinel -> ...` branches.
4. `R08Theme.kt` imported `focusable` from `androidx.compose.ui.focus` (doesn't exist) instead
   of `androidx.compose.foundation`.
5. `GestureSynthesizerTest` line 149 backtick-quoted name contained a `;` (illegal in identifiers).
6. `AndroidManifest.xml` had `xmlns:tools` declared on a `<uses-permission>` (must be on `<manifest>`)
   AND an unescaped `&` in a label string.

In addition to the above, the **second audit pass (2026-05-13 f)** caught 6 more issues — see §1.8 below for
the full write-up; bullet summary:

7. **PowerPolicy lacked a SLOW band** for worn+screen-off → BLE radio stayed at BALANCED (~75-100 ms)
   instead of the design's SLOW (~200-500 ms). Decision shape was `Boolean activeMode`. Replaced
   with `enum IntervalMode { HIGH, BALANCED, SLOW }` and updated `AndroidR08BleClient.setIntervalMode`
   to map to `CONNECTION_PRIORITY_HIGH/BALANCED/LOW_POWER`. Two new test rows in `PowerPolicyTest`
   cover SLOW + screen-on-after-wake re-promotion.
8. **`AppProcessAgentBackend.perform` wrapped the whole call in `withContext(Dispatchers.IO)`**
   including the CPU-bound `mapper.primitives` + `AgentWireProtocol.encode`. Hoisted out so only
   the socket I/O hops dispatchers (Doc/06 §1.2).
9. **`schedulePeriodicBatteryPoll` re-armed itself unconditionally** even after GATT disconnect →
   30-min wake of the scheduler thread for a no-op write. Now gated on `state == ConnectionState.READY`.
10. **`vitalsSnapshotInFlight` never reset on BLE disconnect** → after one mid-snapshot drop, the
    UI's MEASURE NOW button became permanently inert until app restart. Added the cleanup branch
    to `onConnectionStateChange(STATE_DISCONNECTED)`.
11. **First-run wizard labelled "Step X of 4"** but the design (Doc/08 §3) specifies five steps
    including Welcome. Re-numbered.
12. **`uses-permission WAKE_LOCK`** declared but never used. Removed from the manifest.

In addition to the above, the **first power/threading audit pass** caught 4 more issues that
weren't obvious from the design docs:

7. `R08RemoteService.serviceScope` used `Dispatchers.Default` — meaning `interactionRouter.onGesture`
   ran on a worker thread while `synthesizer.onRaw` ran on the scheduler thread (race on
   `router.screenOn` and `synthesizer.config`). **Fixed by binding the scope to
   [`AndroidScheduler.coroutineDispatcher`](../app-project/app/src/main/kotlin/com/r08remote/app/runtime/AndroidScheduler.kt).**
8. The `setActiveMode` path didn't relax to BALANCED after the 10 s idle window required by
   [06 §3.5](06-performance-and-power.md). **Fixed by introducing
   [`PowerPolicy`](../app-project/core/src/main/kotlin/com/r08remote/core/power/PowerPolicy.kt) and
   re-evaluating + scheduling an idle-relax timer after every gesture / wear / screen event.**
9. `AndroidR08BleClient` ran an indefinite LOW_LATENCY scan when no ring was in range. **Fixed
   by adding a 30 s scan timeout.**
10. `InAppFocusController.route` was called from the scheduler thread but mutated Compose state
    via `FocusManager.moveFocus` / Compose-state setters — those are main-thread-only. **Fixed
    by posting the actual mutations onto the main-thread Handler; the bool return is "we'll
    handle it", not "we did it synchronously".**

If you find more, fix here and call it out in the commit message.

### 1.8 Second audit pass (2026-05-13 f) — what changed, what to look for

Triggered by reading the existing handoff + design and asking "is the code actually what the doc
says?" The audit ran in three lanes (UI / power+threading+latency / test sufficiency). Findings:

- **PowerPolicy was only two-band** (`activeMode: Boolean` → HIGH or BALANCED). Doc/06 §3.2's
  worn+screen-off SLOW band was missing → BLE radio stayed at ~75-100 ms during the longest
  fraction of typical wear. Fix: introduced `enum IntervalMode { HIGH, BALANCED, SLOW }`, updated
  decision shape and downstream callers. `AndroidR08BleClient.setIntervalMode` maps SLOW to
  `CONNECTION_PRIORITY_LOW_POWER`. Idle-relax timer now only re-schedules when leaving HIGH
  (SLOW/BALANCED are stable rest states).
- **Background overhead seams** — `schedulePeriodicBatteryPoll` rearmed itself with no `READY`
  gate; `vitalsSnapshotInFlight` never reset on disconnect; `AppProcessAgentBackend.perform`
  hopped the whole call (incl. CPU-bound mapping) to `Dispatchers.IO`. All three fixed inline.
- **Test gaps** — Three production modules had **zero** unit tests: `ModeManager` (5 s manual
  lock + auto-switch interaction), `ActionRouter` (priority/capability/fallback), full modal
  lifecycle (entry → handle → 3 sentinels → cleared). The modal `handle()` was tested but the
  router wiring around `activeModal` was not. Added one new test file per module. Also added
  R08Frame defensive parse tests and synthesizer exact-boundary timing tests.
- **Wizard step labels** off by one (5 enum stops vs "Step X of 4" labels); `WAKE_LOCK`
  permission declared but unused.

Total deltas: 57 new tests (115 → 172), 1 enum + 1 interface rename in `:core`, 5 small fixes in
`:app`. Both flavor APKs still 13 MB. CI green.

If you change `PowerPolicy.IntervalMode` or `R08BleClient.setIntervalMode`, also update
`StatusScreen.intervalMode`, `FakeR08BleClient.setIntervalMode`, and `R08RemoteService.reconcilePower`.

---

## 2. Priority-ordered TODO

Each item is tagged: 🔌 **hardware-required**, ⚡ **hardware-blocked but partly doable now**,
or 🆓 **fully doable now**. Each links to the design section that explains *what* and *why*.

### Priority A — critical path ✅ **complete (modulo hardware verification)**

All seven items are implemented and verified by JVM unit tests where applicable. Build artefacts:
two 9.1 MB flavor APKs + a 10 KB agent JAR (needs manual d8). End-to-end behaviour on real
hardware is in Priority C below.

| # | Task | Status | Refs |
|---|---|---|---|
| A1 | `AndroidR08BleClient` — BluetoothGatt impl + 30 s scan timeout + byte-dedup + staggered init | ✅ done; needs 🔌 to verify timings on the actual ring | [`AndroidR08BleClient.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ble/AndroidR08BleClient.kt) |
| A2 | `:agent` body — `LocalServerSocket("r08agent")` + line protocol + reflection-based `InputManager.injectInputEvent` | ✅ done; agent jar builds at 10 KB. Needs 🔌 (an Android device) to validate the ~1–3 ms latency claim | [`agent/Main.kt`](../app-project/agent/src/main/kotlin/com/r08remote/agent/Main.kt), [`AgentWireProtocol.kt`](../app-project/core/src/main/kotlin/com/r08remote/core/inject/AgentWireProtocol.kt) |
| A3 | `AppProcessAgentBackend` — LocalSocket client, reconnect-on-IOException, heartbeat freshness check, Mutex-serialised send/receive | ✅ done | [`AppProcessAgentBackend.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/inject/AppProcessAgentBackend.kt) |
| A4 | `R08RemoteService` — pipeline assembly + lifecycle + reconcilePower + foreground notification | ✅ done; runs on `AndroidScheduler.coroutineDispatcher` to keep the synth/router race-free | [`R08RemoteService.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/service/R08RemoteService.kt), [`PowerPolicy.kt`](../app-project/core/src/main/kotlin/com/r08remote/core/power/PowerPolicy.kt) |
| A5 | HUD overlay wiring — `onGestureRecognized` → `HudEvent.GestureRecognised`, connection state → Reconnected/Disconnected, ModeManager → ProfileSwitched, battery ≤ 20% → LowBattery, QUADRUPLE_TAP → Peek | ✅ done. Service hosts the `HudServiceHost` (Lifecycle/ViewModelStore/SavedStateRegistry bundle) | [`R08RemoteService.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/service/R08RemoteService.kt), [`HudServiceHost.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/hud/HudServiceHost.kt) |
| A6 | Foreground bypass — `InteractionRouter.inAppShortCircuit = { isInForeground && InAppFocusController.route(it) }`. Compose-state mutations are posted onto the main-thread Handler | ✅ done | [`InAppFocusController.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/InAppFocusController.kt), `InteractionRouter` |
| A7 | `FeedbackPrefs` DataStore — read via `flow` collected by service + MainActivity; write via `updatePrefs`; `armAutoHintAfterPairing` for the 5-min auto-hint window | ✅ done | [`FeedbackPrefsStore.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/FeedbackPrefsStore.kt) |

### Priority B — features (UI completeness; safe to defer until A is solid)

| # | Task | Status | Refs |
|---|---|---|---|
| B1  | Profiles list + editor | ✅ done | [`ProfilesListScreen.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/ProfilesListScreen.kt), [`ProfileEditorScreen.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/ProfileEditorScreen.kt) |
| B2  | Action picker (~35 entries, grouped) | ✅ done | [`ActionPickerScreen.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/ActionPickerScreen.kt), [`SettingsCatalog.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/SettingsCatalog.kt) |
| B3  | System gestures screen + gesture picker + conflict UI | ✅ done | [`SystemGesturesScreen.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/SystemGesturesScreen.kt), [`GesturePickerScreen.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/GesturePickerScreen.kt) |
| B4  | Ring screen — MAC / firmware / signal / battery / Find-Shutdown-Forget CTAs. Values from `AppGraph.ringInfoFlow`; CTAs call into `R08BleClient`. Real firmware-version / MAC / RSSI rendering needs the BLE init handshake to surface them (🔌 verify on hardware). | ✅ done | [`RingScreen.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/RingScreen.kt) |
| B5  | Power & Connection screen — 3 timing windows as tap-to-cycle presets + 3 latency switches per [GestureConfig]; explains the auto BLE-interval policy. Edits flow into the active profile. | ✅ done | [`PowerConnectionScreen.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/PowerConnectionScreen.kt) |
| B6  | Vitals on-demand measurement — `MEASURE NOW` writes `0x69 <kind> 01` for HR / SpO2 / stress sequentially (3 s each), then `0x6A` to stop the PPG LED. `R08BleClient.requestVitalsSnapshot()` is the entry point; `RingEvent.Health` events flow into `AppGraph.vitalsSnapshotFlow` which the UI reads. Cannot dispatch concurrent snapshots (idempotent guard). | ✅ done; needs 🔌 to validate actual ring response timings | [`AndroidR08BleClient.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ble/AndroidR08BleClient.kt), [`R08Protocol.kt`](../app-project/core/src/main/kotlin/com/r08remote/core/ble/R08Protocol.kt) |
| B7  | Advanced screen — 3 toggles (Debug HUD / Latency / Spatial) + 4 actions (A11y deep-link / battery exemption / re-run ADB wizard / export latency log). MainActivity routes the actions via `Settings.ACTION_*` Intents. | ✅ done | [`AdvancedScreen.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/AdvancedScreen.kt) |
| B8  | About screen — version (from `BuildConfig`), detected `DeviceProfile`, credits, docs pointer. | ✅ done | [`AboutScreen.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/AboutScreen.kt) |
| B9  | First-run wizard — 5 steps (Welcome / ADB / A11y / Battery / Pair). Persists a single boolean (`first_run_completed`) via [`FirstRunPrefsStore`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/FirstRunPrefsStore.kt); shown until the user finishes. Advanced → "Re-run ADB bootstrap" resets the flag. | ✅ done | [`FirstRunWizardScreen.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/FirstRunWizardScreen.kt) |
| B10 | Modal layer state machines — `VolumeModal` / `BrightnessModal` / `RecentsModal` / `AIDictateModal`. Service instantiates the right one in the `onEnterModal` hook, sets `interactionRouter.activeModal`, schedules a per-modal timeout. The `Recents` modal uses the new `ModalSentinel.FireAndExit` to dispatch `Confirm` and close in one gesture. | ✅ done | [`core/modal/`](../app-project/core/src/main/kotlin/com/r08remote/core/modal/) |
| B11 | AccessibilityBackend body + R08AccessibilityService → `ModeManager.onForegroundPackage` for auto-switch. | ✅ done | [`AccessibilityBackend.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/inject/AccessibilityBackend.kt), [`R08AccessibilityService.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/accessibility/R08AccessibilityService.kt) |
| B12-skeleton | Public API for the ADB bootstrap (`pairWithCode` / `pushAgentDex` / `grantWriteSecureSettings` / `startAgent`). UI calls this. | ✅ done | [`AdbBootstrap.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/adb/AdbBootstrap.kt) |
| B12-partial | **Cryptographic + transport primitives**: RSA-2048 keypair + self-signed X.509 cert via BouncyCastle ([`AdbCrypto.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/adb/AdbCrypto.kt)); ADB wire packet w/ 7 command-code constants ([`AdbMessage.kt`](../app-project/core/src/main/kotlin/com/r08remote/core/adb/AdbMessage.kt) + 5 round-trip tests); mDNS port discovery via Android `NsdManager` ([`AdbMdnsDiscovery.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/adb/AdbMdnsDiscovery.kt)). | ✅ done | (links in the cell) |
| B12-real | **Remaining**: SPAKE2 pairing handshake + TLS-wrapped ADB connection. ~1600 lines combined of BigInteger + BouncyCastle TLS to port from [`decompiled/v2/.../AdbPairingClient.java`](../decompiled/v2/sources/com/ring/r08remote/adb/AdbPairingClient.java) + [`AdbConnection.java`](../decompiled/v2/sources/com/ring/r08remote/adb/AdbConnection.java). Needs 🔌 to verify cipher parameters before shipping — porting cryptographic protocol code without a known-good test vector is dangerous. | ❌ deferred until hardware | Doc/13 §B12 above; [04 §5](04-architecture.md) |
| B13 | Profiles + SystemGestures DataStore persistence — JSON-via-`org.json` writes; flows seeded from store at app startup; subsequent edits write through. [GlassActionCodec] handles the action serialisation. | ✅ done | [`ProfilesPrefsStore.kt`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/screens/ProfilesPrefsStore.kt), [`GlassActionCodec.kt`](../app-project/core/src/main/kotlin/com/r08remote/core/action/GlassActionCodec.kt) |

### Priority C — verification (hardware-required, do in order when ring + glasses arrive)

| # | Task | Refs |
|---|---|---|
| C1 | **Phase-0 protocol verification** on the actual ring. Run `phase0/r08_probe.py` and tick each item in [11-verification-checklists.md §A](11-verification-checklists.md). | 🔌 |
| C2 | **De-dup window measurement** — count back-to-back tap intervals; pick the right dedup ms; check for varying byte (counter / timestamp). Update [`AndroidR08BleClient`](../app-project/app/src/main/kotlin/com/r08remote/app/ble/AndroidR08BleClient.kt) constants. | 🔌 [11 §A3, §A4](11-verification-checklists.md) |
| C3 | **0xA1 accelerometer frame decode** — try to figure out the byte layout. If decodable, add an `R08Frame.parseAccel` and feed into a phase-3 spatial-mode pipeline. | 🔌 [11 §A2](11-verification-checklists.md) |
| C4 | **Worn-on-finger frame search** — any new sub-frame correlating with wear/take-off? If found, plumb to `WearStateProvider` as an extra signal. | 🔌 [11 §A5](11-verification-checklists.md) |
| C5 | **Keepalive vs auto-sleep** — does sending `BATTERY_QUERY` every 30 s defeat the ring's auto-sleep? If yes, ship keepalive always-on while worn. | 🔌 [11 §A6](11-verification-checklists.md) |
| C6 | **LED behaviour cataloguing** — verify `0x06` (blink 10 s) and `0x10` (blink twice) actual patterns; can we drive faster custom patterns? | 🔌 [11 §A7](11-verification-checklists.md) |
| C7 | **Phase-1 Rokid bring-up** — `adb install`, test DPAD keys in Sprite launcher, verify Intent map (camera/chat/translate/etc.), grant `WRITE_SECURE_SETTINGS`, get accessibility working. | 🔌 [11 §B1.x](11-verification-checklists.md) |
| C8 | **Phase-1 RayNeo X3 Pro bring-up** — same shape: dev mode (Settings → swipe left ×10) → `adb install` → input injection (DPAD keys vs swipe MotionEvents — verify which works on focus); discover Intent names via `adb shell dumpsys activity top`; integrate Mercury SDK if AAR obtained. | 🔌 [11 §B2.x](11-verification-checklists.md) |
| C9 | **End-to-end performance + power** — meet the targets in [06 §5](06-performance-and-power.md): <100 ms p95 swipe/long-press/optimistic-tap, <150 ms wake; <5 mA steady-state delta; ≤1 BLE drop/hour. | 🔌 [11 §B3.x, §C](11-verification-checklists.md) |
| C10 | **Cross-glasses hand-over** — `WearState` driven; verify the ring auto-migrates 1-2 s after switching glasses. | 🔌 [11 §C1](11-verification-checklists.md) |

### Priority D — phase-3 / nice-to-have

| # | Task | Refs |
|---|---|---|
| D1 | **Spatial mode** — once C3 is solved, build the air-gesture recognizer (flick / twist / point). Phase-3-flagged; off by default with a battery warning. | [07 §"Module 7"](07-sensors-and-modules.md) |
| D2 | **Head-gaze cursor mode** — combine glasses IMU + ring tap. Phase 3. Only useful in cursor-friendly apps. | [05 §"5.6"](05-interaction-design.md) (in archive) |
| D3 | **Mobile companion app** — optional richer-config-UI on a phone. CXR-M (Rokid) + RayNeo's mobile SDK. Most users won't need it. | [01 §"Project status"](01-overview.md) |
| D4 | **Shizuku integration** — alternative privilege source; if a user installs Shizuku, use its API instead of our own bootstrap. | [04 §5](04-architecture.md) |
| D5 | **HID-keyboard-from-phone topology** — if RayNeo enables third-party BT peripheral pairing, a phone-in-the-middle architecture removes the need for ADB entirely. Speculative. | [03 §2.6](03-target-platforms.md) |
| D6 | **CI** — run `:core` tests automatically. | n/a |
| D7 | **Localisation** — currently English-only in code; user manual is bilingual. | [09](09-user-manual.md) |

---

## 3. Important context the next agent should know

### 3.1 Two design errors that have been corrected
- The ring's BLE `0x08` is **reboot**, not battery (battery is `0x03`). The original `R08-Dev.md`
  got this wrong; corrected in [02 §3](02-hardware-and-protocol.md). All code uses `0x03`.
- The ring has **no left/right swipes** — only up, down, touch, long-press. The UI design once
  mistakenly referenced `SWIPE_LEFT`/`SWIPE_RIGHT` for tab navigation; corrected. Tab strip is
  navigated via vertical swipes only (`SWIPE_UP` swims past content top onto the strip; then
  `SWIPE_UP`/`SWIPE_DOWN` cycles tabs). See [08 §9.1.1](08-ui-design.md).

### 3.2 Decisions that look small but matter
- **Pure black background**, single green accent matching the ring's LED, NEVER per-profile
  colour coding. See [08 §2](08-ui-design.md).
- **`:core` is dependency-free except Kotlin stdlib.** No coroutines, no Android imports. Keeps
  the gesture state machine JVM-testable. The `Scheduler` interface abstracts timers.
- **Gesture-hint mode is OFF by default**, but auto-on for 5 min after first-time pairing. See
  [08 §10](08-ui-design.md).
- **HUD overlay uses `WindowManager` `TYPE_APPLICATION_OVERLAY`**, not just in-Activity Compose,
  so it appears above any app the user is in.
- **No persistent CPU wakelock** — the BLE controller's IRQ wakes the CPU on every notify; no
  need to override system power management. (`小猪遥控戒指` did this and burns battery.) See
  [06 §3](06-performance-and-power.md).
- **Touch IC stays on when worn even if screen is off.** Otherwise the wake-gesture (long-press
  while screen off) wouldn't work. Only "not worn" disables it. See [06 §3](06-performance-and-power.md).

### 3.3 Where to look when something feels wrong
- Gesture not recognising correctly → [`GestureSynthesizerTest.kt`](../app-project/core/src/test/kotlin/com/r08remote/core/gesture/GestureSynthesizerTest.kt) — every documented behaviour is asserted.
- Action not firing → check `InteractionRouter` layer ordering in [05 §"Routing"](05-interaction-design.md): screen gateway → system → modal → profile.
- HUD not appearing → check `SYSTEM_ALERT_WINDOW` permission granted; `HudOverlay.ensureViewInstalled` falls gracefully on `WindowManager.BadTokenException`.
- Build issues → likely Compose / Kotlin / AGP version mismatch with installed Android Studio.
  Bump versions in [`build.gradle.kts`](../app-project/build.gradle.kts) as needed.

### 3.4 Files to be careful of
- `GestureSynthesizer.kt` — order of operations in `onTouch` / `onLongPress` / `onSwipe` is
  subtle; the test suite catches regressions. Change with care.
- `InteractionRouter.kt` — the 4-layer routing is delicate. Don't add layers without explicit
  rationale.
- `R08Protocol.kt` constants — verified against decompiled `小猪遥控戒指` v2 + `tahnok/colmi_r02_client`.
  Changing any byte value is a bug magnet; defer to phase-0 verification on real hardware first.

### 3.5 What's done vs what's still stubbed

| Layer | Status |
|---|---|
| `:core` (BLE/protocol/synth/router/profile/policy/modal/codec/adb-packet) | ✅ implemented + 172 tests |
| `:agent` (LocalSocket + reflection) | ✅ implemented; ~1–3 ms claim needs 🔌 to verify |
| **`:agent:packageDex` automated** | ✅ d8 + asset-copy happen before any APK build |
| `:app` BLE / agent backend / a11y backend / service / power | ✅ implemented |
| `:app` BLE B6 vitals snapshot (HR/SpO2/stress) | ✅ implemented + D4 disconnect-safe; needs 🔌 to verify timings |
| `:app` UI: 3 tabs + HUD + 7 settings screens + first-run wizard | ✅ all implemented |
| Modal layer state machines (Volume / Brightness / Recents / AIDictate) | ✅ implemented + service-wired |
| Persistence: Feedback + Profiles + SystemGestures + First-run + Advanced + Vitals | ✅ DataStore for all 6 |
| ADB bootstrap: key/cert generation, mDNS port discovery, wire packet | ✅ implemented |
| **ADB bootstrap: SPAKE2 pairing + TLS connection** | ❌ B12-real — needs 🔌 to validate crypto |
| CI: `:core:test` on push/PR | ✅ `.github/workflows/core-tests.yml` |
| Hardware verification (C1–C10) | ⏳ blocked on ring + glasses arriving |

### 3.6 Threading discipline (read this before changing the service)

The whole pipeline runs on **one** thread: `AppGraph.scheduler`'s HandlerThread. Including the
suspending parts — `serviceScope` is bound to `scheduler.coroutineDispatcher`. Don't `launch`
on `Dispatchers.Default` from inside the service unless you have a specific reason and you've
thought through what state you might be racing on.

The exceptions, by design:
- `AppProcessAgentBackend.perform` hops to `Dispatchers.IO` for the blocking socket I/O
- `InAppFocusController.route` posts `FocusManager.moveFocus` etc. onto the main Handler
- BLE callbacks land on a binder thread but immediately repost via `scheduler.post`

---

## 4. Recommended order for the next agent

Priority A, B1–B11, B13, B6, **all engineering follow-ups** (agent dex automation, advanced-prefs
persistence, CompositionLocal, CI), the audit-driven fix pass (D1–D11), and the structural part of
B12-real (keys + mDNS + wire packet) are complete. Only two software pieces remain, both
crypto-sensitive and 🔌 hardware-gated.

**Next, hardware-gated work — needs the actual glasses to verify against:**
1. **B12-real cryptographic core** — finish the SPAKE2 pairing handshake + TLS-wrapped ADB
   connection so the first-run wizard's "RUN PAIRING FLOW" CTA actually pairs over Wi-Fi and
   pushes the agent dex. Reference: [`decompiled/v2/sources/com/ring/r08remote/adb/AdbPairingClient.java`](../decompiled/v2/sources/com/ring/r08remote/adb/AdbPairingClient.java)
   (~800 lines BigInteger SPAKE2) and
   [`AdbConnection.java`](../decompiled/v2/sources/com/ring/r08remote/adb/AdbConnection.java) (~800
   lines BouncyCastle TLS). Suggested approach: test against Android Studio's own pairing dialog
   on a phone before targeting the glasses.
2. **B6 — validate the 0x69 real-time HR sequence on actual hardware.** The current 3-s-per-phase
   timing in `AndroidR08BleClient.requestVitalsSnapshot` is a guess based on the docs; real ring
   may need longer / shorter.

**Other small follow-ups (no hardware needed):**
3. **Profile-edit screen — gestureConfig toggles** — the Power & Connection screen exposes the
   three timing windows + three latency switches for the *active* profile. Profile editor could
   surface these per-profile too (currently it shows only the gesture-to-action map).
4. **CompositionLocal usage** — `LocalAppGraph` is provided but not yet consumed by any composable.
   The screens still take typed callbacks. That's the right default; opportunistic use as cleanup.
5. **Release-build hardening** — disable LogCat verbose logs; verify ProGuard rules (BouncyCastle
   needs keep rules); shrink APK with R8 (currently 13 MB debug, ~4 MB of that is BC; release+R8
   should be ~7-8 MB).

**When hardware arrives:**
1. C1 (phase-0 protocol verification) — Bash through [11 §A](11-verification-checklists.md). Should take ~1 hour with the ring.
2. C2-C6 — the action-item investigations (dedup window, accel, wear, keepalive, LED). ~2 hours.
3. C7 (Rokid bring-up) or C8 (X3 Pro bring-up) — whichever pair of glasses arrives first.
4. C9, C10 — end-to-end perf + hand-over.
5. B6 — vitals screen on-demand measurement.
6. Iterate on real hardware to tune the dedup window, BLE intervals, swipe coordinates / durations.

---

## 5. Things explicitly out of scope (don't do)

| Don't | Why |
|---|---|
| Continuous heart-rate streaming | PPG LED draws too much; ring autonomy goal is ~5 days. On-demand only. |
| Always-on raw-IMU mode | <1 day battery. Spatial mode is phase-3, off by default, with a warning. |
| Per-profile colour theming | Clutters the small canvas. One green accent, period. |
| Light theme | Wastes pixels and leaks light. Black canvas only. |
| Mobile companion app as a primary surface | The product is "ring as remote for glasses"; phone-in-the-middle defeats the value. (Phone-side companion app is D3 only as an *option*.) |
| Material 3 widgets with default styling | Will look wrong on the AR display. Override every widget via theme tokens. |
| Multi-account / multi-user features | One wearer per device. |
| Real-time charts / sparklines in vitals | The display can't render them well at this resolution. The Status tab has one tiny sparkline; that's the limit. |
| Network features (cloud sync of profiles, etc.) | Local-only on the glasses. Profiles stored in DataStore. |

---

## 6. Memory / agent-state continuity

The previous agent kept context in
`/Users/Zack/.claude/projects/-Users-Zack-Code-Projects-R08-dev/memory/`. Three files:

- `project-r08-rokid-remote.md` — what the project is + the chronological design decisions
- `r08-ble-protocol.md` — quick-reference of the BLE protocol
- `rokid-glasses-platform.md` — quick-reference of the Rokid platform
- `rayneo-x3pro-platform.md` — quick-reference of the RayNeo X3 Pro platform

Worth a read for "why was X decided" — they're succinct.

If you need to update or add memories, the index is `MEMORY.md` in the same folder.

---

## 7. Questions you'll probably ask

**Q: Why two flavors (rokid + rayneo) instead of one APK with runtime detection?**
A: Considered; rejected. Build-time selection is simpler, prevents accidental crossover, and the
"one APK" benefit doesn't materially matter when each user has only one or two pairs of
glasses. Runtime detection remains as a sanity check + GENERIC_ANDROID dev fallback. See
[04 §1](04-architecture.md).

**Q: Why an `app_process` agent instead of just using `am`/`input` shell commands?**
A: `input keyevent` spawns a JVM each time — ~50–150 ms latency. The agent is persistent (one
process, one socket connection) and calls `InputManager.injectInputEvent` directly via
reflection — ~1–3 ms. **The biggest performance win in the whole project.** See
[06 §1.2](06-performance-and-power.md).

**Q: Why not just use Shizuku?**
A: Shizuku is supported as a secondary backend (priority 90) — if the user installs it, we'll
use it. But we don't *require* it because it's an extra install for the user. The
`app_process` agent ships with our APK.

**Q: Can the ring be used without the ADB bootstrap (just via Accessibility)?**
A: Partially. AccessibilityService on Android 12 only exposes BACK / HOME / RECENTS /
NOTIFICATIONS — no DPAD key injection (that's API 33+). So gesture navigation in the system UI
needs ADB. See [04 §5.2](04-architecture.md).

**Q: What's the dependency between BLE client and the rest?**
A: The pipeline is one-direction: `R08BleClient.events() → GestureSynthesizer → InteractionRouter
→ ActionRouter → ExecutorBackend → injection`. The synthesizer is testable with a fake
`R08BleClient` (`GestureSynthesizerTest.kt` does this). You can develop the whole pipeline
without a real ring as long as you fake events at the BLE layer.

**Q: Where are the build artifacts going?**
A: Standard Gradle: `app-project/app/build/outputs/apk/{rokid|rayneo}/{debug|release}/*.apk`.
The agent dex goes into `app/src/main/assets/r08agent.dex` (when built) — see
[10 §10](10-developer-guide.md).

---

## 8. One-line summaries of recent sessions

- **2026-05-13 i** (this session): **Open-source release + A-block roadmap items finished**.
  Repo `MRziyi/Halo-Ring` pushed to GitHub; A-3 / A-4 / A-5 / A-6 complete; only A-2 (SPAKE2,
  hardware-gated) remains in the A block.
  - **A-1 OSS repo**: created `/Users/Zack/Code/Halo-Ring/`, pushed clean source (152 files,
    3 MB) to `git@github.com:MRziyi/Halo-Ring.git`. Bilingual README, MIT LICENSE,
    CONTRIBUTING.md, `app/libs/README.md` for the Mercury AAR download flow. `R08-dev/` stays
    locally as the working dir + private vault for non-shippable material (research/,
    decompiled/, SDK/, remote-v*). Helper script `scripts/sync-to-oss.sh` rsyncs subtrees +
    commits + pushes — keeps the OSS mirror current with a single command.
  - **A-3 R8 release shrink**: enabled `isMinifyEnabled = true` + `isShrinkResources = true` on
    release; wrote `proguard-rules.pro` with keeps for BouncyCastle (heavy reflection),
    DataStore, Compose Stable/Immutable, Kotlin metadata, our manifest-referenced classes, the
    R08Protocol constants. Excluded BouncyCastle PQC (`picnic/lowmcL*`) + localised
    CertPath message bundles via `packaging { resources { excludes += ... } }` for an extra
    ~1.2 MB. **Result: debug 14 MB → release 3.1 MB (-78 %)**. Verified on OnePlus 9 Pro that R8
    didn't shrink anything needed at runtime (no crashes, foreground service runs, BLE scan
    state transitions visible in logcat).
  - **A-4 LocalAppGraph**: `VitalsScreen` and `RingScreen` now read `LocalAppGraph.current`
    directly instead of taking callback parameters (`onMeasureNow`, `onFindRing`,
    `onShutdownRing`, `onForgetRing`). Removed 4 parameters from `HaloRingApp` + 4 callback
    definitions from `MainActivity`. Pure threading-removal refactor; same semantics, less
    ceremony. Now uses the `LocalAppGraph` CompositionLocal that was previously declared but
    unconsumed.
  - **A-5 LatencyLogger + CSV export**: new `:core/perf/LatencyLogger` (200-entry ring buffer
    + CSV serialiser, **10 new unit tests, 182 total**). Wired into `HaloRingService.sink`
    capturing `tBle / tEmitted / tDispatched` per gesture. Gated by the Advanced screen's
    "Latency measurement" toggle — **default OFF, zero overhead when off** (single @Volatile
    read per gesture); **~50 µs per gesture when on** (one Sample allocation + one mutex). The
    Advanced "EXPORT LATENCY CSV" action now writes to `Downloads/halo-latency-{ts}.csv` via
    MediaStore (scoped-storage compatible since Android 10), with a toast surfacing
    success / empty / failure.
  - **A-6 InotifydScriptBackend** (skeleton, fallback at priority 60): new file
    `app/src/main/kotlin/com/halo/ring/inject/InotifydScriptBackend.kt`. Implements
    `ExecutorBackend` with NAVIGATE/KEY_EVENT/TAP_SWIPE/LAUNCH_INTENT/SHELL capability set;
    `isReady()` gated by the same 30 s heartbeat-freshness check as the agent (path
    `/data/local/tmp/halo.inotifyd.heartbeat`). `perform()` encodes primitives via
    `AgentWireProtocol`, writes them to `/data/local/tmp/halo.cmd` for the device-side
    inotifyd shell helper to pick up. Registered in both flavor's backend list. The
    shell-side script + ADB bootstrap step to deploy it are TODO — until then, `isReady()`
    returns false and the `ActionRouter` simply skips this backend, so it costs nothing at
    runtime.
  - **Validated**: 182/182 tests green; both debug APKs build at 14 MB; release APK 3.1 MB;
    lint 0 errors; new code installs + runs on the OnePlus 9 Pro.
- **2026-05-13 h**: **Halo Ring rebrand** — adopted final product name "Halo Ring · 环意"
  with slogan "Where the ring goes, the world moves." / 「环之所至，意之所达」. Author byline:
  Zack 紫意. New repo (to be opened-source under `halo-ring`) will host this codebase; the
  `R08-Dev/R08-Remote` codename is now retired everywhere except where it refers to the QRing R08
  ring hardware. Changes:
  - **Package rename**: `com.r08remote.core` → `com.halo.ring.core`; `com.r08remote.app` →
    `com.halo.ring`; `com.r08remote.agent` → `com.halo.ring.agent`. `applicationId` correspondingly
    moved to `com.halo.ring`; flavor suffixes unchanged (`.rokid` / `.rayneo`).
  - **Class rename**: `R08RemoteApplication` → `HaloRingApplication`; `R08RemoteService` →
    `HaloRingService`; `R08AccessibilityService` → `HaloRingAccessibilityService`; `R08App` →
    `HaloRingApp`; `R08Theme/Colors/Type` → `HaloRingTheme/HaloColors/HaloType`;
    `Modifier.r08Focus()` → `Modifier.haloFocus()`. Log tags `R08Service/R08A11y/R08` →
    `HaloService/HaloA11y/Halo`. Hardware-name refs (`R08Protocol`, `R08Frame`, `R08BleClient` and
    its impls) intentionally **kept** — those name the QRing R08 device, not our app.
  - **Agent rename**: `LocalServerSocket("r08agent")` → `LocalServerSocket("halo.agent")`; dex
    asset `r08agent.dex` → `halo-agent.dex`; heartbeat / log paths
    `/data/local/tmp/halo.agent.{heartbeat,log}`; stderr prefix `[r08agent]` → `[halo.agent]`.
  - **Branding resources** (new):
    - Adaptive launcher icon: [`app/src/main/res/drawable/ic_launcher_foreground.xml`](../app-project/app/src/main/res/drawable/ic_launcher_foreground.xml)
      (75 % of master, halo alpha boosted), background = `#000000`, monochrome variant for
      Android 13+ themed icons. Master SVG at [`Doc/brand/v10a-aperture-arcs.svg`](brand/v10a-aperture-arcs.svg).
    - Monochrome status-bar notification icon: [`ic_notification.xml`](../app-project/app/src/main/res/drawable/ic_notification.xml).
    - App theme `Theme.HaloRing` (pure black canvas) + splash theme `Theme.HaloRing.Splash`
      (Android 12+ splash icon = launcher foreground).
    - Bilingual strings: [`values/strings.xml`](../app-project/app/src/main/res/values/strings.xml) +
      [`values-zh/strings.xml`](../app-project/app/src/main/res/values-zh/strings.xml) — `app_name`,
      `app_name_bilingual`, `app_tagline`, `app_byline`, `a11y_service_label`,
      `notification_{channel_name,title,text}`.
    - About screen now leads with `app_name_bilingual` (green) + `app_tagline` + `app_byline`.
    - Foreground-service notification uses `ic_notification` + `@string/notification_*`.
  - **Manifest**: declares `android:icon`, `android:roundIcon`, `android:theme="@style/Theme.HaloRing"`,
    main Activity uses splash theme, AccessibilityService label uses `@string/a11y_service_label`.
  - **Validated on OnePlus 9 Pro / Android 14**:
    - 172/172 unit tests green after rename
    - Both flavor APKs build at 14 MB (was 13 MB pre-icon; +1 MB for adaptive icon vectordrawable+
      colors+themes)
    - Foreground service `HaloRingService` runs as type=connectedDevice with IMPORTANCE_LOW
      silent notification
    - `HaloRingAccessibilityService` captures `WINDOW_STATE_CHANGED` for Settings/SystemUI/Chrome
    - Agent `halo-agent.dex` bootstraps under shell uid, heartbeat fresh, `abstract:halo.agent`
      LocalSocket bound, PING RTT median 5.14 ms (was 4.96 ms — essentially unchanged)
  - **Result**: ready for the open-source repo as `halo-ring`. The internal codename
    `R08-Dev`/`R08-Remote` is retired; the QRing R08 hardware references remain.
- **2026-05-13 g**: First on-device dry-run of Doc/14 on a OnePlus 9 Pro / Android 14.
  Caught + fixed **5 real shipping bugs** that wouldn't have surfaced on the JVM-only test suite:
  - **g1 INTERNET permission** missing from manifest → `AdbMdnsDiscovery`'s `NsdManager` instantiation
    raised `SecurityException`. Added `<uses-permission android:name="android.permission.INTERNET" />`.
  - **g2 Runtime BLE permissions** weren't being requested before starting the `connectedDevice`
    foreground service → Android 14's FGS-type gate rejected the start. Added a runtime
    permission flow in [MainActivity](../app-project/app/src/main/kotlin/com/r08remote/app/MainActivity.kt)
    that asks for `BLUETOOTH_CONNECT/SCAN` (+ `POST_NOTIFICATIONS` on 13+) and retries the service start.
  - **g3 Agent dex missed `kotlin-stdlib`** → agent crashed instantly with
    `NoClassDefFoundError: Lkotlin/jvm/internal/Intrinsics;`. Fixed
    [`:agent:packageDex`](../app-project/agent/build.gradle.kts) to bundle `kotlin("stdlib")` into
    the dex (dex grew 16 KB → 2.2 MB).
  - **g4 `InputManager.getInstance()` gutted on Android 13+** → agent NPE'd on startup. Reflected
    `android.hardware.input.InputManagerGlobal.getInstance()` first, fell back to legacy path.
  - **g5 BLE client log spam every ~10 s** when not connected. Each method of
    [AndroidR08BleClient](../app-project/app/src/main/kotlin/com/r08remote/app/ble/AndroidR08BleClient.kt)
    that writes to the ring now early-returns if `state != READY`.
  - Plus: 4 lint cleanups (TV-targeting warnings from `LEANBACK_LAUNCHER`); manifest
    `tools:ignore="MissingTvBanner"`; coarse-location declared next to fine-location.
  - **Validated**: 172/172 tests green, both flavor APKs install, foreground service runs, 30 s BLE
    scan timeout fires (D-fix #9 regression test passes), AccessibilityService gets foreground
    events, agent bootstraps with median 4.96 ms PING RTT (incl. USB), 5-min idle = 0.0% CPU.
  - **Result**: Doc/14 dry-run now passes end-to-end on Android 14. Ready for hardware.
- **2026-05-13 f**: Audit-driven fix pass. UI/interaction/power/latency/test audit
  ran first; surfaced 1 real functional power gap (no SLOW BLE band), 1 reliability hole (vitals
  stuck after disconnect), and three zero-coverage code paths (ModeManager / ActionRouter / modal
  full lifecycle). Fixes:
  - **D1**: `PowerPolicy` gains `IntervalMode { HIGH, BALANCED, SLOW }` (replacing the boolean
    `activeMode`). `worn && !screenOn` → SLOW (CONNECTION_PRIORITY_LOW_POWER). `R08BleClient.setActiveMode`
    renamed to `setIntervalMode(mode)`; `AndroidR08BleClient` + `FakeR08BleClient` + `StatusScreen`
    updated. Idle-relax timer only scheduled when leaving HIGH (SLOW/BALANCED are stable).
  - **D4**: `vitalsSnapshotInFlight` reset on BLE disconnect so MEASURE NOW survives a dropout.
  - **D5**: `schedulePeriodicBatteryPoll` gated on `state == READY` — no 30-min wake-ups on a
    dead link.
  - **D7**: `AppProcessAgentBackend.perform` keeps `mapper.primitives` + wire encoding on the
    scheduler thread; only the socket I/O block hops `Dispatchers.IO`.
  - **D8**: First-run wizard relabelled "Step X of 5" (welcome counted).
  - **D9**: Removed unused `WAKE_LOCK` permission from manifest.
  - **D2**: New `ModeManagerTest.kt` — 15 cases for cycle / lock / auto-switch interaction /
    upsert / remove.
  - **D3**: New `ActionRouterTest.kt` — 10 cases for priority routing, capability filtering,
    fallback on failure, RayNeo TAP_SWIPE override scenario, dynamic backend provider.
  - **D6**: New `InteractionRouterModalTest.kt` — 10 cases for the modal layer lifecycle
    (sentinels, system-gesture preemption, timeoutMs contract).
  - **D10**: 11 new defensive R08Frame parse cases (empty / truncated / unknown subcodes / zero
    health value).
  - **D11**: 9 new `GestureSynthesizerBoundaryTest.kt` cases for exact-280 ms / 300 ms / 400 ms
    window edges + 5+ tap capping + wake-swallow vs TOUCH interaction.
  - **Result**: 115 → **172 unit tests across 15 suites**, all passing. Both flavor APKs still
    13 MB.
- **2026-05-13 e**: All engineering follow-ups + B6 + Vitals-prefs + B12-real
  structural port.
  - **agent dex automation**: `:agent:packageDex` Gradle task d8's the jar and drops
    `r08agent.dex` (16 KB) into app assets; `:app:preBuild` depends on it.
  - **Advanced + Vitals prefs DataStore** (separate stores, mirroring `FeedbackPrefsStore`).
  - **CompositionLocal** `LocalAppGraph` for back-door graph access in deep composables.
  - **B6**: added `REAL_TIME_*` BLE commands, `R08BleClient.requestVitalsSnapshot()` writes
    HR → SpO2 → stress sequentially with `0x6A` stop; service collects Health events into
    `vitalsSnapshotFlow`; MainActivity renders into `VitalsState`.
  - **B12-real partial**: added BouncyCastle 1.78.1, ported the well-defined pieces — RSA-2048
    keypair + self-signed X.509 cert (`AdbCrypto`); ADB wire packet (`AdbMessage` in `:core` with
    5 round-trip tests); mDNS port discovery via `NsdManager` (`AdbMdnsDiscovery`). The
    cryptographic core — SPAKE2 pairing + TLS-wrapped ADB connection — is explicitly deferred
    until we have hardware to validate against, with detailed file-level pointers in
    `AdbBootstrap.kt`'s kdoc.
  - **D6 CI**: `.github/workflows/core-tests.yml` runs `:core:test` on push/PR. The agent SDK
    lookup is now lazy so the workflow runs without an Android SDK.
  - **APK size**: 9.2 MB → 13 MB (+ BouncyCastle ~4 MB). Release+R8 should reclaim most of that.
  - **Result**: 115 unit tests across 11 suites all passing; both flavor APKs 13 MB; agent jar 10 KB.
- **2026-05-13 d**: All remaining software-doable B work — B13 (persistence) + B4 (Ring) + B5
  (Power & Connection) + B7 (Advanced) + B8 (About) + B10 (Modal state machines for
  Volume/Brightness/Recents/AIDictate) + B9 (5-step First-run wizard) + B12 structural skeleton.
  110 unit tests across 10 suites; both flavor APKs 9.2 MB.
- **2026-05-13 c**: Tier-1 of B complete (B11 + B1 + B2 + B3). Wrote
  `AccessibilityBackend` body + wired `R08AccessibilityService` foreground-package events into
  `ModeManager.onForegroundPackage` via scheduler-thread post. Refactored `R08App` navigation
  from `subScreen: String?` to a typed `SubScreen` sealed hierarchy + `navStack: List<SubScreen>`
  for clean drilldown. Added 5 new Compose screens (Profiles list / Profile editor / Action
  picker / System Gestures / Gesture picker) with inline conflict warnings. Extracted
  `SystemGestures.{Slot,withSlot,gestureFor,conflict}` and `KeyMapProfile.withMapping` into
  `:core` with 13 new JVM tests (83 total). Added three `MutableStateFlow`s to `AppGraph`
  (profiles, activeProfileId, systemGestures) so UI edits flow back to the running pipeline.
  Both flavor APKs build; agent jar unchanged.
- **2026-05-13 b**: Priority A complete + first audit pass. Built the wrapper,
  integrated the Mercury AAR, wrote the agent body + LocalSocket backend + BluetoothGatt client
  + foreground service that assembles the pipeline. Added `PowerPolicy` and the 10 s
  idle-relax timer. Fixed 4 threading/power bugs the audit caught (serviceScope dispatcher,
  active-mode relax, scan timeout, FocusManager main-thread post). 70 unit tests, both flavor
  APKs ship at 9.1 MB.
- **2026-05-13 a**: Added the gesture-hint HUD mode + completed most of the Compose UI; fixed
  the tab-navigation design to not assume non-existent left/right swipes; wrote the original
  handoff doc.
