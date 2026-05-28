# 12 — Research, References & Errata

What we learned from prior work, what we keep around, and where the original community handoff
document went astray.

---

## 1. The four research source-trees (cloned locally in `../research/`)

### 1.1 `buildwithfenna/rokid-docs` → `../research/rokid-docs/`

Community-maintained, comprehensive Rokid AR Glasses platform documentation. Includes:

- **YodaOS decompilation** at `yodaos/DECOMPILED/`. System partitions, system apps, kernel
  modules — sourced from firmware dumps + apktool/jadx. (Note: git-lfs pointers; the actual
  bytes need `git lfs pull` if you want them. For most purposes the `yodaos/docs/` markdown
  is enough.)
- **System app analyses** under `yodaos/docs/apps/`:
  - `sprite-launcher.md` — the launcher (`com.rokid.os.sprite.launcher`) — 21 page activities,
    focus-based navigation model, broadcasts (`cmd / open_app`, `visualaidemo.ACTION_START`)
  - `sprite-assist.md` — the central service (`com.rokid.os.sprite.assistserver`) — Bluetooth
    sub-managers, `RokidTouchManager`, `RokidDoorReceiver` (wear detection)
  - `camera2.md` — KEYCODE_DPAD_CENTER / KEYCODE_CAMERA / KEYCODE_VOLUME_UP/DOWN all bound to
    shutter
  - `sys-config.md` — proximity / hinge / Hall sensor handling
- **Hardware / kernel docs** under `yodaos/docs/hardware/` and `kernel/`:
  - Display: JBD JBD4020 Micro-LED, Qualcomm DPU
  - Sensors: ICM-4x6xx IMU via I3C
  - `psoc_ts_drv_right.ko` — Cypress PSoC touchpad/sensor driver for right temple
- **CXR SDK suite docs** under `cxr-m/` (mobile), `cxr-s/` (on-device), `cxr-l/` (standalone).

This is the source for almost everything in [03-target-platforms.md](03-target-platforms.md) §1.

### 1.2 `tahnok/colmi_r02_client` → `../research/colmi_r02_client/`

Python BLE client for the R02 family of rings. Crucial for cross-checking our R08 protocol —
R08 inherits the bulk of the R02 protocol with R08-specific touchpad additions. Verified
command codes from this repo:

- `0x01` set_time
- `0x03` battery (= what we use; **this is the correction to R08-Dev.md's claim of `0x08`**)
- `0x08` reboot (= what `0x08` actually is in this protocol family)
- `0x10` blink_twice
- `0x15` heart_rate_history
- `0x43` step_someday
- `0x69` start_real_time (HR / SpO2 / stress live streaming sub-commands)
- `0x6A` stop_real_time

If you want broader R02 features (sleep tracking, etc.) the `colmi_r02_client` Python package
is just `pipx install`-able. We deliberately don't depend on it in our Kotlin code (different
runtime), but it's useful for sanity-checking via the phase-0 probe.

### 1.3 `atc1441/ATC_RF03_Ring` → `../research/ATC_RF03_Ring/`

Aaron Christophel's deep dive into the RF03 SoC + custom firmware:

- `BLueX_RF03-01_Datasheet-V3.2.pdf` — the BlueX RF03 SoC datasheet (Cortex-M0, BLE 5.0)
- `Accl_Datasheet_STK8321.pdf` — the STK8321 accelerometer datasheet (used for §2 of [02](02-hardware-and-protocol.md))
- `Firmware_Dump/` — raw firmware extracts
- `OTA_firmwares/` + `R02_3.00.06_FasterRawValuesMOD.bin` — a community mod that ups the
  accelerometer reporting rate to 50 Hz (relevant for the deferred "spatial mode" Phase 3 work)
- `SDKs/` — the BlueX custom-firmware SDK
- `QRing_Patched_to_disable_certificate_pinning.apk` — the official QRing app with cert pinning
  removed, for HCI snoop verification
