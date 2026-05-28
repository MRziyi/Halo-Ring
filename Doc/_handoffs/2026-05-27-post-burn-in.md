# Handoff snapshot — 2026-05-27, post-burn-in + v0.3 P1

> This is a **point-in-time state snapshot**. It augments the chronological
> [Doc/13 Handoff & TODO](../13-handoff.md) (don't replace it — that has the
> audit-pass history) and complements the prospective [Doc/19 v0.3 Refactor
> Plan](../19-v0.3-refactor-plan.md). Read this first if you're picking up
> the project from this point.

---

## 0. The 30-second pitch

The QRing R08 ring + Rokid Glasses + RayNeo X3 Pro project has cleared two
major milestones in the last session:

1. **Protocol truthification** — the user obtained a real R08 ring
   (`R08_E600`, firmware `RT08_3.10.46_250621`) and produced a 1797-line
   verified BLE protocol spec at `R08-dev/phase0/SPEC v3.md`. That spec was
   rolled into the codebase as commit **`19f2824` (audit-pass-y)** — 30
   files, +2694/-285 LOC, 277/277 tests green. **This is committed.**

2. **v0.3 P1: base gestures → system KeyEvents** — design pivot identified
   during burn-in: the ring's 4 base gestures should mirror Rokid temple
   touchpad semantics (DPAD_CENTER / BACK / DPAD_RIGHT / DPAD_LEFT) instead of
   running through the project's custom InAppFocusController focus
   management. Shipped in this session as a small additive layer (5 new
   tests, 282/282 green total). **This is uncommitted** — see §3 below.

Plus three follow-up plans (P2-P6) approved by the user, documented in
[Doc/19](../19-v0.3-refactor-plan.md), waiting for the next agent.

---

## 1. Code state right now (2026-05-27 ~21:00)

### 1.1 Committed (HEAD = `19f2824`)

audit-pass-y — protocol truthification + 12 burn-in fixes. See its commit
message for the comprehensive list. The headline shippables:

- New `R08Protocol.kt` (~470 lines) with every verified opcode + sub-id + frame builder
- New `R08Frame.kt` parser with `0x80 ERROR` flag + 0xEE sentinel + 0xA1 accel decode
- Extended `RingEvent` sealed hierarchy (`Battery(charging)`, `Health(SBP/DBP)`,
  `WearDetectFail`, `Activity(steps,kcal,meters)`, `TargetReached`, `RingGameKey`,
  `AccelSample`, `AccelOpaque`, `SportTick`, `UnsupportedOp`, `Capability`)
- Real bootstrap sequence: GATT-read `0x2A27` → `0x2A26` → 500 ms → SetTime
  (Beijing-locked) → BindAncs → `0x3C` DeviceSupport → `0x61` GetMessagePush →
  `0x03` Battery → TOUCH_ENABLE → TOUCH_MODE(appType=9)
