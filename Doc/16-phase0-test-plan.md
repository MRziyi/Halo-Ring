# 16 — Phase-0 Test Plan (Hardware-in-Hand Verification)

> Read alongside [Doc/02 §0](02-hardware-and-protocol.md) (source-of-truth hierarchy) and
> [`phase0/README.md`](../phase0/README.md) (script catalogue). This document is the **execution
> plan** for the testing session — print it, bring it to your desk with the ring, work through it
> top-down.

## 0. The plan in one screen

```
Source order:  QRing (primary)  →  小猪 (R08 touch addendum)  →  publish to community
Heritage docs:  discarded
Total session:  ~2–3 h with the ring on hand (in 4 phases, with PPG rest periods)
Ring battery:   start full; expect ~30 % drain across the full plan
Output:         each stage prints a verdict block → paste back to update Doc/02
                final output → fill in Doc/17 community spec
```

10 stages, each with its own focused script in `../phase0/`. Run in numerical order:

| Stage | Time | Power | Script | What it resolves |
|---|---|---|---|---|
| 0 | 5 min | 0 % | [`r08_00_scan.py`](../phase0/r08_00_scan.py) | GATT layout, frame size, error-flag presence |
| 1 | 10 min | <0.01 % | [`r08_01_qring_connect.py`](../phase0/r08_01_qring_connect.py) | QRing's connect recipe (0x01, 0x3C) — and the 9-byte capability bitmap |
| 2 | 5 min | <0.01 % | [`r08_02_qring_oneshot.py`](../phase0/r08_02_qring_oneshot.py) | QRing one-shots (0x48 today's totals, 0x50 AA AA find, 0x08 01 reboot) |
| 3 | 15 min | 0 % (RX only) | [`r08_03_passive.py`](../phase0/r08_03_passive.py) | Which of QRing's ~30 `0x73` sub-codes does R08 spontaneously emit |
| 4 | 15 min | <0.05 % | [`r08_04_xiaozhu.py`](../phase0/r08_04_xiaozhu.py) | 小猪's 4 cmds + 4 gestures; dedup window; firmware-side double-tap probe |
| 5 | 45 min | ~0.5 % (PPG) | [`r08_05_vitals.py`](../phase0/r08_05_vitals.py) | Vitals stream timing (25 s vs 3 s) + `errCode = 1` wear-detect |
| 6 | 30 min | 0 % | [`r08_06_auto_monitor.py`](../phase0/r08_06_auto_monitor.py) | Ring-cadence-master verification (0x16 / 0x2C / 0x36 / 0x38) |
| 7 | 30 min | 0 % | [`r08_07_accel.py`](../phase0/r08_07_accel.py) | `0xA1` accelerometer encoding (motion-correlation) |
| 8 | 15 min | 0 % | [`r08_08_history.py`](../phase0/r08_08_history.py) | History multi-packet protocol for HR / HRV / stress / steps / sleep |
| 9 | 5 min | <0.01 % | [`r08_09_contested.py`](../phase0/r08_09_contested.py) | What `0x06` / `0x10` actually do on R08 (🛑 `0x0F` permanently excluded — no firmware backup → OTA brick risk) |

Stages 1–3 can be back-to-back. Stage 5 needs **5-min PPG rest** between sub-runs. Stage 9 is
optional + cheap (only 0x06 and 0x10; both low-risk).

## 1. Pre-flight checklist

Before plugging in the ring:

- [ ] Ring fully charged (LED solid green on the cradle, then off when removed from cradle).
- [ ] Computer with Bluetooth on; BLE permissions granted to Terminal / your IDE.
- [ ] Python 3.10+; in `phase0/` run:
  ```bash
  python3 -m venv .venv && source .venv/bin/activate
  pip install -r requirements.txt
  ```
- [ ] Note your ring's advertised name (`R08_xxxx` where `xxxx` is the last 2 bytes of MAC).
- [ ] Decide whether you'll run Stage 9 (the `0x0F` test risks OTA bootloader; can be skipped).
- [ ] Open a notes file or this doc on screen so you can tick boxes / paste verdicts as you go.

If the ring won't pair / advertise after charging, try the QRing app first (App Store / Play
Store) to wake it. Some sealed-box units arrive in deep-sleep and need a single charge cycle + one
official-app handshake before any reverse-engineered client can connect.

## 2. Stage-by-stage protocol