- `Known_BLE_OEM_Ring_Names.txt` — list of OEM brand names that share the same hardware (Colmi,
  Hugrow, Hyper, Yawell, etc.)
- `OTA_Flasher_image.png` — screenshot of the browser-based flasher at
  https://atc1441.github.io/ATC_RF03_Writer.html

### 1.4 `Quad-Labs/RayDesk` → `../research/RayDesk/`

A real, deployed X3 Pro app — Moonlight (game streaming) port. Crucial because it's the only
public example of:

- Using the **Mercury SDK** (`com.ffalcon.mercury.android.sdk`)
- Real X3 Pro Activity structure
- Sourced gradle dependency setup pointing at `mercury-release.aar` (the AAR comes from RayNeo
  directly, not Maven)
- `TempleAction` subclass usage in production

We don't ship anything from RayDesk; we reference it for "how do other people use the Mercury
SDK" and for confirming our flavor's gradle setup.

Also useful: `RayDesk/RELEASE_NOTES.md` documents target device specs (model code `ARGF20`,
1280×480 binocular display, Android 12+) and the dependency setup.

### 1.5 Other minor references in `../research/`

- `moonlight-android-RayNeoX3/` — another community port (mostly UI / stream tweaks; less
  technical content than RayDesk)

---

## 2. Decompiled `小猪遥控戒指` — the reference app

`com.ring.r08remote` (author WeChat: `qq889538`). Three versions of the APK at
[`../refs/r08remote-apk-v1/`](../refs/r08remote-apk-v1/),
[`../refs/r08remote-apk-v1.1/`](../refs/r08remote-apk-v1.1/),
[`../refs/r08remote-apk-v2/`](../refs/r08remote-apk-v2/). We jadx-decompiled v2 into
[`../refs/r08remote-decompiled-v2/`](../refs/r08remote-decompiled-v2/) — that's the source of truth
for the **R08 BLE protocol** (see [02](02-hardware-and-protocol.md)).

Useful files in `../refs/r08remote-decompiled-v2/sources/com/ring/r08remote/`:

| File | What we learned |
|---|---|
| `bluetooth/ProtocolConstants.java` | All the protocol constants — service/char UUIDs, command bytes, prefix bytes. The canonical reference. |
| `bluetooth/GestureParser.java` | How the reference app handles the 4 raw gestures (state machine for tap-counting + combo windows). We do better in [05](05-interaction-design.md) §3. |
| `bluetooth/DataParser.java` | Frame parsing — confirms which prefixes mean what (`73 2D` gestures, `73 12` activity, etc.) |
| `data/BluetoothDataManager.java` | BLE central implementation — connection lifecycle, dedup window (100 ms), retry logic. We adapt and improve. |
| `touch/TouchCommandRouter.java` | Their gesture → action mapping (3 modes hard-coded). |
| `input/SystemInputInjector.java` | Their input-injection mechanism (foreground keyevent file polling). Slow; we replace with the agent. |
| `service/BluetoothService.java` | Their resident service. Notably: persistent `PARTIAL_WAKE_LOCK` (a power waste we remove). |

---

## 3. Improvements vs `小猪遥控戒指` — the win list

This is the "why our project exists" summary, suitable for a project pitch.

