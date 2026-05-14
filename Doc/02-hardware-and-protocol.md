# 02 — Ring Hardware & BLE Protocol

The QRing R08 smart ring is the input device. This document is the working spec for its hardware
and the BLE protocol our app speaks to it. Everything here is **reverse-engineered** — we have not
yet had a real ring in hand to confirm, so values are tagged by source-confidence (see §0). Phase-0
([`../phase0/`](../phase0/)) is the script suite that turns the tags into "verified ✓ / contradicted ✗".

## 0. Three sources, three confidence tiers

Three independent reverse-engineering sources contribute to this doc. None is sufficient alone:

| Source | Scope | Confidence on R08 | What it covers |
|---|---|---|---|
| **小猪遥控戒指** (`com.ring.r08remote` v2) — see [`../refs/r08remote-decompiled-v2/`](../refs/r08remote-decompiled-v2/) | **Very narrow** — only what's needed to use the R08 as a *touch remote*. Confirmed to work on real R08 hardware (the third-party-app ecosystem proves it). | 🟢 **HIGH** for what it documents; says **nothing** about ~95 % of the protocol. | 4 write commands: `TOUCH_ENABLE`, `TOUCH_DISABLE`, `TOUCH_MODE`, `BATTERY_QUERY`. Notify-frame prefixes: `0x73` (sub `0x2A` touch-status, `0x2D` gesture, `0x12` activity), `0xA1` (accel, received but **not decoded**), `0x03` (battery), `0x69` (HR/SpO2/stress), `0x51` (steps). |
| **QRing** (`com.qcwireless.smart` — the OFFICIAL Yawell/oudmon app) — see [`../refs/qring-new-version-protocol-2026-05-15.md`](../refs/qring-new-version-protocol-2026-05-15.md) | **Broad** — the official app for the whole Colmi family. ~70 write commands + ~30 `0x73` sub-codes. | 🟡 **MEDIUM** on R08 — same firmware family (BlueX RF03), but the broader family lacks R08's touch IC, so 小猪's `73 2D` gesture path likely isn't echoed in QRing decompile and some R08-specific firmware tweaks may diverge. Officially-authoritative for everything beyond touch. | Time-sync, capability bitmap, real-time vitals (HR/SpO2/stress/BP/HRV/temp), history reads (HR/sleep/steps/HRV/stress), today's totals, find-device, reboot, ~30 `0x73` sync-trigger sub-codes (battery-low, alarms, "current HR" push, G-sensor still tick, etc.). |
| **`tahnok/colmi_r02_client`** — community Python client (see [`../research/colmi_r02_client/`](../research/colmi_r02_client/)) | Medium — confirms QRing on the subset it implements. | 🟡 cross-check only — same source corpus as QRing. | Time, HR settings, real-time start/stop, history reads. Mostly agrees with QRing where they overlap. |

**Phase-0 verification is the gate.** Anything not yet confirmed on real R08 stays tagged below
with one of:

- 🟢 **R08-confirmed** — appears in 小猪 v2 (which targets R08 specifically). Trust.
- 🟡 **Family-known** — appears in QRing/colmi for the Colmi family but **not** verified on R08 yet.
- 🔵 **Inherited speculation** — copied from the original `R08-Dev.md` handoff doc with no extant
  source backing. Subject to phase-0 verification (which may simply delete the entry).
- 🔴 **Conflicting** — 小猪 and QRing disagree about what the byte means. Phase-0 must adjudicate.

