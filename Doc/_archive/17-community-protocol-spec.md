# 17 — Community Protocol Spec (Draft Template — fill in after phase-0)

> **Status: TEMPLATE.** This document is the *target* of phase-0; it gets filled in stage-by-stage
> as [Doc/16 — phase-0 test plan](16-phase0-test-plan.md) returns verdicts.
>
> **Audience**: the open-source community — atc1441, `tahnok/colmi_r02_client`, any future
> downstream rebrand of the QRing / Colmi / RF03+STK8321 ring family. Goal is a complete, citable
> BLE protocol reference with R08-specific paths verified empirically.
>
> **Distribution**: when finished, this doc will be:
> - Linked from the project's [README](../scripts/README-public.md)
> - Mirrored as a Gist for SEO + atc1441 cross-link
> - PR'd to `tahnok/colmi_r02_client`'s `MYSTERIES.md` for the entries it resolves
> - Optionally PR'd to the FCC-listed atc1441 RF03 repo

---

## 0. Provenance and methodology

```
Sources combined:
  - QRing official Android app v3.x (com.qcwireless.smart) — primary
  - 小猪遥控戒指 v2 (com.ring.r08remote)              — R08-specific addendum
  - Empirical verification on QRing R08 hardware     — final word
Method:
  - 10-stage phase-0 verification flow (see Doc/02-hardware-and-protocol.md)
  - All scripts open-source, in Python via bleak
Hardware tested:
  - QRing R08 (FCC ID 2AOM3-R08)
  - BlueX RF03 SoC + STK8321 accelerometer + Vcare VC30F PPG + capacitive touch IC
  - Firmware: <fill in after `0x3C` capability + version-query verification>
Author: Halo Ring · 环意 project (https://github.com/MRziyi/Halo-Ring)
Date:   <fill in on publish>
License: CC-BY 4.0 (this spec) — reuse anywhere with attribution
```

## 1. GATT layout

(✓ verified)

```
Advertised name : R08_XXXX           XXXX = last 2 bytes of MAC (hex)
                  also matches names containing R08 / R06 / Colmi / COLMI

GATT service    : 6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E

  Write characteristic    (central → ring)
  6E400002-B5A3-F393-E0A9-E50E24DCCA9E

  Notify characteristic   (ring → central)
  6E400003-B5A3-F393-E0A9-E50E24DCCA9E

  CCCD descriptor (write 0x0001 to enable notify)
  00002902-0000-1000-8000-00805F9B34FB
```

Constraints: no encryption, no pairing key, single concurrent central, BLE off during charging.

## 2. Frame format

(✓ verified)

```
Write commands:  16 bytes fixed
                 [0]     = command code
                 [1..14] = payload (zero-padded)
                 [15]    = checksum = sum(bytes[0..14]) & 0xFF

Notify frames:   2..16 bytes variable
                 [0]     = command code (high bit = error flag, mask before dispatch)
                 [1..]   = response payload
```

<after phase-0 Stage 0: confirm the high-bit error flag is observable>

## 3. Command catalogue

Tables to be filled in per stage. Each row keeps the format:

```
| Hex | Name | Payload | Response | Verified Stage | Notes |
|---|---|---|---|---|---|
```

### 3.1 Connect-time

(Stage 1 verifies)

| Hex | Name | Payload | Response | Verified | Notes |
|---|---|---|---|---|---|
| `0x01` | `SET_DEVICE_TIME` | 7 bytes BCD `[yy-2000, MM, dd, hh, mm, ss, lang]` | `01 <9 bytes capability echo>` | ☐ | Required for history-read timestamps |
| `0x3C` | `DEVICE_FUNCTION_SUPPORT` | (empty) | `3C <9 bytes capability bitmap>` | ☐ | Bitmap decoded in §6 |

### 3.2 One-shot active queries

(Stage 2 verifies)

| Hex | Name | Payload | Response | Verified | Notes |
|---|---|---|---|---|---|
| `0x48` | `GET_STEP_TODAY` | (empty) | `48 <14 bytes BE [steps, run-steps, cal, dist_m, dur_min]>` | ☐ | Authoritative today-totals |
| `0x50 AA AA` | `ANTI_LOST_RATE` | `[0xAA, 0xAA]` | (vibration + LED) | ☐ | "Find device" |
| `0x08 01` | `RE_BOOT` | `[0x01]` | (disconnect+reconnect) | ☐ | Soft reboot |

