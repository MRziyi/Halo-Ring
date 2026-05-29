# R08 / QRing BLE Protocol — Open Specification

**Version 1.2** (2026‑05‑27)

> **v1.2 changelog** — Implementation-guide consistency pass; no protocol
> content changed. Synchronises §9 with the live findings already in
> §4/§6/§10/§11: rewrites Rule 7 (err-byte semantics + BP no-stop-ack);
> adds Rules 11–14 (Beijing-locked timestamps · silent-persist read-back
> requirement · reconnect resilience · realtime-HR path); fixes architecture
> diagram (both HW + FW revisions read; BP-timeout caveat;
> `(op & 0x80) and payload[0] == 0xEE` detection); corrects "11 passes" →
> "13 passes" to match §0.3.

A community reverse-engineering of the BLE protocol used by R08-family smart
rings — those sold under the QRing brand and various OEM aliases (R01..R11,
VK-5098, MERLIN, Hello Ring, RING1, boAtring, Y25, H59, DS-series, …). The
protocol is shared across the family because they all ship the same `oudmon`
BLE SDK on the firmware side; ring-specific behaviour comes from runtime
capability flags, not different opcodes.

This document is the **constitution** for any third-party R08 client.
Every claim here has one of two provenances:

1. **APK-derived** — read directly from the decompiled QRing Android app
   (`com.app.cq.ring`, versionCode 131 / 1.0.1.131); cited with file
   path + line numbers in [Appendix A](#appendix-a--apk-source-citations).
2. **Live-verified** — confirmed on a real ring; specific ring identity
   + verification status per opcode is in [§10](#10-verification-status).

Where the two disagree, the live-verified observation wins.

---

## Conventions

- "Command channel" = the 16-byte fixed-frame opcode protocol on the
  primary GATT service. All references to "opcode" mean this channel.
- "SPP channel" = the variable-length big-data protocol; see [§7](#7-spp-big-data-channel).
- Hex bytes are written MSB-on-the-left, space-separated: `01 26 05 27 …`
- "u24 LE" = 3-byte little-endian unsigned. "u24 BE" = 3-byte big-endian.
- "BCD" = packed binary-coded decimal: `0x26` represents decimal 26.
- "Wire byte N" = position in the over-the-air frame (`byte 0` = opcode).
- "Payload byte N" = position in `bArr[1 .. len-2]` after the SDK strips
  opcode and CRC.

### Status legend (used throughout)

| icon | meaning |
|------|---------|
| ✅ | Spec-correct AND live-verified on a real ring |
| 🔧 | Spec-correct from APK; not exercisable on the firmware we tested (still believed correct elsewhere) |
| 🆕 | Live-discovered behavior NOT present in the APK source |
| 🟡 | Partially verified — see notes |
| ⚠️ | Spec contradicted by live behavior — implement per live observation |
| ❌ | Firmware refuses (uniform `0xEE` "unsupported" reply) on the tested unit |
| ⏱ | Firmware silently drops the request on the tested unit |
| ⛔ | Destructive — intentionally not exercised |

---

## 0. Provenance

### 0.1 Source artifacts
- **APK** — `QRing.apk`, package `com.app.cq.ring`, versionCode 131
  (`1.0.1.131`). Decompiled via [jadx](https://github.com/skylot/jadx) 1.5.5.
- **SDK identity** — the `com.oudmon.ble.base` package inside is the
  Chinese "oudmon" wearable SDK, used (with surface customisations) by
  many no-name ring vendors.

### 0.2 Tested hardware

The "live-verified" status throughout this document refers to one
specific physical unit:

- Advertised name: `R08_E600`
- BLE MAC: `30:35:47:33:E6:00`
- Hardware revision (GATT `0x2A27`): `RT08_V3.1`
- Firmware revision (GATT `0x2A26`): `RT08_3.10.46_250621` (built 2025‑06‑21)
- Chipset: Realtek 8772-family (inferred from oudmon SDK references)

Other R0x rings will have similar protocol surface but **different
working subsets**: a feature listed `❌` here may work on a different
firmware build, and vice versa. Always validate against your actual
hardware before shipping a feature.

### 0.3 Verification runs

Thirteen independent test passes (including one targeted re-test) over a
~38-hour window in May 2026, ranging from a 5-minute walking calibration
to a comprehensive opcode-by-opcode audit. Aggregate: ~1.5 hours of active
BLE traffic, **>160 ACTIVITY_TOTAL frames** across three independent
walking sessions, **120 captured gesture events**, **6 controlled
accelerometer orientations**, **three independent charging-state sessions**
(plug-in with battery rising, plug-in with battery already full, and a
verified unplug), one **active `0x77` sport session** with 62 `0x78`
PhoneSportNotify frames captured, and a `0x21` TargetSetting threshold
enumeration sweep.

Conventions for the verification overlay:
- "PASS N" references in this doc refer to runs documented in the project's
  validation logs.
- Verdicts are conservative — if a feature responded once but couldn't be
  reproduced under controlled conditions, it's marked 🟡 not ✅.

---

## 1. GATT Layer

### 1.1 Service / characteristic tree

```
6e40fff0-b5a3-f393-e0a9-e50e24dcca9e       (primary service — "command channel")
  ├── 6e400002-b5a3-f393-e0a9-e50e24dcca9e   write / write-without-response
  └── 6e400003-b5a3-f393-e0a9-e50e24dcca9e   notify + CCCD descriptor (0x2902)

0000180a-0000-1000-8000-00805f9b34fb       (standard device-info service)
  ├── 00002a23-…  SystemID         ✅ present  (8 bytes binary)
  ├── 00002a25-…  SerialNumber     ✅ present  (empty string on tested unit)
  ├── 00002a26-…  FirmwareRevision ✅ present  (ASCII, e.g. "RT08_3.10.46_250621")
  ├── 00002a27-…  HardwareRevision ✅ present  (ASCII, e.g. "RT08_V3.1")
  ├── 00002a24-…  ModelNumber          ❌ absent on tested unit
  ├── 00002a28-…  SoftwareRevision     ❌ absent
  ├── 00002a29-…  ManufacturerName     ❌ absent
  ├── 00002a2a-…  RegulatoryCertData   ❌ absent
  └── 00002a50-…  PnPID                ❌ absent

de5bf728-d711-4e47-af26-65e3012a5dc7       (SPP "big-data" service)  🔧 absent on prod firmware
  ├── de5bf729-…   notify
  └── de5bf72a-…   write
```

**App-dev rules:**
1. Subscribe notify on `6e400003` first; bootstrap order in [§2](#2-bootstrap) is
   not optional.
2. Read `0x2A27` (HW rev) before sending any opcodes — see [§2.3](#23-internal-ready-gate).
3. The SPP service is only available when the ring is in OTA mode on the
   firmware revisions we've seen. Production firmware (RT08_3.10.46) does
   **not** expose it — all big-data flows in [§7](#7-spp-big-data-channel) are
   spec-only there.

### 1.2 Advertising data

The ring advertises **only its local name** (`R08_E600` style). On the
tested unit no service-UUID, service-data, manufacturer-data, or tx-power
fields are populated. The APK's references to a `0xFEE7` service UUID and
`0x1234` manufacturer ID are leftover from another firmware revision.

To filter for these rings during scanning, match local-name prefix against
one of: `R0`, `R1`, `VK-`, `MERLIN`, `Hello Ring`, `RING1`, `boAtring`,
`Y25`, `H59`, `DS`, `QRing`, `QC`, `Q_`, `O_`. (The APK's
[`QCApplication.java`](work/jadx_out/sources/com/qcwireless/smart/QCApplication.java)
hardcodes this list; the canonical version is in `defaultScanDevice()`.)

### 1.3 MTU

The ring negotiates ATT MTU 247 immediately after `_didConnect`. It also
spontaneously sends one **`0x2F PackageLength`** command-channel frame
during bootstrap (typically `2F F4 00 …` = payload-max 244 bytes) telling
the host the max SPP payload size — see [§4.1](#41-foundational--handshake) for the
opcode reference.

### 1.4 Bonding / pairing

The ring does **not** require BLE pairing/bonding. Do not initiate it
from your app. The oudmon SDK only triggers `BluetoothDevice.createBond()`
opportunistically if the OS reports an existing bond, but this is not
required for any opcode in the catalogue.

---

## 2. Bootstrap

### 2.1 The verified sequence

This is the exact sequence to run after BLE GATT connection succeeds.
Live-verified across all 11 test passes:

```text
1. Set up notify on 6e400003 (CCCD descriptor write)
2. READ 0x2A27 (HW revision string) — required, see §2.3
3. READ 0x2A26 (FW revision string) — required, see §2.3
4. wait 500 ms
5. send 0x01 SetTime, wait for response (carries 14-byte capability extension)
6. send 0x04 BindAncs, wait for response (carries empty-body ack)
7. send 0x3C DeviceFunctionSupport, wait for response (9-byte capability bitmap)
8. send 0x61 GetMessagePush, wait for response
9. send 0x03 Battery, wait for response

Expect an unsolicited `2F F4 00 …` frame to arrive somewhere between
steps 5 and 9 — this is the ring spontaneously announcing its SPP
package-length cap (`0xF4 = 244` bytes). It is part of normal bootstrap,
not an error; record `byte 1` as the max SPP payload size and continue.
```

Reference Python (using the [`r08_protocol.py`](tools/r08_protocol.py) library):

```python
from bleak import BleakClient
import r08_protocol as r, asyncio

async def bootstrap(addr):
    cli = BleakClient(addr, services=[r.SVC_CMD, r.SVC_DEVINFO], timeout=20)
    await cli.connect()

    seen = {}
    def on_frame(_, data):
        op = data[0] & ~r.ERROR_FLAG
        seen[op] = bytes(data)

    await cli.start_notify(r.CHR_CMD_NOTIFY, on_frame)
    hw = (await cli.read_gatt_char(r.CHR_HW_REV)).decode()
    fw = (await cli.read_gatt_char(r.CHR_FW_REV)).decode()
    await asyncio.sleep(0.5)

    for label, frame, op in [
        ("SetTime", r.build_set_time(), 0x01),
        ("BindAncs", r.pack_cmd(0x04, b"\x02\x0Apython-r08"), 0x04),
        ("Support", r.pack_cmd(0x3C), 0x3C),
        ("MsgPush", r.pack_cmd(0x61), 0x61),
        ("Battery", r.pack_cmd(0x03), 0x03),
    ]:
        await cli.write_gatt_char(r.CHR_CMD_WRITE, frame, response=False)
        # Wait up to 4 s for the response — simple polling is fine because
        # commands are sequential.
        for _ in range(40):
            if op in seen: break
            await asyncio.sleep(0.1)

    return hw, fw, seen
```

### 2.2 What happens if you skip steps

Each step's role is concrete; skipping breaks behaviors downstream:

| skip | symptom |
|------|---------|
| CCCD subscribe | No notifications received; opcodes appear to "time out". |
| 0x2A27 read | **The Android SDK's `BleOperateManager.ready` flag never flips to true; all write commands are silently dropped**. The R08 firmware itself may also enforce this gate (see [§2.3](#23-internal-ready-gate)). |
| SetTime | Ring's wall-clock is wrong; any history command returns garbage timestamps. |
| BindAncs | Ring won't know your client's "OS / model"; some firmware variants block notification-push opcodes (0x72) until this is sent. |
| 0x3C | You don't know what capabilities the unit has; capability-gating UI is impossible. |

### 2.3 Internal "ready" gate

The Android SDK has an internal flag `BleOperateManager.ready` that is
`false` until the device-info read of `0x2A27` (HW revision) completes.
While `false`, every write command is **silently dropped** by the SDK
itself ([`BleOperateManager.java:349`](work/jadx_out/sources/com/oudmon/ble/base/bluetooth/BleOperateManager.java)).

For non-Android clients this is a host-side detail (your code doesn't have
to enforce it), but our cross-stack testing strongly suggests the **ring
firmware also enforces this implicitly** — without the 0x2A27 read,
post-SetTime commands time out unpredictably even though SetTime itself
sometimes succeeds.

**Always read 0x2A27 first.** It costs one GATT read; it's the cheapest
insurance you can buy.

---

## 3. Capability Discovery

The ring publishes its working capabilities **twice**, in two different
formats. Always parse both — they're complementary.

### 3.1 SetTime response — 14-byte capability extension

The `0x01` SetTime response carries 14 payload bytes that act as a
"fat capability map". Decoder
([`SetTimeRsp.acceptData`](work/jadx_out/sources/com/oudmon/ble/base/communication/rsp/SetTimeRsp.java)):

```
byte 0  : supportTemperature                 (==1)
byte 1  : supportPlate                       (==1, watchface market)
byte 2  : supportMenstruation                (==1)
byte 3  : packed bitmap:
            0x01 supportCustomWallpaper
            0x02 supportBloodOxygen
            0x04 supportBloodPressure
            0x08 supportFeature
            0x10 supportOneKeyCheck
            0x20 supportWeather
            0x40 *un*supportWeChat   (zero = WeChat is supported)
            0x80 supportAvatar
byte 4..5 : screen width   (u16 LE)
byte 6..7 : screen height  (u16 LE)
byte 8    : mNewSleepProtocol               (==1)
byte 9    : mMaxWatchFace
byte 10   : packed bitmap:
            0x01 supportContact
            0x02 supportLyrics
            0x04 supportAlbum
            0x08 supportGPS
            0x10 supportJieLiMusic
            0x20 supportAppMeasure
            0x40 supportManualBloodOxygen
            0x80 supportYaWei
byte 11   : packed bitmap:
            0x01 supportManualHeart
            0x02 supportECard
            0x04 supportLocation
            0x10 supportMusic            (lock-screen control)
            0x20 rtkMcu                  (Realtek chip)
            0x40 supportEbook
            0x80 supportBloodSugar
byte 12   : mMaxContacts  (0 = default 20, else value × 10)
byte 13   : packed bitmap:
            0x01 supportRecord
            0x02 bpSettingSupport
            0x04 support4G
            0x08 supportNavPicture
            0x10 supportPressure
            0x20 supportHrv
```

Tested-unit value (verified 3× across separate sessions):

```
01 00 00 02 00 00 00 00 01 00 20 00 00 30
└─┬┘ │  │  │  └──┬──┘ └──┬──┘ │  │  │  │  │  └─┬┘
  │  │  │  │     │       │   │  │  │  │  │    │
  │  │  │  └ bloodOxygen │   │  │  │  │  │    └ pressure + hrv
  │  │  │      only      │   │  │  │  │  │
  │  │  │                │   │  │  │  │  └ default 20 contacts
  │  │  │                │   │  │  │  └ no extra ringSDK flags
  │  │  │                │   │  │  └ supportAppMeasure only
  │  │  │                │   │  └ no max watchfaces (screenless)
  │  │  │                │   └ new sleep protocol
  │  │  │                └ screen 0×0 (no display)
  │  │  └ no menstruation
  │  └ no watchface market
  └ supports body temperature
```

### 3.2 0x3C DeviceFunctionSupport — 9-byte bitmap

The `0x3C` response carries 9 capability bytes
([`DeviceSupportFunctionRsp.acceptData`](work/jadx_out/sources/com/oudmon/ble/base/communication/rsp/DeviceSupportFunctionRsp.java)).
Each byte is a tightly packed bit-field:

```
byte 1  0x01 supportTouch            0x02 supportMoslin
        0x04 supportAPPRevision      0x08 supportBlePair
        0x40 deviceNoScreen          0x80 supportGesture

byte 2  0x01 supportRingMusic        0x02 supportRingVideo
        0x04 supportRingEbook        0x08 supportRingCamera
        0x10 supportRingPhoneCall    0x20 supportRingGame
        0x40 supportHeart

byte 3  0x01 supportSkinTemperature  0x04 supportLongSit
        0x08 supportDrink            0x10 supportNoSingleTemperature
        0x20 supportNotification     0x80 supportAiAnalyze

byte 4  0x08 supportRingGestureDND   0x10 tpSleep
        0x20 supportRt11             0x80 resumeServices

byte 5  (when non-zero, overrides the touch bits from byte 2;
         on this unit byte 5 is 0 so byte 2 wins)
        0x01 supportRingMusicTouch   0x02 supportRingVideoTouch
        0x04 supportRingEbookTouch   0x08 supportRingCameraTouch
        0x10 supportRingPhoneCallTouch 0x20 supportRingGameTouch
        0x40 supportHeartTouch       0x80 supportMoslin
        (`supportMoslin` set here overrides the same flag from byte 1)

byte 6  0x04 unSupportTakePhoto      0x08 supportLoverSpace
        0x10 supportWorship          0x20 supportNewPraise
        0x40 supportAlarm            0x80 supportDoNotDisturb

byte 7  0x01 supportUltraviolet      0x02 supportCallReminder
        0x04 supportRealTimeOxygen   0x08 supportRealTimeHr
        0x10 supportRealTimeHrRemind 0x20 supportFriends
        0x40 supportLoverInteract    0x80 supportTempIntervalModify

byte 8  0x20 bodyTag                 0x40 supportTempReminder
        0x80 supportIntervalTemp

byte 9  0x02 supportEcg              0x04 supportTempBoth
        0x08 supportBreathTraining   0x10 supportAudio
        0x20 supportMeetingRecord
```

Tested-unit value `2F AF 2E 00 00 00 00 00 00`:

| byte | hex | flags set | undocumented bits also set |
|------|-----|-----------|----------------------------|
| 1 | `0x2F` | touch + moslin + appRev + blePair | **0x20** (undocumented) 🆕 |
| 2 | `0xAF` | ringMusic + ringVideo + ringEbook + ringCamera + ringGame | **0x80** (undocumented) 🆕 |
| 3 | `0x2E` | longSit + drink + notification | **0x02** (undocumented) 🆕 |
| 4–9 | `0x00` | (none) | — |

**The three undocumented bits (b1.0x20, b2.0x80, b3.0x02) are reported
by the firmware but never decoded by the Android SDK.** They likely
correspond to features the SDK developer hadn't wired up. Treat them as
"reserved for firmware extensions"; don't infer functionality.

### 3.3 Recommended capability-gating strategy

```python
caps_settime = parse_settime_rsp(settime_response_payload)
caps_3c      = parse_support_3c(support_response_payload)
caps_all     = {**caps_settime, **caps_3c}

# Then gate every UI feature:
if caps_all["supportBloodOxygen"]:
    show_spo2_button()
```

Don't trust a single source — `0x3C` advertised flags but the live
opcode returns `0xEE` is a real failure mode for some firmware/SDK
mismatches. **Use `0xEE` detection at runtime as a second gate**:

```python
def is_unsupported(frame):
    return (frame[0] & 0x80) and frame[1] == 0xEE
```

---

## 4. Command-Channel Opcodes

### 4.0 Frame format

```
byte 0      : opcode      (top bit 0x80 set = ERROR response from ring)
byte 1..14  : payload     (zero-padded if shorter)
byte 15     : checksum    = sum(bytes 0..14) & 0xFF   (NOT a CRC)
```

Note the command-channel checksum is a **simple sum-mod-256 byte**, *not*
a CRC. The SPP channel ([§7](#7-spp-big-data-channel)) uses a different
mechanism — CRC-16/Modbus. Don't share code between them.

Source: [`BaseReqCmd.getData()`](work/jadx_out/sources/com/oudmon/ble/base/communication/req/BaseReqCmd.java).
Verified across all 11 test passes.

### 4.1 Foundational / handshake

| op | name | status | request | response | notes |
|----|------|--------|---------|----------|-------|
| `0x01` | SetTime | ✅ | `[YY-2000 BCD][MM BCD][DD BCD][hh BCD][mm BCD][ss BCD][lang u8]` | 14-byte capability extension ([§3.1](#31-settime-response--14-byte-capability-extension)) | Languages: 0=zh_CN, 1=en, 2=zh_HK/TW, 3=el, 4=fr, 5=de, … 16=ar, 17=th. Full list in [`SetTimeReq.initMap`](work/jadx_out/sources/com/oudmon/ble/base/communication/req/SetTimeReq.java) |
| `0x04` | BindAncs | ✅ ⚠️ | `[0x02][sdk_level u8][model UTF-8, ≤13 B]` | empty-body ack `04 00 …` | **The APK comments say "no response" but the ring DOES reply.** Safe to re-send (idempotent). Live-verified across multiple sessions. |
| `0x3C` | DeviceFunctionSupport | ✅ | empty | 9-byte capability bitmap ([§3.2](#32-0x3c-devicefunctionsupport--9-byte-bitmap)) | Always-on, no sub-action |
| `0x61` | GetMessagePush | ✅ | empty | `[??][??][supp1_LE_u16][supp2_LE_u16][supp3_LE_u16]` | On unconfigured unit, response is `FF FF 00 00…` — sentinel meaning "no notification routing set" |
| `0x60` | SetANCS | ✅ | `[0xFF][0x9F]` | empty-body ack | Enables all ANCS notification categories |
| `0x10` | BindSuccess | ✅ | empty | empty-body ack | Sent by Android app's `DeviceBindActivity.onDestroy` to mark completion of binding UI |
| `0xA1` | (Was: DeviceRevision) | ✅ 🆕 | `[sub-id u8]` | **continuous 4-frame-per-second telemetry stream** — see [§6](#6-0xa1-telemetry-stream) | Spec-named "DeviceRevision" but actual function is sensor telemetry |
| `0x93` | GetHwFwVersion | ✅ | empty | 2-frame response; frame 2 carries ASCII FW string in payload[1..] | Alternate to GATT `0x2A26` for firmwares that omit the device-info service |
| `0x2F` | PackageLength | ✅ | (ring-driven; sent once spontaneously after bootstrap) | `[max_payload u8]`, typically `0xF4` = 244 | App can use this as max SPP frame size on the big-data channel |

### 4.2 Battery / power

| op | name | status | notes |
|----|------|--------|-------|
| `0x03` | Battery | ✅ | Req empty; resp `[level_pct u8][charging u8]` (`charging`>0 = on cradle) |
| `0x08` | RebootPowerOff | ⛔ | Req `[0x01]`. Verified at SDK level; **NOT** exercised live (destructive). |
| `0xFF` | FactoryRestore | ⛔ | Req `[0xED][0xED]` magic. Wipes ring data. NOT exercised live. |
| `0x76` | BatterySaving | ❌ | Read/write both return `0xEE` on tested unit. |

### 4.3 Time / display configuration

All of these are **`❌` on the tested unit** because R08 has no display.
Spec retained for screen-equipped variants (band-style siblings) where
they may work.

| op | name | status | sub-actions |
|----|------|--------|-------------|
| `0x0A` | TimeFormat / UserProfile | ✅ | Read `[01]`; write `[02, is_12h, lang]` (short) or `[02, is_12h, metric, gender, age, height_cm, weight_kg, sbp, dbp, warn_HR, open]` (full). Live-verified bidir on tested unit. |
| `0x12` | DisplayClock (raise-to-wake) | ❌ | Read/write both `0xEE`. |
| `0x1B` | Brightness | ❌ | Read/write both `0xEE`. |
| `0x19` | TempUnit (°C / °F) | ✅ | Read `[01]`; write `[02, enable, celsius(1)/F(2)]`. Live-verified bidir. |
| `0x1F` | DisplayTime | ❌ | Read/write/delete all `0xEE`. |
| `0x29` | DisplayOrientation | ❌ | `0xEE`. |
| `0x2A` | DisplayStyle | ❌ | `0xEE`. |
| `0x05` | PalmScreen / gesture-wake | ⏱ | Silent timeout on both `[01]` band and `[03]` ring read variants. |
| `0x09` | Intell (gesture-wake delay) | ❌ | `0xEE`. |
| `0x06` | DND (do-not-disturb) | ❌ | `0xEE`. |
| `0x75` | DialIndex (current watchface) | ❌ | `0xEE`. |
| `0x32` | DeviceAvatar | ❌ | `0xEE`. |

### 4.4 Auto-monitor settings (timed health monitoring)

All follow the pattern: `[01]` = read, `[02, enable, …]` = write.

| op | name | status | notes |
|----|------|--------|-------|
| `0x16` | HR auto-monitor | ✅ | Read body `[01, en, interval_min, start_h, low_thresh, high_thresh, main_switch]`. Write `[02, en, interval, start_h, high, low, main]`. Tested-unit default: `01 02 1E 05 00 00 00` (enabled, 30-min interval). Live-write confirmed persistent. **Side-effect**: while this is enabled (default), the ring autonomously runs PPG every 30 min — observable as the **green LED, then red LED** lighting briefly in sequence on a worn ring (not simultaneously). Disable with `0x16 [02, 00, …]` if your app needs to own measurement timing. |
| `0x36` | Pressure / stress auto | ✅ | Read `[01]`; write `[02, en]`. Live bidir. |
| `0x38` | HRV auto | ✅ | Same shape. Live bidir. |
| `0x2C` | SpO2 auto | ✅ | Read `[01]`; write `[02, en]` or `[02, en, interval_min]`. Live bidir. |
| `0x0C` | BP auto | 🟡 | Read `[01]` returns 7-byte body. Write `[02, en, sh, sm, eh, em, multiple]` is ACK'd but **read-back shows no change** — firmware quietly drops it. |
| `0x3A` | Sugar / Lipids / SkinTemp ext. | 🟡 | Sub-cmd 1=sugar, 2=lipids, 3=skin-temp. `0x3A 03 [01]` works (returns body), `[02, …]` writes echo but don't visibly persist. `0x3A 01 …` and `0x3A 02 …` silent. |

#### Skin-temperature `0x3A type=3` extended write payload

```
[0x03][0x02][en][interval_min][start_h][remind_interval][remind_bitmap][temp_raw]
```

- `remind_bitmap`: `bit0`=low, `bit1`=mid, `bit2`=high, `bit3`=custom
- `temp_raw`: `(custom_temp_celsius × 10) − 200`, so 38.5 °C → raw 185

### 4.5 Live measurements (`0x69` start / `0x6A` stop)

This is the **single most important user-facing feature**. The mechanism:
client sends `0x69 [type, action]` to start; ring streams progress frames
on opcode `0x69` until either it converges (`err=0` with non-zero `val`),
fails (`err=2` wear-detect), or the client sends `0x6A [type, 4, 0]`.

Type IDs ([`StartHeartRateReq`](work/jadx_out/sources/com/oudmon/ble/base/communication/req/StartHeartRateReq.java)):

| type | name | live status | result format |
|------|------|-------------|---------------|
| `0x01` | HR | ✅ converges to HR | `[type][err][bpm][00][00][00][raw_LE_u16][zeros]` |
| `0x02` | BP | 🟡 progress-only | `[type][err][00][00][00][00][raw_LE_u16][zeros]` — val stays 0 on this FW; raw varies. Spec-promised SBP/DBP at bytes 3-4 are **only set for type=0x05 Healthcheck composite, not for type=0x02**. ⚠️ |
| `0x03` | SpO2 | ✅ converges | `[type][err][spo2_pct][quality?][00][00][zeros]` |
| `0x04` | Fatigue | 🟡 | progress-only, val=0 |
| `0x05` | Healthcheck composite | ✅ | `[type][err][bpm][sbp][dbp][00][raw_LE_u16][zeros]` — runs HR + BP measurement simultaneously |
| `0x06` | Realtime HR streaming | ❌ | The SDK spec implies `0x69 [06, 01]` should start a `1E NN …` stream on opcode `0x1E`. On RT08_3.10.46 this does not happen: `0x1E [01]` and `0x1E [00]` both return `0xEE`, and `0x69 [06, 01]` behaves like an ordinary `0x69` measurement (progress with `err=0`, then `err=2` wear-detect). No `1E NN` stream is induced. For live HR use one-shot `0x69 [01, 01]`. |
| `0x07` | ECG | 🔧 | `supportEcg` capability is false on this hardware; `0x6C`/`0x6D`/`0x6E` also return `0xEE`. |
| `0x08` | Pressure / stress | 🟡 | progress-only, val=0 |
| `0x09` | BloodSugar | 🟡 | progress-only, val=0 |
| `0x0A` | HRV | 🟡 | progress-only, val=0 |
| `0x0B` | Body Temperature | 🟡 | progress-only, val=0 |

Sub-actions for `0x69`: `1` = start, `2` = pause, `3` = continue, `4` =
stop, `16` (`0x10`) = ECG.

#### `err` byte semantics

| err | meaning |
|-----|---------|
| `0x00` | Progress / converged. For HR / SpO2 / Healthcheck, `err` stays at `0x00` throughout: early frames carry `val=0`, and once the firmware locks on, the *same* `err=0x00` frame begins carrying non-zero `val` (and `sbp/dbp` for Healthcheck). Convergence is signalled by `val` becoming non-zero, not by an `err` transition. |
| `0x01` | "Measurement complete" sentinel — `69 [type] 01 00 …` with no result payload. Observed only for `type=0x08 Pressure` and `type=0x0B Temp` on this firmware; HR / BP / SpO2 / Healthcheck never emit it. |
| `0x02` | **Wear-detect failure** — `69 [type] 02 [N] …`. Finger not making good optical contact. Show "adjust ring" UI and retry. |

#### `0x6A` stop semantics

- `0x6A [type, 4, 0]` while a measurement is active → ack `6A [type] [final_value] 00 …`
- For HR / SpO2 / Healthcheck, **the stop ack's `payload[1]` carries the
  converged final value** — cleaner than polling `0x69` for `err=1`.
- `0x6A` sent without an active measurement → silent timeout. Don't send
  speculatively.
- BP `0x6A` stop: on this firmware the ring usually returns **no `0x6A` ack
  frame at all** — the next inbound frame after the stop is just the
  trailing `0x69 02` progress echo. (One historical run produced
  `6A 02 49 00`; it could not be reproduced under controlled retests.)
  Don't rely on the BP stop-ack for the final BP value. Either read the
  live `0x69 02` progress frame's `raw_LE_u16` field at the moment of
  stop, or use `0x69 [05, 01]` Healthcheck — which returns sbp/dbp at
  fixed payload offsets.

#### `0x1E` realtime HR enable/disable

`❌` on tested firmware (both `[01]` enable and `[00]` disable return
`0xEE`; see PASS 1 / validate.log). The SDK's intended alternative is
`0x69 [06, 01]` — but that path is **🔧 spec-only on this firmware**
(see the type=`0x06` row above). If you need live HR, the only
PASS-confirmed path on RT08_3.10.46 is `0x69 [01, 01]` for a single
host-initiated measurement.

### 4.6 History reads (multi-frame stream)

| op | name | status | notes |
|----|------|--------|-------|
| `0x07` | TotalStepSomeday | ❌ on tested unit |
| `0x14` | BP history | ⏱ silent at 10 s timeout — assume disabled |
| `0x15` | HR history (one day) | ✅ — returns `15 FF …` sentinel (no data) |
| `0x37` | Stress history | ✅ — `37 FF …` (no data) |
| `0x39` | HRV history | ✅ — `39 FF …` (no data) |
| `0x43` | StepSomedayDetail | ✅ — `43 FF 00 01 …` (no data) |
| `0x44` | SleepDetail | ❌ |
| `0x46` | QueryDataDistribution (which days have data) | ❌ |
| `0x48` | TodaySport summary | ✅ — body `00 00 …` (no data today) |

#### Multi-frame stream sentinel pattern (`§6.2b` in old spec)

Every multi-frame history response uses the same 3-state header convention
on `payload[0]`:

| `payload[0]` | meaning |
|--------------|---------|
| `0xFF` | end-of-stream / no data exists for this query — clear buffer, stop |
| `0xF0` | "header" frame; subsequent bytes carry size / day-offset metadata |
| `0x00` | "size + range" header for HR-style streams |
| other index | data chunk; receiver stops when `index == size - 1` |

(Citations: [`ReadDetailSportDataRsp.acceptData`](work/jadx_out/sources/com/oudmon/ble/base/communication/rsp/ReadDetailSportDataRsp.java),
[`ReadSleepDetailsRsp.acceptData`](work/jadx_out/sources/com/oudmon/ble/base/communication/rsp/ReadSleepDetailsRsp.java),
plus 4 other Rsp classes that share this convention.)

#### Timezone handling

⚠️ **Caveat — empirically unverified on this firmware.** Every history
opcode tested in PASS 1–12 returned the no-data sentinel (`FF` header),
so no real timestamp payload was decoded from a history stream. The
guidance below is from APK source code; if it disagrees with [§4.8
0x77 timestamp encoding](#48-phone-sport-session-0x77--0x78) — which
**is** wire-verified to use a **hardcoded UTC+8 (Beijing) assumption on
SetTime** — trust the 0x77 finding. The most likely truth is that
*every* device-emitted unix-seconds field interprets its internal clock
as Beijing time; the SDK's per-opcode tz-offset convention may be a
remnant of older firmwares.

SDK convention (unverified on RT08_3.10.46):

```python
ring_time_field = local_unix_at_midnight + (tz_offset_hours * 3600)
```

When parsing, subtract `tz_offset * 3600` to recover real UTC. Before
shipping, capture at least one real history frame on a firmware that
actually has data and decode both interpretations against wall-clock to
choose between them.

### 4.7 Alarms / reminders / sit-long / drink

| op | name | status |
|----|------|--------|
| `0x23` | Set alarm slot N (N=0..4) | ❌ `0xEE` |
| `0x24` | Read alarm slot N | ❌ `0xEE` |
| `0x27` | Set drink-alarm slot N (N=0..7) | 🟡 ACK'd but post-write verify via `0x28` is the safe check |
| `0x28` | Read drink-alarm slot N | ✅ — body e.g. `00 00 08 00 FF FF FF FF FF FF FF` |
| `0x25` | Set sit-long config | 🟡 ACK'd; verify via `0x26` |
| `0x26` | Read sit-long config | ✅ — body e.g. `08 00 18 00 00 3C 00` |

Alarm payload format (read response, [`ReadAlarmRsp`](work/jadx_out/sources/com/oudmon/ble/base/communication/rsp/ReadAlarmRsp.java)):

```
[idx u8][enable u8][hour_BCD][min_BCD][7 weekday-bits, b0=Sun..b6=Sat]
```

Write uses the same layout with `enable` = 0=off, 1=once, 2=repeat.

### 4.8 Phone-sport session (`0x77` / `0x78`)

The most complete bidirectional handshake the protocol exposes.

#### `0x77` PhoneSport — phone tells ring to start/stop a session

| sub | meaning |
|-----|---------|
| `0x01` | START session |
| `0x02` | PAUSE |
| `0x03` | RESUME (after pause) |
| `0x04` | STOP |
| `0x06` | FORCE-STOP / cleanup (sometimes paired with 0x04) |

Request body: `[sub u8, sport_type u8]`. Sport-type 1..15 are all
accepted; the firmware doesn't differentiate behavior between them but
**echoes the sport_type byte back** in the `0x78` push.

Response on START:

```
< 78 NN 01 00 00 00 00 00 00 00 00 00 00 00 00 [crc]   ← 0x78 PhoneSportNotify (byte 1 = sport_type)
< 77 01 00 [TS u32 LE] 00 00 00 00 00 00 00 00 [crc]   ← 0x77 ack with unix timestamp at bytes 3..6
```

Then `0x78 NN 01 …` frames continue at session ticks; on STOP the ring
sends `77 00 00 …` ack.

⚠️ **0x77 timestamp encoding — Beijing-locked Unix epoch.** The unix-LE-u32
in bytes 3–6 of the `0x77` START ack is **real Unix-UTC seconds**, but
the firmware computes it by treating the BCD payload of `SetTime` as
**local time in UTC+8 (Beijing)** unconditionally. There is no timezone
field in the protocol; the offset is hardcoded.

For a client in any non-China timezone, the obvious approach (send your
real local wall-clock as SetTime BCD) yields device timestamps that are
off by `(8 h − your_local_utc_offset)`. Two equivalent fixes:

- **Write-side**: send `(now_utc + 8 h)` BCD bytes regardless of where
  the host is — i.e., always send Beijing wall-clock.
- **Read-side**: subtract `(8 h − local_offset)` from every device-emitted
  timestamp.

The `0x78` `bytes 2..3` duration field is documented from SDK sources
only — no live wire data exists for it yet.

#### `0x78` PhoneSportNotify — ring's live sport updates

Payload layout
([`SportRunningActivity$MyDeviceNotifyListener.onDataResponse`](work/jadx_out/sources/com/qcwireless/smart/ui/home/sport/SportRunningActivity.java)):

```
byte 0     : measurement-type code (= sport_type from START)
byte 1     : status  (SDK spec: 3 = session ended → finish activity)
bytes 2..3 : duration_secs   (SDK spec: u16 LE)
byte 4     : sport-status byte
bytes 5..7 : metric A         (SDK spec: u24 LE — "likely distance")
bytes 8..10: metric B         (SDK spec: u24 LE — "likely calories")
bytes 11..13: metric C        (SDK spec: u24 LE — "likely step count")
```

⚠️ **Live wire data on this firmware contradicts the SDK-spec layout.**
Across a 60+ frame session (`0x77 START sport_type=1` → 60 s brisk walk →
`STOP`), the actual payload was:

| payload byte | observed value | interpretation |
|---|---|---|
| 0 | echoes the `sport_type` from START | matches SDK |
| 1 | stays at `0x01` the entire session | **status=3 "session ended" was NEVER observed**, even after STOP |
| 2..3 | **BE u16**, counts up by 1 per wall-second (0x0000 → 0x003D after 61 s) | duration_secs, but **BE not LE** as SDK says |
| 4 | slowly-rising value (`0x52 = 82` at start, `0x6F = 111` after 60 s of brisk walking) | consistent with instantaneous heart rate, **not** a "sport-status byte" |
| 5..11 | all `0x00` for the entire session | the SDK-claimed `metric A` (5..7) and `metric B` (8..10) slots are dead on this firmware |
| 12..13 | monotonically-increasing accumulator at roughly ~50 units/s (0x0000 → ~0x0D97 ≈ 3479 after 60 s) | semantics unclear — not a step count (~196 actual), not km, not kcal at 36 mcal/step rate |

**Practical takeaways for clients:**
- Use `payload[2..3] BE u16` for the duration (in seconds), not LE.
- Use the parallel `0x73 sub=18 ACTIVITY_TOTAL` stream — which *is* well-defined ([§5.3](#53-sub18-activitytotal--detailed-format)) — for step / calorie / distance metrics during a sport session.
- Don't rely on `status=3` to detect "session ended"; rely on the `77 00 00 …` ack of your own STOP (`0x77 [04, …]`) instead.
- Treat the SDK's "3 × u24 LE for distance/calories/steps" claim as **unverified on this firmware**; it may be a leftover band-style layout that this ring firmware never populates.

### 4.9 Quality-of-life / utility

| op | name | status | notes |
|----|------|--------|-------|
| `0x02` | Camera (ring→phone) | 🔧 | Ring asks phone to act: `[action]` where 1=open camera UI, 2=shutter, 3=finish. Not testable from phone→ring direction. |
| `0x22` | FindPhone (ring→phone notify) | 🔧 ring→phone direction; phone→ring write returns `0xEE` |
| `0x50` | FindRing (LED flash) | ✅ — magic `[0x55, 0xAA]`; ring LED flashes briefly. Fire-and-forget, no response. |
| `0x1C` | MusicSwitch | ❌ `0xEE` |
| `0x1D` | MusicCommand (ring→phone) | ❌ ring→phone direction; phone→ring returns `0xEE` |
| `0x11` | PhoneNotify (ring→phone) | ❌ ring→phone direction |
| `0x21` | TargetSetting | ✅ Read returns 9-byte body; write `[02, steps_u24_LE, kcal_u24_LE, dist_u24_LE]` persists; extended write adds `[sport_min_u16, sleep_min_u16]`. ⚠️ **The firmware ACKs every write but only persists `steps ≥ 100`** — every value `< 100` is silently rejected (echo-ack returned, read-back stays at the prior value). The threshold is **exactly 100**: 10, 50, 60, 75, 90, 99 all rejected; 100, 200, 500, 1000, 2000 all persist. Always read back after writing if the exact target matters. |
| `0x17` | ReadPersonalizationSetting | ❌ `0xEE` |
| `0x18` | (reserved/ping) | ✅ 🆕 — `0x18 [00]` returns empty-body ack `18 00 00 …`. **Only the empty-payload variant works**; `[01]..[FF]` all silent timeout. Behaves like a no-op ping. |
| `0x30` | A-GPS switch | ❌ `0xEE` |
| `0x3B` | TouchControl | ✅ — see [§4.10](#410-touchcontrol-0x3b) for full recipe |
| `0x51` | LoverEvent | ❌ `0xEE` |
| `0x52` | MuslimRemind | ❌ `0xEE` |
| `0x7A` | Muslim worship data | ✅ — `[1, N]` reads worship counter; returns multi-frame stream if data exists, `7A FF 00 …` sentinel otherwise |
| `0x7B` | Muslim goal data | ✅ — `[1, N]` for N=1..5 echo successfully (different goal-type categories); writes via `[2, N, …]` |
| `0x6C..0x70` | ECG family | ❌ all return `0xEE` (`supportEcg` capability is false) |
| `0x74` | PhoneGPS push | ❌ `0xEE` |
| `0x7E` | StillTime push | ❌ `0xEE` |
| `0x72` | PushMsg (phone notification → ring) | ⏱ silent; ring has no display and no observable vibration. The opcode is reachable but the UX feedback path is absent on this hardware. |
| `0x1A` | Weather forecast (write) | ❌ `0xEE` |
| `0x4A` | (reserved) | ❌ `0xEE` |
| `0x5A` | heartbeat | ❌ `0xEE` |
| `0x2D` | blacklist location | ❌ `0xEE` |
| `0x13` | ReadBandSport (legacy) | ❌ `0xEE` |

### 4.10 TouchControl (0x3B)

The R08 family uses a touch IC for gesture input. **Activating gesture
reporting requires a 2-step init sequence with a non-obvious magic
appType=9** that the QRing app itself doesn't expose in its UI — recovered
here by on-device reverse-engineering of the touch-IC handshake.

#### The init sequence

```
3B 01 00 01 01 00 00 00 00 00 00 00 00 00 00 [crc]   ← TOUCH_ENABLE
3B 02 00 09 01 00 00 00 00 00 00 00 00 00 00 [crc]   ← TOUCH_SLEEP_MODE write with hidden appType=9 magic
```

The second frame is the `[2, 0, appType, sleepMin]` touch-sleep-write
variant from the sub-action map ([§4.13](#413-complete-sub-action-map)):
sub=2 (write), mode-byte=0 (touch-sleep), appType=`0x09`, sleepMin=`0x01`.
The `appType=9` (REPORT_ALL_GESTURES) is the non-obvious magic — the
QRing UI never selects it. Wait ~500 ms between the two writes.

After init, the ring emits `0x73 sub=0x2D` for every physical gesture
until you send `TOUCH_DISABLE` (`3B 01 00 01 00 …`) or disconnect.

#### TouchControl `appType` values

Per [`TouchActivity.java:196-259`](work/jadx_out/sources/com/qcwireless/smart/ui/device/touch/TouchActivity.java):

| value | UI binding |
|-------|------------|
| `0` | (default / unassigned) |
| `1` | Music |
| `2` | Video |
| `3` | Camera |
| `4` | Ebook |
| `5` | Phone call |
| `7` | Game (also activates `0x73 sub=0x29 RING_GAME_KEY` on wrist-shake) |
| `8` | Heart |
| **`9`** | **REPORT_ALL_GESTURES (hidden — not in QRing UI; required for `sub=0x2D` reporting)** |
| `10` | (reserved) |

#### TouchControl read response

Response to `0x3B 01 [ring_flag]`:

```
[isRead u8][touch_disabled_flag u8][appType u8][param u8]
```

Note `touch_disabled_flag` is **inverted**: `0` = touch IC ACTIVE, non-zero
= disabled. The R08 reports `0x01` here when the user removes the ring
or inserts it into the charging dock.

### 4.11 Notification push (`0x72`)

Frame format: `[app_type u8, count u8, sub_id u8, body UTF-8 ≤11 B]`.
App types ([`PushMsgUintReq`](work/jadx_out/sources/com/oudmon/ble/base/communication/req/PushMsgUintReq.java)):

| value | source |
|-------|--------|
| `0` | incoming-call ringing |
| `1` | SMS |
| `2` | QQ |
| `3` | WeChat |
| `4` | phone-hung-up |
| `5` | Facebook |
| `6` | WhatsApp |
| `7` | Twitter |
| `8` | Skype |
| `9` | Line |

The body is limited to 11 bytes (the leftover space in a 16-byte frame).
On R08 (no display, no observable vibration), sending these has no
visible effect.

### 4.12 Religion / Muslim mode

R08 ships these features enabled out of the box. Status on tested unit:

| op | name | status |
|----|------|--------|
| `0x52` | MuslimRemind (prayer-window config) | ❌ `0xEE` on RT08_3.10.46 |
| `0x7A` | Muslim worship data | ✅ — `[1, N]` read; returns multi-frame stream if data exists |
| `0x7B` | Muslim goal data | ✅ — see sub-action map [§4.13](#413-complete-sub-action-map) |

### 4.13 Complete sub-action map (every MixtureReq variant)

Most settings opcodes follow the `[sub-action, …body]` pattern. This
table enumerates **every** distinct sub-action payload across all `…Req`
classes in the APK. Used together with the per-opcode entries above.

| op | sub-byte(s) | meaning | body after sub |
|----|-------------|---------|---------------|
| 0x05 | `[1]` | read (band) | — |
| 0x05 | `[2,en,leftbits]` | write short | enable + bits |
| 0x05 | `[3]` | read (ring) | — |
| 0x05 | `[4,en,gw,bri,max,dnd,sH,sM,eH,eM]` | write full | |
| 0x06 | `[1]` | read DND | — |
| 0x06 | `[2,en,sH,sM,eH,eM]` | write DND | |
| 0x09 | `[1]` | read intell | — |
| 0x09 | `[2,en,delay_s]` | write intell | |
| 0x0A | `[1]` | read time-format | — |
| 0x0A | `[2,!is24,lang]` | write 12/24 + lang | |
| 0x0A | `[2,!is24,metric,gender,age,height,weight,sbp,dbp,warnHR,open]` | write user profile | |
| 0x0C | `[1]` | read BP-auto | — |
| 0x0C | `[2,en,sH,sM,eH,eM,interval]` | write BP-auto | |
| 0x12 | `[1]` | read | — |
| 0x12 | `[2,en]` | write | |
| 0x16 | `[1]` | read HR-auto | — |
| 0x16 | `[2,en,interval,start_h,high,low,main]` | write HR-auto | |
| 0x19 | `[1]` | read temp-unit | — |
| 0x19 | `[2,en,celsius?]` | write temp-unit | |
| 0x1B | `[1]` | read brightness | — |
| 0x1B | `[2,level]` | write brightness | |
| 0x1F | `[1]` | read display-time | — |
| 0x1F | `[2,time_s,type,alpha,0,total,curr]` | write classic | |
| 0x1F | `[2,time_s,type,alpha,0,total,curr,open,0x05,0x1E,0x05]` | write extended | |
| 0x1F | `[3]` | delete | |
| 0x21 | `[1]` | read targets | — |
| 0x21 | `[2,steps_u24LE,kcal_u24LE,dist_u24LE]` | write basic | |
| 0x21 | `[2,steps_u24LE,kcal_u24LE,dist_u24LE,sportMin_u16,sleepMin_u16]` | write extended | |
| 0x29 | `[1]` | read orientation | — |
| 0x29 | `[2,en,(0\|dir)]` | write orientation | |
| 0x2A | `[1]` | read style | — |
| 0x2A | `[2,style_idx]` | write style | |
| 0x2B | `[1]` | read menstruation | — |
| 0x2B | `[2,en,startMonth,startDay,cycleDays,periodDays,remind,remindDays,remind_h,remind_m,res]` | write menstruation | |
| 0x2C | `[1]` | read SpO2-auto | — |
| 0x2C | `[2,en]` or `[2,en,interval]` | write SpO2-auto | |
| 0x36 | `[1]` | read pressure-auto | — |
| 0x36 | `[2,en]` | write pressure-auto | |
| 0x38 | `[1]` | read HRV-auto | — |
| 0x38 | `[2,en]` | write HRV-auto | |
| 0x3A | `[t,1]` | read for metric t (1=sugar, 2=lipids, 3=skin-temp ext.) | — |
| 0x3A | `[t,2,en,lo_u16,hi_u16]` | write simple | |
| 0x3A | `[t=3,2,en,interval,start_h,remind_interval,bitmap,custom_raw]` | write skin-temp extended | |
| 0x3B | `[1,ring?(0/1)]` | read | — |
| 0x3B | `[2,0,appType,sleepMin]` | write touch-sleep | |
| 0x3B | `[2,1,appType,strength]` | write gesture-strength | |
| 0x3B | `[2,2,appType,appType2]` | write dual-touch | |
| 0x52 | `[1,3]` | read prayer-window | — |
| 0x52 | `[2,1,idx,en,hourBCD,minBCD,offset,advance]` | write per-prayer timer | |
| 0x52 | `[2,2,sHBCD,sMBCD,eHBCD,eMBCD,en,weekmask,cycleSec]` | write prayer window | |
| 0x52 | `[2,3,algo,asrAlgo]` | write algorithm | |
| 0x7A | `[1,t]` | read counter at offset t | — |
| 0x7A | `[2,1]` | write enable | |
| 0x7B | `[1,t]` (t=1..5) | read goal sub-type t | — |
| 0x7B | `[2,1,target_u32LE,en?]` | write 3/100/N target | |
| 0x7B | `[2,2,en,perDay,target_u16LE]` | write daily goal | |
| 0x7B | `[2,3,en,val,threshold_u16LE]` | write custom threshold | |
| 0x7B | `[2,4,asrChoice,asrAlgo,prayer_u16LE × N]` | write advanced | |
| 0x7B | `[2,5,value_u16LE × N]` | write user array | |
| 0x76 | `[0]` | read | — |
| 0x76 | `[1,en]` | write | |
| 0x75 | `[0]` | read current dial | — |
| 0x75 | `[1,idx]` | write dial select | |
| 0x17 | `[1,2,3]` | read 3 settings | — |
| 0x30 | `[1]` | read AGPS | — |
| 0x30 | `[2,en]` | write AGPS | |

### 4.14 Universal error sentinel: `0xEE`

When the firmware doesn't implement an opcode, it always replies:

```
[op | 0x80] [0xEE] [zeros to fill 14] [crc]
```

i.e. opcode-with-error-flag-set, single `0xEE` payload byte. Detect with:

```python
def is_unsupported(frame):
    return (frame[0] & 0x80) and frame[1] == 0xEE
```

Treat as silent skip; never surface to user.

38 distinct opcodes returned this exact shape on the tested firmware
(see [§10.2](#102-unsupported-on-rt08_31046-return-0xee) for the full list).

---

## 5. CMD_DEVICE_NOTIFY (`0x73`)

This is the ring's primary event channel — every spontaneous "something
happened" notification arrives as `0x73 [sub_id] [payload]`.

### 5.1 Frame format

```
73 [sub_id] [sub-specific payload, up to 13 bytes] [crc]
```

### 5.2 Sub-id table

Sources: [`HealthyFragment.MyDeviceNotifyListener.onDataResponse`](work/jadx_out/sources/com/qcwireless/smart/ui/home/healthy/HealthyFragment.java)
(canonical dispatcher, lines 680–1015), [`MineFragment.MyDeviceNotifyListener`](work/jadx_out/sources/com/qcwireless/smart/ui/mine/MineFragment.java)
(additional sub-ids), plus the validation overlay.

| sub | hex | name | payload | status | notes |
|-----|-----|------|---------|--------|-------|
| 1 | 0x01 | NEW_HR_RECORD | (header only) | 🔧 | Triggers app HR sync. Does NOT auto-fire from `0x69` measurements on this FW; fires during firmware-internal data-sync windows. |
| 2 | 0x02 | NEW_BP_RECORD | — | 🔧 | (not observed) |
| 3 | 0x03 | NEW_SPO2_RECORD | — | 🔧 | |
| 4 | 0x04 | NEW_STEP_DETAIL | — | 🟡 | Observed firing once during a charging-state-change window (single zero-body frame, ~25 s after plug-in). Specific trigger isn't pinned down; SDK treats it as "tell app to sync step-detail history". |
| 5 | 0x05 | NEW_TEMP_RECORD | — | 🔧 | |
| 7 | 0x07 | SPORT_ENDED | — | 🔧 | |
| 9 | 0x09 | (silent sync) | — | 🔧 | App falls through to generic `DeviceToAppSyncEvent` |
| 11 | 0x0B | (silent sync) | — | 🔧 | Same |
| **12** | **0x0C** | **BATTERY_STATE_PUSH** | `[battery_pct u8][charging u8]` | ✅ | Push pattern: (a) one push within seconds of plug-in (sub-second to ~30 s observed; timing is variable); (b) ~60 s heartbeat while the percentage is actively rising; (c) silent once the ring is at 100 %; (d) on unplug, **two pushes ~50 ms apart, both with `charging=False`**. Example: `0C 63 01` = 99 %, charging; `0C 64 00` = 100 %, not charging. |
| 13 | 0x0D | NEW_BLOOD_SUGAR | — | 🔧 | |
| 16 | 0x10 | TARGET_REACHED | — | ⚠️ | Specified as "step count crossed configured target". Empirically not fired in two controlled live walks that *did* cross the threshold: (i) target=100, walk 26→144, no sport session; (ii) target=300, walk 227→410, with `0x77` sport session ACTIVE. Neither sufficed. The real trigger is not a pure ACTIVITY_TOTAL crossing and is not gated by sport-session presence — likely a once-per-day persistent flag or a server-sync signal. Don't depend on it for UI on this firmware. |
| 17 | 0x11 | STEP_INCREMENT | `byte 2 = delta steps` | 🔧 | **Superseded by sub=18 on this FW** — sub=17 never observed. |
| **18** | **0x12** | **ACTIVITY_TOTAL** | `[steps u24 BE][cal_raw u24 BE][dist_raw u24 BE]` | ✅ | Live step counter. `cal_raw / 1000 = kcal`, `dist_raw = meters`. Pushed every few seconds during walking. See [§5.3](#53-sub18-activitytotal--detailed-format). |
| 37 | 0x25 | MUSLIM_PRAISE_COUNT | `[counter u32]` | 🔧 | Never fired in 13.5 min of testing — worship counter doesn't increment via touch-IC gestures. **Endianness unverified** on this firmware (BE vs LE indeterminate — the counter never moved). |
| 39 | 0x27 | NEW_TEMP_2 | — | 🔧 | |
| 40 | 0x28 | DEVICE_SETTINGS_REFRESH | — | 🔧 | Tells app to reload device settings from server |
| 41 | 0x29 | RING_GAME_KEY | (zero payload) | ✅ | Fires on wrist shake when `appType=7 Game` is configured. The only "shake event" exposed on this FW. |
| **42** | **0x2A** | **TOUCH_STATUS_ECHO** | `byte 1 = touch_disabled_flag` (0 = ACTIVE) | ✅ | Fires after **touch-sleep-variant** writes only — `0x3B [2, 0, appType, sleepMin]` — within a few hundred ms of the ACK. The other sub=2 variants (strength `[2, 1, …]`, dual-touch `[2, 2, …]`) do **not** trigger it; neither do sub=1 reads. Also fires on charging-dock insertion (with byte 1 = 1, DISABLED). Inverted polarity. |
| 43 | 0x2B | NEW_HRV | — | 🔧 | |
| 44 | 0x2C | NEW_STRESS | — | 🔧 | |
| **45** | **0x2D** | **TOUCH_GESTURE** | `byte 1 = gesture code` | ✅ | The 4 atomic gestures: `0x01` SWIPE_UP, `0x02` SWIPE_DOWN, `0x03` TAP, `0x04` LONG_PRESS. Only fires after the [§4.10](#410-touchcontrol-0x3b) init sequence. |
| 48 | 0x30 | LOVER_DOUBLE_TAP | — | 🔧 | |
| 49 | 0x31 | EXERCISE_HR_HIGH | `byte 1 = bpm` | 🔧 | "HR too high during exercise" dialog |
| 50 | 0x32 | DRINK_WATER_REMIND | — | 🔧 | |
| 51 | 0x33 | SEDENTARY_REMIND | — | 🔧 | |
| 52 | 0x34 | ALARM_RING | — | 🔧 | |
| 55 | 0x37 | MANUAL_HR_TEST | `byte 1 = bpm` | 🔧 | Fires for **user-initiated** measurement done on ring; R08 has no button for this. |
| 56 | 0x38 | CUSTOMER_PRAISE_COUNT | `bytes 2..3 = count u16 LE` | 🔧 | |
| 57 | 0x39 | MENSTRUATION_TICK | `bytes 1, 2 = sub-codes` | 🔧 | |
| 58 | 0x3A | REST_HR_ALERT | `byte 1: 1=low / 2=high` | 🔧 | "Resting HR too low/high" |
| 61 | 0x3D | TEMP_ALARM | `bytes 1..2 = temp u16 LE / 10` | 🔧 | "Body temp out of range" |
| 62 | 0x3E | G_SENSOR_STILL_TICK | — | 🔧 | Accelerometer reports user is still |
| 63 | 0x3F | ECG_CONNECT_STATE | `byte 1 = state` | 🔧 | ECG electrode contact (unused on R08) |

#### 5.2.1 Touch-gesture codes (sub-id 0x2D)

| code | hex | gesture |
|------|-----|---------|
| 1 | 0x01 | SWIPE_UP |
| 2 | 0x02 | SWIPE_DOWN |
| 3 | 0x03 | TAP (single) |
| 4 | 0x04 | LONG_PRESS (~3 s hold) |

The firmware does **not** debounce TAPs — observed inter-tap interval as
short as 0.14 s. "Double tap", "triple tap", and combo gestures are
**app-layer synthesized** by buffering the atomic events — none of this is
enforced by the firmware, so the windows are the implementer's choice. The
synthesis windows recommended by this project:

- Multi-tap window: 400 ms
- Single-tap commit delay: 400 ms (TAP not followed by another within 400 ms → "tap")
- Combo wait: 513 ms (after double-tap, wait 513 ms for swipe to complete combo)

### 5.3 Sub-18 ACTIVITY_TOTAL — detailed format

This is the live step-counter push. **Order of fields was originally
documented incorrectly in the APK reading; the live-verified order
matches the on-device sanity-check (steps/cal/dist against real walking)**:

```
73 12 [steps_u24_BE] [cal_raw_u24_BE] [dist_raw_u24_BE] [4 zero bytes]
```

Wire example from PASS 3 (`73 12 00 00 19 00 03 84 00 00 14 00 00 00 00 39`):
- Payload bytes 1..3 (frame bytes 2..4) = `00 00 19` → BE u24 = **25** steps
- Payload bytes 4..6 (frame bytes 5..7) = `00 03 84` → BE u24 = **900** = 0.9 kcal
- Payload bytes 7..9 (frame bytes 8..10) = `00 00 14` → BE u24 = **20** meters

("Payload byte 0" = the `sub_id` byte; payload starts after `73`.)

#### Units

- **Distance: integer meters**. The per-step ratio sits in
  `0.77 – 0.80 m/step` (normal adult stride). Divide by 1000 for km
  display, or treat the raw value as meters directly.
- **Calories: fixed-rate millicalories**. On RT08_3.10.46 the firmware
  computes `cal_raw = steps × 36` exactly — verified across two
  independent walking sessions and 71 ACTIVITY_TOTAL samples, with no
  observed deviation from the 36 mcal/step ratio. This is **not** a
  physiological estimate. Other firmware revisions or user-profile
  changes may yield a different multiplier; re-validate before treating
  the rate as universal. **Don't display the value as "calories burned"
  without that caveat.**

### 5.4 Sub-ids not observed (most-likely reasons)

Several sub-ids never fired across our live capture. None of these
indicate a protocol gap; they need ring-side triggers that didn't occur:

| sub | reason |
|-----|--------|
| 1–5 | The firmware persists records during data-sync windows we didn't trigger |
| 9, 11 | Server-pull triggers — only fire if QRing cloud has new data for the device |
| 16 | Trigger is not a simple ACTIVITY_TOTAL crossing — see [§11](#11-open-questions) #2 |
| 17 | Superseded by sub-18 on this firmware |
| 37 | Worship counter doesn't increment via touch-IC alone (Game-mode wrist-shake produces sub=41, not sub=37) |
| 49–58, 61, 63 | No alarm scheduled; no HR / temp threshold crossed during test |

---

## 6. 0xA1 Telemetry Stream

The most surprising live finding: opcode `0xA1` — named "DeviceRevision"
in the APK SDK — is actually a **persistent multi-channel sensor
telemetry stream**. The SDK's name comes from a different earlier role
on band-style devices; on R08 it's been repurposed.

### 6.1 Lifecycle

```
> A1 [01]                            → start basic continuous stream
< A1 01 …  A1 02 …  A1 03 …  A1 04 … (4-sub-frame cycle, ~4 cycles/s)
…
> A1 [00]                            → STOP (ring sends `A1 FF 00 …` ack, stream halts)
```

Sub-actions:

| sub | behavior |
|-----|----------|
| `[00]` | STOP — emits `A1 FF 00 …` ack within ~1 s, stream halts |
| `[01]` | BASIC continuous stream (4 sub-frames per cycle, ~4 cycles/sec) |
| `[02]` | unrecognised — no response |
| `[03]` | **ONE-SHOT snapshot** — emits a single 4-frame burst, then stops |
| `[04]` | EXTENDED continuous stream — same rate, ch1/ch2 carry more data, ch4 silent |
| `[05]` | unrecognised |
| `[FF]` | same effect as `[00]` (stops stream) |

**App-dev guidance:** For one-shot reads, use `[03]`. For continuous live
data, use `[01]` (start) + `[00]` (stop). Expect ~64 B/s of background
traffic during continuous mode.

### 6.2 The 4-sub-frame cycle

Each "cycle" (every ~250 ms in `[01]` mode) consists of 4 frames in
order:

```
A1 01 00 [QQ] 00 00 00 00 00 00 00 00 00 00 00 [crc]   "channel 1"
A1 02 24 [VV] 00 00 00 00 00 00 00 00 00 00 00 [crc]   "channel 2"
A1 03 [X1 X0 Y1 Y0 Z1 Z0] 00 00 00 00 00 00 00 [crc]   "channel 3"
A1 04 00 [n] 00 [m] 00 [p] 0F FF 00 00 00 00 00 [crc]  "channel 4"
```

`[QQ]`, `[VV]`, `[n m p]` are opaque single-byte values; their semantics
(or lack thereof) are described in [§6.4](#64-channels-1-2-4--internal-accumulators-not-decoded).
The accelerometer triplet `[X1 X0 Y1 Y0 Z1 Z0]` is decoded in
[§6.3](#63-channel-3--3-axis-accelerometer-fully-decoded).

### 6.3 Channel 3 — 3-axis accelerometer (FULLY DECODED)

PASS 8 captured 6 ring orientations and confirmed:

- **Format**: `[X_hi X_lo Y_hi Y_lo Z_hi Z_lo]` — three big-endian signed
  16-bit integers (BE int16).
- **Scale**: ≈ **8192 LSB / g** (= standard ±4 g range on a typical
  Realtek-paired accelerometer).
- All 6 cardinal orientations produced sum-of-squares ≈ 8192² (gravity
  vector of magnitude 1 g across the 3 axes).

Decoder:

```python
def parse_accel(frame):
    # frame[2..7] is the 6-byte body
    def s16(hi, lo):
        v = (hi << 8) | lo
        return v - 65536 if v >= 32768 else v
    x = s16(frame[2], frame[3])
    y = s16(frame[4], frame[5])
    z = s16(frame[6], frame[7])
    return (x / 8192.0, y / 8192.0, z / 8192.0)   # in units of g
```

The axis-to-ring-feature mapping (which physical direction is X+, etc.)
is **not fully determined** by the 6 captures — user hand-positioning
introduces some cross-axis components in some orientations. For apps
that need known orientation, calibrate at runtime with a known-still
position.

### 6.4 Channels 1, 2, 4 — internal accumulators (NOT decoded)

- **Channel 1 (`A1 01 00 [QQ]`)** and **Channel 2 (`A1 02 24 [VV]`)** are
  **runtime-accumulated** firmware-internal PPG-derived metrics. The
  `0x24` in ch2 byte 2 is a channel-ID constant, not data.
  - `[03]` one-shot returns `QQ = VV = 0x00` in **every** PPG-related
    scenario tested (finger on / off / loose / tight). Only sustained
    `[01]` streaming makes them develop meaningful values.
  - They are not real-time signal-quality indicators.
- **Channel 4 (`A1 04 00 [n] 00 [m] 00 [p] 0F FF …`)** is **NOT a
  motion-event counter** — observed values *decrease* under motion (still
  values [39, 37, 41] vs rapid-tap [15, 10, 12]). Hypothesis: optical-
  signal-stability metric. The `0F FF` is a constant terminator sentinel.
  Channel 4 is silent in `[04]` extended mode.

**App-dev impact:** Treat ch1/ch2/ch4 as opaque "firmware-internal"
metrics. Do not compute medical values from them. Only ch3 has known
useful semantics on this firmware.

### 6.5 Stream stability

The `[01]` continuous stream sometimes goes silent on finger-on/off
transitions or after extended sessions. Empirical guidance:

- For **finger on/off transitions**, prefer `[03]` one-shot snapshots.
- For **continuous sampling**, expect to re-issue `[01]` if the stream
  goes quiet for >5 s.
- Stream is reliable for ~5 minutes when the ring stays on the same
  finger and there's no heavy interleaved opcode traffic.

**Broader BLE link instability** (not just `0xA1` stream): on this
firmware, multi-minute sessions experience occasional spontaneous
disconnects, observed across PASS 1, PASS 8 series, and others — both
during `0xA1` streaming and when the channel is mostly idle. Implement
reconnect-and-resume logic; do not assume a single GATT connection holds
for long-form recording. For the bootstrap-to-first-result path, plan
for retry-on-disconnect, not just retry-on-error.

---

## 7. SPP Big-Data Channel

The oudmon SDK defines a second BLE service (`de5bf728-…`) for
variable-length data transfer (sleep stages, contacts, watchfaces, A-GPS,
etc.). **On RT08_3.10.46 production firmware this service is not
exposed.** All the action codes below are spec-only on this hardware;
they may be reachable on:

- A pre-production firmware build with the channel left enabled, or
- After entering OTA mode (`0x0F` / `0x2E`) — destructive, untested.

For completeness, the channel uses a different frame format:

```
byte 0      : 0xBC                          (magic)
byte 1      : action / sub-opcode
bytes 2..3  : payload length (u16 LE)
bytes 4..5  : CRC-16 of payload (Modbus, poly 0xA001, init 0xFFFF, no XOR-out)
bytes 6..   : payload
```

CRC reference ([`CRC16.calcCrc16`](work/jadx_out/sources/com/oudmon/ble/base/communication/utils/CRC16.java)):

```python
def crc16_modbus(data: bytes) -> int:
    if not data:
        return 0xFFFF
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc
```

### 7.1 LargeDataHandler action codes (spec only)

| action | name | request | notes |
|--------|------|---------|-------|
| 0x20 | Location push | `[02, lat_deg_u16_LE, lat_min, lat_min_frac, lat_sub, lng_deg_u16_LE, lng_min, lng_min_frac, lng_sub, location_utf8…]` | push current GPS to ring |
| 0x27 | NewSleep | `[0|0xFF, 0x01]` | day=0xFF means latest. Multi-day stream response: per-day blocks `[dayIdx, len, st_u16, et_u16, (timeBin, duration_min) × N]` |
| 0x28 | ManualHeartRate list | `[0x00 or 0xFF]` | Response carries `[index]` then triples `[minute_u16_LE, value]` |
| 0x29 | Contacts (paginated) | `[total+1, 0, total_u16_LE]` header, then chunks with name+phone | up to 950 bytes per chunk |
| 0x2A | BloodOxygen day | `[day_offset]` | 49-byte frames: `[-day_offset, (min, max) × N]` |
| 0x2C | Alarm list | read `[01]`; write `[02, total, (alarmLength, repeatAndEnable, min_u16_LE, content_utf8) × N]` | watch alarm batch |
| 0x2D | Contact (legacy) | raw | older format |
| 0x2E | Classic-BT MAC | `[00]` | reply: `[mac × 6][name_len][name…]` |
| 0x2F | E-Card / QR code | read `[01, type]`; write `[02, type, url_len, url]` | one slot per type |
| 0x3A | Custom watchface JSON | `[02, (elem_type, x_u16, y_u16, R, G, B) × N]` | DIY face composition |
| 0x3E | NewSleep + Lunch nap | `[0|0xFF, 0x01]` | extended sleep with nap detection |
| 0x47 | BloodSugar day | `[day_offset]` | same 49-byte chunking as 0x2A |
| 0x48 | GPS Navigation push | status `[status, 0]`; running `[1, len+2, …, name_utf16_BE]` | turn-by-turn directions |
| 0x49 | Manual Oxygen list | `[00 or 0xFF]` | triple format like 0x28 |
| 0x4A | Avatar (device nickname) | `[01, 01, len, name_utf16_BE]` | |
| 0x54 | A-GPS data | huge binary blob | satellite ephemeris |
| 0x5F | Interval Oxygen day | `[day, packet_idx]` | paged: `[dayIndex, interval, totalPackets, packetIndex]` at offsets 6..9 |
| 0x75 | Interval Heart Rate day | `[day, packet_idx]` | same paging |
| 0x76 | Emergency Contact | read `[day, 01]`; write `[bytes…]` | |
| 0x77 | Interval Temperature day | `[day, packet_idx]` | each value is u16 LE / 100.0 (°C) |
| 0x78 | Single-Measure ECG | `[01, start_or_stop, len_u16_LE]` | response `[type, status, hr, dataWidth, dataLen_u16_LE][data]` (dataWidth=1 → 2 B/sample, =2 → 4 B/sample, signed LE) |

### 7.2 Specialized file handlers

Same channel; different action numbers used by the per-feature handlers:

- **FileHandle** (`com.oudmon.ble.base.communication.file.FileHandle`):
  - `0x25` ACTION_SERIES — request skin-temperature time series.
    Decode: `value_celsius = (raw_byte / 10.0) + 20.0`.
    Format: `[dayIndex, timeSpan_min, value × (1440/timeSpan)]`.
  - `0x26` ACTION_ONCE — single instantaneous temperature.
    Format: `[dayIndex, (minutes_from_midnight_u16_LE, raw_byte) × N]`.
  - `0x30` start handshake (no body)
  - `0x31` file init: `[01, file_size_u32_LE, name_len, name_utf8]`
  - `0x33` list installed files
  - `0x35` ACTION_PLATE — list watchfaces. Format: `[total_len, (isCurrent, nameLen, name_utf8) × N]`
  - `0x39` file delete: `[01, name_utf8]`
  - `0x54` ACTION_A_GPS

- **AvatarHandle / AlbumHandle / EbookHandle / RecordHandle** — share
  three action numbers (`0x31`/`0x32`/`0x33` for file-init/file-data/
  file-list) plus `0x4A` (Avatar), `0x80` (deleting), `0x81`/`0x82`/`0x86`
  status frames.

---

## 8. DFU / OTA

⛔ Destructive. Spec retained for completeness; not exercised live.

The OTA flow lives on the SPP channel and uses dedicated sub-actions
([`DfuHandle`](work/jadx_out/sources/com/oudmon/ble/base/communication/DfuHandle.java)):

| action | meaning | request body |
|--------|---------|--------------|
| `0x01` | DFU_START | empty |
| `0x02` | DFU_INIT | `[01, file_size_u32_LE, file_crc16_u16_LE, file_checksum_u16_LE]` |
| `0x03` | DFU_DATA | `[pocket_index_u16_LE, up to 1024 bytes of slice]` |
| `0x04` | DFU_FORMAT | empty (check / format flash) |
| `0x05` | DFU_INNER | empty (end / release) |

DFU ack frames echo the action byte and carry the result code in
`bArr[6]`:

| code | meaning |
|------|---------|
| 0 | OK |
| 1 | size error |
| 2 | content error |
| 3 | status error |
| 4 | format error |
| 5 | inner error |
| 6 | low battery |

File limit: ≤ 12 MB. CRC is CRC-16/Modbus (poly 0xA001). Checksum is
sum of all bytes truncated to 16 bits.

While DFU is in progress, the normal command service is reduced — most
opcodes are rejected with the `0x80` error flag set. **Untested live**;
do not run without a verified firmware image you can recover from.

---

## 9. Implementation Guide

### 9.1 The core rules

Distilled from 13 live test passes (see [§0.3](#03-verification-runs)):

1. **Always read 0x2A27 (HW rev) before sending any opcode.** The Android
   SDK enforces this; the ring firmware appears to as well.
2. **Send the bootstrap sequence in order**: SetTime → BindAncs → 0x3C →
   0x61 → 0x03. Wait for each response before the next.
3. **Detect `(op & 0x80) and payload[0] == 0xEE`** as a uniform
   "unsupported on this firmware" — silent skip, don't error UX.
4. **Capability-gate UI** off the union of SetTime's 14-byte caps and
   0x3C's 9-byte bitmap. Many SDK features (alarms, dial faces, music,
   ANCS personalization, ECG) are spec-only on prod R08.
5. **`0x73` dispatcher**: accept unknown sub-ids and log them, don't
   crash. New events fire when triggered by ring-side conditions.
6. **`0xA1` is a telemetry stream, not a one-shot read.** Only send if
   you want telemetry; use `[03]` one-shot for snapshots, `[01]`+`[00]`
   for continuous.
7. **For measurements**, use `0x69 type=N` to start. **Detect convergence
   by `val byte ≠ 0`** in the `0x69 [type] 00 [val] …` stream — *not* by an
   `err` byte transition. On this firmware `err` stays at `0x00` throughout;
   early frames carry `val=0`, then once the firmware locks on, the same
   `err=0x00` frame begins carrying a non-zero `val` (and `sbp/dbp` for
   Healthcheck). The `0x6A` stop-ack carries the final value in `payload[1]`
   **for HR / SpO2 / Healthcheck only** — cleaner than polling. **BP
   (`type=0x02`) usually emits no `0x6A` ack frame at all**; read the live
   `0x69 02` progress frame's `raw_LE_u16` at the moment of stop, or use
   Healthcheck composite (`type=0x05`) which returns sbp/dbp at fixed
   payload offsets. Show "adjust ring" UI on `err=0x02` (wear-detect fail)
   and retry. (See [§4.5](#45-live-measurements-0x69-start--0x6a-stop).)
8. **Touch gestures need TOUCH_ENABLE + TOUCH_MODE with hidden
   `appType=9`**. The QRing app's UI doesn't expose this; copy the
   sequence from [§4.10](#410-touchcontrol-0x3b).
9. **Don't bond**. Connect-only; the firmware doesn't require pairing.
10. **Treat distance as meters, calories as `steps × 36 mcal` fixed**.
    Don't display the per-step calorie as a physiological metric.
11. **All wall-clock timestamps from the ring are UTC+8 (Beijing)-locked.**
    SetTime BCD bytes are interpreted as Beijing local time unconditionally
    — there is no timezone field in the protocol. Either send SetTime as
    `(now_utc + 8 h)` BCD regardless of host TZ, or subtract
    `(8 h − local_utc_offset)` from every device-emitted timestamp (`0x77`
    START ack, history payloads). The Android SDK's per-opcode `tz_offset`
    convention for *parsing* history streams appears to be a leftover
    from older firmwares — see [§4.6](#46-history-reads-multi-frame-stream)
    and [§4.8](#48-phone-sport-session-0x77--0x78). (Open question
    [§11](#11-open-questions) #9.)
12. **Don't trust the write ACK; read back when persistence matters.**
    Some opcodes return a clean ACK or echo-ack but never persist:
    `0x0C` BP-auto write (any value); `0x21` TargetSetting (any `steps <
    100`, threshold is exact); likely `0x25` Sit-long and `0x27`
    Drink-alarm (not re-verified through dedicated read). The only way to
    detect silent-persist failure is a write-then-read-back cycle through
    the dedicated read opcode. (See
    [§10.3.5](#1035-silent-persist-failure).)
13. **Plan for spontaneous reconnects on long sessions.** Multi-minute
    sessions experience spontaneous disconnects across PASS 1, 8 series,
    and others — both during `0xA1` streaming and when the channel is
    mostly idle. Implement reconnect-and-resume logic; do **not** assume a
    single GATT connection holds for long-form recording. For
    bootstrap-to-first-result, plan for retry-on-disconnect, not just
    retry-on-error. (See [§6.5](#65-stream-stability).)
14. **For live HR on RT08_3.10.46, use `0x69 [01, 01]` only.** The SDK's
    intended realtime-HR path (`0x69 [06, 01]` triggering an `0x1E NN …`
    stream) is **🔧 spec-only on this firmware**: `0x1E [01]`/`[00]` both
    return `0xEE`, and `0x69 [06, 01]` produces ordinary `0x69` progress
    frames (eventually `err=0x02` wear-detect), not a live HR stream. For
    apps that need rolling HR readouts, repeatedly issue one-shot
    `0x69 [01, 01]` measurements. (See
    [§4.5](#45-live-measurements-0x69-start--0x6a-stop), type=`0x06`.)

### 9.2 Recommended client architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Capability layer                                            │
│   - Reads 0x2A27 + 0x2A26, sends SetTime/BindAncs/0x3C/      │
│     0x61/0x03 (see §2.1 verified bootstrap)                  │
│   - Decodes both capability blobs into a single dict         │
│   - Caches per-MAC; refresh on FW change                     │
│   - Applies Beijing-locked-timestamp adjustment (Rule 11)    │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│  Command-channel writer / response router                    │
│   - One writer queue with response-future per opcode         │
│   - Detects unsupported via `(op & 0x80) and payload[0]==EE` │
│   - Timeout: 4 s default for response-bearing ops; do NOT    │
│     wait on 0x6A BP stop-ack (often absent — see Rule 7)     │
│   - Optional write-then-read-back wrapper for stateful ops   │
│     (see Rule 12 / §10.3.5)                                  │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│  Event subscriber (0x73 / 0x78 / 0xA1)                       │
│   - Sub-id dispatch table (unknown sub-id → log, don't crash)│
│   - Multi-frame stream sentinel handling (FF/F0/00)          │
│   - 0xA1 ch3 accel decode; ch1/2/4 forwarded as opaque       │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│  Feature modules                                             │
│   - Each module checks `caps[feature]` before activating     │
│   - Measurement module wraps 0x69 / 0x6A flow with err=2     │
│     retry + "adjust ring" prompt                             │
│   - Touch module wraps the 2-step gesture init               │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│  BLE transport (bleak / NordicSemi / WinRT / CoreBluetooth)  │
│   - On macOS: services=[SVC_CMD, SVC_DEVINFO] filter         │
│     required to avoid 30s CCCD-ACK stall (see §9.3)          │
└─────────────────────────────────────────────────────────────┘
```

### 9.3 Platform-specific gotchas

**macOS via CoreBluetooth (bleak ≤3.0.2)**: the system's `setNotifyValue`
synchronously waits for the CCCD-write ACK from the peripheral. The R08
firmware can delay this ACK by ~30 s, by which point the ring has
disconnected on idle timeout. **Workaround**: filter service discovery
with `services=[…]` to keep discovery short; even then, expect intermittent
failures. **Windows (WinRT) and Linux (BlueZ)** don't have this problem.

**Windows**: works reliably. Run PowerShell as admin for BLE permissions
on first connection. Use `bleak ≥ 0.21` for the modern WinRT backend.

**Android**: works natively (the APK is the reference). Use the standard
`BluetoothGatt` API with `BluetoothGattCallback`.

**Linux / BlueZ**: works natively. `gatttool` or `bluepy` is fine; bleak
on BlueZ also works.

### 9.4 Reference Python library

[`tools/r08_protocol.py`](tools/r08_protocol.py) ships frame builders,
parsers, and constants matching this spec. Includes:

- `pack_cmd(opcode, payload=b"")` → 16-byte frame with CRC
- `parse_cmd(frame)` → `(opcode, error_flag, payload)`; validates CRC
- `pack_spp(action, payload=b"")` → BC-headed SPP frame
- `crc16_modbus`, `decimal_to_bcd`, `bcd_to_decimal`
- Opcode constants (CMD_*), measure types (MEASURE_*), DeviceNotify
  sub-ids (DN_*), PhoneSport status codes (PHONE_SPORT_*), Touch app
  types (TOUCH_APP_*)
- Builders: SetTime, GetBattery, ReadDeviceSupport, Realtime HR, Start/
  StopMeasure, FindRing, FactoryRestore, etc.
- Parsers: Battery, TodayStep, Realtime HR, Measure samples, capability
  bitmaps, sport-notify, accel
- Helpers: `temp_byte_to_celsius`, `celsius_to_temp_byte`

[`tools/r08_probe.py`](tools/r08_probe.py) is the interactive validation
probe used to generate this report.

---

## 10. Verification Status

### 10.1 Working opcodes (call freely)

25 opcodes confirmed working in some form on RT08_3.10.46. 24 are host-issued
(listed below); `0x78` is a receive-only notification channel, separated in
[§10.6](#106-receive-only-sub-channels).

```
0x01 SetTime           0x03 Battery            0x04 BindAncs
0x10 BindSuccess       0x18 Ping(empty)        0x19 TempUnit (R/W)
0x21 TargetSetting     0x28 ReadDrinkAlarm     0x2B Menstruation (R only)
0x2F PackageLength     0x3B TouchControl       0x3C DeviceSupport
0x43 StepSomedayDetail 0x48 TodaySport         0x50 FindRing
0x60 SetANCS           0x61 GetMessagePush     0x69 measure / 0x6A stop
0x77 PhoneSport        0x7A Muslim worship data
0x7B Muslim goal data  0x93 GetHwFwVersion     0xA1 Telemetry stream
+ history-FF responders (work, but only by returning the no-data sentinel):
  0x15 0x37 0x39 0x43
```

### 10.2 Unsupported on RT08_3.10.46 (return `0xEE`)

38 opcodes confirmed unsupported (return the `[op|0x80][0xEE]…` shape):

```
0x06 0x09 0x12 0x13 0x17 0x1A 0x1B 0x1C 0x1D 0x1E 0x1F
0x22 0x23 0x24 0x29 0x2A 0x2D 0x30 0x32 0x33 0x40 0x42 0x44 0x46
0x4A 0x51 0x52 0x5A 0x6C 0x6D 0x6E 0x6F 0x70 0x74 0x75 0x76 0x7E
0x07 (StepTotalSomeday)
```

(`0x0C` write is **not** in this list — it ACKs cleanly but doesn't persist;
see [§10.3.5](#1035-silent-persist-failure) below.)

### 10.3 Silent timeouts on RT08_3.10.46

3 opcodes silently drop the request:

```
0x05 PalmScreen           — silent
0x14 BP history           — silent (tested with 10 s wait)
0x72 PushMsg              — silent (and no visible effect on R08 anyway)
```

### 10.3.5 Silent-persist failure

These are **not** `0xEE` failures and **not** silent timeouts — the ring
returns a clean empty-body ACK (or echo-ack), but the value never persists.
The only way to detect this is a write-then-read-back cycle.

| op | name | behavior |
|----|------|----------|
| `0x0C` write | BP-auto write | Write returns a clean `0C 00 …` ACK; the subsequent read via `0C 01` returns the *original* body bytes unchanged. The value never persists. |
| `0x21 [02, steps<100, …]` | TargetSetting with steps below 100 | Write returns the usual echo-ack, but read-back stays at the prior target. Values ≥ 100 persist normally; minimum effective granularity ≈ 100 steps (see [§4.9](#49-quality-of-life--utility)). |

Two more opcodes show the same write-ACK shape but persistence was not
checked through the dedicated read opcode, so leave them flagged 🟡
until re-tested:

- `0x25` Sit-long write (the read is `0x26`)
- `0x27` Drink-alarm write (the read is `0x28`)

**App-dev guidance**: don't trust the ACK; always verify state via the
dedicated read opcode whenever persistence matters.

### 10.4 Destructive / untested

```
0x08 RebootPowerOff     0x0F toOTA / OTA mode entry
0x20 dial paint / calibration
0x2E OTA mode switch (alias of 0x0F)
0x35 alarm sub-action
0xC9 / 0xCA factory test open/close
0xFF FactoryRestore
```

### 10.5 Across-unit variation flags

These items vary by firmware/hardware revision; **always test on your
target hardware before relying on them**:

- `0xFEE7` service-UUID in advertising data — absent on RT08_V3.1; may
  appear on other revisions
- SPP service `de5bf728-…` — absent on prod firmware; may be reachable
  in OTA mode
- BP / Fatigue / Pressure / BloodSugar / HRV / Temp `0x69` measurement
  final-value computation — opcodes are reachable but `val` stays 0 on
  this firmware. Other builds may compute real values.
- ECG family (`0x69 type=7`, `0x6C..0x70`) — `supportEcg` capability is
  false on this unit; would work on an ECG-equipped variant.
- `0x73` long-tail sub-ids (1-5, 9, 11, 16, 17, 37, 39, 43, 44, 48-58, 61,
  63) — depend on ring-side events that didn't occur during our test
  window. Sub=17 (STEP_INCREMENT) in particular appears to be superseded
  by sub=18 on this firmware (we observed sub=18 throughout PASS 3/11
  walking; never sub=17). They're documented above per the APK source.
- Capability-bitmap bits not decoded by the SDK (`b1.0x20`, `b2.0x80`,
  `b3.0x02` in the 0x3C response on our unit) — reserved for firmware
  extensions; semantics unknown.

### 10.6 Receive-only sub-channels

These opcodes appear on the notify characteristic as **ring-initiated**
pushes only — host-issued writes return `0xEE`. They belong to the working
catalogue because they are part of a live working session flow, but a
client must not try to "call" them.

| op | name | direction | host write status | how to receive |
|----|------|-----------|-------------------|----------------|
| `0x78` | PhoneSportNotify | ring → phone | host-issued writes return `0xEE` | Starts pushing automatically after `0x77 [01, sport_type]` START; ticks during the session; one ack on `0x77 [04, …]` STOP. |

---

## 11. Open Questions

Items genuinely unresolved after 13 passes of validation:

| # | item | what we know | what we don't |
|---|------|--------------|---------------|
| 1 | `0x73 sub=37` endianness | Ambiguous (BE vs LE) | Worship counter doesn't increment via touch gestures on this FW; can't disambiguate without a different unit |
| 2 | `0x73 sub=16 TARGET_REACHED` trigger | Two independent live walks have crossed the configured target in real time without firing sub=16: (i) target=100, ACTIVITY_TOTAL went 26 → 144, no sport session; (ii) target=300, ACTIVITY_TOTAL went 227 → 410, **with an active `0x77 START` sport session**. So sub=16 is *not* a pure rising-edge crossing of the daily counter, and an active sport session is *not* the gate either. | The actual trigger. Plausibly: a once-per-day persistent flag that already fired earlier in the day, or a server-sync window, or some other signal we haven't supplied. Don't depend on sub=16 for UI on this firmware. |
| 3 | `0xA1` channels 1, 2, 4 semantics | Channel 3 = accel (decoded); ch1/2 are PPG runtime accumulators; ch4 inversely-correlated with motion | Specific units / sensor mapping / firmware-internal meaning |
| 4 | `0x6E` PPG raw stream | Spec exists in APK | Never emitted on this firmware; can't verify byte layout |
| 5 | `0x69 type=0x02 BP` final-value path | Progress frames flow with `raw_LE_u16` varying; `val` stays 0; the `0x6A 02 04 …` STOP usually returns **no `0x6A` ack frame at all**. One historical run produced an anomalous `6A 02 49 00` reply that could not be reproduced. | What firmware state triggers the anomalous reply, and whether other firmwares populate sbp/dbp via `0x6A`. |
| 6 | `0x78` metric A/B/C mapping on RT08_3.10.46 | The SDK-spec "3 × u24 LE metric A/B/C" layout is **not** populated by this firmware — payload bytes 5..11 stayed `0x00` throughout a 60 s brisk walk that accumulated 196 real steps. The non-zero post-duration content is at payload[4] (looks like HR, not "sport-status") and payload[12..13] (~50/s monotonic accumulator, unit unknown). See [§4.8](#48-phone-sport-session-0x77--0x78). | What the firmware *does* put at payload[4] and [12..13] (HR? cadence-derived counter? raw timer tick?), and whether other firmware revisions populate the SDK-claimed metric A/B/C slots. |
| 7 | Capability-bitmap undocumented bits | Three bits (`b1.0x20`, `b2.0x80`, `b3.0x02`) are set by firmware but never decoded in APK | Their semantic meaning (firmware-only extensions?) |
| 8 | `0xA1 [02]` and `0xA1 [05]` purpose | Probes of both sub-actions produced **zero** response frames — neither a stream, an ACK, nor an EE. [03] and [04] in the same probe sequence did respond. | Whether [02] / [05] are reserved-unused, or have a different trigger condition (e.g. require prior state set by [01]). |
| 9 | History-stream timestamp endianness/TZ | APK spec says history payloads use "local-time-as-utc-seconds with explicit tz_offset"; the `0x77` START ack — the only device-emitted u32-UTC field we have on the wire — uses the hardcoded UTC+8 Beijing interpretation of SetTime bytes (§4.8). | Whether other emitters (`0x15` / `0x37` / `0x43` payloads etc.) follow the same Beijing-lock convention or the SDK's per-opcode tz-offset convention. Every history opcode returned the no-data sentinel on this unit, so no real history payload was decoded. |

For an active app: items 1, 3, 4, 6, 8 don't block — fall back to safe
defaults and add observers. Items 2, 9 affect any feature that depends on
TARGET_REACHED or history timestamps and should be re-validated against
the QRing app's behaviour before shipping. Items 5, 7 are minor and can
be left for future field validation.

---

## Appendix A — APK Source Citations

Every claim above traces to a specific file in the decompiled APK. Paths
are relative to `work/jadx_out/sources/`.

### Frame & framing

- `com/oudmon/ble/base/communication/req/BaseReqCmd.java` —
  command-channel frame builder + CRC
- `com/oudmon/ble/base/communication/utils/CRC16.java` —
  Modbus CRC-16 for SPP
- `com/oudmon/ble/base/bluetooth/QCDataParser.java` —
  notify-frame dispatcher
- `com/oudmon/ble/base/communication/LargeDataHandler.java`,
  `DfuHandle.java`, `file/FileHandle.java` — three identical SPP
  `addHeader()` implementations

### Bootstrap

- `com/qcwireless/smart/ui/base/receiver/MyBluetoothReceiver.java`
  `onServiceDiscovered()` + `initCmd()` — the first commands after
  service discovery
- `com/qcwireless/smart/ui/base/receiver/BleCommonDataParseKt.java`
  `parseDeviceInfoData()` — sets `BleOperateManager.ready=true` on
  `0x2A27` read response
- `com/oudmon/ble/base/bluetooth/BleOperateManager.java` `execute()` —
  the `ready` gate (line 349)
- `com/qcwireless/smart/ui/base/watch/DeviceCmdInit.java` `init()` —
  the post-`ready` bootstrap sequence

### Opcodes (constants)

- `com/oudmon/ble/base/communication/Constants.java` — every named
  `CMD_*` constant
- 47 `…Req.java` files in `com/oudmon/ble/base/communication/req/`
- 67 `…Rsp.java` files in `com/oudmon/ble/base/communication/rsp/`
- `com/oudmon/ble/base/bluetooth/BeanFactory.java` — opcode → Rsp class
  dispatch

### Capability decoders

- `com/oudmon/ble/base/communication/rsp/DeviceSupportFunctionRsp.java`
  `acceptData` — the 9-byte bitmap decoder
- `com/oudmon/ble/base/communication/rsp/SetTimeRsp.java`
  `acceptData` — the 14-byte capability extension decoder

### 0x73 sub-id dispatcher

- `com/qcwireless/smart/ui/home/healthy/HealthyFragment.java`
  `MyDeviceNotifyListener.onDataResponse` lines 680–1015 — canonical
  table of sub-ids
- `com/qcwireless/smart/ui/mine/MineFragment.java`
  `MyDeviceNotifyListener.onDataResponse` lines 375–470 — additional
  sub-ids 40, 42 + battery payload format
- `com/qcwireless/smart/ui/device/touch/RevisionActivity.java` line 89 —
  battery state push payload format

### Touch / gesture

- `com/qcwireless/smart/ui/device/touch/TouchActivity.java` lines
  196–259 — appType enumeration
- the `TOUCH_ENABLE` / `TOUCH_MODE appType=9` init recipe — recovered by
  on-device reverse-engineering (see §4.10)
- `com/oudmon/ble/base/communication/req/TouchControlReq.java`,
  `com/oudmon/ble/base/communication/rsp/TouchControlResp.java`

### Sport / activity

- `com/qcwireless/smart/ui/home/sport/SportRunningActivity.java` lines
  138-143 — 0x78 byte layout
- 5 places in same file calling `PhoneSportReq.getSportStatus()` with
  status codes 1/2/3/4/6
- `com/oudmon/ble/base/communication/req/PhoneSportReq.java`

### Measurement (0x69 / 0x6A)

- `com/oudmon/ble/base/communication/req/StartHeartRateReq.java` —
  type IDs + sub-action constants
- `com/oudmon/ble/base/communication/req/StopHeartRateReq.java`
- `com/oudmon/ble/base/communication/rsp/StartHeartRateRsp.java`

### SPP / file handlers

- `com/oudmon/ble/base/communication/LargeDataHandler.java`
- `com/oudmon/ble/base/communication/file/FileHandle.java`
- `com/oudmon/ble/base/communication/file/AvatarHandle.java`,
  `AlbumHandle.java`, `EbookHandle.java`, `RecordHandle.java`,
  `DataHelper.java`
- `com/oudmon/ble/base/communication/DfuHandle.java`

### Encoding helpers

- `com/oudmon/ble/base/communication/file/DataHelper.java` —
  temperature `raw / 10.0 + 20.0` decoder
- `com/qcwireless/smart/ui/base/repository/healthy/SleepDetailRepository.java`
  line 1906 — sleep-quality byte packing
- `com/qcwireless/smart/ui/base/repository/healthy/HeartRateDetailRepository.java`
  line 1912 — timezone offset encoding

---

## Appendix B — Frame examples (verified on wire)

Hex captures from the live validation passes (16-byte command-channel
frames unless noted).

### Bootstrap exchange

```
> 01 26 05 27 03 17 24 01 00 00 00 00 00 00 00 92    SetTime
< 2F F4 00 00 00 00 00 00 00 00 00 00 00 00 00 23    PackageLength (spontaneous, max=244)
< 01 01 00 00 02 00 00 00 00 01 00 20 00 00 30 55    SetTime response (14-byte capability extension)

> 04 02 0A 70 79 74 68 6F 6E 2D 72 30 38 00 00 B9    BindAncs (model="python-r08")
< 04 00 00 00 00 00 00 00 00 00 00 00 00 00 00 04    BindAncs ack (empty body)

> 3C 00 00 00 00 00 00 00 00 00 00 00 00 00 00 3C    DeviceSupportFunction
< 3C 00 2F AF 2E 00 00 00 00 00 00 00 00 00 00 48    9-byte capability bitmap

> 61 00 00 00 00 00 00 00 00 00 00 00 00 00 00 61    GetMessagePush
< 61 FF FF 00 00 00 00 00 00 00 00 00 00 00 00 5F    (FF FF = unconfigured sentinel)

> 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03    Battery
< 03 64 00 00 00 00 00 00 00 00 00 00 00 00 00 67    100% not-charging
```

### Live step counter (sub-18 ACTIVITY_TOTAL)

```
< 73 12 00 00 19 00 03 84 00 00 14 00 00 00 00 39
  └─sub=18  └─25 steps  └─900 mcal  └─20 meters
```

### Touch gestures (sub-id 0x2D, after the init sequence)

```
> 3B 01 00 01 01 00 00 00 00 00 00 00 00 00 00 3E    TOUCH_ENABLE
< 73 2A 00 00 …                                      TOUCH_STATUS_ECHO byte 1=0 (active)

> 3B 02 00 09 01 00 00 00 00 00 00 00 00 00 00 47    TOUCH_SLEEP_MODE with appType=9 ← THE MAGIC
< 73 2A 00 00 …                                      echo: touch IC active

…physical TAP on ring…
< 73 2D 03 00 00 00 00 00 00 00 00 00 00 00 00 A3    TAP   (PASS 7-verified)
…SWIPE_UP…
< 73 2D 01 00 00 00 00 00 00 00 00 00 00 00 00 A1    SWIPE_UP   (PASS 7-verified)
```

### 0xA1 telemetry cycle (one cycle = 4 frames at ~250 ms intervals)

Hex example below is illustrative of the per-channel field layout — the
checksums shown are computed from the bytes as written, not from any
single capture, so they correspond to *these* byte values.

```
< A1 01 00 D2 00 00 00 00 00 00 00 00 00 00 00 74    ch1: QQ=0xD2 (PPG accumulator)
< A1 02 24 B6 00 00 00 00 00 00 00 00 00 00 00 7D    ch2: VV=0xB6
< A1 03 1F DA FE 35 00 2F 00 00 00 00 00 00 00 FF    ch3: accel X=+8154, Y=-459, Z=+47 (LSB) → 0.997 g
< A1 04 00 20 00 18 00 1C 0F FF 00 00 00 00 00 07    ch4: motion-stability metric
```

### Battery push during charging (sub=12)

```
…plug ring into charging dock at t = 0…
< 73 0C 64 01 00 00 00 00 00 00 00 00 00 00 00 E4    100% charging (PASS 12, +0.70 s after plug-in)
                                                     ─ when battery is already full: just this 1 push,
                                                       then silence (PASS 12 listened 240 s, 0 more pushes)

…or, when battery is below 100 % and actually rising (PASS 11 at 99 → 100 %)…
< 73 0C 63 01 …                                      99% charging   t = +83.4 s
< 73 0C 63 01 …                                      99% charging   Δt = +30.2 s
< 73 0C 63 01 …                                      99% charging   Δt = +59.9 s
< 73 0C 64 01 …                                      100% charging  Δt = +60.1 s ← stops after full
```

### Phone sport session (0x77/0x78)

```
> 77 01 01 00 00 00 00 00 00 00 00 00 00 00 00 79    START sport_type=1
< 78 01 01 00 00 00 00 00 00 00 00 00 00 00 00 7A    PhoneSportNotify (echoes sport_type=1)
< 77 01 00 26 7E 16 6A 00 00 00 00 00 00 00 00 9C    response: TS LE u32 = 0x6A167E26
…live ticks…
< 78 01 01 00 01 00 00 …                            duration tick
…
> 77 04 01 00 00 00 00 00 00 00 00 00 00 00 00 7C    STOP
< 77 00 00 00 00 00 00 00 00 00 00 00 00 00 00 77    STOP ack
```

Self-check for the Beijing-lock timestamp behavior (§4.8): send
`SetTime(now_utc + 8 h)` as the BCD bytes and immediately issue
`0x77 [01, sport_type]`. The returned LE u32 should equal real
`now_utc` within a few seconds of execution lag. If it lags by
exactly an integer-hour amount, the firmware version has a different
hardcoded offset than UTC+8.

### Unsupported opcode (uniform `0xEE` shape)

```
> 1B 01 00 …                                          read brightness
< 9B EE 00 00 00 00 00 00 00 00 00 00 00 00 00 89    error: 0x1B | 0x80 = 0x9B, payload=EE
```

---

## License & Provenance

This specification is released into the public domain (CC0). The R08
protocol itself was reverse-engineered clean-room from the public
distribution of the QRing Android APK; no proprietary code is
redistributed here.

Citations to the QRing app's source are for verification reference only —
the actual protocol description is independently authored.

For contributions, corrections, or cross-firmware validation reports,
please open an issue or PR in the canonical repository.
