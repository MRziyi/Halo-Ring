# 02 — Ring Hardware & BLE Protocol

The QRing R08 smart ring is the input device. This doc is the working spec for its BLE protocol.
**Everything here is reverse-engineered**; phase-0 (a 10-stage hardware-in-hand validation pass
run in the private R08-dev research workspace) turns every claim into a "✓ verified on R08
firmware" or "✗ contradicted, here's what it actually does". The verified bytes get published
back into this document.

## 0. Source-of-truth hierarchy (revised 2026-05-15)

Two reverse-engineered sources matter for this project; one prior source is being phased out:

| Source | What it gives us | Confidence on R08 | Role |
|---|---|---|---|
| **QRing official** (`com.qcwireless.smart` v3.x — Yawell/oudmon SDK) | Complete protocol — ~70 write commands, ~30 `0x73` sub-codes, full request/response builder catalogue. The Yawell SDK *is* the authoritative implementation; QRing is its consumer. | 🟡 Medium. R08 shares the BlueX RF03 firmware family with the rest of the Colmi ring lineup; QRing's protocol almost certainly applies in full, but R08 may differ on the touch-IC-specific path (which QRing doesn't expose). | **Primary** — phase-0 verifies QRing first, end-to-end. |
| **小猪遥控戒指** (`com.ring.r08remote` v2) | Touch-IC path + 4 raw gesture frames. **The only known third-party app that drives the R08 touch ring as input.** | 🟢 High *for the 7 bytes it touches* (4 write cmds + 5 notify-frame interpretations) — confirmed working on real R08 by the existence of the app. But the developer may have got there by trial-and-error, not by understanding. | **Cross-check + R08-specific addendum** — phase-0 verifies after QRing, to catch where 小猪 differs from QRing's interpretation. |
| ~~R08-Dev.md heritage~~ | The original handoff doc claimed `0x06 = find-device`, `0x10 = blink-twice`, `0x0F = shutdown`. **None of these are backed by either decompile** — they're inherited speculation that ended up in our `R08Protocol.kt`. | 🔴 Speculation. | **Phasing out** — phase-0 Stage 9 will judge each, expected outcome is "wrong → delete". |
| ~~`tahnok/colmi_r02_client`~~ | Community Python implementation, same source corpus as QRing. | — | **Reference only** — interesting for the R02 cross-family check, but not load-bearing for our protocol decisions. |

Confidence tags used below: 🟢 R08-verified · 🟡 QRing-only (phase-0 pending) · 🔴 speculation
(scheduled for deletion) · ⚫ undecoded by anyone.