### Stage 0 — Sanity (5 min, **no writes**)

```bash
python3 r08_00_scan.py
```

Confirms the basics so we know the rest of the plan can run.

- [ ] Script scans + finds your `R08_xxxx` ring within ~8 s
- [ ] Script connects and discovers the GATT service `6e40fff0-…`
- [ ] Write/notify characteristics + CCCD are present at the expected UUIDs
- [ ] Subscribing to the notify char succeeds
- [ ] **Listen 10 s with no writes** — does the ring push anything spontaneously? (Battery? Activity?
      G-sensor tick? 0xA1 stream?) Note the prefixes seen.

**If anything fails here**, stop and investigate. The rest of the plan assumes Stage 0 passes.

### Stage 1 — QRing connect recipe (10 min)

```bash
python3 r08_01_qring_connect.py
```

Sends `0x01 SetTime` + `0x3C DeviceFunctionSupport` exactly the way QRing does at connect. Each
write is single-shot, ≤ 16 bytes; total cost: trivial.

- [ ] `0x01 SetTime` — ring ACKs with a `01 …` frame? (QRing's `SetTimeRsp` echoes a capability
      bitmap.)
- [ ] `0x3C DeviceFunctionSupport` — ring responds with a 9-byte capability bitmap?
- [ ] **Paste the 9 bytes back**. The bitmap tells us which Stage 5/6/8 features the firmware
      supports — we won't need to test ones the ring says it doesn't support.
- [ ] Bonus: after writing `0x01`, send `0x03 BATTERY_QUERY` — does the battery response carry a
      **third byte** (the QRing-claimed `isCharging` flag)? Compare two readings: on the cradle vs.
      off the cradle.

After this stage, Doc/02 §4.1 row for `0x01` and `0x3C` and §5.1 row for `0x03` resolve to ✓ / ✗.

### Stage 2 — QRing one-shots (5 min)

```bash
python3 r08_02_qring_oneshot.py
```

Three single-write queries / actions, with grading prompts between each:

- [ ] `0x48 GET_STEP_TODAY` — ring responds with a `48 …` frame? Count the bytes; is it the 14
      QRing claims? Decode → does it match the `73 12` activity push values?
- [ ] `0x50 AA AA ANTI_LOST_RATE` — does the ring vibrate? Does the LED blink? For how long?
- [ ] `0x08 01 RE_BOOT` — gated; if you say `YES`, watch whether the ring disconnects and
      reconnects within ~3 s. If yes, this is our soft-reboot primitive.

Verdict: Doc/02 §4.2 rows for `0x48` / `0x50 AA AA` / `0x08 01` resolve.

### Stage 3 — Passive observation of `0x73 <sub>` (15 min)

```bash
python3 r08_03_passive.py
```

Three 60-second observation windows. Wear the ring throughout. No writes; just listen.

- [ ] **Window A (rest)** — sit still with the ring on. What `0x73 0xNN` sub-codes show up? Tabulate.
- [ ] **Window B (wear cycle)** — at +5 s remove the ring, at +30 s put it back on. Watch for sub-codes
      correlated with the on/off transition (especially `0x73 0x3E` G-sensor still-tick).
- [ ] **Window C (motion)** — keep your hand moving. Look for activity sub-codes (`0x73 0x11` step
      increment, `0x73 0x12` activity totals).
- [ ] **Bonus** — perform a few physical double-taps. Does `0x73 0x30` ("lover double-tap") fire?
      If yes, firmware-side double-tap is available and we can short-circuit the app-side combo
      window for that one gesture.

The script auto-tabulates each window into a markdown table you paste back. Doc/02 §4.3 rows
resolve from these counts.

### Stage 4 — 小猪 touch + 4 gestures + dedup (15 min)

```bash
python3 r08_04_xiaozhu.py
```

Sends 小猪's `TOUCH_ENABLE` + `TOUCH_MODE`, then walks through the gesture tests.

- [ ] Touch enable echo: `73 2A 00` arrives within ~800 ms?
- [ ] Each of 4 raw gestures × 10 reps → 10 frames each, no drops?
  - Single tap → `73 2D 03`
  - Forward swipe → `73 2D 01`
  - Backward swipe → `73 2D 02`
  - Long press → `73 2D 04`
- [ ] **Fast-tap × 30** → script computes the inter-tap delta distribution and prints the minimum.
      Decision: dedup window = `min − 10 ms` (probably 40–80 ms).
