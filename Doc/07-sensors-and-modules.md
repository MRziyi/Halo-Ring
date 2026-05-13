# 07 — Sensor Utilisation & Functional Modules

What we read from each sensor, what we do with it, and how the app is decomposed into modules.

## 1. Sensor capability matrix

Categorisation:

- **Control-grade** — direct input to the gesture system
- **Gate / power** — drives the lifecycle / power state machine
- **Passive (HUD / stats)** — display-only, zero extra cost
- **On-demand** — measured only when the user requests
- **TBD / decoded** — known to exist but not yet fully usable

### 1.1 Ring sensors

| Source | Data | Push / pull | Power cost | Role | Notes |
|---|---|---|---|---|---|
| Touch ring | 4 raw events (`73 2D 01..04`) | push | Touch IC always-on while enabled = dominant ring drain | **Control (core)** | Synthesises → 12 gestures |
| Touch status | `73 2A 00` echo | push | — | Gate | Confirms `TOUCH_ENABLE` took effect |
| Battery | `0x03` response | pull (every 30 min) | trivial | Passive HUD + adaptive | Low battery → relax conn-interval, warn |
| Activity | `73 12` (steps / cal / dist), `0x51` (steps) | push (passive) | zero extra — firmware counts anyway | Passive stats | Optional HUD overlay; CSV export |
| HR (real-time) | `0x69 01` | start/stop stream | PPG LED on continuously = **large** drain | On-demand | Vitals snapshot: one measurement, stop. **Never continuous.** |
| SpO2 | `0x69 03` | same | same | On-demand | Same protocol |
| Stress | `0x69 08` | same | same | On-demand | Same protocol |
| HRV history | `0x37` | pull | low | On-demand | Niche; from `tahnok/colmi_r02_client` |
| Accelerometer raw | `0xA1` frames | push (continuously?) | low (default) / high (raw IMU mode) | **TBD: control (potential)** | Encoding unknown. Phase-0 action item §A2. |
| LED control | `0x06` / `0x10` | write | trivial | **Output channel** | "Find my ring" + status feedback patterns |
| Firmware version, MAC, model | `0xXX` queries | pull | — | Housekeeping | HUD About page |
| Auto-sleep state | inferred from disconnect+reconnect timing | — | — | Gate | Drives `armWakeSwallow()` |
| **"Worn on finger"** | maybe in `73 0x??` namespace? | push? | — | **TBD: gate (potential)** | Investigate phase-0 §A4 |

### 1.2 Glasses sensors