- `RingPairingScreen` + `RingPairingPrefsStore` — explicit pick-a-ring UI
  with persisted MAC (replaces the previous "auto-connect to first
  name-keyword match" hazard)
- Vitals snapshot: parallel HR + SpO2 with `val ≠ 0` convergence detection
  per SPEC §4.5; 30 s safety timeout per phase. Stress (`0x69 type=0x08`)
  removed — SPEC v3 verified progress-only on this firmware.
- `AccelProcessor` (pure :core, 10 tests) — posture / free-fall / impact /
  wrist-shake detection on 3-axis accel stream
- Capability bitmap parsing (SetTime 14B + 0x3C 9B) → `AppGraph.ringCapabilitiesFlow`
- TOUCH_STATUS_ECHO (`0x73 sub=0x2A`) → wear-state signal (charging dock detection)
- Battery push (`0x73 sub=0x0C`) consumed; 30-min battery poll dropped
- `HaloRingApp.kt:96` bug fix — `LaunchedEffect` to sync `initial` snapshots
  into local `state` (status bar / vitals were frozen on first composition)
- `TabBar` auto-`requestFocus` so ring nav works without a screen tap
- Find ring CTA uses real `0x50 [0x55, 0xAA]`; Shutdown CTA removed
  (`0x0F` was OTA-mode entry — would brick)
- ZH + EN strings for all new UI

### 1.2 Uncommitted (working tree, P1 of Doc/19)

**8 files modified / created**, all related to v0.3 P1:

```
 M app-project/app/src/main/kotlin/com/halo/ring/MainActivity.kt
 M app-project/app/src/main/kotlin/com/halo/ring/service/HaloRingService.kt
 M app-project/app/src/main/kotlin/com/halo/ring/ui/screens/RingPairingScreen.kt
 M app-project/core/src/main/kotlin/com/halo/ring/core/gesture/Gestures.kt
 M app-project/core/src/main/kotlin/com/halo/ring/core/gesture/InteractionRouter.kt
 M app-project/core/src/test/kotlin/com/halo/ring/core/gesture/InteractionRouterTest.kt
?? Doc/19-v0.3-refactor-plan.md
?? Doc/_handoffs/2026-05-27-post-burn-in.md          (this file)
?? app-project/app/src/main/kotlin/com/halo/ring/ui/ActivitySystemKeyDispatcher.kt
?? app-project/core/src/main/kotlin/com/halo/ring/core/gesture/SystemKeyDispatcher.kt
```

**What changed (P1 of Doc/19)**:

- `GestureConfig` gained two boolean fields:
  - `useSystemKeyEvents: Boolean = true` — base gestures fire system KeyEvents
    instead of going through profile → ActionRouter
  - `reverseSwipeSemantics: Boolean = false` — swap SWIPE_UP/DOWN KeyEvent mapping
- New `:core/gesture/SystemKeyDispatcher.kt` — `fun interface` + `SystemKeyMapping` object holding the
  hard-coded keycode integers (`KEYCODE_DPAD_CENTER` = 23, `KEYCODE_BACK` = 4, etc — `:core` stays Android-free)
- New `:app/ui/ActivitySystemKeyDispatcher.kt` — object holding `WeakReference<Activity>`,
  dispatches via `activity.dispatchKeyEvent(KeyEvent(...))` on the main handler
- `InteractionRouter` accepts a `systemKeyDispatcher` slot; defaults to `NoopSystemKeyDispatcher`. In
  `onGesture()` it inserts a **new step 2.5** between modal layer and pushed-profile
  layer: for the 4 base gestures with `useSystemKeyEvents=true`, dispatch the keycode and return.
- `MainActivity.onResume/onPause` attach/detach the activity ref on `ActivitySystemKeyDispatcher`
- `HaloRingService` wires `r.systemKeyDispatcher = ActivitySystemKeyDispatcher` on the router
- `RingPairingScreen` — fixed the picker-stuck-at-Connecting bug (guard was `!is Connecting` →
  changed to `is Picking || is Done`)
- 5 new test cases in `InteractionRouterTest` covering the base-gesture passthrough behaviour;
  3 pre-existing tests updated to use LONG_PRESS (non-base) for inAppShortCircuit assertions

**Build status**: 282/282 tests green, both flavor debug APKs build clean.

**Why uncommitted**: the user wants to do a final burn-in pass to verify P1
actually feels right before committing. The picker-stuck UI bug fix was just
landed in this session and the APK is installed but the user hadn't re-tested
when they asked for the handoff.

---

## 2. Hardware status

### 2.1 What's plugged in

- **OnePlus 9 Pro (LE2121)** on Android 14, adb serial `854afb6b` —
  burn-test rig
- **R08_E600 ring** (MAC `30:35:47:33:E6:00`, HW `RT08_V3.1`, FW
  `RT08_3.10.46_250621`) — paired + persisted in DataStore
- Charging dock for the ring (used to verify TOUCH_STATUS_ECHO dock signal)

### 2.2 What's NOT plugged in

- Rokid Glasses — **not yet ordered** (user said "次序中" earlier). The
  whole project is bare-metal-on-glasses targeted (Doc/19 confirms this),
  but the burn-in has been on the OnePlus burn-test rig because that's
  what we have.
- RayNeo X3 Pro — same status; rayneo flavor still builds + compiles but
  hasn't been validated post-spec-v3 changes.

---

## 3. What was verified on actual hardware (R08_E600 burn-in)

| Subsystem | Verified | Evidence |
|---|---|---|
| Ring pairing picker | ✅ | RSSI shown, user-selectable |
| Pairing persistence | ✅ | Service auto-starts BLE every app launch from saved MAC |
| Bootstrap sequence | ✅ | `HW revision: RT08_V3.1` + `FW revision: RT08_3.10.46_250621` + 17 capability flags decoded in ~7 s end-to-end |
| TouchControl init + 4 atomic gestures | ✅ | `raw gesture: SWIPE_UP / SWIPE_DOWN / TOUCH / LONG_PRESS` all observed in logcat |
| Find Ring LED (`0x50 [0x55, 0xAA]`) | ✅ | LED flashes; user confirmed |
| HR measurement (`0x69 [01, 01]`) | ✅ | HR converges in 15-25 s with snug ring fit |
| SPO2 measurement (parallel) | 🟡 | Wire-correct; user reported "red sensor lights up" so the hardware can do it, but full convergence-to-result UI flow needs re-test post-bug-fix |
| Wear-state from TOUCH_STATUS_ECHO | ✅ | `wear state from ring touch-IC: worn=true/false` toggles when ring is on finger vs in dock |
| Spontaneous reconnect loop | ✅ self-heals | SPEC §6.5 firmware quirk; ~10-20 s cycle of READY → DISCONNECTED → reconnect. App handles gracefully. |
| v0.3 P1 system KeyEvent dispatch | ✅ | `onKeyDown 23` (DPAD_CENTER) / `21` (DPAD_LEFT) / `22` (DPAD_RIGHT) all observed on the OnePlus's Activity |

### 3.1 What's known broken / unverified

| Item | Status | Notes |
|---|---|---|
| `Healthcheck composite` (`0x69 type=05`) | ⚠️ | Spec marks ✅ but on this unit only green LED lit (no red) — fell back to HR + parallel SpO2 |
| BP / Stress / HRV / Temp `0x69` types | ⚠️ | SPEC §4.5 marks them progress-only on RT08 — fw limitation, not our bug |
| In-app focus traversal pre-P1 | 🔴 | Was patchy — root cause was `InAppFocusController.moveFocus` failing silently when no focus was set. P1 fix is to bypass `InAppFocusController` for base gestures and use system KeyEvents instead. **Not yet re-tested post-P1**. |
| RingPairingScreen UI flow | 🔴 was stuck | Fixed in this session — picker hung at "Connecting 2/3" because of a guard typo. Installed in current debug APK. **User hasn't confirmed yet.** |
| Accelerometer stream UI | 🟡 | `AccelProcessor` exists + tested, but no UI yet subscribes to its output. Phase 5 (Doc/19 §3.P5). |
| Sport session UI (`0x77`/`0x78`) | 🟡 | BLE methods wired in audit-pass-y; no UI. Phase 4 (Doc/19 §3.P4). |
| Capability-gated UI | 🟡 | `ringCapabilitiesFlow` populated; no UI reads it yet. Phase 3 (Doc/19 §3.P3). |
| Rokid Glasses bare-metal | ❓ | Glasses not yet on hand; whole architecture is now designed for this target but unverified. |
| RayNeo flavor | ❓ | Still compiles, but the post-spec-v3 protocol changes haven't been tested against a RayNeo build (no hardware). |

---

## 4. The architectural pivot from this session

The user's **core invariant** crystallised during burn-in:

> "戒指它正好对应眼镜默认上的镜腿操作：有前滑、下滑、单击确认、双击返回。
> 我戒指上就都有这些功能。我只是在这些基础的操作上面加了一些额外的自定义键，
> 保持住这个核心。所以你的那些基础操作的键都要触发眼镜系统内部对应的静止操作，
> 它们是一个 exactly same 的东西。"

Translation: **the ring is the temple touchpad's wireless extension**. The 4 base
gestures (TAP / DOUBLE_TAP / SWIPE_UP / SWIPE_DOWN) MUST fire the same system
KeyEvents the temple does — not be remappable via our profile system. The
project's flagship is the **additional custom gestures layered on top** (triple
tap, quadruple tap, long press + various combos), fully programmable and
extendable via the Doc/18 plugin protocol.