**Phase-0 verification is the gate** — see [Doc/16](16-phase0-test-plan.md) for the 10-stage flow.
Whenever phase-0 returns a verdict, [`../app-project/core/.../ble/R08Protocol.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/ble/R08Protocol.kt)
gets updated and the corresponding 🟡 / 🔴 tag in this doc resolves to ✓ / ✗.

After phase-0 closes, we'll publish a clean Colmi-family protocol spec as [Doc/17 community
contribution](17-community-protocol-spec.md) for upstream / atc1441 / colmi_r02_client to pull
from.

## 1. Hardware identity

| Property | Value | Source |
|---|---|---|
| Product | **QRing R08** smart ring | Marketing |
| FCC ID | `2AOM3-R08` | https://fccid.io/2AOM3-R08 |
| Registered to | Shenzhen YaWell intelligent Technology (Yawell) | FCC |
| Distributed as | Yawell OEM = Colmi (largest rebrand) + Hugrow, Hyper, ~dozens of smaller rebrands; all share PCB + firmware | atc1441 community research |
| Companion app | **QRing** (App Store / Google Play) | Marketing |

Hardware specs (inferred from the shared Colmi BOM):

| Component | Part | Notes |
|---|---|---|
| Main SoC | **BlueX Micro RF03** | ARM Cortex-M0, 200 KB RAM, 512 KB Flash, BLE 5.0. Datasheet in `../research/ATC_RF03_Ring/`. |
| Accelerometer | **STK8321** (Sitronix) | 3-axis, ±2/4/8/16 g, 14-bit, I²C. Datasheet in `../research/ATC_RF03_Ring/`. |
| PPG sensor | Vcare VC30F | Optical heart rate + SpO2. The 25-s vitals stream burns this. |
| Touch IC | Unknown capacitive controller | **R08-specific** — the broader Colmi BOM doesn't have it. Drives the `73 2D` gesture path. |
| Battery | LiPo 17 mAh | ~5–7 days in event mode; <1 day with always-on raw IMU |
| Charging | Magnetic-contact cradle, USB-C | ~60–90 min full charge. BLE is **off** while charging. |
| Water resistance | IP68 / 5 ATM | 50 m water depth |
| LED | Green (single colour) | Controllable via BLE — opcode pending phase-0 Stage 9 |
| Debug | SWD (P00 = SWCK, P01 = SWD) | Internal solder pads, need to crack the ring open |
| OTA | BLE, **unsigned**, **unencrypted** | Browser-based flasher at https://atc1441.github.io/ATC_RF03_Writer.html |

⚠ Touch IC is the **only** hardware differentiator between R08 and the rest of the Colmi family.
That's why QRing's decompile lacks gesture-frame parsing — QRing targets ringless / touch-less
models too — and why 小猪 is the only third-party app speaking the `73 2D` gesture frame.

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

🟢 Confirmed by both QRing (`Constants.java` L100-102) and 小猪 v2 (`ProtocolConstants.java` L87-99).
**✓ Verified on real R08 hardware 2026-05-26** (phase-0 Stage 0): advertised name `R08_E600`,
service UUID, write characteristic, notify characteristic, and CCCD descriptor are all present
at the expected UUIDs on the first connect.

Nordic-style "UART over BLE" service layout. No encryption, no pairing key — anyone can connect;
multi-user defence is MAC whitelist on the central. One central at a time. Charging kills BLE.

**Idle baseline finding (Stage 0)**: ✓ the ring emits **zero notify frames** in the 10 s after
subscription without any writes. No spontaneous accelerometer push, no spontaneous battery push,
no `0x73 <sub>` sync triggers. Out of the box the ring is in "quiet" mode — every reporting
stream (gestures, vitals, auto-monitor sync) has to be explicitly enabled before the ring sends
anything. **Good for our power budget**: the BLE-link cost is the only idle baseline draw; we
don't have to dedup or discard a default-on stream.

**macOS BLE quirk (phase-0 setup note)**: CoreBluetooth's default service discovery walks every
characteristic + descriptor on every service the peripheral advertises (GAP, GATT-generic, etc.),
which takes 1-2 s and trips R08's idle disconnect during the long discovery — `bleak.exc.BleakError:
disconnected` mid-`discover_descriptors`. **Workaround**: construct the BleakClient with
`services=[SERVICE_UUID]` so only `6e40fff0-…` is scanned. The phase-0 scripts wrap this in
`r08_lib.make_client(addr)`; production Android code uses `discoverServices()` which doesn't
have the same issue.

## 3. Frame format (write commands)

🟢 All write commands are **16 bytes fixed**: `[0] = command code`, `[1..14] = payload (zero-
padded)`, `[15] = checksum = sum(bytes[0..14]) & 0xFF`. Confirmed by both decompiles.

⚫ **Response frames carry a high-bit error flag**: `data[0] & 0x80` set = error. QRing's
`QCDataParser.parserAndDispatchNotifyData` (Constants.java L112) masks this before dispatch. 小猪
ignores it (which is why 小猪 sometimes silently mis-decodes errors as `Unknown` frames).
Phase-0 Stage 0 confirms whether R08 sets the high bit on error responses.

## 4. QRing-discovered commands (🟡 primary protocol; phase-0 verifies)

The full list lives in [`../refs/qring-new-version-protocol-2026-05-15.md`](../refs/qring-new-version-protocol-2026-05-15.md);
below are the ones Halo Ring would actually use, ordered by phase-0 stage.

### 4.1 Connect-time recipe (Stage 1)

🟡 QRing writes these once after every (re)connect:

| Hex | Name | Payload | Purpose | Verifier |
|---|---|---|---|---|
| `0x01` | `CMD_SET_DEVICE_TIME` | 7 bytes BCD `[yy-2000, MM, dd, hh, mm, ss, lang]` | Sync RTC. Required for history reads to have meaningful timestamps. | [`r08_01_qring_connect.py`](02-hardware-and-protocol.md) |
| `0x3C` | `CMD_DEVICE_FUNCTION_SUPPORT` | (read-only) | 9-byte capability bitmap. Gates which features are available on this firmware. | `r08_01_qring_connect.py` |

After the response from `0x3C`, we'd know which of the optional commands below are even meaningful
to send.

### 4.2 One-shot active queries (Stage 2)

🟡 Three single-write commands with response notifications:

| Hex | Name | Response | Purpose | Verifier |
|---|---|---|---|---|
| `0x48` | `CMD_GET_STEP_TODAY` | 14 bytes BE: `[steps, running-steps, calories, distance(m), duration(min)]` | Canonical today's totals query (more authoritative than waiting for the `73 12` push hint). | [`r08_02_qring_oneshot.py`](02-hardware-and-protocol.md) |
| `0x50 AA AA` | `CMD_ANTI_LOST_RATE` | (vibration + LED) | QRing's "find device" — should vibrate + LED-blink the ring. | `r08_02_qring_oneshot.py` |
| `0x08 01` | `CMD_RE_BOOT` | (disconnect+reconnect) | Soft reboot. | `r08_02_qring_oneshot.py` (gated) |

### 4.3 Passive sync triggers — the `0x73 <sub>` namespace (Stage 3)

🟡 QRing's `HealthyFragment.java:367-705` dispatches on `data[1]` for sync-trigger frames the ring
pushes spontaneously. R08 emits a subset of these depending on firmware + user settings — phase-0
Stage 3 (60 s × 3 conditions of passive observation) catalogues which subset.

| `data[1]` | Meaning | Decode | Importance to Halo Ring |
|---|---|---|---|
| `0x01` | New manual-HR record | (pull HR history via `0x15`) | Low |
| `0x04` | New step detail | (pull step history via `0x43`) | Low |
| `0x0C` (12) | **Battery low warning** | (auto-query battery via `0x03`) | High — replaces polling with push |
| `0x10` (16) | Daily target reached | (re-read goal via `0x21`) | Low |
| `0x11` (17) | Step increment | `data[2]` = increment | Medium — finer than `73 12` |
| `0x12` (18) | Activity total sync hint | (re-query via `0x48` for authoritative) | Confirmed via 小猪; also re-listed here for completeness |
| `0x2B` (43) | New HRV record | (pull HRV via `0x39`) | Low |
| `0x2C` (44) | New stress record | (pull via `0x37`) | Low |
| `0x30` (48) | **"Lover double-tap"** | (no payload) | **HIGH** — if R08 emits this on a physical double-tap, the app-side combo window (~280 ms latency) becomes redundant for that one gesture |
| `0x31` (49) | "Current HR is X" reminder | `data[1] (sub-byte +1)` = bpm | Medium — passive HR readout without burning PPG |
| `0x34` (52) | Alarm-ring event | (app shows alarm dialog) | Low |
| `0x3D` (61) | Temperature alarm | `((data[2]<<8)\|data[1])/10.0` °C | Low |
| `0x3E` (62) | **G-sensor still-time tick** | (no payload?) | **HIGH** — likely "ring not moving" signal → use as wear-state / power-gate input |
| `0x3F` (63) | ECG connect state | `data[1]` = state | Skip (no ECG UI) |

### 4.4 Real-time vitals (Stage 5)

🟡 Universal `0x69 / 0x6A` protocol per QRing `StartHeartRateReq.java` + `HeartActivity.java:415-481`.
All measurement screens use **25 s** countdown with 500 ms tick — not 3 s as
[`R08Protocol.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/ble/R08Protocol.kt)
currently assumes.

