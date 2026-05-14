# 02 — Ring Hardware & BLE Protocol

The QRing R08 smart ring is the input device. This document is the authoritative spec for its
hardware and the BLE protocol our app speaks to it. Everything here is **reverse-engineered** —
verified against either the published `tahnok/colmi_r02_client` or the decompiled
`com.ring.r08remote` v2 APK (sources in [12-research-and-references.md](12-research-and-references.md)).
Re-verify against a real ring before relying on it — see [11-verification-checklists.md](11-verification-checklists.md).

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
| Touch IC | Unknown capacitive controller | **R08-specific**; the broader Colmi BOM doesn't have it (other models are touchless). Lives between the touch ring and the RF03 SoC. The RF03 receives touch events and re-publishes them over BLE. |
| Battery | LiPo 17 mAh | ~5–7 days in event mode; <1 day with always-on raw IMU |
| Charging | Magnetic-contact cradle, USB-C | ~60–90 min full charge. BLE is **off** while charging. |
| Water resistance | IP68 / 5 ATM | 50 m water depth |
| LED | Green (single colour) | Controllable via BLE commands `0x06` / `0x10` |
| Debug | SWD (P00 = SWCK, P01 = SWD) | Internal solder pads, need to crack the ring open |
| OTA | BLE, **unsigned**, **unencrypted** | Browser-based flasher at https://atc1441.github.io/ATC_RF03_Writer.html |

⚠️ The hardware details for *the R08 specifically* are inferred from the broader Colmi family
(`R08 vs R02`: R02 has no touchpad, R08 does, so R08 adds a touch IC; everything else is
unchanged). Direct verification needs either a teardown or the FCC internal photos.

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

This is the Nordic-style "UART over BLE" service layout (`fff0` family). Naming is from the
ring's perspective; from the central's perspective the **write** char is our outbound channel,
the **notify** char is our inbound.

- No encryption, no pairing key, **anyone can connect**. Multi-user defence is MAC whitelist in
  the central.
- One central at a time. Two devices trying to grab the ring conflict.
- Charging the ring kills BLE.

## 3. Write commands (central → ring)

All commands are **16 bytes fixed**: `[0] = command code`, `[1..14] = payload (zero-padded)`,
`[15] = checksum`. Checksum = sum of bytes `[0..14]` `& 0xFF`.

Confirmed commands (all from decompiling `com.ring.r08remote` + `tahnok/colmi_r02_client`):

| Name | Hex (16 bytes) | Checksum verification | Purpose |
|---|---|---|---|
| `TOUCH_ENABLE` | `3B 01 00 01 01 00*10 3E` | 3B+01+00+01+01 = 3E ✓ | **Required after connecting + enabling notify.** Without this, the ring won't report touch/gesture events. |
| `TOUCH_MODE` | `3B 02 00 09 01 00*10 47` | 3B+02+00+09+01 = 47 ✓ | Send ~500 ms after `TOUCH_ENABLE`. Configures touch-report mode. |
| `TOUCH_DISABLE` | `3B 01 00 01 00 00*10 3D` | 3B+01+00+01+00 = 3D ✓ | Powers down the touch IC (the dominant ring drain). Send when the user takes off the glasses. |
| `BATTERY_QUERY` | `03 00*14 03` | 03 = 03 ✓ | Async; the ring replies with a `03 <level%>` notify frame. |
| `FIND_DEVICE` | `06 00*14 06` | | Blinks the green LED for ~10 s. The "find my ring" command. |
| `BLINK_TWICE` | `10 00*14 10` | | Quick two-blink. Useful for connect-OK / mode-switch ACK. |
| `SHUTDOWN` | `0F 00*14 0F` | | Powers the ring off. Re-pair to wake. |

> Other R02-family commands that exist but we don't use (from `tahnok/colmi_r02_client`):
> `0x01` set time, `0x09` SpO2, `0x15`/`0x21` HR history, `0x37` HRV, `0x43` step-someday,
> `0x69`/`0x6A` real-time-data start/stop. `0x08` is **reboot** (NOT battery, despite what
> `R08-Dev.md` originally claimed). See [12-research-and-references.md](12-research-and-references.md).