### 3.3 Battery + identity

(Stage 1 verifies battery; firmware-version opcode TBD)

| Hex | Name | Payload | Response | Verified | Notes |
|---|---|---|---|---|---|
| `0x03` | `GET_BATTERY` | (empty) | `03 <level%> <isCharging>` | ☐ | <fill in whether `isCharging` is present on R08> |
| `0x??` | `GET_FIRMWARE_VERSION` | TBD | TBD | ☐ | <find via passive observation or further decompile> |

### 3.4 Real-time vitals (Stage 5 verifies)

| Hex | Name | Payload | Response | Verified | Notes |
|---|---|---|---|---|---|
| `0x69 <kind> 01` | `START_VITALS` | `[kind, 1]` | `69 <kind> <err> <value>` repeating every <verify ms> | ☐ | kind: 1=HR, 2=BP, 3=SpO2, 4=Fatigue, 5=HealthCheck, 6=RealtimeHR, 7=ECG, 8=Pressure, 9=BloodSugar, 10=HRV, 11=Temp |
| `0x6A <kind> <last>` | `STOP_VITALS` | `[kind, last_value, opt]` | (none / 6A ACK) | ☐ | Auto-stop after <verify> seconds; otherwise must be explicit |

Observed timing on R08:
- First reply latency: ☐ ms
- Tick cadence: ☐ ms (QRing claims 500 ms)
- Total stream duration before auto-stop: ☐ s (QRing claims 25 s)
- `errCode = 1` semantics: ☐ "not worn" / ☐ other

### 3.5 Auto-monitor cadence (Stage 6 verifies)

The ring is the cadence master. Phone configures intervals via these write commands; the ring
measures internally and emits `0x73 <subtype>` sync triggers when a new record is ready.

| Hex | Name | Read payload | Write payload | Verified |
|---|---|---|---|---|
| `0x16` | `HR_TIMING_SETTINGS` | `{1}` | `{2, enable, intervalMin, startHr, low, high, mainSwitch}` | ☐ |
| `0x2C` | `SPO2_AUTO` | `{1}` | `{2, enable[, intervalMin]}` | ☐ |
| `0x36` | `STRESS_AUTO` | `{1}` | `{2, enable}` | ☐ |
| `0x38` | `HRV_AUTO` | `{1}` | `{2, enable}` | ☐ |

### 3.6 History reads (Stage 8 verifies)

Multi-packet streams. Terminator: `<cmd> FF` (no data) or `<cmd> <pktCount-1>` (last record).

| Hex | Name | Read payload | Header | Per-record | Verified |
|---|---|---|---|---|---|
| `0x15` | `HR_HISTORY` | 4-byte LE midnight unix-time | `15 00 <pktCount> <bin-min>` | 9 samples (pkt 1) / 13 samples (pkts 2..N) | ☐ |
| `0x39` | `HRV_HISTORY` | 1 byte day index | `39 00 <pktCount> <bin-min>` | 13 samples / pkt | ☐ |
| `0x37` | `STRESS_HISTORY` | 4-byte LE time + `[0, 50]` | identical to HRV | 13 samples / pkt | ☐ |
| `0x43` | `STEP_HISTORY` | `[dayOff, 0x0F, segLo, segHi, 0x01]` | (per-record header) | 1 record / pkt, 15-min bins | ☐ |
| `0x44` | `SLEEP_HISTORY` | `[dayOff, segLo, segHi]` | `44 F0 …` init | Q-staged records | ☐ |

### 3.7 R08-specific touch + gesture path

(Stage 4 verifies — the only path unique to R08)

| Hex | Name | Payload | Response | Verified | Notes |
|---|---|---|---|---|---|
| `0x3B 01 00 01 01` | `TOUCH_ENABLE` | `[01, 00, 01, 01]` | `73 2A 00` echo | ☐ | Required after enabling notifications; ring won't report gestures otherwise |
| `0x3B 02 00 09 01` | `TOUCH_MODE` | `[02, 00, 09, 01]` | (none?) | ☐ | Send ~500 ms after `TOUCH_ENABLE` |
| `0x3B 01 00 01 00` | `TOUCH_DISABLE` | `[01, 00, 01, 00]` | (none?) | ☐ | Powers down touch IC |

