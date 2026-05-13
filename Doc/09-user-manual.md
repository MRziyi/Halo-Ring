# R08 Ring Remote — User Manual

> A QRing R08 smart ring as the remote for your Rokid Glasses or RayNeo X3 Pro AR glasses.
> One ring; works with both glasses; swap glasses freely.

This manual is for **end users** of the finished app. Setup steps that need a computer happen
**once** — after that, everyday use is just gestures.

---

## 1. What you'll need

- A **QRing R08 smart ring** (model `R08_XXXX` advertised over Bluetooth; check that the companion
  app for it would be "QRing" — that's how we know it's the right hardware). Around $30–50 on
  Taobao/1688/AliExpress.
- The R08 charging cradle (came with the ring).
- One or both of:
  - **Rokid Glasses** (RG-glasses, YodaOS / Android 12)
  - **RayNeo X3 Pro** (ARGF20, RayNeo AIOS / Android 12)
- A computer (Mac / Windows / Linux) **once** for the initial ADB bootstrap on each pair of glasses.

You do **not** need:
- A phone (the app runs on the glasses themselves)
- The official QRing app (and you should remove it from any phone that's been paired with the
  ring — the ring only talks to one device at a time)

---

## 2. Day-one setup (~10 minutes per glasses)

### 2.1 Charge the ring

Drop it in the cradle, plug the cradle in via USB-C. ~60–90 min for a full charge. The ring's LED
will indicate charge progress (check the QRing manual that came in the box for the exact pattern).
While charging the ring's Bluetooth is off, so you can't connect.

### 2.2 Install the app on your glasses

You get one APK per kind of glasses:

| Glasses | APK | How to install |
|---|---|---|
| Rokid Glasses | `r08-ring-remote-rokid-vX.Y.apk` | Connect Rokid's official 5-pin dev cable + the Rokid companion phone app to enable ADB; then `adb install <apk>` |
| RayNeo X3 Pro | `r08-ring-remote-rayneo-vX.Y.apk` | Settings on the glasses → swipe left 10 times to unlock developer mode; then USB-C data cable → `adb install <apk>`. (Windows: if `adb devices` doesn't show the glasses, install the WinUSB driver via Zadig.) |

For both: the install is one-time. The app then runs as a foreground service and self-starts on
boot.

### 2.3 First-run wizard

After install, launch **R08 Ring Remote** from your glasses' app launcher. The wizard walks you
through four short steps. On each, focus the highlighted button and press the temple touch bar to
confirm (you're using the glasses' own touch bar here — the ring isn't connected yet).

1. **ADB bootstrap.** A one-time `pm grant` so the app can self-pair with wireless ADB on
   subsequent reboots. The screen will show a single `adb shell` command to copy-paste from your
   computer; you're done with the computer after this.
2. **Optional: enable accessibility.** Tap the "Open Settings" deep-link. In the system
   accessibility menu, enable "R08 Remote (foreground & back/home helper)". This unlocks
   auto-profile-switch (the app changes mode based on which app is in the foreground) and lets
   Back/Home/Recents work even if the ADB connection drops.
3. **Battery optimization exemption.** Tap "Allow" on the system prompt. Without this Android
   will eventually kill our foreground service to save power.
4. **Pair the ring.** Take the ring out of the cradle and put it on your dominant index finger
   (or whichever finger feels most natural — sizing matters; the ring is most reliable when
   snug). Tap the ring once. The wizard will scan, find a device named `R08_XXXX`, and connect.
   You'll feel a single haptic-ish confirmation: the ring's green LED blinks twice. Done.

If you have a second pair of glasses, repeat 2.2–2.3 on those. You'll install the matching APK,
walk through the wizard, and pair the **same** ring on the new glasses. The ring can only be
connected to one set of glasses at a time, but **swapping is automatic** — see §5.

---

## 3. Where to wear the ring

A few things to know about the hardware:

- **Indexing finger of the dominant hand** is what we'd suggest. Most reliable detection of
  tap/swipe on the touch-sensitive surface.
- **Touch surface = the flat band on the outside of the ring**, opposite the LED. You'll feel a
  slight rougher texture or marking when you've found it.
- **Auto-sleep**: after ~60 seconds of no touches, the ring goes into a low-power state. Wake it
  by double-tapping the touch surface; the green LED flashes once when it wakes. The app handles
  the wake-up internally so the first deliberate gesture afterwards still does the right thing
  (your first real tap after wake won't accidentally fire as a stale "back").
- **IP68 / 5 ATM**: fine in the rain or for hand-washing. Not for swimming long sessions.
- **Charging breaks the connection** — you can't use the ring while it's in the cradle. Expect
  to charge once every 5–7 days.

---

## 4. The 12 gestures

There are **four atomic moves** you make on the ring; the app combines them into a richer
vocabulary by watching for timing patterns. You only ever **physically do four things**:

| Atomic move | What it feels like |
|---|---|
| **Tap** | A quick tap on the touch surface, like clicking a touchpad. |
| **Long press** | Touch and hold for ~600 ms. The ring distinguishes this from a tap internally. |
| **Forward swipe** | Glide your finger along the touch surface in one direction. "Forward" means away from your hand toward your fingertip. |
| **Backward swipe** | The opposite direction. |

From these four, the app recognises **12 gestures**:

| # | Gesture | How to do it | Time budget |
|---|---|---|---|
| 1 | TAP | One tap | ≈ 0 ms (optimistic profiles) or ~280 ms (precise profile) |
| 2 | DOUBLE_TAP | Two taps within ~280 ms | ~300 ms after the 2nd tap |
| 3 | TRIPLE_TAP | Three taps within ~280 ms of each other | ~280 ms after the 3rd |
| 4 | QUADRUPLE_TAP | Four taps in a row | ~280 ms after the 4th |
| 5 | SWIPE_UP | Forward swipe | immediate |
| 6 | SWIPE_DOWN | Backward swipe | immediate |
| 7 | LONG_PRESS | One long press | ~400 ms (if "Await long-press combos" is on) or immediate (Fast profile) |
| 8 | DOUBLE_TAP_SWIPE_UP | Two taps, then a forward swipe within ~500 ms | ≤ 800 ms |
| 9 | DOUBLE_TAP_SWIPE_DOWN | Two taps, then a backward swipe | ≤ 800 ms |
| 10 | LONG_PRESS_SWIPE_UP | Long press, then a forward swipe within ~400 ms | ≤ 1 s |
| 11 | LONG_PRESS_SWIPE_DOWN | Long press, then a backward swipe | ≤ 1 s |
| 12 | DOUBLE_LONG_PRESS | Two long presses within ~400 ms | ≤ 1 s |

> **Tip.** You don't need to memorise all 12. In practice you'll use TAP, DOUBLE_TAP, the two
> swipes, and LONG_PRESS 95% of the time. The combos (8–12) are for less-common actions and
> system control (sleep/wake/peek).

---

## 5. What each gesture does — default mappings

The app has four built-in **profiles**. Each profile maps the same 12 gestures to different
actions, optimised for different contexts. **Triple-tap cycles** through them. The current
profile is shown briefly in the HUD on every switch.

### 5.1 Navigation profile (default — for browsing the system UI)

| Gesture | Action |
|---|---|
| TAP | **Confirm** (= press Enter on what's focused) |
| DOUBLE_TAP | **Back** |
| SWIPE_UP | Move focus **previous** (up in a vertical list) |
| SWIPE_DOWN | Move focus **next** |
| LONG_PRESS | Open **menu** |
| DOUBLE_TAP_SWIPE_UP | **Take photo** |
| DOUBLE_TAP_SWIPE_DOWN | Ask **Visual AI** (point at something, get an AI answer) |
| LONG_PRESS_SWIPE_UP | Pull down **notifications** |

Tap → Confirm has no optimistic shortcut here, so a tap waits ~280 ms before firing — this keeps
"is it a tap or a double-tap?" unambiguous for menu navigation where you don't want extra clicks.

### 5.2 Media profile (short video, music)

| Gesture | Action |
|---|---|
| TAP | **Play / pause** (fires instantly — optimistic) |
| DOUBLE_TAP | Back |
| SWIPE_UP | Previous track / video |
| SWIPE_DOWN | Next track / video |
| LONG_PRESS | Volume **up** (quick +1) |
| DOUBLE_TAP_SWIPE_UP | Take photo |
| DOUBLE_TAP_SWIPE_DOWN | Ask Visual AI |
| LONG_PRESS_SWIPE_UP | Enter **volume modal** — swipes then change volume continuously until you tap to confirm |

### 5.3 Reader profile (teleprompter, translation, long-form reading)

| Gesture | Action |
|---|---|
| TAP | Confirm (instant — optimistic) |
| DOUBLE_TAP | Back |
| SWIPE_UP | Previous page / line |
| SWIPE_DOWN | Next page / line |
| LONG_PRESS | **Home** |
| DOUBLE_TAP_SWIPE_UP | Take photo |
| DOUBLE_TAP_SWIPE_DOWN | Open **translate** (translate what you're looking at) |
| LONG_PRESS_SWIPE_UP | Open AI **chat** (long-form conversation) |

### 5.4 Fast profile (minimum latency, fewest gestures)

| Gesture | Action |
|---|---|
| TAP | Confirm (instant) |
| DOUBLE_TAP | *(disabled — no double-tap so TAP fires immediately)* |
| SWIPE_UP | Previous |
| SWIPE_DOWN | Next |
| LONG_PRESS | **Back** (instant — no follow-up window) |
| DOUBLE_TAP_SWIPE_UP / _DOWN | *(disabled — no combos)* |
| LONG_PRESS_SWIPE_UP | *(disabled)* |

Use Fast when you want absolutely-no-perceptible-latency gestures and don't need the richer
combos. You can still **sleep / wake the glasses** via system gestures in Fast — see §6.

### 5.5 Auto-switch by app

If you enabled the accessibility service in §2.3 step 2, the app **automatically switches
profile** based on which app is in the foreground. For example, opening a video app activates
the Media profile. Triple-tap to override; your manual choice "wins" for 5 seconds after which
auto-switch resumes.

You can configure which apps trigger which profile in **Settings → Modes → \<profile\> → Trigger
apps**.

---

## 6. Special gestures (always-on, override the profile)

These five gestures **always do the same thing**, regardless of the active profile. They're the
"system layer" — the app intercepts them before the profile gets a chance.

| Gesture | Always does | Notes |
|---|---|---|
| **LONG_PRESS** while screen off | **Wake the screen** | The fast path — no synthesis window, fires instantly. Choosing long-press for wake means a casual brush won't wake the screen by accident, but a deliberate press is single-action fast. |
| **LONG_PRESS_SWIPE_DOWN** while screen on | **Sleep the screen** | "Press and pull down to put it away." Two-part gesture so it's hard to misfire — important because waking the screen costs power. |
| **TRIPLE_TAP** | **Cycle profile** | Goes to the next profile in your list and shows a brief HUD + ring-LED flash. |
| **QUADRUPLE_TAP** | **Peek HUD** | Shows status (connection / battery / current mode / signal) for ~2 s. Read-only, doesn't change anything. |
| **DOUBLE_LONG_PRESS** | **Force reconnect** | When the ring is acting weird, this tears down + re-establishes the BLE link. Rarely needed. |

You can rebind any of these in **Settings → System gestures**.

---

## 7. Status feedback — what the ring's LED is telling you

The ring's LED gives you feedback you can perceive without looking at the glasses display. Useful
for "did that gesture register?" without having to focus the HUD. (R08's LED is green-only; we
use blink patterns to encode meaning.)

| Pattern | What just happened |
|---|---|
| Two quick flashes | Connected to your glasses |
| One flash | Profile switched (also when a system action fires) |
| One flash | Screen woken / screen slept |
| Slow periodic double-flash | A modal is active (volume / brightness / etc.) — you're "in" it |
| Two quick flashes | Modal exited via Confirm (Tap) |
| *Nothing* | Modal exited via Cancel / timeout, or your gesture was deliberately ignored (e.g. while screen-off you did a non-wake gesture) |
| Slow lone flash every ~60 s | Ring battery ≤ 20%, time to charge |
| Continuous slow flashing | Force-reconnect in progress |

---

## 8. Using one ring with two pairs of glasses

The R08 ring can only be connected to one set of glasses at a time, but **switching is hands-off**:

1. You're wearing your **Rokid Glasses**; the ring is connected to them.
2. You take the Rokid off. The app on the Rokid detects you've stopped wearing them (proximity
   sensor + hinge state) and releases the ring's Bluetooth connection.
3. You put on the **RayNeo X3 Pro**. Its copy of the app — which has been quietly scanning in
   the background for ~5 seconds — sees the ring is now available and connects.
4. The ring's LED double-flashes to confirm. Your gestures now drive the X3 Pro.

The whole handover takes about 1–2 seconds and needs no input from you.

If for some reason the handover doesn't happen automatically (worn-detection sometimes confuses
itself with hair / hats), **double-long-press** on the ring to force a reconnect to whichever
glasses are currently active.

---

## 9. Common workflows (cheat sheet)

| What you want | How |
|---|---|
| Wake the screen | Long press |
| Sleep the screen | Long press + swipe down |
| Confirm what's focused | Tap |
| Go back one screen | Double tap |
| Scroll through a list | Forward swipe / backward swipe |
| Open the menu in current screen | Long press (in Navigation profile) |
| Take a photo | Double tap + forward swipe |
| Ask the Visual AI about what you're looking at | Double tap + backward swipe |
| Switch from "navigating menus" to "watching short videos" mode | Triple tap (or just open the video app — auto-switch handles it) |
| See ring battery and connection status | Quadruple tap (Peek HUD) |
| Open notifications | Long press + forward swipe (Navigation profile) |
| Translate the menu / sign you're looking at | Double tap + backward swipe (Reader profile, or in Navigation profile this opens Visual AI which will translate via image — same result) |
| Reset the ring connection | Double long-press |

---

## 10. Troubleshooting

**The ring won't connect.**
- Is it charged? (Drop it in the cradle for 10 minutes and try again.)
- Is the QRing app still running on a phone somewhere? The ring only talks to one device at a
  time — uninstall QRing or turn off Bluetooth on that phone.
- In the app, go to **Settings → Ring → Forget and re-pair**.

**A gesture I'm sure I did right isn't being recognised.**
- Check the HUD or do a Quadruple-tap → does the connection dot say "Connected"?
- For multi-tap gestures, the rhythm matters — the app counts taps that arrive within ~280 ms of
  each other. If you're tapping slowly (>280 ms between), each is a separate single tap.
- For combo gestures (Double-tap + Swipe), the combo window is ~500 ms — be reasonably quick.
- You can tune both windows in **Settings → Ring → Multi-tap window** and **Combo window**.

**The screen keeps waking by itself.**
- Are you doing the configured wake gesture (default: long-press) accidentally? Try rebinding to
  a less-likely combo in **Settings → System gestures → Wake**.
- Worst case: disable wake-by-ring entirely (set to "(none)").

**Auto-switch keeps fighting my manual triple-tap choice.**
- After a manual switch the app gives you 5 seconds of "manual lock" before auto-switch can
  override. Increase that in **Settings → Modes → Manual lock duration**.
- Or just remove the app from the trigger list of whichever profile is grabbing you.

**Latency feels worse than it should.**
- Open **Settings → Advanced → Debug HUD** — it shows the actual BLE connection interval and
  round-trip times. If the connection interval is >100 ms during interaction, your phone/glasses
  BLE stack is being conservative; try **Force reconnect** (Double-long-press) and it'll
  re-request a fast interval.
- For TAP latency in menu contexts, try **Settings → Modes → Navigation → Optimistic single
  tap**. It makes TAP fire instantly at the cost of occasional extra clicks during fast
  double-taps.

**The ring's LED is flashing all the time / not at all.**
- Continuous flashing = a modal is active. Tap or wait 3 seconds to exit.
- Never flashing = either you've disabled feedback (Settings → Advanced) or the ring isn't
  connected.

---

## 11. Where to learn more

- `Doc/05-interaction-design.md` — the full design of gestures, profiles, modals
- `Doc/02-hardware-and-protocol.md` — the ring itself: hardware, BLE protocol
- `phase0/r08_probe.py --tutorial` — a guided walkthrough that confirms each gesture is being
  detected correctly (good for first-time setup verification)

---

**Maintenance note for future docs work.** This manual covers the *as-designed* gesture set
(R08-Remote-Design.md §23–§25 / Doc/05). If we add new gestures or change defaults, update §4–§6
of this file in lockstep. The phase-0 tutorial (`r08_probe.py --tutorial`) walks through the same
12 gestures and should stay in sync.