- [ ] Look at consecutive `73 2D 03` frames' raw bytes — any varying byte (counter / timestamp)?
      If yes, dedup is "drop exact match within ~50 ms" (simpler + safer).
- [ ] Compare what 小猪's `TOUCH_ENABLE` (`3B 01 00 01 01`) does vs. QRing's `0x3B`
      payload schema `{02, mode, appType, strength}`. The script tries QRing's payload too —
      does the ring respond differently?

Verdict: Doc/02 §5 rows resolve.

### Stage 5 — Real-time vitals (45 min, with PPG rest periods)

⚠ **PPG warning**: the LED is on continuously while a `0x69` stream is active. Budget: 4 × 25-s
runs ≈ 0.08 mAh ≈ 0.5 % of the 17 mAh battery. Allow ~5 min rest between runs so the LED + sensor
have a cooldown beat (this isn't strictly required by the silicon, but is QRing's UX pattern).

```bash
python3 r08_05_vitals.py --measure hr        # 30 s observation
# wait 5 min
python3 r08_05_vitals.py --measure spo2
# wait 5 min
python3 r08_05_vitals.py --measure stress
# wait 5 min
python3 r08_05_vitals.py --measure hr --wear-test  # take ring off at +5 s
```

For each measurement run:

- [ ] When does the first `69 <kind> <err> <val>` frame arrive? (latency to first reading)
- [ ] What's the tick cadence (delta between consecutive frames)? QRing claims 500 ms.
- [ ] Does the ring **auto-stop after ~25 s** (firmware ends the stream), or stream until our
      `0x6A` stop arrives?
- [ ] For each frame, what's `data[2]` (errCode)? QRing claims `0` = OK, `1` = "not worn".

For the wear-test run:

- [ ] Start `--measure hr --wear-test`. The script prompts you at +5 s to remove the ring.
- [ ] After removal, do subsequent frames carry `errCode = 1`? Within how many seconds of removal?
- [ ] Put the ring back on. Does `errCode` flip back to `0`?

Verdict: Doc/02 §4.4 timing + wear-detect rows resolve.

### Stage 6 — Auto-monitor settings (30 min)

```bash
python3 r08_06_auto_monitor.py --kind hr     # 0x16
python3 r08_06_auto_monitor.py --kind spo2   # 0x2C
python3 r08_06_auto_monitor.py --kind stress # 0x36
python3 r08_06_auto_monitor.py --kind hrv    # 0x38
```

Each invocation: read current settings → write a known config → wait → observe whether the ring
emits the corresponding `0x73 <sub>` sync trigger at the configured cadence.

For HR (most useful first):

- [ ] Read `0x16 {1}` → ring returns current setting? Format: `{enable, intervalMin, startHr, …}`.
- [ ] Write `0x16 {2, 1, 5, 8, 50, 200, 1}` (enable, 5-min interval, start at 8 am, range
      50–200 bpm, master switch on).
- [ ] Wait 6 min. Does the ring fire `0x73 0x01` (new HR record sync trigger)?
- [ ] After the trigger: send `0x15 <today-midnight-LE>` → does the ring stream the freshly-measured
      HR back as `15 <sub> …` multi-packet frames?
- [ ] Reset: write `0x16 {2, 0, 30, 8, 50, 200, 0}` (disable to default cadence, master off).

For SpO2 / stress / HRV: repeat with shorter probes (just verify read + write + one trigger).

Verdict: Doc/02 §4.5 rows resolve. Bonus: confirms the "ring is cadence master" mental model
(Doc/07 §2.6).

### Stage 7 — Accelerometer characterisation (30 min)

```bash
python3 r08_07_accel.py --pattern still      --duration 30
python3 r08_07_accel.py --pattern rotate-x   --duration 20
python3 r08_07_accel.py --pattern rotate-y   --duration 20
python3 r08_07_accel.py --pattern rotate-z   --duration 20
python3 r08_07_accel.py --pattern tap        --duration 30
python3 r08_07_accel.py --pattern shake      --duration 10
python3 r08_07_accel.py --pattern free       --duration 30 --record accel.csv
```

The script auto-tabulates per-byte variance during each pattern. Per-pattern observations:

- [ ] `still` — what's the noise floor? Which payload bytes (`data[2..7]`) are nearly constant?
      What does the constant value imply for gravity (+1g should pin one axis to a non-zero mean)?