| Hex | Action | Payload |
|---|---|---|
| `0x69 <kind> 01` | Start streaming | kind: 1=HR, 2=BP, 3=SpO2, 4=Fatigue, 5=HealthCheck, 7=ECG, 8=Pressure/stress, 9=BloodSugar, 10=HRV, 11=Temp. (Action constants: 1=START, 2=PAUSE, 3=CONTINUE, 4=STOP.) |
| `0x6A <kind> <last> <opt>` | Stop streaming | `last` = last sampled value (for HR/SpO2/stress); for BP, `(sbp, dbp)` |

🟡 Response frame `0x69 <kind> <err> <value>`:
- `err = 0` → reading is valid; `data[3]` = bpm / % / index
- `err = 1` → **"not worn properly"** → free wear-detection signal. Halo Ring can opportunistically
  flip its WearStateProvider to off-finger when this fires mid-stream. Tested by
  [`r08_05_vitals.py --wear-test`](02-hardware-and-protocol.md).

⚠ Power: 25 s of PPG LED ≈ 0.02 mAh per snapshot. Sustainable ≤ 1 / hour. Continuous would dead the
17 mAh battery in hours. See [06 §3.4](06-performance-and-power.md).

### 4.5 Auto-monitor cadence settings (Stage 6)

🟡 **The ring is the cadence master**, not the phone. We tell it "measure HR every 30 min" via
`0x16`; the ring measures internally, stores the result, and emits a `73 01` sync trigger to tell
the phone to come read.