`R08Protocol.kt` (in [`../app-project/core/.../ble/R08Protocol.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/ble/R08Protocol.kt))
currently mixes 🟢 + 🔵 entries without distinguishing — the audit log captures the cleanup
direction; the constants stay as-is until phase-0 returns evidence.

## 1. Hardware identity

| Property | Value | Source |
|---|---|---|
| Product | **QRing R08** smart ring | Marketing |
| FCC ID | `2AOM3-R08` | https://fccid.io/2AOM3-R08 |
| Registered to | Shenzhen YaWell intelligent Technology (Yawell) | FCC |
| Distributed as | Yawell OEM = Colmi (largest rebrand) + Hugrow, Hyper, and dozens of smaller rebrands; all share PCB + firmware | atc1441 community research |
| Companion app | **QRing** (App Store / Google Play) | Marketing |

Hardware specs (inferred from the shared Colmi BOM list — covers R02, R03, R02/03 Pro, R06, R06
Pro, **R08**, R09, R10, R11, R12, R15–R22; all share the PCB):

| Component | Part | Notes |
|---|---|---|
| Main SoC | **BlueX Micro RF03** | ARM Cortex-M0, 200 KB RAM, 512 KB Flash, BLE 5.0. Datasheet in `../research/ATC_RF03_Ring/`. |
| Accelerometer | **STK8321** (Sitronix) | 3-axis, ±2/4/8/16 g, 14-bit, I²C. Datasheet in `../research/ATC_RF03_Ring/`. |
| PPG sensor | Vcare VC30F | Optical heart rate + SpO2. Not relevant to remote control. |
| Touch IC | Unknown capacitive controller | **R08-specific**; the broader Colmi BOM doesn't have it. Drives the `73 2D` gesture path that only `com.ring.r08remote` consumes. |
| Battery | LiPo 17 mAh | ~5–7 days in event mode; <1 day with always-on raw IMU |
| Charging | Magnetic-contact cradle, USB-C | ~60–90 min full charge. BLE is **off** while charging. |
| Water resistance | IP68 / 5 ATM | 50 m water depth |
| LED | Green (single colour) | Controllable via BLE — exact cmd unknown until phase-0 §A7 verifies |
| Debug | SWD (P00 = SWCK, P01 = SWD) | Internal solder pads, need to crack the ring open |
| OTA | BLE, **unsigned**, **unencrypted** | Browser-based flasher at https://atc1441.github.io/ATC_RF03_Writer.html |

⚠ Touch IC presence and R08-specific firmware paths are the **only** hardware differentiator vs
the rest of the Colmi family. Everything else (BLE stack, sensor I²C, firmware base) is shared.

## 2. BLE GATT structure

```
Advertised name : R08_XXXX           (XXXX = last 2 bytes of MAC, hex)
                  (also matches names containing "R08", "R06", "Colmi", "COLMI")

GATT service    : 6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E

  Write characteristic    (central → ring)
  6E400002-B5A3-F393-E0A9-E50E24DCCA9E

  Notify characteristic   (ring → central)
  6E400003-B5A3-F393-E0A9-E50E24DCCA9E

  CCCD descriptor (write 0x0001 to enable notify)
  00002902-0000-1000-8000-00805F9B34FB
```

Nordic-style "UART over BLE" service layout (`fff0` family). 🟢 Confirmed both by 小猪 v2
(`ProtocolConstants.java` L87-99) and by QRing (`Constants.java` L100-102).

- No encryption, no pairing key, **anyone can connect**. Multi-user defence is MAC whitelist on the central.
- One central at a time. Two devices trying to grab the ring conflict.
- Charging the ring kills BLE.
- High bit of `byte[0]` in any response = error flag (🟡 QRing `Constants.java` L112 — not seen in 小猪).

## 3. Write commands

All commands are **16 bytes fixed**: `[0] = command code`, `[1..14] = payload (zero-padded)`,
`[15] = checksum = sum(bytes[0..14]) & 0xFF`. 🟢 Confirmed by both 小猪 v2 (`ProtocolConstants.java`
L100-103) and QRing (`Constants.java` L111, `BaseReqCmd.java` L13-19).

### 3.1 🟢 R08-confirmed commands (small but trusted)

These four are the **only** commands `com.ring.r08remote` (小猪 v2) implements. Verified by:
the published 小猪 app works on R08 hardware in the wild → these bytes are what real R08 firmware
honours.

| Name | Hex (16 bytes) | Checksum | Purpose | 小猪 source |
|---|---|---|---|---|
| `TOUCH_ENABLE` | `3B 01 00 01 01 00*10 3E` | 3B+01+00+01+01 = 3E ✓ | **Required after connecting + enabling notify.** Without this, the ring won't report touch/gesture events. | `ProtocolConstants.java:100` |
| `TOUCH_MODE` | `3B 02 00 09 01 00*10 47` | 3B+02+00+09+01 = 47 ✓ | Send ~500 ms after `TOUCH_ENABLE`. Configures touch-report mode. | `ProtocolConstants.java:102` |
| `TOUCH_DISABLE` | `3B 01 00 01 00 00*10 3D` | 3B+01+00+01+00 = 3D ✓ | Powers down the touch IC (the dominant ring drain). Send when the user takes off the glasses. | `ProtocolConstants.java:101` |
| `BATTERY_QUERY` | `03 00*14 03` | 03 = 03 ✓ | Async; the ring replies with a `03 <level%>` notify frame. | `ProtocolConstants.java:103` |

Notes:
- 小猪 v2 polls `BATTERY_QUERY` every **10 minutes** (`BATTERY_QUERY_INTERVAL = 600000`), not 30 min
  as earlier drafts of this doc claimed. Our current `AndroidR08BleClient` uses 30 min — phase-0 §A6
  will tell us whether the more frequent 10-min poll matters.

### 3.2 🟡 Family-known commands (QRing-discovered; need phase-0 to confirm on R08)

QRing implements ~70 cmds. The handful below are the ones we'd actually want for Halo Ring — none
verified on R08 yet. Cited paths refer to QRing decompile under
`/tmp/qring-decompiled/sources/com/oudmon/...` (full report: [`../refs/qring-new-version-protocol-2026-05-15.md`](../refs/qring-new-version-protocol-2026-05-15.md)).

| Hex | Name | Purpose | Phase-0 test | QRing source |
|---|---|---|---|---|
| `0x01` | `CMD_SET_DEVICE_TIME` | Sync ring's RTC. Payload = 7 bytes BCD `[yy-2000, MM, dd, hh, mm, ss, lang]`. Required for any history read to return useful timestamps. | §B "phase B" in `r08_verify_qring.py` — write once, observe ACK frame `01 …`. | `SetTimeReq.java`, `Constants.java:9` |
| `0x03` battery RSP | (response, not write) | QRing parses `03 <level> <charging>` — the charging byte. 小猪 ignores byte [2]. | §A passive observation: does the battery frame carry 3 bytes or 2? | `BatteryRsp.java:8-13` |
| `0x08 01` | `CMD_RE_BOOT` | Soft reboot. Used in QRing `SystemSettingActivity.java:60`. | §B with explicit confirmation gate (ring disconnects briefly). | `Constants.java:78`, `SimpleKeyPowerOffReq.java:6` |
| `0x16` | `CMD_HR_TIMING_MONITOR_SWITCH` | Read/write auto-HR-monitor cadence. Payload: `{1}` to read, `{2, enable, intervalMin, startHr, low, high, switch}` to write. **The ring is cadence master, not the phone.** | Not in `r08_verify_qring.py` yet — add later when we have a use case for background HR. | `HeartRateSettingReq.java` |
| `0x3C` | `CMD_DEVICE_FUNCTION_SUPPORT` | Read-only. Response = 9-byte capability bitmap exposing 28+ feature flags (touch, gesture, real-time HR, ECG, sleep, etc.). **Should be called once per connect** to gate UI on what firmware actually supports. | §B in `r08_verify_qring.py` — write `3C …`, observe `3C <9 bytes>` response. | `DeviceSupportReq.java`, `DeviceSupportFunctionRsp.java:60-136` |
| `0x48` | `CMD_GET_STEP_TODAY` | Canonical today-totals query. Response = 14 bytes BE: `[steps, running-steps, calories, distance(m), duration(min)]`. **More authoritative than waiting for `73 12` push** (which is just a sync hint). | §B in `r08_verify_qring.py` — write `48 …`, observe `48 <14 bytes>`. | `TodaySportDataRsp.java:7-22` |
| `0x50 AA AA` | `CMD_ANTI_LOST_RATE` | **QRing's actual find-device command.** Triggers vibration + LED blink. (QRing uses `MineFragment.java:3872`.) | §B in `r08_verify_qring.py` — does the ring vibrate / LED blink? | `FindDeviceReq.java:11` |
| `0x69 <kind> 01` | `CMD_START_HEART_RATE` (universal) | **Universal real-time vitals start.** `kind`: 1=HR, 2=BP, 3=SpO2, 4=Fatigue, 5=HealthCheck, 6=RealtimeHR, 7=ECG, 8=Pressure/stress, 9=BloodSugar, 10=HRV, 11=Temp. **All measurement screens run 25 s, not 3 s** as our [R08Protocol.kt](../app-project/core/src/main/kotlin/com/halo/ring/core/ble/R08Protocol.kt) currently assumes. Tick = 500 ms. | `r08_health_probe.py` (new) — start HR, log every notify for 30 s, see when ring goes quiet on its own vs. when we issue stop. | `StartHeartRateReq.java:1-46`, timing per `HeartActivity.java:415-481` |
| `0x6A <kind> <last> <opt>` | `CMD_STOP_HEART_RATE` | Stop the stream. Payload carries the last sampled value (for HR: `[kind, value, 0]`; for BP: `[kind, sbp, dbp]`). | Same as above. | `StopHeartRateReq.java:1-56` |

### 3.3 🔴 Conflicting opcodes — 小猪 has these constants, QRing has different meanings

`R08Protocol.kt` defines three more commands inherited from the original `R08-Dev.md` handoff doc.
**Neither 小猪 v2 source nor QRing source supports the names we gave them**, but they may still
work the way our doc claims if R08's firmware happens to interpret them that way. Phase-0 §C in
[`r08_verify_qring.py`](../phase0/r08_verify_qring.py) tests each one carefully (with the
dangerous one — `0x0F` — gated behind a `YES` confirmation since it may trigger OTA bootloader
mode that requires the web flasher to recover from).

| Hex | Halo Ring assumption (R08-Dev.md heritage) | QRing's name | Risk | Phase-0 test |
|---|---|---|---|---|
| `0x06` | `FIND_DEVICE` — blink LED ~10 s | `CMD_MUTE` (DnD) | Low: ring goes mute instead of blinking, no damage. | §C in `r08_verify_qring.py` — watch for LED blink for 12 s after send. |
| `0x10` | `BLINK_TWICE` — quick 2-blink LED | `CMD_BIND_SUCCESS` (first-bind ACK only) | Low: silent / no visible action. | §C — watch for 2 blinks within 2 s. |
| `0x0F` | `SHUTDOWN` — power off | `TO_OTA` — switch to firmware-flasher bootloader | **HIGH** — if QRing is right, the ring enters OTA mode and needs https://atc1441.github.io/ATC_RF03_Writer.html to recover. | §C gated behind explicit `YES` confirmation; can be skipped. Test alongside QRing's `0x08 01` (which IS a real soft reboot). |

Working hypothesis: 小猪 was originally based on a 2022-era predecessor of QRing where these
opcodes did mean what our `R08Protocol.kt` claims; QRing has since refactored. Or: the original
R08-Dev.md handoff doc was simply wrong and 小猪 inherited the error without consequence (since
nobody clicks "Shutdown ring" in a remote-control app). Phase-0 will tell us.

## 4. Notify frames (ring → central)

The ring pushes events over the notify char. Length varies (2–16 bytes typically). Disambiguate
by `data[0]` (and sometimes `data[1]`). High bit of `data[0]` = error flag (mask before dispatch).

### 4.1 🟢 R08-confirmed frame catalogue (from 小猪 v2)

These frames are what 小猪 v2 actively parses (`DataParser.java`, `GestureParser.java`):

| `data[0]` | `data[1]` | Length | Meaning | Payload decode |
|---|---|---|---|---|
| `0x73` ('s') | `0x2D` ('-') | ≥ 3 | **Control gesture** (R08-specific touch IC firmware path; not seen in QRing decompile) | `data[2]`: `0x01`=swipe up, `0x02`=swipe down, `0x03`=single touch, `0x04`=long press |
| `0x73` | `0x2A` ('*') | ≥ 3 | Touch enable/disable echo | `data[2] == 0` → touch IC enabled (`DataParser.java:36-37`) |
| `0x73` | `0x12` | ≥ 11 | Activity counters | `[2..4]` steps BE 24-bit, `[5..7] / 1000` calories, `[8..10] / 1000` distance (raw int is metres; divided value is km despite the historical `distanceMeters` field name — `DataParser.java:50-53`). **🟡 byte-order verification needed**: 小猪 source confirms steps & calories are BE, distance is also BE (data[8]=MSB, data[10]=LSB). |
| `0xA1` (161) | — | 16 (fixed) | Accelerometer raw | 6 payload bytes at `data[2..7]`. **小猪 reads them but does not decode** (`DataParser.java:58-69`). **QRing does not decode either**. Likely `(x_lo, x_hi, y_lo, y_hi, z_lo, z_hi)` signed-16 pairs from STK8321; phase-0 §A2 will calibrate by physical motion. |
| `0x03` | — | ≥ 2 | Battery percentage | `data[1]` = battery %. 🟡 QRing extends: `data[2]` = isCharging (0/1). 小猪 ignores byte [2]. Phase-0 §A confirms whether R08 firmware actually populates [2]. |
| `0x69` ('i') | `1` / `3` / `8` | ≥ 4 | Real-time health reading | `data[3]` = value: kind 1 = HR (bpm), 3 = SpO2, 8 = stress. Only valid if value > 0. 🟡 QRing adds: `data[2]` = errCode where 1 = "not worn properly" (`StartHeartRateRsp.java:18-22`). 小猪 doesn't check this. Phase-0 §D probes by removing the ring during a vitals stream. |
| `0x51` (81) | — | ≥ 3 | Steps only | `data[1] \| (data[2] << 8)` (little-endian 16-bit) per 小猪 `DataParser.java:92`. ⚠ Note: QRing repurposes `0x51` as `CMD_LOVER_EVENT`. 小猪's interpretation is what we trust on R08 since it's an inbound R08 frame. |

### 4.2 🟡 QRing-only frames (family-broad; presence on R08 needs phase-0 confirmation)

QRing decodes ~30 more `0x73 <sub>` "sync trigger" frames the ring pushes when an internally-stored
measurement is ready. None of these are in 小猪 because 小猪 is a touch-remote app and doesn't
care about health. Whether the R08 firmware actually emits these depends on the auto-monitor
settings (`0x16` / `0x2C` / `0x36` / `0x38`) — most are factory-default OFF.

Common subset (full catalogue in [`../refs/qring-new-version-protocol-2026-05-15.md`](../refs/qring-new-version-protocol-2026-05-15.md)):

| `data[1]` | Meaning | Decode |
|---|---|---|
| `0x01` | New manual-HR record stored | (sync trigger — pull HR history via `0x15`) |
| `0x0C` (12) | **Battery low warning** | (we'd auto-query battery in response) |
| `0x10` (16) | Daily target reached | (re-read goal via `0x21`) |
| `0x11` (17) | Step increment | `data[2]` = inc count |
| `0x12` (18) | Activity total sync hint | Same as the 0x73 0x12 we already decode |
| `0x30` (48) | "Lover double-tap" event | ⚠ R08-specific firmware MIGHT emit this for double-touch on the touch IC; phase-0 §A check |
| `0x31` (49) | "Current HR is X" reminder | `data[1]` (after sub-byte) = bpm |
| `0x34` (52) | Alarm-ring event | (app shows alarm dialog) |
| `0x3D` (61) | Temperature alarm | `((data[2] << 8) \| data[1]) / 10.0` °C |
| `0x3E` (62) | **G-sensor still-time tick** | Possibly the closest signal to "ring sitting still / wearer not moving" — relevant for power gating. Phase-0 §A check for presence. |

Phase-0 §A passive observation will tabulate which of these the R08 firmware actually emits.

### 4.3 No richer gestures from firmware

`com.ring.r08remote` (小猪) synthesises double-tap, triple-tap, combos **entirely on the app
side** — the ring firmware only ever reports the 4 raw gestures (TOUCH / LONG_PRESS / SWIPE_UP /
SWIPE_DOWN). The original R08-Dev.md speculated about "swing"/in-air gestures being recognised in
firmware; **they aren't**. The accelerometer at `0xA1` is being pushed continuously but the
encoding hasn't been decoded by anyone publicly (small mystery; phase-0 §A2 action item).

**There MAY be a hardware double-tap path** via the QRing `0x73 0x30` ("lover double-tap") frame —
if the R08 touch firmware emits this, our app could short-circuit double-tap recognition for a
~200 ms latency improvement. Phase-0 §A check.

## 5. Connection lifecycle (the recipe)

This is what 小猪 v2 (`DeviceBindingRepository.java` — to be confirmed) does on every connect.
Times in milliseconds after each step:

```
connectGatt(autoConnect = true)
on STATE_CONNECTED →
  discoverServices()

on services discovered →
  setCharacteristicNotification(NOTIFY_CHAR, true)
  writeDescriptor(CCCD, ENABLE_NOTIFICATION_VALUE)
+800 ms →
  writeCharacteristic(WRITE_CHAR, TOUCH_ENABLE)
+500 ms →
  writeCharacteristic(WRITE_CHAR, TOUCH_MODE)
+1500 ms →
  queryBattery()  (first sample)
  startBatteryPoll(every 10 min per 小猪; we use 30 min — phase-0 §A6 to revisit)

  startRssiPoll(every 5 s)  (for HUD signal indicator)

  (optional) request a short connection interval (~15–30 ms) for low latency.
  Relax to ~100–200 ms after N seconds idle. See 06-performance-and-power.md §3.
```

QRing additionally writes `0x3C` (capability query) and `0x01` (set-time) on first connect.
**If we adopt these, they should fire right after the touch init** so the rest of the connect
flow has the capability bitmap available — phase-0 §B will tell us if R08 honours them at all
(if it doesn't, we just skip the writes).

On disconnect:
- Don't actively re-scan; rely on `autoConnect`. App-level continuous scanning is a power killer.
- On the next reconnect, arm the **wake-swallow** in the gesture synthesiser — the user's first
  one or two TOUCH events may be the "double-tap to wake the ring from auto-sleep", not intent.
  See [05-interaction-design.md](05-interaction-design.md) §3.4.

## 6. De-duplication (critical, easy to get wrong)

Because gestures are repeats of the same packet bytes (e.g. two real taps both look like
`73 2D 03`), naïve "drop identical packet" de-duplication is dangerous: a fast human double-tap
(say 150 ms apart) would have its second tap dropped, breaking the double-tap gesture.

🟢 小猪 v2 uses **`DEDUP_INTERVAL = 100`** ms (`ProtocolConstants.java:25`) — barely below the
human double-tap minimum (~120–300 ms). Works **most** of the time. We do better:

1. Phase-0 §A1 measures the inter-tap interval distribution for real fast taps on your ring.
2. We also check if the ring's notify packets contain any varying byte (counter / timestamp). If
   yes, de-dup is trivial: drop only byte-for-byte identical packets within ~50 ms.
3. If no varying byte, use a tight window — based on the measurement, probably 40–60 ms.

The dedup belongs in the BLE client layer (`AndroidR08BleClient`), not the gesture synthesiser.
The synthesiser has an optional `minRawIntervalMs` defence-in-depth knob (default off).

## 7. Errata vs `R08-Dev.md` (and now vs current `R08Protocol.kt`)

The original `R08-Dev.md` handoff claimed several command bytes that turn out **not to be backed
by either source we've decompiled**. They live in `R08Protocol.kt` as 🔵 *inherited speculation*.
Documented in detail in [12-research-and-references.md](12-research-and-references.md) §4. Quick
list:

- Battery is `0x03`, not `0x08` (`0x08` is **soft reboot** per QRing `Constants.java:78`; the
  original `R08-Dev.md` was wrong, and `R08Protocol.kt` reflects the corrected mapping).
- Gesture command codes are `0x73 0x2D 0x01-04`, not the `0x70-7F` range.
- Swing / in-air gestures **do not** exist in firmware; the original doc's "swing → camera" mapping
  is recreated in the app as a double-tap + swipe combo.
- "Double-tap is firmware-recognised" — also false (mostly). All multi-tap counting is app-side
  timing. The QRing `0x73 0x30` "lover double-tap" frame is the one exception worth phase-0'ing.
- 🔵 **`FIND_DEVICE = 0x06` / `BLINK_TWICE = 0x10` / `SHUTDOWN = 0x0F`** — not in 小猪 v2 source;
  QRing names them mute / bind-success / OTA-switch instead. Phase-0 §C will adjudicate.

## 8. Phase-0 verification map

| Question | Phase-0 script + section | Resolves what |
|---|---|---|
| Does the ring really advertise `R08_xxxx` over service `6E40FFF0`? | `r08_probe.py` opening lines | §2 |
| Does the TOUCH_ENABLE / TOUCH_MODE / BATTERY init sequence work? | `r08_probe.py` (acceptance criteria) | §3.1, §5 |
| What are the four raw gesture frames timed like for fast double-tap? | `r08_probe.py --record` then analyse CSV | §6 |
| Does `0xA1` accelerometer appear, and what's the byte layout? | `r08_probe.py --record` motion patterns; planned `r08_health_probe.py --accel` | §4.1 |
| Does battery `03` carry the QRing-claimed charging byte? | `r08_verify_qring.py` §A passive | §4.1 |
| Does `0x73 0x3E` (G-sensor still-tick) appear? Other QRing-only `0x73` sub-codes? | `r08_verify_qring.py` §A | §4.2 |
| Does `0x3C` capability query / `0x48` today-totals / `0x01` set-time work on R08? | `r08_verify_qring.py` §B | §3.2 |
| Does `0x50 AA AA` actually blink + vibrate the ring? | `r08_verify_qring.py` §B | §3.2 |
| What does our `0x06` / `0x10` / `0x0F` actually do on R08? | `r08_verify_qring.py` §C (with safety gates) | §3.3 |
| Does `0x69 01 01` start a 25-s HR stream, with `errCode=1` when off-finger? | `r08_health_probe.py` (planned) | §3.2, §4.1 |

After phase-0 closes, every 🟡 / 🔴 / 🔵 tag in this doc should resolve to ✓ / ✗, and the next
audit pass updates `R08Protocol.kt` accordingly.
