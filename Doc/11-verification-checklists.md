# 11 — Verification Checklists

The work items that confirm the design works on real hardware. Three buckets:

- **A. Phase-0 (ring only)** — run with just a ring + a computer with bleak. No glasses needed.
  These verify the BLE protocol on your actual unit.
- **B. Phase-1 (per glasses)** — run on the actual Rokid or RayNeo glasses with the app
  installed. These verify the executor / Intent / accessibility paths.
- **C. End-to-end** — the full system together.

Tick each item off as you go. If something doesn't match the design's assumption, that's a
finding; update the relevant `Doc/` section and the code constants.

---

## A. Phase-0 — ring & protocol (no glasses required)

Setup:
```bash
cd phase0
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

### A1. Acceptance criteria (must-pass before any further work)

```bash
python3 r08_probe.py
```

- [ ] Scan finds a device named `R08_xxxx`. Note its MAC.
- [ ] Service UUID `6e40fff0-…` present in the GATT.
- [ ] Within ~800ms of `TOUCH_ENABLE`, a `73 2a 00` frame arrives (touch-status enabled).
- [ ] `BATTERY_QUERY` response: a `03 <%>` frame with a plausible percentage.
- [ ] Single tap → `73 2d 03` arrives within ~150 ms.
- [ ] Forward swipe → `73 2d 01`; backward swipe → `73 2d 02`.
- [ ] Long press → `73 2d 04`.
- [ ] Each of the four atomic events, done ×10 in a row, produces 10 frames (no drops).

### A2. Decode the `0xA1` accelerometer frames

```bash
python3 r08_probe.py --record /tmp/accel.csv
```

Move your hand in a controlled pattern: rotate the ring around X axis 10x, then Y, then Z. Sit
still. Look at the CSV's `Accel` rows.

- [ ] Are `0xA1` frames present at all? At what rate?
- [ ] Do the bytes vary with motion? If yes, can you guess the encoding (signed 16-bit BE? LE?
      offsets? scale factor?)
- [ ] If decoding looks tractable, write a Python decoder and validate against known motion.
- [ ] Update [02-hardware-and-protocol.md](02-hardware-and-protocol.md) §4.1 with the encoding
      details; remove the TBD on the AccelRaw entry.

### A3. Inter-tap interval measurement (for the dedup window)

```bash
python3 r08_probe.py --record /tmp/taps.csv
```

Tap the ring **as fast as you physically can** 30 times in a row.

- [ ] Open the CSV; filter to `Gesture / TOUCH` rows.
- [ ] Compute deltas between consecutive timestamps. What's the minimum?
- [ ] **Decision**: set `R08BleClient`'s byte-level dedup window to **min observed delta − 10 ms**
      (typical result: 40-80 ms).
- [ ] Update [02-hardware-and-protocol.md](02-hardware-and-protocol.md) §6 with the measurement.

### A4. Counter / timestamp byte check

In `/tmp/taps.csv`, look at the `hex` column for back-to-back `73 2d 03` rows.

- [ ] Are the bytes byte-for-byte identical, or does some byte change?
- [ ] If changing → the dedup can be "drop if exact match", any window. Simpler + safer.
- [ ] If identical → use the tight window from A3.
- [ ] Update [02-hardware-and-protocol.md](02-hardware-and-protocol.md) §6.

### A5. Worn-on-finger frame

Wear the ring 30s, take it off 30s, repeat 3 times.

- [ ] Look in the CSV for `Unknown` rows in the `0x73 0xNN` namespace.
- [ ] If any new sub-frame appears correlated with wear state, hypothesise it's the wear flag.
- [ ] Verify by deliberate on/off cycles.
- [ ] If found: add to `R08Frame.parse()` as a new `RingEvent.WornChanged(worn: Boolean)`; wire
      to the wear lifecycle.
- [ ] Update [02-hardware-and-protocol.md](02-hardware-and-protocol.md) §4.1.

### A6. Keepalive preventing auto-sleep

Tap the ring once. Wait 60 s without touching. Tap again — was the second tap noticeably slower
(or did the ring need a "wake double-tap" first)?

Now try with a 30-second keepalive: connect, write `BATTERY_QUERY` every 30 s. Tap once, wait
60s with the keepalive running, tap again.

- [ ] Does the keepalive defeat auto-sleep?
- [ ] If yes: the first-gesture-after-idle penalty (~200-400 ms of ring wake-up) disappears.
      Set the BLE client to keep a 30-second keepalive when the user is wearing the glasses.
- [ ] Update [06-performance-and-power.md](06-performance-and-power.md) §3.2.

### A7. LED command behaviour

```bash
python3 r08_probe.py --interactive
# at the REPL:
blink   # 0x10
# wait 5s
blink
```

- [ ] Confirm `0x10` flashes the LED. How many times? How long total?
- [ ] Confirm `0x06` (FIND_DEVICE) flashes for ~10 s.
- [ ] Test a sequence: `blink`, wait 200 ms, `blink`. Can we drive faster patterns?
- [ ] Update the LED feedback patterns in [05-interaction-design.md](05-interaction-design.md) §4.3
      with the *actual* observable patterns the ring supports.

### A8. Guided gesture tutorial run-through

```bash
python3 r08_probe.py --tutorial
```

- [ ] Complete the tutorial (all 12 gestures). Note any gesture where the recogniser had to
      retry.
- [ ] For frequently-retried gestures, the timing window defaults probably need tuning. Adjust
      in [`core/.../action/DefaultProfiles.kt`](../app-project/core/src/main/kotlin/com/r08remote/core/action/DefaultProfiles.kt) and confirm.

---

## B. Phase-1 — per-glasses verification

### B1. Rokid Glasses

```bash
# Install your APK
adb install app-rokid-debug.apk
adb shell am start -n com.r08remote.app.rokid/com.r08remote.app.MainActivity
```

- [ ] Run `adb shell getprop | grep -i 'version\|model\|product\|build'`. Confirm
      `Build.MODEL == RG-glasses`, brand `Rokid`, fingerprint `Rokid/glasses/...`. Update
      [`DeviceProfile.detect()`](../app-project/app/src/main/kotlin/com/r08remote/app/di/AppGraph.kt)
      if anything differs.

#### B1.1 DPAD key injection in system UI

```bash
adb shell input keyevent 19   # UP
adb shell input keyevent 20   # DOWN
adb shell input keyevent 21   # LEFT
adb shell input keyevent 22   # RIGHT
adb shell input keyevent 23   # DPAD_CENTER
adb shell input keyevent 66   # ENTER
adb shell input keyevent 4    # BACK
```

In the Sprite Launcher main screen and Settings:

- [ ] DPAD_UP/DOWN/LEFT/RIGHT moves focus visibly.
- [ ] DPAD_CENTER and ENTER both activate the focused item. Which is preferred?
- [ ] BACK works.
- [ ] Try KEYCODE_MENU (82), KEYCODE_APP_SWITCH (187), KEYCODE_HOME (3). What works?
- [ ] Update `RokidActionMapper.primitives()` for any keycode that didn't match expectations.

#### B1.2 Feature Intent map

```bash
adb shell am start -n com.rokid.os.sprite.launcher/.page.camera.CameraPageActivity
# verify camera opens
adb shell input keyevent 27   # KEYCODE_CAMERA — does it shoot?
# verify, then back out

