# Phase-0 R08 protocol probe

Run this with a real R08 ring in hand to verify everything `../R08-Remote-Design.md` §3 says about
the BLE protocol actually matches your specific unit — **before** we trust any of the Kotlin code
in `../app-project/` that depends on those bytes.

## Quick start

```bash
cd phase0
python3 -m venv .venv && source .venv/bin/activate     # optional but recommended
pip install -r requirements.txt
python3 r08_probe.py                                   # macOS / Linux / Windows
```

On macOS the first run will prompt for Bluetooth permission for Terminal/iTerm/your IDE. On
Windows, just have Bluetooth on (Win10+ works with bleak's WinRT backend).

## What you should see (acceptance criteria)

```
⌕ Scanning for 8.0s …
  ✓ R08_2A3F  XX:XX:XX:XX:XX:XX  rssi=-57dBm
⌕ Connecting to …
  ✓ connected
  ✓ found service 6e40fff0-…
  ✓ subscribed to notify char

→ TOUCH_ENABLE      3b 01 00 01 01 00 00 00 00 00 00 00 00 00 00 3e
  [    812ms]      73 2a 00                                          TouchStatus   enabled
→ TOUCH_MODE        3b 02 00 09 01 00 00 00 00 00 00 00 00 00 00 47
→ BATTERY_QUERY     03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03
  [   2826ms]      03 5b                                              Battery        91%

⌖ Now make your gestures…
  [   5341ms]      73 2d 03                                          Gesture        TOUCH
  [   8112ms]      73 2d 03                                          Gesture        TOUCH
  [  11244ms]      73 2d 01                                          Gesture        SWIPE_UP
  [  14001ms]      73 2d 04                                          Gesture        LONG_PRESS
```

All eight checks from `R08-Remote-Design.md` §10 should pass:

- [ ] Scan finds an `R08_xxxx` device.
- [ ] A `73 2a 00` (TouchStatus enabled) frame arrives within ~800ms of `TOUCH_ENABLE`.
- [ ] Single touch → `73 2d 03` (TOUCH).
- [ ] Forward swipe → `73 2d 01` (SWIPE_UP).
- [ ] Backward swipe → `73 2d 02` (SWIPE_DOWN).
- [ ] Long press → `73 2d 04` (LONG_PRESS).
- [ ] `BATTERY_QUERY` reply → `03 <level%>`.
- [ ] Each of the four gestures, done ×10 in a row, produces 10 distinct frames (or shows their
      timing — see "what to record" below).

If anything fails to match, dig into `bleak`'s debug logs (`BLEAK_LOGGING=1 python3 r08_probe.py`)
and consider whether the firmware on *your* unit differs. Update the design doc §3 and the Kotlin
constants in `../app-project/core/.../ble/R08Protocol.kt` to match.

## What to record (the §20.1 action items)

Run with `--record gestures.csv` and do each of these, then look at the CSV:

1. **Inter-event interval for repeated taps.**
   Tap 10 times at your natural double-tap speed. Open the CSV; look at the `t_ms_since_start`
   deltas between consecutive `73 2d 03` rows. The minimum delta tells us how tight the
   `R08BleClient` byte-level de-dup window can be (§20.3). Aim for "min real gap >> dedup window
   >> 0". Likely safe value: ~50ms (you can't physically tap that fast).

2. **Is there a varying byte in repeated frames?**
   Look at the `hex` column for back-to-back `73 2d 03` events: are the bytes byte-for-byte
   identical, or does some byte (a counter, a timestamp) change? If varying → de-dup is trivial
   (drop exact duplicates only, any window). If identical → keep the tight window.

3. **`0xA1` accelerometer frames — do they appear?**
   Without doing anything special, just sit still then move your hand around for 30s. Any rows
   tagged `Accel`? If yes — record a few minutes of recognisable motion (move-still-move-still)
   and try to correlate the bytes with motion. The aim is to decode the format so we can use the
   ring as a finger-IMU later (§7 spatial mode).

4. **Is there a "worn-on-finger" frame?**
   Put the ring on, leave it on for 30s, take it off, leave it off 30s. Any new frame types in
   the CSV between "on" and "off"? Most likely candidate: an unknown `73 0x??` sub-frame. If you
   find one → great, that's our power-saving gate (§20.1 wear detection). If not → fall back to
   "screen on/off" as the proxy.

5. **Does a keepalive prevent ring auto-sleep?**
   Tap the ring once. Wait 60 seconds without touching it. Tap again — was the second tap
   slower / did you have to "wake" it first (green LED blinking)? Then try with this script
   running and periodically sending `BATTERY_QUERY` every 30s — does the ring stay awake / does
   the second tap feel as fast as the first? If keepalive works, that's how we kill the
   "first-gesture-is-slow" penalty (§20.3).

## Modes

- `--tutorial` — **guided gesture walkthrough**. Walks through every gesture in
  [`../Doc/09-user-manual.md` §4](../Doc/09-user-manual.md) in order; for each one, prompts you,
  waits up to 5 seconds, recognises what you actually did, and prints Pass / Fail. Then summarises
  results. Use this for first-time end-user onboarding AND as a verification that all 12 gestures
  are reaching the BLE layer cleanly on your specific ring.
- `--no-init` — connect & subscribe but don't send TOUCH_ENABLE/MODE. Lets you see what the ring
  pushes by default (e.g. does it stream activity / health on its own?).
- `--interactive` — after init, drop into a tiny REPL: `enable | disable | mode | battery | blink
  | shutdown | quit`. Use `blink` to confirm "find my ring" works (`0x06` lights the LED ~10s).
- `--mac AA:BB:…` — skip scanning, connect by MAC. Faster reconnects during testing.
- `--record path.csv` — log every frame to CSV for offline analysis.

## What about `colmi_r02_client`?

`tahnok/colmi_r02_client` (cloned to `../research/colmi_r02_client/`) is the broader R02-family
Python client. We deliberately don't depend on it — this probe is a single ~250-line file you can
read in five minutes. If you want to *also* exercise the broader R02 command set (HRV, sleep,
device info, etc., from `../research/colmi_r02_client/`), `pipx install` it and run e.g.
`colmi_r02_client get-battery --address $MAC`. Mixing the two clients is fine: the ring is happy
to talk to anyone (no pairing key), but only one BLE central at a time.
