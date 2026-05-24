# Phase-0 — R08 ring protocol verification

10 focused scripts that turn QRing's protocol claims (primary, see
[`../refs/qring-new-version-protocol-2026-05-15.md`](../refs/qring-new-version-protocol-2026-05-15.md))
and 小猪's R08 touch-IC addendum into **verified ✓ / contradicted ✗** findings on real R08
hardware, then output the artefacts to publish back to the community.

Read first:
- [`../Doc/02-hardware-and-protocol.md`](../Doc/02-hardware-and-protocol.md) — the protocol spec
- [`../Doc/16-phase0-test-plan.md`](../Doc/16-phase0-test-plan.md) — the **stage-by-stage execution plan**
- [`../Doc/17-community-protocol-spec.md`](../Doc/17-community-protocol-spec.md) — the publish target

## Source priority (revised 2026-05-15)

1. **QRing official** (`com.qcwireless.smart` — Yawell/oudmon SDK). **Primary.** Authoritative for
   ~70 write commands + ~30 `0x73` sync-trigger sub-codes. R08 likely honours most; phase-0 verifies.
2. **小猪遥控戒指** (`com.ring.r08remote` v2). **R08-specific addendum.** Only useful for the touch-IC
   path (4 write cmds + `73 2D` gestures) that QRing doesn't expose because the broader Colmi
   family lacks the touch IC. The 小猪 developer may have reached working code by trial-and-error
   — we cross-check, don't trust.
3. ~~`R08-Dev.md` heritage~~ — discarded; phase-0 Stage 9 deletes the last three opcodes it
   contributed (`0x06`, `0x10`, `0x0F`).
4. ~~`tahnok/colmi_r02_client`~~ — reference only.

## The 10-stage script catalogue

Each script is self-contained: `python3 r08_NN_*.py [--mac …] [--record …]`. Stage scripts share
[`r08_lib.py`](r08_lib.py) (connection, scan, decode, CSV writer, verdict block printer).

| Stage | Script | Cost | Time | What it resolves |
|---|---|---|---|---|
| 0 | [`r08_00_scan.py`](r08_00_scan.py) | 0 % | 5 min | GATT layout · baseline passive listen |
| 1 | [`r08_01_qring_connect.py`](r08_01_qring_connect.py) | <0.01 % | 10 min | `0x01 SetTime` · `0x3C CapabilityBitmap` · battery `<charging>` byte |
| 2 | [`r08_02_qring_oneshot.py`](r08_02_qring_oneshot.py) | <0.01 % | 5 min | `0x48 today-totals` · `0x50 AA AA find-device` · `0x08 01 reboot` |
| 3 | [`r08_03_passive.py`](r08_03_passive.py) | 0 % | 15 min | Which `0x73 <sub>` sync triggers R08 spontaneously emits (4 windows: rest / wear / motion / double-tap) |
| 4 | [`r08_04_xiaozhu.py`](r08_04_xiaozhu.py) | <0.05 % | 15 min | 小猪's `0x3B TOUCH_*` cmds · 4 raw gestures · dedup window measurement · firmware-side `73 30` double-tap probe |
| 5 | [`r08_05_vitals.py`](r08_05_vitals.py) | ~0.5 % (PPG) | 45 min | `0x69 <kind> 01` start / `0x6A <kind>` stop · 25 s vs 3 s timing · `errCode = 1` wear-detect |
| 6 | [`r08_06_auto_monitor.py`](r08_06_auto_monitor.py) | <0.01 % | 30 min | Auto-monitor `0x16` HR / `0x2C` SpO2 / `0x36` stress / `0x38` HRV · "ring is cadence master" |
| 7 | [`r08_07_accel.py`](r08_07_accel.py) | 0 % | 30 min | `0xA1` accelerometer encoding by motion correlation (8 labelled patterns) |
| 8 | [`r08_08_history.py`](r08_08_history.py) | 0 % | 15 min | Multi-packet history reads: `0x15` HR · `0x39` HRV · `0x37` stress · `0x43` step · `0x44` sleep |
| 9 | [`r08_09_contested.py`](r08_09_contested.py) | <0.01 % | 10 min | What `0x06` / `0x10` / `0x0F` actually do on R08 (gated on `0x0F` for OTA-mode risk) |