This pivot triggered:

1. The doc audit (run by an Explore agent) which concluded:
   > *"The architecture is fundamentally sound. The refactor is a focus+polish
   > effort, not a redesign."*

2. The Doc/19 refactor plan covering 6 phases (P1 = base gestures pass through,
   P2 = settings UX, P3 = capability gating, P4 = sport session, P5 = spatial
   features, P6 = doc cleanup).

3. The P1 implementation (uncommitted, working).

User-locked decisions captured in the
[Doc/19 §1.2 invariant](../19-v0.3-refactor-plan.md#12-user-invariant) + §2
non-goals:

- ❌ Don't delete the 4 default profiles
- ❌ Don't delete profile editor / action picker / system gestures customization
- ❌ Don't delete plugin protocol (Doc/18) — Constellation already uses it
- ❌ Don't delete RayNeo flavor or the 4-strategy pattern
- ❌ Don't delete Test Arena, HUD overlay, gesture-hint HUD
- ❌ Don't touch `:core` gesture state-machine logic — tested and load-bearing

---

## 5. Recommended next actions for the next agent

### 5.1 Confirm + commit P1

1. Pull the current uncommitted state (it's in the working tree at
   `/Users/Zack/Code/Projects/Halo-Ring/`).
2. Run `./gradlew :core:test :app:assembleRokidDebug` from
   `app-project/` — should be 282/282 green.
3. Reinstall the rokid debug APK on the OnePlus burn-test rig
   (serial `854afb6b`): `adb -s 854afb6b install -r -d
   app-project/app/build/outputs/apk/rokid/debug/app-rokid-debug.apk`.
4. Have the user (or yourself) verify the 4 base gestures behave like temple
   gestures:
   - TAP fires onClick on the focused composable
   - DOUBLE_TAP backs out (system Back, pops navStack or backgrounds the
     app)
   - SWIPE_UP / SWIPE_DOWN move Compose focus right / left
5. **Commit P1** as its own commit with the message template in
   [Doc/19 §4](../19-v0.3-refactor-plan.md#4-sequencing-rules):
   "Phase 1 ships first ... pure-mechanic fix, easy to revert if it breaks
   anything subtle."

A reasonable commit message body:

```
v0.3 P1 — base gestures fire system KeyEvents (Doc/19 §3.P1)

The 4 BASE gestures (TAP / DOUBLE_TAP / SWIPE_UP / SWIPE_DOWN) now pass
through as system KeyEvents (DPAD_CENTER / BACK / DPAD_RIGHT / DPAD_LEFT)
instead of routing through the per-profile GlassAction map. This mirrors
Rokid temple-touchpad semantics — the ring becomes a wireless extension of
the temple controls, not a separate app with its own focus conventions.

Custom gestures (TRIPLE_TAP, QUADRUPLE_TAP, LONG_PRESS, all combos) continue
to go through the profile system unchanged — that's the project's flagship
customization layer.

Two new GestureConfig fields:
  useSystemKeyEvents: Boolean = true       // base passthrough enabled
  reverseSwipeSemantics: Boolean = false   // for "up = scroll up" users

New :core/gesture/SystemKeyDispatcher.kt (interface + SystemKeyMapping
constants); new :app/ui/ActivitySystemKeyDispatcher.kt (Activity-window
impl, wired to MainActivity.onResume/onPause).

InteractionRouter inserts a new step between modal layer and pushed-profile
layer: when useSystemKeyEvents=true AND the gesture is one of the 4 base
ones, dispatch the keycode and return. Custom gestures hit pushedProfile +
active-profile layers normally.

Also fixes the RingPairingScreen "stuck at Connecting 2/3" bug — picker's
guard condition `!is Connecting` blocked advancement to ReadingInfo + Done.
Changed to `is Picking || is Done` so the LaunchedEffect can advance through
all post-tap states.

Tests: 277 → 282 (+5 for v0.3 P1 base passthrough; 3 pre-existing tests
updated to use LONG_PRESS for inAppShortCircuit assertions since base
gestures no longer reach that path).

Burn-in verified on OnePlus + R08_E600:
  onKeyDown 23 (DPAD_CENTER) ← ring TAP
  onKeyDown 21 (DPAD_LEFT)   ← ring SWIPE_DOWN (default semantics)
  onKeyDown 22 (DPAD_RIGHT)  ← ring SWIPE_UP (default semantics)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

### 5.2 Then proceed through Doc/19 P2 → P6

Order is documented; phases are independent so you can mix order if useful.
**P2 (Settings reorganization)** is the next user-facing payoff and probably
worth doing next.

### 5.3 RayNeo regression check (cheap)

Since we touched a lot of `:core` and `:app/main/` code in audit-pass-y +
P1, the RayNeo flavor probably still compiles (we ran `:app:assembleRayneoDebug`
and it passed) but hasn't been run anywhere. Worth a `./gradlew
:app:assembleRayneoDebug :app:assembleRayneoRelease` smoke check on next
build to catch any flavor-specific drift before any user picks up RayNeo
hardware.

### 5.4 Things explicitly punted to v0.4+ (per Doc/19 §7)

- Sport-session history (workouts log + analytics) — only start/stop in P4
- IMU-based air-gestures (wave / circle / point) — need WristShake proof
  of concept first
- Healthcheck composite (`0x69 type=05`) — retry once a fw update fixes the
  red-LED gap
- Cross-glasses hand-over (Rokid ↔ RayNeo) — blocked on user owning both
- Mobile companion app (D3 in Doc/13)

---

## 6. Where the truth lives

| Question | Source of truth |
|---|---|
| BLE protocol bytes / opcodes / sub-ids | **`R08-dev/phase0/SPEC v3.md`** (1797 lines, verified across 13 test passes on the actual hardware). Doc/02 is now an integration guide that should reference this; P6 of Doc/19 covers the rewrite. |
| Architectural decisions for v0.3 | **`Doc/19-v0.3-refactor-plan.md`** |
| Project status as of right now | **this file** (`Doc/_handoffs/2026-05-27-post-burn-in.md`) |
| Historical audit-pass chronology | `Doc/13-handoff.md` §"State snapshot" + §1.8-1.12 audit-pass write-ups |
| The 12 gesture vocabulary + 4 profiles | `Doc/05-interaction-design.md` (still accurate; needs §3.8 + §4.4 added in P6) |
| Plugin protocol | `Doc/18-plugin-protocol.md` (shipped in v0.2.5; Constellation is the first client) |
| Rokid bare-metal API constraints | `/Users/Zack/Code/Projects/Constellation/reference/rokid-glass/bare-metal-docs/` (4 files captured from custom.rokid.com 2026-05-26; KeyEvent mappings drive P1) |
| Constellation-Glass minimal-app reference | `/Users/Zack/Code/Projects/Constellation-Glass/Doc/GLASS-CLIENT-DESIGN.md` (the "thin i/o surface" design Halo-Ring borrows philosophy from) |

---

## 7. Open questions the user should weigh in on (when picking up)

These were raised during this session but parked for a deliberate decision:

1. **Should `reverseSwipeSemantics` get a Settings toggle UI?** Currently it's
   a `GestureConfig` field but the user has no way to flip it without code
   change. The toggle could live in P2 Settings reorganization under "Gestures
   → Customization" or even right on the picker.

2. **Should `useSystemKeyEvents` be exposable to advanced users?** Default
   `true` (P1 behaviour) suits the user's invariant. But somebody who wants the
   old InAppFocusController-driven custom routing might want to flip it off
   per-profile. Recommendation: don't expose in v0.3 — keep the invariant
   clean. Revisit if a real use case appears.

3. **Sport-session UI shape (P4)**: just "active workout / duration / HR" or
   also a sport-type picker (Walk / Run / Cycle / …)? The BLE accepts
   sport_type 1..15 but the firmware doesn't differentiate behaviour, only
   echoes it back. Recommendation: ship a small picker (Walk / Run / Other) in
   P4; expand if users care.

4. **Accelerometer streaming default (P5)**: ON by default would unlock
   posture / wrist-shake / free-fall features but adds ~64 B/s of BLE
   traffic. User asked for these features as the project's biggest highlight,
   but defaulting ON has a power cost. Recommendation: ship with a single
   "Spatial features" Settings → Advanced toggle, default OFF, opt-in.

5. **HEALTHCHECK retry path**: SPEC v3 says it should work but our unit's
   red LED doesn't light. Should P4 (sport session) try it again? Recommendation:
   yes — wrap in capability check, if `bpSetting` capability is advertised
   AND user hasn't explicitly opted out, attempt Healthcheck first; on
   wear-detect-fail or timeout, fall back to HR + SpO2 parallel.

---

## 8. Commands the next agent will want

```bash
# Where to be
cd /Users/Zack/Code/Projects/Halo-Ring/app-project

# Tests
./gradlew :core:test                          # 282 cases as of session end
./gradlew :app:assembleRokidDebug             # ~15 MB
./gradlew :app:assembleRayneoDebug            # not yet re-validated post-P1
./gradlew :app:assembleRokidRelease           # R8 shrink, ~4 MB

# Install + smoke check
adb devices                                   # 854afb6b should be the OnePlus
adb -s 854afb6b install -r -d app-project/app/build/outputs/apk/rokid/debug/app-rokid-debug.apk
adb -s 854afb6b shell am force-stop com.halo.ring.rokid
adb -s 854afb6b logcat -c
adb -s 854afb6b shell am start -n com.halo.ring.rokid/com.halo.ring.MainActivity

# Watch ring activity in real time
adb -s 854afb6b logcat -v time | grep -E "Halo|AndroidR08|HaloFocus"
```

---

## 9. Things to NOT undo

In case the next agent reads commit history + sees a previous design and
thinks "let me revert that":

- The pairing picker (`RingPairingScreen` + `RingPairingPrefsStore`) — the
  pre-burn-in "auto-connect to first name-keyword match" was a phantom-
  device hazard once we relaxed the scan filter for SPEC §1.2 (ring doesn't
  advertise service UUID). The picker is correct.
- The Beijing-locked SetTime helper — SPEC §4.8 verified the firmware
  treats SetTime BCD as UTC+8 unconditionally. The helper is correct.
- The 30 s vitals safety timeout — bumped from 12 s because PPG warm-up is
  15-25 s. The timeout is correct.
- `HaloRingApp.kt:96` `LaunchedEffect` syncing `initial` into local
  `state` — fixes a Compose pitfall where `remember { mutableStateOf(initial) }`
  reads the initial value once and ignores later updates. The sync is correct.
- `GestureConfig.useSystemKeyEvents = true` default — the user explicitly
  asked for this as the v0.3+ invariant.

---

## 10. Single closing summary

The project shipped its biggest single quality jump in this session
(audit-pass-y) and is now wrapping up a small architectural pivot (Doc/19 P1)
that aligns the ring's behaviour with the user's mental model of "wireless
temple extension". The protocol is fully truthful to verified hardware; the
gesture pipeline is sound; the UI has a clean small set of follow-ups left
in P2-P6.

The next agent's main job is to **(a) commit P1** after a final burn-in
check, then **(b) work through P2-P6 in order** with Settings reorganisation
being the highest user-visible payoff after the P1 navigation fix.

If anything in this snapshot is wrong by the time the next agent reads it,
trust the commits + `Doc/19` + `R08-dev/phase0/SPEC v3.md` over this file —
this is a point-in-time artefact, those are the maintained sources of truth.

Good luck.
