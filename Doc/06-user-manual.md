# Halo Ring · 环意 — User Manual

A QRing R08 smart ring as the remote for your Rokid Glasses (or RayNeo X3 Pro). One ring; works
with both; **switching glasses is automatic.**

## 1. What you need

- A **QRing R08 ring** (model `R08_XXXX`; matches the "QRing" companion app — that's how you
  know it's the right hardware). ~$30-50 on Taobao / 1688 / AliExpress.
- The R08 charging cradle.
- One or both of: **Rokid Glasses** (RG-glasses, YodaOS) / **RayNeo X3 Pro** (ARGF20, AIOS).
- A computer once for the initial ADB bootstrap. After that, the ring talks straight to the
  glasses — no phone, no internet, nothing.

## 2. One-time setup

1. **Charge the ring** in the cradle (~60-90 min). BLE is off while charging.
2. **Install the APK** on your glasses (`r08-ring-remote-rokid-vX.Y.apk` or `…-rayneo-…`). One
   APK per platform; the install is one-time.
3. **Launch the app.** On first launch you'll see one screen: a list of nearby R08 rings. Select
   yours. The ring's green LED double-flashes — you're paired.
4. (Once, lazily) **Grant ADB-over-WiFi + Accessibility + Battery-optimisation exemption** when
   the app asks; each unlocks features as you need them.

Repeat steps 2-3 on the other pair of glasses if you have both. The ring can only be connected to
one set of glasses at a time, but **swapping is automatic** (§6).

## 3. Wearing the ring

- **Index finger of dominant hand** for the most reliable touch detection.
- **Touch surface = the flat band on the outside of the ring** (opposite the LED).
- **Auto-sleep** after ~60 s of no touches; wake it by double-tapping the touch surface — the LED
  flashes once. The app silently swallows the wake events so your first deliberate gesture
  afterwards still does the right thing.
- **IP68 / 5 ATM** — fine in rain or hand-washing; not for swimming long sessions.
- Charging breaks the connection. Expect ~5-7 day autonomy.

## 4. The 12 gestures

You physically make **four moves**: tap, long press, forward swipe, backward swipe. The app
combines them by timing pattern into 12 gestures:

| # | Gesture | How to do it |
|---|---|---|
| 1 | TAP | One tap |
| 2 | DOUBLE_TAP | Two taps within ~280 ms |
| 3 | TRIPLE_TAP | Three taps within ~280 ms each |
| 4 | QUADRUPLE_TAP | Four taps in a row |
| 5 | SWIPE_UP | Forward swipe (away from your hand) |
| 6 | SWIPE_DOWN | Backward swipe |
| 7 | LONG_PRESS | Hold for ~600 ms |
| 8 | DOUBLE_TAP_SWIPE_UP | Two taps, then forward swipe within ~500 ms |
| 9 | DOUBLE_TAP_SWIPE_DOWN | Two taps, then backward swipe |
| 10 | LONG_PRESS_SWIPE_UP | Long press, then forward swipe within ~400 ms |
| 11 | LONG_PRESS_SWIPE_DOWN | Long press, then backward swipe |
| 12 | DOUBLE_LONG_PRESS | Two long presses within ~400 ms |

### Two layers

- **Base 4** (TAP / DOUBLE_TAP / SWIPE_UP / SWIPE_DOWN) — fire the **same system actions** as
  your glasses' own temple touchpad. TAP = confirm, DOUBLE_TAP = back, swipes = navigate. **Not
  rebindable.** Think of the ring as a wireless extension of the temple.
- **Custom 8** (everything else) — fully programmable per-profile. This is the project's
  headline feature; rebind via Settings → Gestures → Profile editor.

## 5. The 4 default profiles

Open **Settings → Gestures → Profiles** to see / edit. The app auto-switches based on the
foreground app; cycle manually with TRIPLE_TAP.

- **Navigation** (default) — for browsing menus. Precise mode.
- **Media** (Spotify / YouTube / NetEase / 抖音…) — optimistic-tap = play/pause; swipes =
  volume; combos = track nav, screenshot.
- **Reader** (Translate / Kindle / Chrome…) — optimistic-tap = confirm; long-press = brightness;
  combos = AI translate / AI chat.
- **Fast** — minimum latency, no combos.

Each profile fills all 12 gesture slots. Auto-switch respects a 5-second manual lock after you
explicitly cycle, so "I want Media right now even though I'm in the launcher" works.

## 6. Switching between two pairs of glasses

Both pairs run the same app + same ring MAC. **Wear detection** drives hand-over:

- Take Glasses A off → A's app disconnects from the ring.
- Put Glasses B on → B's app autoConnects within ~1-2 s.

If both report "worn" simultaneously (rare; sensor confusion), the first to grab the BLE link
wins. The other shows "Ring is in use on the other glasses" with a manual Reconnect button in
Settings → Ring.

## 7. System gestures (5 always-on)

Override the active profile. Default bindings:

| Slot | Default gesture | What it does |
|---|---|---|
| Screen wake | LONG_PRESS (screen off only) | Wake the glasses; sub-100 ms |
| Screen sleep | LONG_PRESS_SWIPE_DOWN | "Press + pull down" |
| Profile cycle | TRIPLE_TAP | Next profile + HUD pip + LED blink |
| Peek HUD | QUADRUPLE_TAP | Show current profile + status for 2 s |
| AI Assistant | DOUBLE_LONG_PRESS | Open Rokid ChatPage / RayNeo voice search |

All reassignable in **Settings → Gestures → System gestures**.

## 8. The HUD

A small pip in the upper-right corner that flashes for ~2 s on key events: gesture recognised
(if you turned on "gesture hint" — useful while learning), profile switched, low battery,
disconnected, vitals snapshot, sport tick, ring dropped. Auto-fades. Never centred.

## 9. Vitals & sport

**Settings → Vitals**. Tap MEASURE NOW to take an on-demand HR + SpO2 snapshot (15-25 s
convergence with a snug ring fit). Start a workout via the sport sub-section — live HR + duration
appear in the HUD until you stop.

## 10. Troubleshooting

- **Ring not pairing**: pull it out of the cradle (BLE is off while charging). Try Settings →
  Ring → Reconnect. If still nothing, Settings → Ring → Forget → re-pair.
- **Gestures not landing**: check Settings → Gestures → Test Arena — gestures light up the
  matching row as you make them. If they don't, the ring may need a snugger fit.
- **App crashed**: it auto-restarts as a foreground service; check the system Settings →
  Battery → Halo Ring → ensure unrestricted background.
- **Wrong profile activates in some app**: Settings → Gestures → Profile editor → bottom of the
  profile, edit the `triggerPackages` list.

---

License: AGPLv3 + commercial (see `COMMERCIAL-LICENSE.md` in the repo).