Total: ~2–3 h with ~30 % battery used. Run stages in order; stages 5, 7, 8 may be skipped or
re-ordered without affecting earlier results. Stage 9 is optional.

## Quick start

```bash
cd phase0
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# Stage 0 — sanity
python3 r08_00_scan.py

# Stages 1-2 — QRing's everyday recipe
python3 r08_01_qring_connect.py
python3 r08_02_qring_oneshot.py

# Stage 3 — passive sync-trigger catalogue
python3 r08_03_passive.py

# Stage 4 — 小猪 touch path + gestures + dedup
python3 r08_04_xiaozhu.py

# Stage 5 — vitals (5-min PPG rest between back-to-back invocations)
python3 r08_05_vitals.py --measure hr
# wait 5 min
python3 r08_05_vitals.py --measure spo2
# wait 5 min
python3 r08_05_vitals.py --measure stress
# wait 5 min
python3 r08_05_vitals.py --measure hr --wear-test

# Stage 6 — auto-monitor (write + observe pattern)
python3 r08_06_auto_monitor.py --kind hr --set 5 --observe 360   # 6-min observation

# Stage 7 — accelerometer characterisation
python3 r08_07_accel.py --pattern still    --duration 30 --record accel-still.csv
python3 r08_07_accel.py --pattern rotate-x --duration 20 --record accel-rx.csv
python3 r08_07_accel.py --pattern rotate-y --duration 20 --record accel-ry.csv
python3 r08_07_accel.py --pattern rotate-z --duration 20 --record accel-rz.csv
python3 r08_07_accel.py --pattern tap      --duration 30
python3 r08_07_accel.py --pattern shake    --duration 10

# Stage 8 — history reads (multi-packet)
python3 r08_08_history.py --kind hr
python3 r08_08_history.py --kind hrv
python3 r08_08_history.py --kind stress
python3 r08_08_history.py --kind steps
python3 r08_08_history.py --kind sleep

# Stage 9 — contested opcodes (optional; 0x0F is gated YES/SKIP)
python3 r08_09_contested.py --probe 0x06
python3 r08_09_contested.py --probe 0x10
python3 r08_09_contested.py --probe 0x0F     # be careful
```

## Each script's output

Every stage ends with a **verdict block** in this format:

```
========================================================================
VERDICT — paste this back to the project (Doc/02 + Doc/17 updates)
========================================================================

## Stage N — name

### Passive observations
  • …

### Tests
  • 0x… test name                     → QRing ✓     (notes)
  • another test                       → unclear     (skipped)
```

Paste the verdict blocks back to the project to feed [Doc/17 community
spec](../Doc/17-community-protocol-spec.md) and update [Doc/02 §3-§5](../Doc/02-hardware-and-protocol.md).

## What we publish at the end

Once all 10 stages return verdicts, **[Doc/17](../Doc/17-community-protocol-spec.md)** gets filled
in with the verified bytes. Final outputs:

1. **Updated [Doc/02](../Doc/02-hardware-and-protocol.md)** — every 🟡 / 🔴 tag resolved
2. **Updated [`R08Protocol.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/ble/R08Protocol.kt)** — constants reflect verified bytes
3. **[Doc/17 community spec](../Doc/17-community-protocol-spec.md) published**:
   - As a Gist for SEO
   - PR'd to `tahnok/colmi_r02_client/MYSTERIES.md` for the resolutions
   - Optionally cross-linked from atc1441's RF03 repo

The spec is licensed CC-BY 4.0 — the scripts in this dir are MIT (see `LICENSE`). Reuse anywhere
with attribution.

## Troubleshooting

- **macOS BLE permissions denied** — System Settings → Privacy & Security → Bluetooth → check
  Terminal / iTerm / your IDE.
- **Ring won't pair / advertise** — re-cradle 5 min, then open the QRing official app once to
  wake the firmware. Any of our scripts work afterwards.
- **Mid-session disconnect** — ring auto-sleeps after ~5 min idle. Tap once / long-press to wake.
- **Stage 9 `0x0F` puts ring in OTA mode** — flash via https://atc1441.github.io/ATC_RF03_Writer.html
  with `research/ATC_RF03_Ring/OTA_firmwares/R02_3.00.06_240523.bin`. ~10 min total.
- **`BLEAK_LOGGING=1`** prepended to any command turns on bleak's verbose log for low-level BLE
  debug.
