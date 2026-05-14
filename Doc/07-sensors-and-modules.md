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

Confidence tags below match [Doc/02 §0](02-hardware-and-protocol.md): 🟢 R08-confirmed (in 小猪 v2
source) / 🟡 family-known (QRing only, needs phase-0 to confirm on R08) / 🔵 inherited speculation
/ 🔴 conflicting between sources.

| Source | Data | Push / pull | Power cost | Role | Tier | Notes |
|---|---|---|---|---|---|---|
| Touch ring | 4 raw events (`73 2D 01..04`) | push | Touch IC always-on while enabled = dominant ring drain | **Control (core)** | 🟢 | R08-specific firmware path; not in QRing decompile. Synthesises → 12 gestures app-side. |
| Touch status | `73 2A 00` echo | push | — | Gate | 🟢 | `data[2] == 0` = enabled. Confirms `TOUCH_ENABLE` took effect. |
| Battery | `0x03` response (`<level>`) | pull (10 min per 小猪 / 30 min per our code) | trivial | Passive HUD + adaptive | 🟢 | 🟡 QRing extends to `03 <level> <charging>` — second byte is charging flag. Phase-0 §A check. |
| Battery push (low) | `0x73 0x0C` sync trigger | push | trivial | Adaptive | 🟡 | Ring auto-pushes when low. Lets us drop the 10-min poll → poll-on-event. Phase-0 §A. |
| Activity totals (push hint) | `73 12 …` | push (passive) | zero extra | Passive stats | 🟢 | Steps BE 24-bit, calories BE 24-bit / 1000, distance BE 24-bit / 1000 — verified against 小猪 `DataParser.java:50-53`. |
| Activity totals (canonical) | `0x48` query → 14-byte BE response | pull | trivial | Passive stats | 🟡 | More authoritative than the `73 12` push (which is just a hint). Adds running-step + sport-minute. Phase-0 §B. |
| Step-only push | `0x51 lo hi` | push | trivial | Passive stats | 🟢 | LE-16. Used when only step count changes (no cal/dist). 小猪 `DataParser.java:92`. |
| HR (real-time) | `0x69 01 01` start, `0x6A 01 …` stop | start/stop stream | PPG LED on continuously = **large** drain | On-demand | 🟡 | **25-second stream per QRing** (`HeartActivity.java:415-481`), not 3 s as our current code assumes. Tick = 500 ms. Phase-0 `r08_health_probe.py`. |
| SpO2 / Stress / HRV / Temp | `0x69 <kind> 01` (kind = 3 / 8 / 10 / 11) | same | same | On-demand | 🟡 | All identical 25-s recipe per QRing. |
| Wear-detection (via vitals) | `0x69` response `data[2] = errCode` | passive on streaming | — | **Gate (opportunistic)** | 🟡 | `errCode = 1` → "not worn properly". Only fires while vitals stream is active. Use as a cheap wear-state hint without dedicated wear-sense hardware. Phase-0 §D. |
| HR history | `0x15` query → multi-packet `15 <sub>` | pull | low | On-demand | 🟡 | 288 samples/day = ~24 packets. QRing `ReadHeartRateRsp.java:17-52`. |
| HRV history | `0x39` query → multi-packet | pull | low | On-demand | 🟡 | Was `0x37` in earlier doc drafts — **wrong**. QRing maps `0x37 = stress` and `0x39 = HRV`. |
| Stress history | `0x37` query → multi-packet | pull | low | On-demand | 🟡 | Same multi-packet shape as HRV. |
| Step history | `0x43` query → multi-packet | pull | low | On-demand | 🟡 | 96 records/day, 15-min granularity. QRing `ReadDetailSportDataRsp.java:9-43`. |
| Sleep history | `0x44` query → multi-packet | pull | low | On-demand | 🟡 | Q-staged sleep records. QRing `ReadSleepDetailsRsp.java:8-37`. |
| Auto-monitor cadence (HR/SpO2/Stress/HRV) | `0x16` / `0x2C` / `0x36` / `0x38` | read/write | low | Config (cadence is ring-side) | 🟡 | **Ring is cadence master**, not phone. We set the interval; ring measures internally and pushes via `0x73 <sub>` when ready. |
| Accelerometer raw | `0xA1` 16-byte frame, 6-byte payload `[2..7]` | push (continuously?) | low (default) / high (raw IMU mode) | **TBD: control (potential)** | 🟢 frame present, 🟡 encoding | 小猪 receives but does **not** decode (`DataParser.java:58-69`). QRing does not decode either. Likely 3× int16 axes from STK8321. Phase-0 §A2 — log raw bytes during specific motion. |
| LED + vibration | `0x50 AA AA` (find-device per QRing) | write | trivial | **Output channel** | 🟡 | Vibrate + LED blink. Replaces the unverified 🔵 `0x06` (find) / `0x10` (blink-twice) we'd inherited. Phase-0 §B. |
| Soft reboot | `0x08 01` | write | trivial | Housekeeping | 🟡 | Per QRing `SystemSettingActivity.java:60`. Real shutdown cmd unknown (no opcode confirmed). |
| Capability bitmap | `0x3C` query → 9-byte response | pull (once per connect) | trivial | Config gate | 🟡 | 28+ feature flags from `DeviceSupportFunctionRsp.java:60-136`. Lets UI hide unsupported actions on a given firmware. Phase-0 §B. |
| Time sync | `0x01` (BCD payload) | write (once per connect) | trivial | Housekeeping | 🟡 | Required for any history read to return useful timestamps. Phase-0 §B. |
| Firmware version, MAC, model | TBD queries | pull | — | Housekeeping | 🔵 | Opcode for "get firmware version" not yet identified in either source. About page can use the BLE-advertised name's hex suffix as a proxy. |
| Auto-sleep state | inferred from disconnect+reconnect timing | — | — | Gate | 🟢 | Drives `armWakeSwallow()`. |
| G-sensor still-time tick | `0x73 0x3E` push | push | trivial | **Power gate (potential)** | 🟡 | QRing only. Likely fires when the ring detects it's not moving. Phase-0 §A — does R08 emit? If yes, replaces our screen-on/off wear proxy with a real "ring still" signal. |
| "Lover double-tap" event | `0x73 0x30` push | push | trivial | **Control (potential shortcut)** | 🟡 | QRing-side handler; if R08 touch firmware emits this for the same double-tap we already synthesise app-side, we could short-circuit the synthesiser for ~280 ms latency improvement on double-tap. Phase-0 §A. |

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