| Hex | Subject | Read payload | Write payload | Source |
|---|---|---|---|---|
| `0x16` | HR auto-monitor | `{1}` | `{2, enable, intervalMin, startHr, low, high, mainSwitch}` | `HeartRateSettingReq.java` |
| `0x2C` | SpO2 auto-monitor | `{1}` | `{2, enable[, intervalMin]}` | `BloodOxygenSettingReq.java` |
| `0x36` | Stress auto-monitor | `{1}` | `{2, enable}` | `PressureSettingReq.java` |
| `0x38` | HRV auto-monitor | `{1}` | `{2, enable}` | `HrvSettingReq.java` |

### 4.6 History reads (Stage 8)

🟡 Multi-packet streams. First response packet (`<cmd> 00 <pktCount> <range>`) is the header,
subsequent are data, terminator is `<cmd> FF` (no data) or `<cmd> <pktCount-1>` (final).

| Hex | Subject | Payload | Records | Source |
|---|---|---|---|---|
| `0x15` | HR history | 4-byte LE midnight unix-time | 288 samples/day (5-min bins) → ~24 packets | `ReadHeartRateReq.java`, `ReadHeartRateRsp.java:17-52` |
| `0x39` | HRV history | 1 byte day index | 13 samples/packet, 30-min bins | `HRVRsp.java:13-50` |
| `0x37` | Stress history | 4-byte LE time + `[0, 50]` | identical shape to HRV | `PressureRsp.java:13-50` |
| `0x43` | Step history | `[dayOff, 0x0F, segLo, segHi, 0x01]` | 96 records/day (15-min bins) | `ReadDetailSportDataRsp.java:9-43` |
| `0x44` | Sleep history | `[dayOff, segLo, segHi]` | Q-staged sleep records | `ReadSleepDetailsRsp.java:8-37` |

## 5. 小猪-cross-check commands (🟢 R08-specific; small subset)

These are the 4 write commands that 小猪 v2 implements (`ProtocolConstants.java` L100-103).
Confirmed working on R08 by the existence of the 小猪 app. **But** 小猪's developer reverse-
engineered an even older 2022-era QRing, so the byte interpretations may be coincidentally correct
rather than authoritatively so. Phase-0 Stage 4 verifies each on R08 firmware against QRing's
broader naming.

| Name | Hex (16 bytes) | 小猪 source | Cross-check vs QRing |
|---|---|---|---|
| `TOUCH_ENABLE` | `3B 01 00 01 01 00*10 3E` | `ProtocolConstants.java:100` | QRing has `0x3B CMD_DEVICE_TOUCH` (`TouchControlReq.java`) but with a different payload schema — `{02, mode, appType, strength}`. **Phase-0 must check whether R08 even responds to QRing's schema**, or whether 小猪's R08-specific framing is mandatory. |
| `TOUCH_MODE` | `3B 02 00 09 01 00*10 47` | `ProtocolConstants.java:102` | Likely R08-specific; QRing does not have this byte sequence. |
| `TOUCH_DISABLE` | `3B 01 00 01 00 00*10 3D` | `ProtocolConstants.java:101` | Mirror of TOUCH_ENABLE. |
| `BATTERY_QUERY` | `03 00*14 03` | `ProtocolConstants.java:103` | 🟢 confirmed both sources. QRing parses response as `03 <level> <charging>`; 小猪 only reads `<level>`. Phase-0 Stage 1 confirms whether the charging byte is present. |