| Property | `小猪遥控戒指` | This project |
|---|---|---|
| **Platforms** | Rokid only | Rokid + RayNeo X3 Pro, shared core |
| **Latency: optimistic tap** | Always 400 ms (multi-tap window fixed) | 0 ms or tunable; per-profile |
| **Latency: injection** | `input keyevent` per gesture (~100 ms JVM spawn each time) | `app_process` agent, direct `InputManager.injectInputEvent` (~1–3 ms) |
| **Latency: BLE** | Default Android conn interval, not tuned | Active mode requests 15–30 ms interval, idle mode relaxes |
| **Power: wakelock** | Persistent `PARTIAL_WAKE_LOCK` (CPU never deep-sleeps) | No wakelock; BT IRQ wakes CPU |
| **Power: scanning** | 2 s scan loop forever | `connectGatt(autoConnect = true)`; no app-level scan |
| **Power: injection** | 50 ms file polling shell script | Event-driven LocalSocket; zero CPU idle |
| **Power: touch IC** | Always on while connected | `TOUCH_DISABLE` when not worn (worn + screen off keeps it on so wake gesture works) |
| **Power: HR** | Real-time stream sometimes left running | On-demand snapshot only; PPG LED off when idle |
| **Gestures** | Tap, double tap, triple tap (count), forward swipe, backward swipe, long press | + quadruple tap, double tap+swipe ×2, long press+swipe ×2, double long press → 12 total |
| **Mappings** | 3 modes, hard-coded `when` branches | User-configurable per profile; 4 built-in; profile name + trigger apps + per-profile timing |
| **Mode switch** | Triple-tap manual only | Triple-tap + auto-switch by foreground app (via Accessibility) + 5 s manual lock |
| **Screen on / off** | Not handled (rely on Android timeout) | Dedicated wake gesture (`LONG_PRESS`, fast-path bypass for instant) + sleep gesture (`LONG_PRESS_SWIPE_DOWN`) |
| **Dedup** | 100 ms (can eat real fast double-taps in edge cases) | Phase-0 calibrated, byte-identity-aware |
| **Ring LED feedback** | None | Per-event patterns for connect / mode-switch / wake / sleep / modal / low-battery / reconnect |
| **Reconnect / hand-over** | None (single device) | Ring auto-switches between two pairs of glasses by wear state |
| **Observability** | None | Debug HUD (RSSI / conn-interval / RTT / drops / active backend), latency-measurement mode, CSV export |
| **Onboarding** | Read a Notes.md | First-run wizard + interactive `r08_probe.py --tutorial` |
| **Code quality** | Working but monolithic | Multi-module, JVM-testable core, ~25 unit tests, full design docs (you are reading them) |

---

## 4. Errata against the original `R08-Dev.md`

`R08-Dev.md` in the repo root is the original community hand-off doc. We've kept it as-is for
historical reference. Here's what it got wrong, corrected by reverse-engineering and clarified
in [02](02-hardware-and-protocol.md) and elsewhere:

### Wrong: "0x08 = battery query"

R08-Dev.md says: *"battery command is `0x08`"*. **Reality**: `0x08` is **reboot**. The battery
command is `0x03`. Confirmed via:
- `tahnok/colmi_r02_client`'s `battery.py`: `CMD_BATTERY = 3`
- `tahnok/colmi_r02_client`'s `reboot.py`: `CMD_REBOOT = 8`
- `com.ring.r08remote`'s `ProtocolConstants.java`: uses `0x03` and reads response as battery

### Wrong: "Touch command codes are in the 0x70–0x7F range"

R08-Dev.md says: *"the touch command codes are presumably in the `0x70–0x7F` range, needs
investigation"*. **Reality**: the gesture notify frames are `73 2D <code>` where the prefix bytes
are `0x73 0x2D` (i.e. "s-"), not single-byte 0x7X. The code byte at position 2 is `0x01-0x04`,
not 0x7X. Documented in [02](02-hardware-and-protocol.md) §4.1.

### Wrong: "Swing / in-air gestures are firmware-recognised"

R08-Dev.md says: *"the ring's firmware recognises swing gestures (e.g. for camera trigger)"*.
**Reality**: There are **no firmware-side combo gestures**. The firmware only reports the 4
atomic events. Everything richer (double-tap, "swing", combos) is **app-side synthesis with
timing windows**. We re-create "swing for camera" as `DOUBLE_TAP_SWIPE_UP` (default) — see
[05](05-interaction-design.md) §4.