The Kotlin builder is at [`../app-project/core/.../ble/R08Protocol.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/ble/R08Protocol.kt);
the Python builder is at [`../phase0/r08_probe.py`](../phase0/r08_probe.py).

## 4. Notify frames (ring → central)

The ring pushes events over the notify char. Length varies (2–16 bytes typically). Disambiguate
by `data[0]` (and sometimes `data[1]`).

### 4.1 Frame catalogue

| `data[0]` | `data[1]` | Length | Meaning | Payload decode |
|---|---|---|---|---|
| `0x73` ('s') | `0x2D` ('-') | ≥ 3 | **Control gesture** (core remote input) | `data[2]`: `0x01`=swipe up, `0x02`=swipe down, `0x03`=single touch, `0x04`=long press |
| `0x73` | `0x2A` ('*') | ≥ 3 | Touch enable/disable echo | `data[2] == 0` → touch IC enabled |
| `0x73` | `0x12` | ≥ 11 | Activity counters | `[2..4]` steps (BE), `[5..7]/1000` calories, `[8..10]/1000` distance (m) |
| `0xA1` (161) | — | varies | Accelerometer raw | **Encoding unknown** — `com.ring.r08remote` receives but doesn't decode these. Action item: investigate via phase-0 (see [11](11-verification-checklists.md) §A2) |
| `0x03` | — | ≥ 2 | Battery percentage | `data[1]` = battery % |
| `0x69` ('i') | `1` / `3` / `8` | ≥ 4 | Real-time health reading | `data[3]` = value: kind 1 = HR (bpm), 3 = SpO2, 8 = stress. Only valid if value > 0. |
| `0x51` (81) | — | ≥ 3 | Steps only | `data[1] \| (data[2] << 8)` |

The Kotlin parser is at [`../app-project/core/.../ble/R08Frame.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/ble/R08Frame.kt)
(stateless; pure function `parse(ByteArray): RingEvent`). The Python equivalent is in
`../phase0/r08_probe.py::decode`.

### 4.2 The gesture frame namespace (`0x73 0xNN`)

The `0x73` prefix is the **ring-specific report namespace** — everything new in R08 that wasn't
in the broader R02 family seems to live under it (gestures, touch status, activity). The R02
standard health stuff (battery, HR, SpO2, steps) uses different prefixes.

**The 4 gesture codes (01/02/03/04) are the only ones confirmed.** It's plausible there are more
sub-codes the third-party app doesn't subscribe to — for instance, "ring put on / taken off",
"charging case button", or "left/right swipe". Phase-0 HCI snoop of the official QRing app is the
way to find out — see [11](11-verification-checklists.md) §A4.

### 4.3 No richer gestures from firmware

`com.ring.r08remote` synthesises double-tap, triple-tap, combos **entirely on the app side** — the
ring firmware only ever reports the 4 raw events. The original `R08-Dev.md` speculated about
"swing"/in-air gestures being recognised in firmware; **they aren't** (in the default firmware at
least). The accelerometer at `0xA1` is being pushed continuously but the encoding hasn't been
decoded by anyone publicly.

## 5. Connection lifecycle (the recipe)

This is what `com.ring.r08remote` does on every connect; copy it exactly. Times in milliseconds
after each step:

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
  startBatteryPoll(every 30 min)

  startRssiPoll(every 5 s)  (for HUD signal indicator)

  (optional) request a short connection interval (~15–30 ms) for low latency.
  Relax to ~100–200 ms after N seconds idle. See 06-performance-and-power.md §3.
```

On disconnect:
- Don't actively re-scan; rely on `autoConnect`. App-level continuous scanning is a power killer.
- On the next reconnect, arm the **wake-swallow** in the gesture synthesiser — the user's first
  one or two TOUCH events may be the "double-tap to wake the ring from auto-sleep", not intent.
  See [05-interaction-design.md](05-interaction-design.md) §3.4.

## 6. De-duplication (critical, easy to get wrong)

Because gestures are repeats of the same packet bytes (e.g. two real taps both look like
`73 2D 03`), naïve "drop identical packet" de-duplication is dangerous: a fast human double-tap
(say 150 ms apart) would have its second tap dropped, breaking the double-tap gesture.

`com.ring.r08remote` uses a 100 ms window — barely below the human double-tap minimum
(~120–300 ms). Works **most** of the time. We do better:

1. Phase-0 measures the inter-tap interval distribution for real fast taps on your ring — see
   [11](11-verification-checklists.md) §A1.
2. We also check if the ring's notify packets contain any varying byte (counter / timestamp). If
   yes, de-dup is trivial: drop only byte-for-byte identical packets within ~50 ms.
3. If no varying byte, use a tight window — based on the measurement, probably 40–60 ms.

The dedup belongs in the BLE client layer (`AndroidR08BleClient`), not the gesture synthesiser.
The synthesiser has an optional `minRawIntervalMs` defence-in-depth knob (default off).

## 7. Errata against `R08-Dev.md`

Documented in detail in [12-research-and-references.md](12-research-and-references.md) §4. Quick
list:

- Battery is `0x03`, not `0x08` (`0x08` is **reboot**).
- Gesture command codes are `0x73 0x2D 0x01-04`, not the `0x70-7F` range.
- Swing/in-air gestures **do not** exist in firmware; the original doc's "swing → camera" mapping
  is recreated in the app as a double-tap + swipe combo.
- "Double-tap is firmware-recognised" — also false. All multi-tap counting is app-side timing.
