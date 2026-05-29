# 02 — Ring Hardware & BLE Protocol — Integration Notes

> **The full protocol spec is [Doc/09 — R08 BLE Protocol Spec](09-r08-ble-protocol-spec.md)** — 1797 lines, verified end-to-end on R08_E600 firmware `RT08_3.10.46_250621`. This document only captures Halo-Ring-specific integration details: which opcodes we use, when we use them, what dedup/delay constants the app picked, and what we explicitly avoid.

Updated 2026-05-27 as part of the v0.4 doc pass (absorbed Doc/07's sensor matrix — now lives in [Doc/03 §9](03-architecture.md#9-sensor-utilisation-matrix-formerly-doc07-11)).

---

## 1. Hardware identity

| Property | Value |
|---|---|
| Product | **QRing R08** smart ring (Yawell OEM) |
| FCC ID | `2AOM3-R08` |
| Main SoC | BlueX Micro RF03 (ARM Cortex-M0, 200 KB RAM, 512 KB Flash, BLE 5.0) |
| Accelerometer | STK8321 (3-axis, ±2/4/8/16 g, 14-bit, I²C) |
| PPG sensor | Vcare VC30F (optical HR + SpO2; the 25 s vitals stream burns this) |
| Touch IC | R08-specific capacitive controller (drives the `73 2D` gesture path) |
| Battery | LiPo 17 mAh — ~5-7 days in event mode |
| Charging | Magnetic-contact cradle, USB-C, BLE **off** while charging |
| Water resistance | IP68 / 5 ATM |
| LED | Green single-colour (driven by `0x50 [0x55, 0xAA]` = Find Ring) |
| OTA | BLE, unsigned, unencrypted (observed first-hand; not used by Halo Ring) |

The touch IC is the only hardware that distinguishes R08 from the rest of the Yawell/Colmi
family — that's why most third-party Colmi tooling lacks gesture-frame parsing.

## 2. BLE GATT structure (verified)

```
Advertised name : R08_XXXX           (XXXX = last 2 bytes of MAC, hex)
                  (also matches names containing "R06", "Colmi", "COLMI" for the family)

GATT service    : 6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E

  Write characteristic    (central → ring)
  6E400002-B5A3-F393-E0A9-E50E24DCCA9E

  Notify characteristic   (ring → central)
  6E400003-B5A3-F393-E0A9-E50E24DCCA9E

  CCCD descriptor (write 0x0001 to enable notify)
  00002902-0000-1000-8000-00805F9B34FB
```

Nordic-style "UART over BLE" service layout. No encryption, no pairing key. One central at a
time. Charging kills BLE. Multi-user defence is **MAC whitelist on the central** —
`RingPairingPrefsStore` persists the selected MAC.

**Idle baseline**: zero notify frames in the 10 s after subscription. Every reporting stream
(gestures, vitals, auto-monitor sync) has to be explicitly enabled — good for our power budget.

## 3. Frame format

All write commands are **16 bytes fixed**: `[0] = command code`, `[1..14] = payload (zero-
padded)`, `[15] = checksum = sum(bytes[0..14]) & 0xFF`.

Response frames carry a **high-bit error flag**: `data[0] & 0x80` set = error. `R08Frame.parse`
masks this before dispatch.

## 4. The connect-time recipe Halo Ring actually runs

Implemented in `AndroidR08BleClient` + `HaloRingService` per SPEC v3 §3:

```
connectGatt(autoConnect = true)
on STATE_CONNECTED → discoverServices()
on services discovered →
  GATT-read 0x2A27 (Hardware Revision)
  GATT-read 0x2A26 (Firmware Revision)
  setCharacteristicNotification(NOTIFY_CHAR, true)
  writeDescriptor(CCCD, ENABLE_NOTIFICATION_VALUE)

+500 ms (settle) →
  writeCharacteristic(0x01 SetTime)   — Beijing-locked UTC+8 BCD per SPEC §4.8
                                        (returns 14-byte capability bitmap extension)
+150 ms →
  writeCharacteristic(0x21 BindAncs)  — bind ANCS for iOS-style notifications passthrough
+150 ms →
  writeCharacteristic(0x3C DeviceSupport) — pull 9-byte capability bitmap
+300 ms →
  writeCharacteristic(0x61 GetMessagePush)
+150 ms →
  writeCharacteristic(0x03 BATTERY_QUERY)
+800 ms →
  writeCharacteristic(0x3B TOUCH_ENABLE)         — R08-specific touch path
+500 ms →
  writeCharacteristic(0x3B TOUCH_MODE appType=9)

request CONNECTION_PRIORITY_HIGH (~15-30 ms interval) — relaxed by PowerPolicy after 10 s idle
```

End-to-end **~7 s** on R08_E600. Capability bitmap (17 flags including `appMeasure`, `bloodOxygen`,
`hrv`, `newSleepProtocol`, `pressure`, `temperature`, `wechat`) is parsed into
`AppGraph.ringCapabilitiesFlow` and used by v0.4 P3 capability-gated UI ([Doc/11 §8](11-v0.4-design.md)).

## 5. Opcodes Halo Ring uses (cross-ref SPEC v3)

| Opcode | Direction | Purpose | SPEC § |
|---|---|---|---|
| `0x01` SetTime | write | RTC sync (UTC+8 BCD) + capability bitmap response | §4.8 |
| `0x03` Battery | write/notify | Level query + low-battery push (`0x73 0x0C`) | §4.2 |
| `0x21` BindAncs | write | iOS-style notification passthrough binding | §4.4 |
| `0x3B 01` TOUCH_ENABLE/DISABLE | write | Enable/disable the R08 touch IC | §4.10 |
| `0x3B 02` TOUCH_MODE | write | App-type (9 = remote-control mode) | §4.10 |
| `0x3C` DeviceSupport | write/notify | 9-byte capability bitmap pull | §3 |
| `0x48` ActivityTotals | write/notify | 14-byte BE today totals | §4.3 |
| `0x50 [0x55, 0xAA]` FindRing | write | LED blink + vibration | §4.9 verified on burn-in |
| `0x61` GetMessagePush | write/notify | Notification push config | §4.4 |
| `0x69 <kind> 01` VitalsStart | write | HR/SpO2/Stress/HRV/Temp start | §4.5 |
| `0x6A` VitalsStop | write | Stop the active PPG stream | §4.5 |
| `0x73 <sub>` SyncTrigger | notify | 30+ sub-codes — see §6 | §4.7 |
| `0x77 [01, sport_type]` SportStart | write | Begin workout session | §4.8 + §10 |
| `0x78` SportTick | notify | Per-second duration + HR during session | §10 |
| `0xA1` Accel | notify | 16-byte frame, 6-byte payload = 3× int16 LE axes | §4.10 verified |

**Notable absences**:

- `0x0F` SHUTDOWN — **DO NOT SEND**. SPEC §0.2 / phase-0 confirmed: this opcode puts the ring
  into OTA bootloader mode. No working R08-firmware `.bin` exists to recover with → would brick.
  Removed from `R08Protocol.kt`.
- `0x06` (heritage "find-device") and `0x10` (heritage "blink-twice") — not used; replaced by
  `0x50 [0x55, 0xAA]` per SPEC §4.9.

## 6. `0x73 <sub>` sync-trigger codes we consume

Push-only frames the ring emits to tell us something happened.

| `data[1]` | Meaning | Halo Ring action |
|---|---|---|
| `0x0C` | Battery low | Reads `0x03`, replaces our 30-min poll with push-driven update |
| `0x12` | Activity total sync hint | Optionally re-query `0x48` for canonical totals |
| `0x2A` | Touch status echo | Wear-state signal (charging dock detection: `data[2]==0` while in dock) |
| `0x2D` | **Gesture** — the core of the project | `data[2]` = SWIPE_UP(1) / SWIPE_DOWN(2) / TOUCH(3) / LONG_PRESS(4) → GestureSynthesizer |
| `0x30` | Firmware-side "lover double-tap" (if emitted) | Currently unused; could short-circuit our 280 ms app-side combo window |
| `0x31` | Current-HR reminder | Passive HR readout without burning PPG (not currently consumed) |
| `0x3E` | G-sensor still-time tick (if emitted) | Currently unused; potential wear-state proxy |

Full sub-code catalogue (~30 codes) in SPEC v3 §4.7.

## 7. Dedup window calibration

`AndroidR08BleClient` drops byte-identical notify frames within a **50 ms** window before passing
to `GestureSynthesizer`. Calibrated empirically on R08_E600 burn-in: minimum observed inter-tap
gap is ~80 ms during deliberate-fast tapping; 50 ms is comfortably below that with no false drops.

## 8. Errata vs the original `R08-Dev.md` heritage

The original community handoff doc had several speculative claims; SPEC v3 + burn-in settled
them. Discarded:

- ~~`0x06` = find-device~~ → silent on R08; use `0x50 [0x55, 0xAA]` instead
- ~~`0x10` = blink-twice~~ → silent on R08
- ~~`0x0F` = shutdown~~ → OTA-mode entry, would brick (do not send)
- ~~`0x08` = battery query~~ → actually soft-reboot; battery is `0x03`
- ~~Firmware-recognised double-tap~~ → app-side timing via `GestureSynthesizer`; `0x73 0x30` may
  give a true firmware-side double-tap but we haven't observed it on R08_E600
- ~~Continuous swing/in-air gestures from the ring~~ → no; the `0xA1` accel push only contains
  raw 3-axis samples (we decode it via `AccelProcessor` for posture/free-fall/impact/wrist-shake)

## 9. References

- **Full protocol spec**: [Doc/09 — R08 BLE Protocol Spec](09-r08-ble-protocol-spec.md) — the
  1797-line reverse-engineered protocol, verified on `RT08_3.10.46`
- **Sensor matrix** (which sensors + what we do with each): [Doc/03 §9](03-architecture.md#9-sensor-utilisation-matrix-formerly-doc07-11)
- **Code that implements this**: `core/.../ble/R08Protocol.kt` + `R08Frame.kt`
- **Pre-v0.4 versions of this doc** (longer, included phase-0 stage planning): [`_archive/`](_archive/)