- [ ] `rotate-x` — which 2 payload bytes change the most? That pair is the X axis (likely an
      int16: hi/lo or lo/hi).
- [ ] `rotate-y` — Y axis (different pair than X).
- [ ] `rotate-z` — Z axis (the third pair).
- [ ] `tap` — does `0xA1` spike alongside `73 2D 03`? If yes, accel + gesture are correlated.
- [ ] `shake` — do bytes saturate (go to 00 / FF / 7F / 80)? Tells us scale range.

After the recording, **offline analysis**: pair the six bytes as three int16s. Try LE and BE, try
signed and unsigned. The "right" interpretation is the one where:
- `still` gives values clustered around `(0, 0, ±g)` where `±g` ≈ ±1 (scale factor TBD).
- Rotations move smoothly between extremes without wrapping.

Verdict: Doc/02 §5.1 `0xA1` row resolves. Encoding goes into a new `R08Frame.parseAccel()` (the
production code change happens later, after all phase-0 stages close).

### Stage 8 — History multi-packet reads (15 min)

```bash
python3 r08_08_history.py --kind hr      # 0x15
python3 r08_08_history.py --kind hrv     # 0x39
python3 r08_08_history.py --kind stress  # 0x37
python3 r08_08_history.py --kind steps   # 0x43
python3 r08_08_history.py --kind sleep   # 0x44
```

Each invocation: send the read command → buffer all response packets until the terminator → print
packet count + decoded sample count.

For HR (most useful first):

- [ ] Send `0x15 <today-midnight-LE>`. Script counts packets and decodes.
- [ ] Header packet: `15 00 <pktCount> <range>` — what's `range` (the bin-minute size)? QRing says 5.
- [ ] Data packets: 9 samples in packet 1, 13 in 2..N-1, terminator at `15 <pktCount-1>` or `15 FF`.
- [ ] Total samples per day: 288 (24h × 60 / 5). Confirm by counting.

For other kinds: count packets, verify header format, sanity-check.

Verdict: Doc/02 §4.6 rows resolve.

### Stage 9 — Contested opcodes (5 min, optional)

This stage tests **only** 0x06 and 0x10 — both low-risk (worst case the ring stays silent for a
few seconds, no permanent state change).

🛑 **0x0F is intentionally NOT tested.** If QRing's interpretation is correct, sending 0x0F puts
the ring into OTA bootloader mode. We have **no known-good R08 firmware backup** to flash back
(the `.bin` files in `research/ATC_RF03_Ring/OTA_firmwares/` are for the R02 model, not R08 —
flashing them on R08 may brick the touch IC or cause unknown corruption). Until a real R08
firmware backup exists, 0x0F stays untested by design. The script does not accept `--probe 0x0F`.

```bash
python3 r08_09_contested.py --probe 0x06
python3 r08_09_contested.py --probe 0x10
```

For each:

- [ ] `0x06` — does the LED blink ~10 s (heritage)? Or does the ring go mute (QRing)? Or neither?
- [ ] `0x10` — does the LED quickly double-blink? Or silent ACK?

After each: the script asks you to grade `[q]ring / [h]eritage / [?]neither` with optional notes.

Verdict: Doc/02 §6 rows for `0x06` / `0x10` resolve to ✓ / ✗; the `0x0F` row stays
"🛑 DO NOT TEST" until a firmware backup becomes available.

## 3. What you actually DO per stage (operator's flow)

Every stage script prints an **operator card** when it starts that tells you exactly what to do.
The card has four sections:

```
┌─────────────────────────────────────────────────────────────────┐
│  STAGE N — name                                                 │
│  ≈ M min · ring battery cost: X%                                │
├─────────────────────────────────────────────────────────────────┤
│  📦 BEFORE YOU START                                            │
│    • (e.g. ring on a finger; not on cradle)                     │
│  ▶ WHAT'LL HAPPEN                                               │
│    • (e.g. write 0x01, wait, grade prompt, repeat)              │
│  ✓ SUCCESS LOOKS LIKE                                           │
│    • (e.g. ACK frame `01 …` appears within 2 s)                 │
│  📤 AFTER                                                       │
│    • verdict block prints + auto-saves to phase0/verdicts/      │
└─────────────────────────────────────────────────────────────────┘
```