### Wrong: "Double-tap to wake the ring is hardware-level"

R08-Dev.md implies: *"the ring's firmware handles double-tap-to-wake; the app can ignore this"*.
**Reality**: The touch IC's wake-on-touch behaviour silently consumes the first 1-2 TOUCH events
after auto-sleep. These events still arrive over BLE (the IC's wake report). The app must
**explicitly handle this** via `armWakeSwallow()` in the synthesiser. Otherwise the user's first
deliberate gesture after the ring auto-sleeps gets treated as the wake-up's "first half" and
swallowed. See [05](05-interaction-design.md) §3.4.

### Wrong: "Yawell / Colmi / Hugrow OEM brands all share the exact same hardware"

R08-Dev.md says: *"All Yawell-OEM smart rings share BOM and firmware"*. **Mostly true** for the
R02 / R06 / R09 / etc. lines — the R0X series shares core hardware and firmware. **But R08
specifically adds the touchpad IC and touch ring**, which the R02 doesn't have. So the BLE
protocol diverges in exactly one place: the `73 2D` and `73 2A` namespace (touch + touch-status
reports) only exists in R08. The rest of the protocol is R02-compatible.

### Unclear: Accelerometer data format

R08-Dev.md acknowledges: *"the accelerometer data format hasn't been decoded"*. **Still true**.
The `0xA1` frames are received by `com.ring.r08remote` but its parsing function is a no-op stub.
Phase-0 action item §A2 in [11](11-verification-checklists.md). Likely encoding (per atc1441's
FasterRawValuesMOD): 3 axes × 16 bits = 6 bytes per sample, multi-sample frames at ~50 Hz when
enabled.

---

## 5. Acknowledgements (for any future publication)

Per the original R08-Dev.md and our additional sources:

- **Aaron Christophel** (atc1441) — BlueX RF03 hardware reverse-engineering, firmware
  dump-and-rebuild, custom firmware SDK, datasheet extraction
- **Wesley Ellis** (tahnok) — R02-family BLE protocol reverse-engineering, working Python client
- **Freeyourgadget team** — Gadgetbridge's Colmi device class (broad protocol coverage)
- **`小猪遥控戒指` author** (WeChat qq889538) — first working Rokid-side implementation; our
  reference for the touch-event-specific protocol additions
- **buildwithfenna** — Rokid platform reverse-engineering and documentation
- **Quad-Labs / informalTechCode** — practical examples of using the RayNeo Mercury SDK
- **RayNeo, Rokid official developer docs** — what they document plus what we infer

If you publish work building on this project, attribute these folks.

---

## 6. Useful external links

- atc1441 OTA flasher: https://atc1441.github.io/ATC_RF03_Writer.html
- FCC database (R08 internal photos): https://fccid.io/2AOM3-R08
- RayNeo dev portal: https://open.rayneo.com/ (registration required for Mercury SDK)
- RayNeo official dev docs (GitBook): https://rayneo.gitbook.io/rayneo-devdoc/
- Rokid dev portal: https://ar.rokid.com/sdk?lang=en (for CXR SDK — not used by us)
- Qualcomm AR1 / RayNeo X3 Pro getting-started: https://www.qualcomm.com/developer/project/get-started-with-rayneo-x3-pro-ar-development
- `小猪遥控戒指` original distribution: search WeChat ID `qq889538` (manual outreach)

---

## 7. Documents and pages this project supersedes

| Document | Status | Replaced by |
|---|---|---|
| `R08-Dev.md` (root) | Historical; corrections in §4 above | This Doc/ |
| `R08-Remote-Design.md` (root) | Historical monolithic v0.7 | `Doc/_archive/R08-Remote-Design-v0.7.md`; canonical content is split across Doc/01–12 |

Both originals are kept; nothing is deleted. New maintenance should target the `Doc/` files;
update `R08-Dev.md` only if you find another error in it.