### 3.8 Contested opcodes (Stage 9 verifies)

| Hex | Heritage claim | QRing claim | R08 actual | Verified |
|---|---|---|---|---|
| `0x06` | `FIND_DEVICE` (LED 10 s) | `MUTE` (DnD) | ☐ | ☐ |
| `0x10` | `BLINK_TWICE` | `BIND_SUCCESS` (silent ACK) | ☐ | ☐ |
| `0x0F` | `SHUTDOWN` | `TO_OTA` (firmware-flasher mode) | **🛑 NEVER TESTED** | **N/A — DO NOT TEST** |

> **About `0x0F`**: phase-0 deliberately did not test this byte because no known-good R08
> firmware backup exists. If QRing's interpretation is correct, sending `0x0F` puts the ring
> into OTA bootloader mode with no recovery path. The opcode is documented here for
> completeness but should be treated as **never send** until either (a) an R08 firmware backup
> is published, or (b) sniffing the QRing app on real R08 traffic confirms what it actually
> sends. Until then, "do not send `0x0F` to an R08" is the only safe rule.

## 4. Notify frame catalogue

### 4.1 `0x73 <sub>` sync triggers

(Stage 3 verifies which subset R08 emits)

| `data[1]` | QRing name | Decode | R08 emits? | Triggered by |
|---|---|---|---|---|
| `0x01` | NEW_HR_RECORD | sync trigger | ☐ | `0x16` auto-monitor |
| `0x04` | NEW_STEP_DETAIL | sync trigger | ☐ | hourly / step-increment |
| `0x0C` | BATTERY_LOW | sync trigger → re-query 0x03 | ☐ | battery drops below threshold |
| `0x10` | TARGET_REACHED | sync trigger → re-read 0x21 | ☐ | daily step goal met |
| `0x11` | STEP_INCREMENT | `data[2]` = inc count | ☐ | each step batch |
| `0x12` | ACTIVITY_TOTAL | (sync hint; canonical via 0x48) | ☐ | activity update |
| `0x2B` | NEW_HRV | sync trigger | ☐ | auto-HRV measurement |
| `0x2C` | NEW_STRESS | sync trigger | ☐ | auto-stress measurement |
| `0x30` | LOVER_DOUBLE_TAP | (no payload) | ☐ | **R08-specific** — fires on a physical double-tap of touch IC? |
| `0x31` | CURRENT_HR_PUSH | `(sub-byte+1)` = bpm | ☐ | reminder dialogue trigger |
| `0x34` | ALARM_RING | (app dialogue) | ☐ | alarm fires |
| `0x3D` | TEMP_ALARM | `((data[2]<<8)\|data[1])/10.0` °C | ☐ | temp out of range |
| `0x3E` | G_SENSOR_STILL_TICK | (no payload) | ☐ | **ring stationary** — wear-state proxy |
| `0x3F` | ECG_CONNECT_STATE | `data[1]` = state | ☐ | ECG mode connect |

### 4.2 R08-specific (`73 2D` gestures + `73 2A` touch + `73 12` activity)

(Stage 4 verifies)

| Frame | Length | Decode | R08 verified? |
|---|---|---|---|
| `73 2D 01` | 3 | swipe forward | ☐ |
| `73 2D 02` | 3 | swipe backward | ☐ |
| `73 2D 03` | 3 | single touch | ☐ |
| `73 2D 04` | 3 | long press | ☐ |
| `73 2A 00` | 3 | touch IC enabled | ☐ |
| `73 12 …` | ≥11 | `[2..4]` steps BE, `[5..7]` cal BE / 1000, `[8..10]` dist BE / 1000 | ☐ |

### 4.3 Other prefixes

| `data[0]` | Length | Decode | Verified |
|---|---|---|---|
| `0x03` | ≥2 | `[1]` = battery %, `[2]` = isCharging | ☐ |
| `0x48` | ≥14 | today totals (see §3.2) | ☐ |
| `0x69` | ≥4 | real-time vitals tick (see §3.4) | ☐ |
| `0x51` | ≥3 | step-only LE-16 push (R08-specific?) | ☐ |
| `0xA1` | 16 fixed | accelerometer raw — see §5 | ☐ |