When a script needs your input it'll say so explicitly — either:
- **`Press Enter when ready…`** — block until you hit Enter (you can take all the time you need).
- **`Grade [...]:`** — type a single letter then Enter:
  - `q` → the ring matched QRing's prediction
  - `x` → matched 小猪 / R08-Remote's prediction
  - `h` → matched the heritage prediction (rare — only Stage 9 has heritage rows)
  - `?` → neither / something unexpected (add free-text after the letter)
  - You can also type `q observed three blinks and a vibration` to record notes alongside the
    grade. Everything after the first character is captured as the note.

If a stage doesn't need grading (Stage 0, 3, 7, 8) just watch the terminal output. The verdict
block prints automatically when the timed window ends.

## 4. How to report results back

Each stage ends with a **verdict block** like this:

```
─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─
PASTE TO CHAT — everything between the two scissor lines
─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─

## Stage 1 — QRing connect recipe
_recorded 2026-05-15 14:23:01_

### Tests
- **0x01 SetTime** → QRing ✓ — got `01 0a 02 ...` (15 bytes total)
- **0x3C DeviceFunctionSupport** → QRing ✓ — got `3c 1f 00 88 ff ff ff 03 ff` (9 bytes)
- **0x03 Battery payload shape** → QRing ✓ — saw `03 56 01` (3 bytes — level + charging)
- **0x03 charging-byte responsive** → QRing ✓ — byte[2] flipped 0→1 on cradle

**Overall**: PASS (4/4 graded)

─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─
END VERDICT BLOCK
─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─ ✂ ─

💾 also saved to /Users/.../phase0/verdicts/stage_01_qring_connect_recipe.md
```

**Two ways to share the result with the AI agent assisting you:**

1. **Copy from terminal** — select the lines between the two `─ ✂ ─` markers, paste into chat.
2. **Send the file** — every verdict is auto-saved as a `.md` under `phase0/verdicts/`. After
   running all stages: `cat phase0/verdicts/*.md` gives you a full session report you can paste
   in one shot, or attach the directory.

After each stage:
- Tick the boxes in §2 of this doc.
- Paste / send the verdict block.
- The agent updates [Doc/02](02-hardware-and-protocol.md) (🟡 → ✓ or ✗), then [Doc/17](17-community-protocol-spec.md) (filling in the ☐).
- When all stages are done, [Doc/17](17-community-protocol-spec.md) is the publishable artefact.

The terminal also accumulates every notify frame in memory; pass `--record /tmp/stage-N.csv` to
any script to also dump a per-frame CSV. The CSV is useful for offline analysis (e.g. correlating
0xA1 accelerometer bytes with motion patterns in Stage 7).

## 5. Recovery / safety procedures

- **Ring won't pair / advertise**: re-cradle for 5 min, then open the QRing official app once to
  wake the firmware. After that any of our scripts should work.
- **Ring stops responding mid-session**: it may have entered auto-sleep (~5 min idle). Wake it
  with one touch / long-press; the BLE link should restore.
- **Battery drops below 20 % mid-session**: pause, charge to full (60–90 min), resume — order of
  remaining stages doesn't matter.
- **MacOS BLE permissions denied**: System Settings → Privacy & Security → Bluetooth → check
  Terminal / iTerm / your IDE.
- **What if 0x0F got sent by accident?**: It won't via the phase-0 scripts — `r08_09_contested.py`
  doesn't accept `--probe 0x0F` at all. If you manually crafted a 0x0F write somewhere and the
  ring went silent / changed BLE name: the only public path back is a firmware flash, which we
  can't currently do safely. Until a working R08 firmware backup exists, treat 0x0F as a
  one-way trip. (This is exactly why we skip the probe.)

## 6. Outputs

After the session, three artefacts:

1. **Updated [Doc/02](02-hardware-and-protocol.md)** — every 🟡 / 🔴 tag resolved to ✓ / ✗.
2. **Updated [`R08Protocol.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/ble/R08Protocol.kt)** — constants reflect what the firmware actually honours.
3. **Published [Doc/17 community spec](17-community-protocol-spec.md)** — ready for the
   atc1441 README, the `tahnok/colmi_r02_client` MYSTERIES.md, etc.

The community spec is the durable outcome: it documents what *no one else has published* (full
R08 BLE protocol with the touch-IC path verified end-to-end), useful to every downstream rebrand
of the BlueX RF03 + STK8321 hardware.