adb shell am start -n com.rokid.os.sprite.launcher/.page.chat.ChatPageActivity
# verify chat opens

adb shell am broadcast -a com.rokid.visualaidemo.ACTION_START
# verify Visual AI mode launches

adb shell am broadcast -a com.rokid.os.sprite.launcher.cmd --es cmd open_app --es pkg com.android.settings
# verify it opens settings
```

- [ ] All four work as documented in [03-target-platforms.md](03-target-platforms.md) §1.3.
- [ ] If any don't, dig with `adb shell dumpsys activity top` and `adb shell pm list packages`
      to find what's actually there. Update `RokidFeatureIntents`.

#### B1.3 ADB wireless self-bootstrap

```bash
adb shell pm grant com.r08remote.app.rokid android.permission.WRITE_SECURE_SETTINGS
adb shell settings put global development_settings_enabled 1
# Try to enable wireless debugging from your app and self-connect to 127.0.0.1
```

- [ ] Confirm the app can `Settings.Global.putInt("adb_wifi_enabled", 1)` after the grant.
- [ ] Confirm the embedded ADB client connects to 127.0.0.1 and is functional.

#### B1.4 AccessibilityService

Enable our service in **Settings → Accessibility**.

- [ ] `performGlobalAction(GLOBAL_ACTION_BACK / HOME / RECENTS / NOTIFICATIONS)` — each works?
- [ ] `onAccessibilityEvent(TYPE_WINDOW_STATE_CHANGED)` — can you read the foreground package?
      Useful for confirming auto-switch.
- [ ] On Android 12 — confirm `GLOBAL_ACTION_DPAD_*` is NOT available (it shouldn't be — API 33+).

#### B1.5 Wear state

- [ ] Listen for the `RokidDoorReceiver` broadcast (action string TBD — dig with `adb shell
      dumpsys activity broadcasts`). Confirm it fires on hinge open/close and / or proximity.
- [ ] Confirm `ACTION_SCREEN_ON/OFF` fires as expected.

### B2. RayNeo X3 Pro

Settings → swipe left 10 times to unlock dev mode. Plug USB-C → `adb devices`.

```bash
adb install app-rayneo-debug.apk
```

- [ ] `adb shell getprop` — confirm model contains `ARGF20`, RayNeo AIOS 2.0 version. Update
      detection if different.

#### B2.1 Input injection — try both DPAD keys and swipe MotionEvents

```bash
# DPAD keys
adb shell input keyevent 19
adb shell input keyevent 20
adb shell input keyevent 23