## 5. Accelerometer `0xA1` encoding

(Stage 7 verifies — currently undecoded by all public sources)

Once Stage 7 returns the byte layout, fill in:

```
Frame:     A1 <reserved?> <X_lo> <X_hi> <Y_lo> <Y_hi> <Z_lo> <Z_hi> <padding...>
                  (or whatever order Stage 7 determines)

Encoding:  signed int16 LE / BE — ☐ (Stage 7 picks)
Scale:     1 LSB = ☐ g  (STK8321 14-bit at ±2g → ~0.5 mg/LSB)
Rate:      ~☐ Hz  (Stage 7 measures inter-frame delta)
Reserved bytes: ☐
```

## 6. Capability bitmap (`0x3C` response)

(Stage 1 verifies)

QRing's `DeviceSupportFunctionRsp.java:60-136` exposes 28+ feature flags via the 9 bytes. Fill in
the bit positions seen on R08:

```
Byte 0: ☐
Byte 1: ☐
...
Byte 8: ☐

Decoded flags (after Stage 1):
  supportTouch:        ☐
  supportGesture:      ☐
  supportRealTimeHr:   ☐
  supportSpo2:         ☐
  supportStress:       ☐
  supportHrv:          ☐
  supportEcg:          ☐
  supportSleep:        ☐
  ...
```

## 7. Connection lifecycle (verified recipe)

(Final output of phase-0)

```
connectGatt(autoConnect = true)
on STATE_CONNECTED → discoverServices()
on services discovered →
  setCharacteristicNotification(NOTIFY_CHAR, true)
  writeDescriptor(CCCD, ENABLE_NOTIFICATION_VALUE)
+800 ms → write 0x01 SetTime
+150 ms → write 0x3C DeviceFunctionSupport
+await 0x3C response →
+writes (only those §6 capability says are supported):
        0x3B TOUCH_ENABLE
        0x3B TOUCH_MODE  (+500 ms after)
        0x03 BATTERY_QUERY  (+1500 ms after)
ongoing → battery poll every ☐ min (Stage 3 picks)
          conn-interval HIGH on activity, BALANCED idle
          listen for 0x73 sync triggers (§4.1)
```

## 8. Dedup window

(Stage 4 measures)

```
Min observed inter-tap delta: ☐ ms
Counter byte present:         ☐ yes / no
Recommended dedup logic:      ☐ "drop exact-match within Nms" / "drop all within Nms"
                              N = ☐ ms
```

## 9. Errata + corrections to existing community docs

Fill in once verdicts are in:

- atc1441's `Known_BLE_OEM_Ring_Names.txt`: ☐ corrections
- `tahnok/colmi_r02_client/MYSTERIES.md` resolutions:
  - `0x37` is **stress**, not HRV (HRV is `0x39`)
  - `0x0F` is ☐ (Stage 9 verdict)
  - `0x06` is ☐ (Stage 9 verdict)
  - Accelerometer `0xA1` encoding: ☐ (Stage 7 verdict)
- `R08-Dev.md` original handoff doc:
  - `0x06 = FIND_DEVICE`: ☐
  - `0x10 = BLINK_TWICE`: ☐
  - `0x0F = SHUTDOWN`: ☐ (likely wrong; QRing says OTA)

## 10. Power budget reference

(Empirically verified during Stage 5 + 7)

```
Baseline (always-on BLE link, idle):           ~ ☐ mAh / day
Battery poll every 10 min:                     + ☐ mAh / day
Touch IC always-on (touch_enable held):        + ☐ mAh / day
PPG (during 25-s vitals stream):               ~ ☐ mAh per snapshot
                                                = ~ ☐ mAh / day at 1 snapshot per hour
0xA1 accelerometer stream (default rate):      + ☐ mAh / day
Continuous HR (1E channel):                    incompatible with 17 mAh battery
```

## 11. Acknowledgements

- atc1441 — hardware teardown + OTA tooling
- `tahnok/colmi_r02_client` — Python R02-family reference
- 小猪遥控戒指 author — R08 touch-IC path discovery
- QRing app authors — the full Yawell/oudmon SDK (decompiled for protocol reference; not copied)

## 12. License

This spec: CC-BY 4.0.  
phase-0 scripts: MIT (see `Doc/02` LICENSE).
