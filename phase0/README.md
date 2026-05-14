# Phase-0 — R08 ring protocol verification

Three scripts that turn the reverse-engineered protocol catalogue in
[`../Doc/02-hardware-and-protocol.md`](../Doc/02-hardware-and-protocol.md) into "verified ✓ on real
hardware" findings — before we commit any production code to the bytes.

## The three-source picture

Doc/02 §0 explains the confidence tiers. Briefly:

| Source | What it gives us | Confidence on R08 |
|---|---|---|
| **小猪遥控戒指** (`com.ring.r08remote` v2 — `../refs/r08remote-decompiled-v2/`) | The R08-specific touch + battery subset. 4 write commands, 5 notify-frame prefixes. **Confirmed to work on R08 hardware** (third-party ecosystem proves it). | 🟢 HIGH on what it documents; **silent on ~95 % of the protocol**. |
| **QRing** (official Yawell/oudmon app — `../refs/qring-new-version-protocol-2026-05-15.md`) | The broader Colmi-family protocol — ~70 write commands, ~30 `0x73` sync-trigger sub-codes. **Officially authoritative**. | 🟡 MEDIUM — same firmware family as R08, but R08 has firmware-specific paths (touch IC) the broader family doesn't, and some commands may differ on R08. |
| **`tahnok/colmi_r02_client`** (`../research/colmi_r02_client/`) | Community Python implementation. Mostly agrees with QRing. | 🟡 cross-check only. |

The three scripts here cover the matrix:

| Script | Coverage | Status |
|---|---|---|
| `r08_probe.py` | The original 小猪-aligned probe. Confirms the **🟢 R08-confirmed core** — TOUCH_ENABLE/DISABLE/MODE, BATTERY_QUERY, the 4 raw gesture frames, dedup-window measurement. Has an interactive REPL and a guided gesture tutorial. | original |
| `r08_verify_qring.py` | Adjudicates **🟡 QRing-only commands** (`0x3C` capability bitmap, `0x48` today-totals, `0x01` set-time, `0x50 AA AA` find-device, `0x08 01` reboot) and **🔴 contested opcodes** (`0x06`, `0x10`, `0x0F` — what 小猪 names FIND_DEVICE / BLINK_TWICE / SHUTDOWN, but QRing names mute / bind-ACK / OTA-mode). Includes passive observation phase for QRing's `0x73` sync-trigger sub-codes. | added 2026-05-15 |
| `r08_health_probe.py` | Tests the **vitals stream timing** (3 s per kind that our `R08Protocol.kt` currently assumes vs the **25 s per kind** QRing documents), the **`errCode = "not worn"` wear-detect** signal QRing checks, and characterises the **`0xA1` accelerometer** layout via labelled motion patterns. | added 2026-05-15 |

## Quick start

```bash
cd phase0
python3 -m venv .venv && source .venv/bin/activate     # optional but recommended
pip install -r requirements.txt
```

Then run the three scripts in order — each one tells you what to do.

### 1. Core acceptance — `r08_probe.py`

```bash
python3 r08_probe.py                    # auto-scan, init, listen for gestures
python3 r08_probe.py --tutorial         # guided walkthrough of all 12 gestures
python3 r08_probe.py --record taps.csv  # log every frame to CSV for offline analysis
python3 r08_probe.py --interactive      # REPL: enable / disable / battery / blink / shutdown / quit
```

See [`../Doc/11-verification-checklists.md`](../Doc/11-verification-checklists.md) §A1–A8 for what
to tick off (acceptance criteria, dedup window, counter-byte check, etc.).

### 2. Conflict + completeness — `r08_verify_qring.py`

Three phases, ordered safest first:

```bash
python3 r08_verify_qring.py                       # all three phases
python3 r08_verify_qring.py --skip-c              # safe ones only (no contested 0x06/0x10/0x0F)
python3 r08_verify_qring.py --record verify.csv   # log every notify
```

- **Phase A (passive)** — 15 s of pure listening. Does the `03` battery frame carry a charging
  byte? Does the ring emit any `0x73` sub-code beyond the three 小猪 knows? Does `0xA1` show up?