| Source | What it gives | Power cost | Role |
|---|---|---|---|
| Glasses IMU | head pose (Rokid: `SensorManager` + ICM-4x6xx; RayNeo: `TYPE_GAME_ROTATION_VECTOR` 219 Hz + Mercury SDK) | low | **Phase 3 only**: head-gaze + ring-pointer cursor mode |
| Wear detection | Rokid: `RokidDoorReceiver` + proximity + hinge / RayNeo: Mercury SDK 佩戴检测 | trivial | **Power gate (key)**: gates touch IC + ring hand-over |
| Screen state | `ACTION_SCREEN_ON/OFF` system broadcasts | trivial | Gate (fallback when wear detection unreliable) + drives wake/sleep |
| Battery | `BatteryManager` | trivial | Passive HUD (optional "ring 80% / glasses 45%") |
| Foreground app | AccessibilityService `WINDOW_STATE_CHANGED` | low | Drives auto-switch profile (`ModeManager.onForegroundPackage`) |
| Glasses' own temple touchpad | DPAD keys (Rokid) / MotionEvents (RayNeo) | — | Secondary input to navigate **our app's own UI** (so you can configure the ring even if the ring isn't paired) |

## 2. Functional module breakdown

The app decomposes into 9 modules. Each maps to specific files in
[`../app-project/`](../app-project/) and a specific section in [05](05-interaction-design.md)
or the design.

### Module 1: Connection & device management

**Responsibility**: scan / connect / reconnect the ring; expose connection state to the rest of
the app; supply BLE writes (TOUCH_ENABLE / DISABLE / BATTERY / LED / SHUTDOWN); manage MAC
whitelist; coordinate hand-over with the other-glasses' instance.

**Files**: `core/.../ble/R08BleClient.kt` (interface) +
`app/.../ble/AndroidR08BleClient.kt` (Android impl, TODO).

**Key behaviours**:
- `connectGatt(autoConnect = true)` — relies on BT stack background reconnect
- Initialisation sequence per [02](02-hardware-and-protocol.md) §5
- Byte-level de-dup with phase-0-calibrated window
- BLE connection interval adaptation (HIGH on activity, BALANCED idle)
- Arm `armWakeSwallow()` on every reconnect

### Module 2: Gesture engine

**Responsibility**: convert raw events → 12-gesture vocabulary; tunable timing windows;
optimistic vs precise tap; wake-swallow.

**Files**: `core/.../gesture/GestureSynthesizer.kt`, `Gestures.kt`, `Scheduler.kt` +
`app/.../runtime/AndroidScheduler.kt` (production HandlerThread impl).

**Reference**: [05-interaction-design.md](05-interaction-design.md) §1–3.

### Module 3: Mapping & modes

**Responsibility**: profile vocabulary (Tap → Confirm / etc.); profile cycling; auto-switch by
foreground app; manual lock.

**Files**: `core/.../action/KeyMapProfile.kt`, `DefaultProfiles.kt`, `ModeManager.kt`.

**Reference**: [05](05-interaction-design.md) §4.

### Module 4: Action execution

**Responsibility**: abstract `GlassAction` → device injection. Backend selection by capability.
The agent. Per-device Intent maps.

**Files**: `core/.../action/Action.kt`, `ActionRouter.kt`, `inject/ExecutorBackend.kt`,
`device/DeviceStrategy.kt` (interfaces); per-flavor strategy impls in
`app/src/rokid/...` and `app/src/rayneo/...`; the agent at `agent/.../Main.kt`.

**Reference**: [04](04-architecture.md) §4–5.

### Module 5: Lifecycle & power state machine

**Responsibility**: foreground service, no wakelock, boot-restart, agent management
(health-check + auto-recover), the §3 power state machine (worn/screen/battery → TOUCH_ENABLE
+ BLE interval + feature gating).

**Files**: `app/.../service/R08RemoteService.kt` (TODO), `BootReceiver.kt`,
`app/.../runtime/` (planned: `WearLifecycle.kt`, `PowerStateMachine.kt`).

**Reference**: [06](06-performance-and-power.md) §3.

### Module 6: Vitals & activity (secondary)

**Responsibility**: read passive activity push (steps); on-demand vitals snapshot (HR / SpO2 /
stress / HRV); optional CSV export.

**Files**: `app/.../vitals/` (planned).

**Design note**: This is a *bonus* feature — comes "free" with the ring being there, but the
project's reason for existing is remote control, not a fitness tracker. Resist the urge to
expand it; if users want full health features, they can use the official QRing app on a phone.

### Module 7: Spatial / raw-IMU mode (Phase 3, optional)

**Responsibility**: gate raw IMU (`0xA1` decoded or FasterRawValuesMOD firmware on the ring →
50 Hz accel stream); air-gesture recognition (flick / twist / point); head-gaze cursor mode
combining glasses IMU + ring tap.

**Files**: not yet stubbed. Plans:
- `core/.../spatial/RawImuSource.kt`
- `core/.../spatial/AirGestureRecognizer.kt`
- `app/.../spatial/HeadGazeProvider.kt`

**Defaults off**, prominent battery warning. Ring lasts <1 day with always-on raw IMU.

### Module 8: Observability & debug

**Responsibility**: per-stage latency measurement; connection-quality HUD (RSSI, conn-interval,
RTT, drop count); active backend; log export.

**Files**: `app/.../debug/` (planned: `LatencyTrace.kt`, `DebugHud.kt`).

**Reference**: [06](06-performance-and-power.md) §4.

### Module 9: UI presentation

**Responsibility**: render the app's status overlay, configuration screens, first-run wizard,
debug HUD. Wrapped by the device's `DisplayAdapter`.

**Files**: `app/.../ui/` (Compose; TODO) + `MainActivity.kt`. Wrapped per flavor via
`DisplayAdapter`.

**Reference**: [08](08-ui-design.md) — design done; Compose implementation mostly complete (see [13](13-handoff.md) §1.4).

## 3. The "物尽其用" check

A sanity test: did we use every signal the hardware offers? Going down the matrix:

| Signal | Used? | If not, why |
|---|---|---|
| Ring touch (4 events) | ✓ Core control |  |
| Touch status echo | ✓ Connection ACK |  |
| Battery | ✓ HUD + adaptive |  |
| Activity (steps/cal/dist) | ✓ Passive stats (optional) |  |
| HR / SpO2 / stress / HRV | ✓ Vitals snapshot |  |
| Ring accel `0xA1` | TBD (Phase 3) | Encoding unknown; pending phase-0 decode |
| Ring LED | ✓ Status feedback patterns |  |
| Firmware version / MAC | ✓ About page |  |
| "Worn on finger" frame | TBD | Phase-0 investigation; if found → gates gesture processing |
| Glasses IMU | TBD (Phase 3) | Head-gaze cursor mode is optional |
| Glasses wear detection | ✓ Power state + hand-over |  |
| Glasses screen on/off | ✓ Screen state + wake/sleep system gestures |  |
| Glasses battery | ✓ HUD (optional) |  |
| Glasses foreground app | ✓ Auto-switch profile |  |
| Glasses' own temple touchpad | ✓ Navigates our own config UI |  |

Coverage: ~85% (everything except the two TBDs). Acceptable. The TBDs unlock real value (raw
IMU = spatial mode; worn-on-finger = better power gating) when decoded.

## 4. Module interactions (dependency cheat sheet)

```
1 Connection ────► 2 Gesture engine ───┐
                                       ▼
                            3 Mapping & modes ────► 4 Action execution
                                       ▲
                                       │
   5 Lifecycle & power ────► 1, 2, 3 (gates everything; controls touch IC, conn interval, etc.)
   6 Vitals ────► 1 (uses BLE writes for on-demand measurement)
   7 Spatial (phase 3) ────► alternate path: 1 → spatial recogniser → 4
   8 Observability ────► 1, 2, 4 (subscribes for measurements; doesn't drive flow)
   9 UI ────► 1, 3 (renders state) + 5 (system gestures bind through it via SettingsPage)
```

Module 1 is the lowest layer; everything else builds on it. Module 5 is "the conductor" — it
listens for sensor changes and adjusts the lower modules' behaviour.