# Swipe MotionEvents
adb shell input swipe 400 240 240 240 100   # right-to-left (forward swipe equivalent?)
adb shell input swipe 240 240 400 240 100   # left-to-right
```

In the RayNeo launcher and settings:

- [ ] Do DPAD keys move focus? Most likely **no** (X3 Pro's TouchDispatcher works on MotionEvents).
- [ ] Does `input swipe 400 240 240 240 100` move focus to previous?
- [ ] What's the **shortest** swipe duration that's still recognised? Try 60 ms, 30 ms.
- [ ] Update `RayNeoActionMapper.primitives()` with the validated swipe coordinates / duration.

#### B2.2 Feature Intent discovery

The X3 Pro launcher / camera / AI activity names are NOT publicly documented.

```bash
adb shell pm list packages | grep -iv 'android\|google\|qualcomm\|com.qti' | head -20
# Spend a few minutes navigating the on-glasses UI; for each screen run:
adb shell dumpsys activity top | head -20
```

Note down for each feature the `ComponentName`:

- [ ] Camera Activity: `com.rayneo.??/.??`
- [ ] AI assistant: Activity OR broadcast — record the actual mechanism
- [ ] Translate (if there is one)
- [ ] Settings: `com.android.settings/.Settings` (Android default) OR a RayNeo-specific one?
- [ ] Music / Gallery
- [ ] Update `RayNeoFeatureIntents.kt` with the real values.

#### B2.3 Mercury SDK integration

Apply for the AAR at https://open.rayneo.com (registration / contact RayNeo).

- [ ] Drop the AAR in `app-project/app/libs/mercury-release.aar`.
- [ ] Uncomment the dependency in `app/build.gradle.kts` (in the rayneo flavor block).
- [ ] Build a minimal `BaseMirrorActivity` and confirm:
  - [ ] Content renders on both eyes (binocular mirroring works)
  - [ ] `TempleAction` events arrive in the Flow
  - [ ] `DeviceUtil.isX3Device()` returns true
  - [ ] 佩戴检测 API is accessible

If no AAR: confirm the DIY fallback (render content twice into a 1280×480 surface) looks okay.

#### B2.4 ADB wireless self-bootstrap on X3 Pro

Same as B1.3 — confirm `pm grant WRITE_SECURE_SETTINGS` works and the app can toggle wireless
debugging.

### B3. Per-glasses, both

#### B3.1 Phase-0 gesture tutorial on the glasses

Re-run `phase0/r08_probe.py --tutorial` while wearing one of the glasses (the ring is paired
with our app on those glasses). Verify our app sees the same events the Python probe does.

- [ ] Through 1 hour of operation, no extraneous BLE disconnects.

#### B3.2 End-to-end gesture → action

```bash
adb logcat -s R08RemoteService:V GestureSynthesizer:V InteractionRouter:V ActionRouter:V
# Then do each of the 12 gestures slowly
```

- [ ] For each gesture, confirm in logcat:
  - The raw event arrives
  - The synthesised Gesture is emitted
  - The InteractionRouter routes it
  - The ActionRouter selects a backend
  - The backend executes
  - The expected UI change happens

#### B3.3 Latency measurement

In the app, enable **Settings → Advanced → Latency measurement (next 20)**. Do 20 gestures of
each variety. Inspect the CSV.

- [ ] 95th-percentile latency for swipe / optimistic-tap / long-press ≤ 100 ms.
- [ ] 95th-percentile for wake (long-press while screen off) ≤ 150 ms.

#### B3.4 Power consumption

Run the app resident for 1 hour with the glasses worn but no interaction.

- [ ] Compare with baseline (glasses worn but app not installed). Aim for ≤ +5 mA difference.
- [ ] Ring battery delta over 24 hours of light use: ≤ 20% (i.e. on track for 5-day battery
      life with active use).

---

## C. End-to-end (full system)

### C1. Cross-glasses hand-over

You have both pairs of glasses with the app + the ring paired on each.

- [ ] Wear Rokid. Use the ring → works.
- [ ] Take off Rokid, put on X3 Pro. Within 1-2 s, X3 Pro's app indicates connected.
- [ ] Do a gesture on X3 Pro → works.
- [ ] Take off X3 Pro, put on Rokid → reverse hand-over.
- [ ] Repeat 5 times. Hand-over reliability ≥ 100%.

### C2. Daily-life soak test

Wear the glasses for a normal day (3-5 hours). Use the ring naturally.

- [ ] No surprise disconnections.
- [ ] No misfired gestures (e.g. accidental screen wakes while resting your hand on a table).
- [ ] Profile auto-switch (if enabled) feels right.
- [ ] Battery at end-of-day: glasses ≥ 30% (with phone use mixed in), ring ≥ 60%.

### C3. Stress test

- [ ] Stand 2 m from the glasses; can the ring still drive it? (BLE distance edge.)
- [ ] BLE-busy environment: 10 minutes in a crowded place. Drop count?
- [ ] Hot glasses (worn for hours in summer): any thermal misbehaviour?
- [ ] Mode switch (triple-tap) 50 times in a minute — robustness?