**Files**: `app/.../service/HaloRingService.kt`, `BootReceiver.kt`,
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

A sanity test: did we use every signal the hardware offers? Going down the matrix. After the
QRing decompile (2026-05-15), three rows previously marked TBD have candidate solutions pending
phase-0 verification.

| Signal | Used? | Tier | If not, why |
|---|---|---|---|
| Ring touch (4 events) | ✓ Core control | 🟢 |  |
| Touch status echo | ✓ Connection ACK | 🟢 |  |
| Battery | ✓ HUD + adaptive | 🟢 | + 🟡 charging-byte and `73 0C` low-push pending phase-0 confirmation |
| Activity (steps/cal/dist push) | ✓ Passive stats (optional) | 🟢 |  |
| Activity totals (canonical `0x48`) | △ designed | 🟡 | Wired pending phase-0 §B — more authoritative than `73 12` push |
| HR / SpO2 / stress / HRV (real-time) | △ designed | 🟡 | App calls `0x69`/`0x6A` but our duration is a guess (3 s); QRing protocol is **25 s** — `r08_health_probe.py` will confirm |
| Wear via `0x69 errCode=1` | △ designed | 🟡 | Replaces "needs dedicated wear-sense hardware" gap — opportunistic during vitals streams |
| Ring accel `0xA1` | TBD (Phase 3) | 🟢 frame / 🟡 layout | Encoding unknown; pending phase-0 §A2 motion-correlation |
| Ring LED / vibration | △ designed | 🟡 | `0x50 AA AA` (per QRing) replaces unverified 🔵 `0x06` we inherited |
| Firmware version / MAC | ✗ partial | 🔵 | Get-version opcode not yet identified; About page uses BLE-name hex suffix instead |
| Capability bitmap `0x3C` | TBD | 🟡 | Phase-0 §B; if R08 responds, lets UI gate features per firmware |
| Time sync `0x01` | TBD | 🟡 | Phase-0 §B; required if we ever pull history |
| G-sensor still-tick `73 3E` | TBD | 🟡 | Phase-0 §A; alternative wear-state proxy |
| "Lover double-tap" `73 30` | TBD | 🟡 | Phase-0 §A; firmware-side double-tap shortcut |
| 0x73 sync-trigger fanout (~30 sub-codes) | TBD | 🟡 | Phase-0 §A presence-check; useful subset: low-battery push, alarm-ring, current-HR push |
| Glasses IMU | TBD (Phase 3) | — | Head-gaze cursor mode is optional |
| Glasses wear detection | ✓ Power state + hand-over | — |  |
| Glasses screen on/off | ✓ Screen state + wake/sleep system gestures | — |  |
| Glasses battery | ✓ HUD (optional) | — |  |
| Glasses foreground app | ✓ Auto-switch profile | — |  |
| Glasses' own temple touchpad | ✓ Navigates our own config UI | — |  |

Coverage outlook after phase-0: every 🟡 row promotes to ✓ if the R08 firmware honours QRing's
broader protocol — that's the path to ~95 %. The 🔵 "get firmware version" gap stays open until
we sniff the QRing app talking to a real R08 (or another decompile turns up the opcode).

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