### 5.1 Notify frames 小猪 decodes (🟢; cross-checked with QRing)

These five prefixes are what 小猪's `DataParser.java` actively decodes.

| `data[0]` | Length | 小猪 interpretation | QRing interpretation | R08 verdict |
|---|---|---|---|---|
| `0x73 0x2A` | ≥3 | TouchStatus: `data[2]==0` → enabled | **Not in QRing's `0x73` sub-code list** — R08-specific to the touch IC | Trust 小猪; phase-0 Stage 4 confirms |
| `0x73 0x2D` | ≥3 | Gesture: `data[2]` = swipe-up(1) / swipe-down(2) / touch(3) / long-press(4) | **Not in QRing's list** | Trust 小猪; phase-0 Stage 4 confirms |
| `0x73 0x12` | ≥11 | Activity: steps[2..4] BE, calories[5..7] BE /1000, distance[8..10] BE /1000 | Same prefix, but QRing treats it as a sync-trigger only; canonical totals come from `0x48` | Trust 小猪 for the byte layout, but prefer `0x48` for queries |
| `0x03` | ≥2 | Battery: `data[1]` = % | QRing adds: `data[2]` = isCharging | Trust QRing's extension; phase-0 Stage 1 confirms |
| `0x69` | ≥4 | Health: `data[3]` = value (kind in `data[1]`) | QRing adds: `data[2]` = errCode (1 = "not worn") | Trust QRing's extension; phase-0 Stage 5 confirms |
| `0x51` | ≥3 | Steps-only LE-16: `data[1] \| (data[2]<<8)` | QRing repurposes `0x51` as `CMD_LOVER_EVENT` | Trust 小猪 for R08 (R08 emits this as steps-only; QRing's interpretation is for a different model's firmware) |
| `0xA1` | 16 fixed | Accelerometer raw — **reads but does not decode** (`DataParser.java:58-69`) | QRing also doesn't decode | ⚫ unknown by anyone publicly. Phase-0 Stage 7 characterises. |

### 5.2 Timing constants from 小猪 (cross-check)

- `BATTERY_QUERY_INTERVAL = 600_000 ms` (10 min) — `ProtocolConstants.java:18`. Our code uses 30 min;
  phase-0 Stage 3 passive observation will tell us if the ring runs out of fresh battery readings
  with the longer interval.
- `DEDUP_INTERVAL = 100 ms` — `ProtocolConstants.java:25`. Phase-0 Stage 4 measures the actual
  inter-tap floor and tightens / loosens.
- `MULTI_TAP_TIMEOUT = 400 ms` — `ProtocolConstants.java:36`. Our gesture synthesiser uses 280 ms;
  worth measuring real human distribution to choose properly.

## 6. Contested opcodes (🔴; phase-0 Stage 9 judges)

These three live in our `R08Protocol.kt` but appear in **neither** 小猪 nor QRing source. Inherited
from the original R08-Dev.md handoff doc with no extant backing.

| Hex | R08-Dev.md heritage | QRing's name | Risk | Status |
|---|---|---|---|---|
| `0x06` | `FIND_DEVICE` (blink LED ~10 s) | `CMD_MUTE` (DnD) | Low — recoverable | ☐ phase-0 Stage 9 tests via `r08_09_contested.py --probe 0x06` |
| `0x10` | `BLINK_TWICE` (quick 2-blink) | `CMD_BIND_SUCCESS` (silent ACK) | Low — recoverable | ☐ phase-0 Stage 9 tests via `r08_09_contested.py --probe 0x10` |
| `0x0F` | `SHUTDOWN` (power off) | `TO_OTA` (firmware-flasher mode) | 🛑 **DO NOT TEST** | **No known-good R08 firmware backup exists.** If QRing's interpretation is correct, sending `0x0F` puts the ring into OTA bootloader and we have nothing to flash back. Stage 9 deliberately does NOT include a `0x0F` probe. The opcode stays out of `R08Protocol.kt` regardless. |

`0x06` and `0x10` are the only two contested opcodes phase-0 verifies. `0x0F` is treated as
permanently-unresolved-by-design until either (a) a working R08-firmware OTA `.bin` is published,
or (b) we sniff QRing-app traffic and observe what it sends — neither path is in scope right now.