- **Phase B (additive)** — single-write each of `0x3C` / `0x48` / `0x01` / `0x50 AA AA` / `0x08 01`,
  then asks you to grade what happened. Safe: each cmd is one 16-byte write.
- **Phase C (contested)** — sends `0x06`, `0x10`, and (gated behind `YES`) `0x0F`, asking whether
  the ring behaves the way 小猪 thinks or the way QRing thinks. ⚠ `0x0F` may put the ring into OTA
  bootloader mode — recovery via the web flasher at
  https://atc1441.github.io/ATC_RF03_Writer.html. **You can skip it.**

The script prints a final verdict block ready to paste back into Doc/02 §3.

### 3. Vitals timing + accelerometer — `r08_health_probe.py`

```bash
# Vitals stream timing — does R08 do 3 s (our doc) or 25 s (QRing) per measurement kind?
python3 r08_health_probe.py --measure hr
python3 r08_health_probe.py --measure spo2
python3 r08_health_probe.py --measure stress

# QRing's "not worn" detection via errCode = 1
python3 r08_health_probe.py --measure hr --wear-test   # take the ring off mid-stream

# Accelerometer characterisation — what's the 0xA1 byte layout?
python3 r08_health_probe.py --accel still       # noise floor
python3 r08_health_probe.py --accel rotate-x    # one axis at a time
python3 r08_health_probe.py --accel rotate-y
python3 r08_health_probe.py --accel rotate-z
python3 r08_health_probe.py --accel tap         # does 0xA1 spike alongside 73 2D 03?
python3 r08_health_probe.py --accel free --record accel-free.csv
```

The script auto-tabulates per-byte variance during each accel pattern so the active axis is easy
to spot. **Power warning**: a 25-s vitals stream has the PPG LED on continuously. Don't loop
`--measure` unattended.

## What this stack answers

Once these scripts have all been run on a real R08, every 🟡 / 🔴 / 🔵 tag in
[`../Doc/02`](../Doc/02-hardware-and-protocol.md) should resolve to ✓ or ✗. The next audit pass
then updates [`../app-project/core/.../ble/R08Protocol.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/ble/R08Protocol.kt)
to reflect what the firmware actually honours.

The biggest open questions (priority order):

1. Does `0x69 <kind> 01` stream for 25 s or 3 s? Wrong duration → either missing data or wasted
   PPG time (15+× more battery cost than necessary).
2. Does the ring carry the `errCode = 1` wear-detect QRing checks? If yes, we get a no-cost wear
   signal that lets the auto-snapshot loop skip itself when off-finger.
3. What does `0xA1` actually encode? If decodable, phase-3 spatial mode unlocks.
4. Does `0x0F` shutdown the ring (R08-Remote interpretation) or OTA-brick it (QRing
   interpretation)? Critical because production code's "Shutdown" button writes 0x0F.
5. What's the actual find-device pathway? Both 小猪's `0x06` and QRing's `0x50 AA AA` need testing.

Paste the script output back to the project as we go and we'll update `R08Protocol.kt` together.

## Cross-references

| Question | Where to read |
|---|---|
| Why these three sources? | [`../Doc/02-hardware-and-protocol.md`](../Doc/02-hardware-and-protocol.md) §0 |
| What does each phase-0 test resolve? | [`../Doc/11-verification-checklists.md`](../Doc/11-verification-checklists.md) §A |
| Sensor matrix + module breakdown | [`../Doc/07-sensors-and-modules.md`](../Doc/07-sensors-and-modules.md) |
| Power/latency budget | [`../Doc/06-performance-and-power.md`](../Doc/06-performance-and-power.md) |
| QRing extraction report (raw findings) | [`../refs/qring-new-version-protocol-2026-05-15.md`](../refs/qring-new-version-protocol-2026-05-15.md) |
| 小猪 v2 source tree | [`../refs/r08remote-decompiled-v2/`](../refs/r08remote-decompiled-v2/) |
| Community Python client | [`../research/colmi_r02_client/`](../research/colmi_r02_client/) |