The phase-0 verdict for `0x06` / `0x10` is the final word; the heritage column gets deleted
regardless of outcome.

## 7. Connection lifecycle (post-verification target)

Once phase-0 confirms which commands R08 honours, the connect recipe becomes:

```
connectGatt(autoConnect = true)
on STATE_CONNECTED → discoverServices()
on services discovered →
  setCharacteristicNotification(NOTIFY_CHAR, true)
  writeDescriptor(CCCD, ENABLE_NOTIFICATION_VALUE)

+800 ms (settle) →
  writeCharacteristic(0x01 SetTime)             // 🟡 confirm Stage 1
+150 ms →
  writeCharacteristic(0x3C DeviceFunctionSupport) // 🟡 confirm Stage 1
+300 ms (await capability response) →
  writeCharacteristic(0x3B TOUCH_ENABLE)        // 🟢 R08-specific
+500 ms →
  writeCharacteristic(0x3B TOUCH_MODE)          // 🟢
+1500 ms →
  writeCharacteristic(0x03 BATTERY_QUERY)       // 🟢
  startBatteryPoll(every 10 min per 小猪 / 30 min current code — phase-0 Stage 3 picks)
  startRssiPoll(every 5 s)
  request CONNECTION_PRIORITY_HIGH (~15-30 ms interval) → relax after 10 s idle
```

Disconnect: rely on `autoConnect`. App-level continuous scanning is a power killer. On reconnect,
arm `armWakeSwallow()` in the synthesiser (Doc/05 §3.4).

## 8. De-duplication

🟢 Both decompiles agree on `DEDUP_INTERVAL = 100 ms` — but it's a guess. Phase-0 Stage 4 measures
the actual minimum inter-tap interval (~30 fast taps in a row) and the byte-pattern of repeated
frames (any counter byte?), then sets the dedup window to `min_observed − 10 ms` or "drop only on
exact match within ~50 ms" if a counter byte exists.

## 9. Errata against `R08-Dev.md` heritage

Discarded claims (all from the original handoff doc, no source backing):
- ~~`0x06` = find-device~~ → see §6
- ~~`0x10` = blink-twice~~ → see §6
- ~~`0x0F` = shutdown~~ → see §6
- ~~`0x08` = battery query~~ → actually soft-reboot per QRing
- ~~Firmware-recognised double-tap~~ → app-side timing (but `0x73 0x30` may give us a true
  firmware-side double-tap; phase-0 Stage 3 checks)
- ~~Swing/in-air gestures in firmware~~ → no, those are 0xA1 accel push, encoding unknown

## 10. Phase-0 verification map

Every claim above resolves through one of the 10 phase-0 stages. See
[Doc/16 — phase-0 test plan](16-phase0-test-plan.md) for the full stage-by-stage protocol and the
matching scripts in the BLE protocol spec ([`Doc/02`](02-hardware-and-protocol.md)).

| Stage | Resolves | Script |
|---|---|---|
| 0 — Sanity | §2 GATT, §3 frame format error flag | `r08_00_scan.py` |
| 1 — QRing connect recipe | §4.1, §5.1 battery charging byte | `r08_01_qring_connect.py` |
| 2 — QRing one-shots | §4.2 | `r08_02_qring_oneshot.py` |
| 3 — Passive `0x73` | §4.3 sync-trigger fanout | `r08_03_passive.py` |
| 4 — 小猪 touch + gestures | §5 + §8 dedup window | `r08_04_xiaozhu.py` |
| 5 — Vitals stream | §4.4 timing + errCode wear | `r08_05_vitals.py` |
| 6 — Auto-monitor settings | §4.5 ring-cadence-master verification | `r08_06_auto_monitor.py` |
| 7 — Accelerometer | §5.1 `0xA1` layout | `r08_07_accel.py` |
| 8 — History reads | §4.6 | `r08_08_history.py` |
| 9 — Contested opcodes | §6 | `r08_09_contested.py` |

When phase-0 closes, [Doc/17 community protocol spec](17-community-protocol-spec.md) gets filled
in with the verified bytes, ready to ship as a community contribution to atc1441 / colmi_r02_client
upstreams.
