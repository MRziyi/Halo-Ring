# 05 — Interaction Design

The complete specification of **what the user can do**, **what each thing means**, and **how the
state machines work**. This is the most carefully-thought-out part of the project — read it in
full before changing any gesture or mapping. It is **the gem** of the design — the v0.4 doc
prune left it intact.

For the end-user-facing summary, see [09-user-manual.md](09-user-manual.md).

## 0. The mental model — two layers

The ring presents to the user as **two layers** stacked on top of each other:

1. **Base layer (4 gestures, hard-locked, mirrors temple touchpad)** — TAP / DOUBLE_TAP /
   SWIPE_UP / SWIPE_DOWN fire system KeyEvents identically to how the Rokid temple touchpad
   does. The wearer should think of the ring as a wireless extension of the temple, not as a
   separate app with its own conventions. **Not editable; not exposed in Settings.** See §3.8.

2. **Custom layer (8 gestures + 5 system slots, fully programmable)** — TRIPLE_TAP /
   QUADRUPLE_TAP / LONG_PRESS + 4 combos + DOUBLE_LONG_PRESS go through the per-profile
   `KeyMapProfile` (or get bound to the 5 system slots: ScreenWake / ScreenSleep / ProfileCycle /
   PeekHUD / AI_assistant). **This IS the product** — the project's headline value-add over a
   plain temple touchpad. See §4-§5 and [Doc/18 Plugin Protocol](18-plugin-protocol.md).

---

## 1. The four atomic events

The R08 ring's firmware reports only **four** kinds of events over BLE (notify frame `73 2D <code>`):

| Raw event | Hex | Physical action |
|---|---|---|
| `SWIPE_UP` | `73 2D 01` | Forward swipe along the touch surface |
| `SWIPE_DOWN` | `73 2D 02` | Backward swipe |
| `TOUCH` | `73 2D 03` | One tap (also serves as the basic count unit) |
| `LONG_PRESS` | `73 2D 04` | One press-and-hold (~600 ms) |

The firmware does no multi-event counting; it doesn't recognise double-taps, swing/in-air
gestures, or anything else. **Everything richer than these four is synthesised on our side by the
`GestureSynthesizer` state machine.**

## 2. The gesture vocabulary (12 touch + 1 sensor)

Twelve are synthesised from the 4 raw touch events by [GestureSynthesizer]; the 13th
(`WRIST_SHAKE`) comes from the accelerometer via [AccelProcessor] (v0.4), not the synthesiser.

| # | Gesture | How synthesised | When it commits |
|---|---|---|---|
| 1 | `TAP` | one TOUCH, no follow-up within multi-tap window | immediate (optimistic mode) or window-expiry (~300 ms) |
| 2 | `DOUBLE_TAP` | two TOUCHes within multi-tap window | window-expiry of the combo window (~400 ms) — see §4 |
| 3 | `TRIPLE_TAP` | three TOUCHes | tap window expiry after the 3rd (~300 ms) |
| 4 | `QUADRUPLE_TAP` | four TOUCHes | tap window expiry after the 4th |
| 5 | `SWIPE_UP` | one SWIPE_UP (not in a combo window) | immediate |
| 6 | `SWIPE_DOWN` | one SWIPE_DOWN | immediate |
| 7 | `LONG_PRESS` | one LONG_PRESS, no follow-up within follow-up window | follow-up window expiry (~60 ms in v0.4 — near-instant) |
| 8 | `DOUBLE_TAP_SWIPE_UP` | DOUBLE_TAP + SWIPE_UP within combo window | immediate on the swipe |
| 9 | `DOUBLE_TAP_SWIPE_DOWN` | DOUBLE_TAP + SWIPE_DOWN | immediate on the swipe |
| 10 | `LONG_PRESS_SWIPE_UP` | LONG_PRESS + SWIPE_UP within follow-up window | immediate on the swipe |
| 11 | `LONG_PRESS_SWIPE_DOWN` | LONG_PRESS + SWIPE_DOWN | immediate on the swipe |
| 12 | `DOUBLE_LONG_PRESS` | LONG_PRESS + LONG_PRESS within follow-up window | immediate on the 2nd LP |
| 13 | **`WRIST_SHAKE`** (v0.4) | accelerometer (`0xA1` ch3) → `AccelProcessor.WristShake` → `InteractionRouter.onGesture` | on shake detection; needs the accel stream ON (Settings → Vitals → Spatial features) |

**v0.4 window tuning (from on-glasses iteration):** `multiTapWindowMs=300`, `comboWindowMs=400`
(DOUBLE_TAP_SWIPE combos confirmed trainable at 400), `longPressFollowupWindowMs=60` (bare
LONG_PRESS near-instant — the user found 280 ms "deathly slow"; the ring firmware's ~600 ms
hold-to-register is the remaining floor and is not ours to change). At the 60 ms window the
LONG_PRESS_SWIPE / DOUBLE_LONG_PRESS combos are effectively off; DOUBLE_TAP_SWIPE is unaffected
(separate `comboWindowMs`). The `Fast` profile was **dropped** in v0.4 — base gestures are already
instant system KeyEvents, so its low-latency niche became the default.

## 3. The state machine

Source of truth: [`core/.../gesture/GestureSynthesizer.kt`](../app-project/core/src/main/kotlin/com/halo/ring/core/gesture/GestureSynthesizer.kt).

### 3.1 Three concurrent timing windows

| Window | Default | Purpose |
|---|---|---|
| `multiTapWindowMs` | **280 ms** | Maximum gap between two TOUCHes that should count as part of the same multi-tap |
| `comboWindowMs` | **300 ms** | After a DOUBLE_TAP, time window for a follow-up SWIPE that would upgrade it to a combo |
| `longPressFollowupWindowMs` | **400 ms** | After a LONG_PRESS, time window for a follow-up SWIPE / 2nd LP that would upgrade it to a combo |

The synthesiser maintains three pieces of state corresponding to these:

```kotlin
private var tapCount = 0
private var lastTapAtMs = Long.MIN_VALUE
private var tapTimer: Cancellable           // alive while count==1 (await single) or ≥3 (await triple/quadruple)

private var inComboWindow = false
private var comboTimer: Cancellable         // alive while a double-tap awaits a possible swipe

private var inLpFollowupWindow = false
private var lpFollowupTimer: Cancellable    // alive while a long-press awaits a possible swipe / 2nd LP
```

### 3.2 Optimistic single-tap

`optimisticSingleTap` controls whether `TAP` waits the full multi-tap window. The trade-off:

| Setting | TAP latency | Cost when user actually does double-tap |
|---|---|---|
| `false` (precise) | ~280 ms | None — double-tap is clean |
| `true` (optimistic) | **~0 ms** | TAP fires immediately, then DOUBLE_TAP fires after the combo window — the action is fired *twice* (e.g. "select item, then back") |

Profile-bindable. Default `false` for Navigation (precise menus), `true` for Media / Reader.

### 3.3 Await-combos (for the `DOUBLE_TAP_SWIPE_*` and `LONG_PRESS_SWIPE_*`)

Two related knobs:

- `awaitCombos` controls whether DOUBLE_TAP waits `comboWindowMs` to see if a swipe follows. If
  false: DOUBLE_TAP fires immediately on the 2nd tap, no DOUBLE_TAP_SWIPE_* possible.
- `awaitLongPressCombos` controls whether LONG_PRESS waits `longPressFollowupWindowMs` to see if
  a swipe or 2nd LP follows. If false: LONG_PRESS fires immediately, no LONG_PRESS_SWIPE_* and no
  DOUBLE_LONG_PRESS possible.

Trade-offs same shape as optimistic-tap: enabled = clean combos, slower bare gesture. Disabled =
instant bare gesture, no combos.

### 3.4 Wake-swallow (handling ring auto-sleep)

The R08 firmware auto-sleeps after ~60 s of no touches. Re-waking it requires a double-tap (the
ring's own behaviour, not us). The first TOUCH events after the ring re-connects to us are this
wake double-tap, **not user intent**.

The synthesiser exposes `armWakeSwallow()`. The lifecycle layer (`HaloRingService`) calls it on
the BLE reconnect callback. After arming, the next `wakeSwallowCount` (default 1) TOUCH events
are dropped. `LONG_PRESS` is **never** swallowed — which is why we can use it as the screen-wake
gesture even right after the ring re-wakes from auto-sleep.

The exact count (1 or 2) depends on the firmware variant; calibrate after first use of the
phase-0 probe. The default of 1 assumes the ring's touch IC silently wakes on the 1st touch and
reports the 2nd.

### 3.5 Ordering preservation

Two real-world cases that look subtle:

1. **TOUCH, then SWIPE before the multi-tap window expires.** In conservative mode, the TAP is
   still pending. The synthesiser commits the bare TAP first, then emits SWIPE_UP — order is
   "TAP then SWIPE", matching the user's intent. The helper is `flushPendingTapBeforeDefiniteGesture()`.
2. **LONG_PRESS, then TOUCH before the follow-up window expires.** The LONG_PRESS commits first
   (it's not a swipe → it's not a combo), then the TOUCH starts a fresh tap-counting sequence.

### 3.6 De-duplication

Defence-in-depth. The synthesiser has an optional `minRawIntervalMs` to drop TOUCHes that arrive
within (default 0 = disabled) of the previous. The **real** de-duplication is in the BLE layer
(see [02-hardware-and-protocol.md](02-hardware-and-protocol.md) §7 — 50 ms window calibrated
on R08_E600 burn-in).

### 3.7 Test coverage

The state machine is exercised by ~28 JVM tests in
[`core/src/test/.../GestureSynthesizerTest.kt`](../app-project/core/src/test/kotlin/com/halo/ring/core/gesture/GestureSynthesizerTest.kt)
+ 9 boundary tests in `GestureSynthesizerBoundaryTest.kt`. Every documented behaviour is asserted;
if you change the state machine, the tests will catch behavioural regressions. To run:
`cd app-project && ./gradlew :core:test`.

### 3.8 Base-gesture passthrough (v0.4)

When `GestureConfig.useSystemKeyEvents = true` (the default), the 4 base gestures are intercepted
**immediately after synthesis** by the `InteractionRouter` and dispatched as system `KeyEvent`s
instead of being looked up in the active profile:

| Gesture | KeyCode(s) (default) | KeyCode(s) (`reverseSwipeSemantics = true`) |
|---|---|---|
| `TAP` | `KEYCODE_DPAD_CENTER` (23) | (unchanged) |
| `DOUBLE_TAP` | `KEYCODE_BACK` (4) | (unchanged) |
| `SWIPE_UP` | `DPAD_UP` (19) **+** `DPAD_LEFT` (21) | `DPAD_DOWN` + `DPAD_RIGHT` |
| `SWIPE_DOWN` | `DPAD_DOWN` (20) **+** `DPAD_RIGHT` (22) | `DPAD_UP` + `DPAD_LEFT` |

Why: the wearer's mental model is "ring = wireless extension of the temple touchpad" — so the
ring's 4 base gestures must trigger the SAME system behaviour the temple does.

**On-glasses fix (2026-05-27): a swipe dispatches BOTH a vertical and a horizontal DPAD key.**
The Rokid Sprite Launcher **app list is laid out horizontally** (needs `DPAD_LEFT/RIGHT`), while
home / notifications / in-app are vertical (`DPAD_UP/DOWN`). Sending one vertical + one horizontal
"previous"/"next" key per swipe navigates either orientation — only the axis with a focusable
neighbour moves; the other no-ops. This mirrors the temple swipe (the bare-metal doc: "forward →
DPAD_RIGHT + DPAD_DOWN"). Without the horizontal key, the ring couldn't move in the app list.
Also note: the Rokid temple **click fires `KEYCODE_ENTER`, not `DPAD_CENTER`** as the older doc
claimed — Compose's `clickable` handles ENTER so taps still work.

**Implications:**
- The 4 base gestures **are not editable** in v0.4. The Profile Editor shows them as `(system)`.
- Custom gestures (TRIPLE_TAP, QUADRUPLE_TAP, LONG_PRESS, all 4 combos, DOUBLE_LONG_PRESS,
  WRIST_SHAKE) continue through the profile system unchanged.
- The router's "screen-off fast path" (§5.1) is checked **first** — wake-via-LONG_PRESS still
  bypasses the synthesiser entirely.
- **Out-of-app dispatch** (v0.4): `CompositeSystemKeyDispatcher` sends base-gesture KeyEvents to
  the foreground Activity window when Halo Ring is foreground, else routes them through the agent's
  `InputManager.injectInputEvent` system-wide — so the ring drives *other* apps too (needs the
  agent bootstrapped).

Implementation: `:core/gesture/SystemKeyDispatcher.kt` interface + `:app/.../ui/ActivitySystemKeyDispatcher.kt`
production impl that calls `Activity.dispatchKeyEvent` on the main thread. Wired by `MainActivity.onResume/onPause`.

---

## 4. Mapping profiles

A **profile** is a complete behavioural setting: which `GlassAction` each gesture produces +
which timing knobs are on/off + which apps auto-activate this profile.

```kotlin
data class KeyMapProfile(
    val id: String,
    val name: String,
    val map: Map<Gesture, GlassAction>,
    val gestureConfig: GestureConfig,             // multi-tap window, optimistic-tap, etc.
    val triggerPackages: List<String>,            // for auto-switch by foreground app
)
```

### 4.1 The three built-in profiles (v0.4 — Fast dropped)

Each profile fills all 13 gesture slots. Slots reserved for the system layer (TripleTap,
QuadrupleTap, LongPress, DoubleLongPress — see §5) are effectively overridden by it so
they show as "(system)" in the mapping UI and can't be double-bound.

#### Navigation (default — for browsing menus and the system launcher)

```
TAP                     → Confirm
DOUBLE_TAP              → Back
SWIPE_UP                → NavPrev          (= DPAD_UP on Rokid / forward-swipe on RayNeo)
SWIPE_DOWN              → NavNext
LONG_PRESS              → Menu             (the system menu key)
DOUBLE_TAP_SWIPE_UP     → TakePhoto
DOUBLE_TAP_SWIPE_DOWN   → AskVisualAI
LONG_PRESS_SWIPE_UP     → Notifications   ← "press and pull up to pull notifications down"

config: optimistic single-tap OFF
        awaitCombos       ON
        awaitLP combos    ON
```

Precision matters more than speed in menu navigation; `optimisticSingleTap = false` so TAP
unambiguously commits after the window.

#### Media (short video / music)

```
TAP                     → MediaPlayPause   (fires instantly — optimistic)
DOUBLE_TAP              → Back
SWIPE_UP                → MediaPrev
SWIPE_DOWN              → MediaNext
LONG_PRESS              → VolumeUp         (quick +1; for finer control use the modal)
DOUBLE_TAP_SWIPE_UP     → TakePhoto
DOUBLE_TAP_SWIPE_DOWN   → AskVisualAI
LONG_PRESS_SWIPE_UP     → EnterVolumeModal ← swipes then change volume continuously

config: optimistic single-tap ON
```

Auto-triggered by: any package the user adds to `triggerPackages` (video / music apps).

#### Reader (teleprompter / translation / long-form reading)

```
TAP                     → Confirm          (optimistic, instant)
DOUBLE_TAP              → Back
SWIPE_UP                → NavPrev          (previous page/line)
SWIPE_DOWN              → NavNext
LONG_PRESS              → Home
DOUBLE_TAP_SWIPE_UP     → TakePhoto
DOUBLE_TAP_SWIPE_DOWN   → OpenTranslate    ← "translate what I'm looking at"
LONG_PRESS_SWIPE_UP     → OpenChat         ← start AI conversation about the text

config: optimistic single-tap ON
```

Auto-triggered by: Translate / WordTips Activities (Rokid Sprite Launcher).

#### ~~Fast~~ — **dropped in v0.4 (on-glasses iteration)**

The Fast profile existed to give "instant TAP + instant LONG_PRESS, no combos". Post-v0.4 that's
the *default* behaviour: the 4 base gestures are instant system KeyEvents (§3.8) and LONG_PRESS is
the near-instant screen-toggle (§5). So Fast was redundant and is no longer in
`DefaultProfiles.ALL` (now **Navigation / Media / Reader**). The definition is kept in source for
easy restore.

> **v0.4 profile notes (on-glasses):** the per-profile `LONG_PRESS` binding above (Menu / VolumeUp /
> Home) is **shadowed while the screen is on** by the system SLEEP slot (LONG_PRESS → ScreenSleep) —
> rebind the SLEEP slot in System Gestures to get the profile's LONG_PRESS back. Every profile also
> binds the new **`WRIST_SHAKE`** air-gesture: Navigation/Media → AI assistant, Reader → AI chat.

### 4.2 ModeManager: switching between profiles

[`ModeManager`](../app-project/core/src/main/kotlin/com/halo/ring/core/action/ModeManager.kt) owns
the profile list + the active one. Three switching mechanisms:

| Mechanism | How | Effect |
|---|---|---|
| **Triple-tap** | System-level gesture (see §5) | `cycleNext()` → next in the list. Triggers manual lock. |
| **Auto-switch** | AccessibilityService `WINDOW_STATE_CHANGED` → `ModeManager.onForegroundPackage(pkg)` | Match `pkg` against each profile's `triggerPackages`; switch if a match is found that isn't the current profile. **Respects** the manual lock — won't override a recent triple-tap. |
| **Manual** | Settings page | `switchTo(id)` — explicit user choice. Triggers manual lock. |

**Manual lock duration** is 5 s (configurable). It prevents the natural "I want to override
auto-switch for this thing" flow from being immediately re-overridden by auto-switch firing on
the same foreground package.

### 4.3 Per-switch feedback

On every profile change:
- **HUD toast** flashes the new profile name for ~2 s
- **Tone**: a brief click via `ToneGenerator`
- **Ring LED**: one blink (`BLINK_TWICE`)

The redundant audio + ring-LED feedback is deliberate — AR display content is often outside the
user's focus, so eyes-off confirmation matters.

**v0.4 — distinct per-gesture sounds:** beyond profile-switch feedback, every recognised gesture
now plays its own `ToneGenerator` tone (`GestureSounds`) — TAP a crisp blip, DOUBLE_TAP a
double-blip, swipes high/low, LONG_PRESS sustained, WRIST_SHAKE distinctive — so the wearer can
tell *what* the ring understood without looking. Gated by the "UI click sound" feedback pref.

---

## 5. System-level gestures (screen on/off + meta)

These five gestures are intercepted **before** the active profile by `InteractionRouter`
(see [04](04-architecture.md) §6). They produce the same action regardless of which profile is
active.

| System action | Default gesture | Only when | Why this default |
|---|---|---|---|
| **`ScreenWake`** | `LONG_PRESS` | screen off | Single raw event from the ring → **fast path bypasses the synthesiser** → instant. Deliberate, doesn't trigger from a casual brush. And it doesn't collide with the ring's own "double-tap to wake" because wake-swallow only consumes TOUCHes. |
| **`ScreenSleep`** | `LONG_PRESS_SWIPE_DOWN` | screen on | "Press and pull down" metaphor. Hard to misfire — important because waking the screen costs power. |
| **`ProfileCycle`** | `TRIPLE_TAP` | screen on | Explicit, distinct from any common action. |
| **`PeekHud`** | `QUADRUPLE_TAP` | screen on | Very deliberate; pure read-only. |
| **`ForceReconnect`** | `DOUBLE_LONG_PRESS` | screen on | Extremely deliberate; only needed when something's gone wrong. |

All five are reassignable via Settings → System Gestures.

### 5.1 The screen-off fast path

When the screen is off and we receive a raw event:

```
if raw == systemGestures.wake (matched against RawGesture, not Gesture!):
    dispatch ScreenWake
else:
    drop silently   # no LED, no HUD; explicitly not "feedback that something happened"
```

Notice we match the **raw** event, not the **synthesised** gesture — that's the speed win.
Long-press takes ~400 ms via the synthesiser (waiting for follow-up combos); via the fast path
it fires the moment the ring sends `73 2D 04`. Sub-100 ms wake latency end-to-end.

The fast-path only supports raw-event wake gestures: `LONG_PRESS`, `SWIPE_UP`, `SWIPE_DOWN`. If
you bind wake to a synthesised gesture like `DOUBLE_TAP`, the fast path doesn't fire — the
synthesiser must run, which adds ~280 ms minimum. (We don't recommend it.)

### 5.2 The on-screen system layer (gestures 2-5)

When the screen is on, the synthesiser produces `Gesture`s normally; the router then checks the
system map first:

```kotlin
when (gesture) {
    systemGestures.sleep          → ScreenSleep
    systemGestures.profileCycle   → ModeManager.cycleNext()
    systemGestures.peekHud        → onPeekHud()
    systemGestures.forceReconnect → onForceReconnect()
    else                          → fall through to modal/profile
}
```

`LONG_PRESS` (= the wake gesture) is intentionally **not** in the on-screen system map — when the
screen is already on, "wake" is meaningless, so it falls through to the profile (e.g.
Navigation's `LONG_PRESS → Menu`).

### 5.3 Why these specific defaults

| Question | Answer |
|---|---|
| Why not single-tap or double-tap to wake? | Both can fire accidentally (hand brushing the ring, the ring's own auto-sleep wake). Long-press is deliberate. |
| Why does sleep need to be more complex than wake? | Waking the screen costs power; an accidental wake is annoying but short-lived. Putting the screen to sleep accidentally would interrupt the user's task — much worse. |
| Why is wake on the raw event, not the synthesised one? | Speed. Single raw event = ~50–80 ms end-to-end; synthesised LONG_PRESS = +400 ms follow-up window. |
| Why is force-reconnect in there at all? | When the BLE link gets confused (rare but possible in busy 2.4 GHz environments), without a way to manually reset you'd have to kill the app from settings. Two long-presses is a "two-key combo" that requires intent. |

---

## 6. The modal layer

A **modal** is a transient interaction state opened by one gesture, where subsequent gestures
have special meaning until it exits. Implemented in `:core/modal/` (4 modal state machines —
`VolumeModal`, `BrightnessModal`, `RecentsModal`, `AIDictateModal`; 17 tests in `ModalsTest.kt`).

The router checks `activeModal != null` between the system-gesture layer and the profile layer;
when set, `activeModal.handle(gesture)` takes over. Sentinels exit the modal: `ModalSentinel.Exit`
(confirmed), `ModalSentinel.Cancel`, `ModalSentinel.FireAndExit(payload)` (dispatch one final
action + close). LED feedback: slow double-flash (~1 Hz) while active; two quick flashes on
confirm; nothing on cancel/timeout.

Default entry gestures: `VolumeModal` ← Media profile's `LONG_PRESS_SWIPE_UP`. Others are
unbound by default and rely on the user assigning them via the Profile Editor.

---

## 7. Hand-over between two pairs of glasses

The R08 ring connects to one BLE central at a time. The hand-over algorithm relies on each
glasses' `WearStateProvider` reporting whether it's currently being worn:

```
A: I'm worn AND ring is connected to me → hold the connection.
A: I become not-worn → disconnect from the ring (release).
B: I'm worn AND ring is not connected to me → autoConnect picks it up within ~1-2 s.
```

If both pairs report "worn" (probably a sensor confusion), the first to grab the BLE link wins;
the other shows "Ring is in use on the other glasses" and offers a `ForceReconnect` button.

This is described in user-facing terms in [09-user-manual.md](09-user-manual.md) §8.

---

## 4.4 External plugin actions (Doc/18 protocol)

A gesture can be bound to an action provided by another installed Android app instead of one of
Halo Ring's built-in `GlassAction` cases. From the wearer's perspective there's no observable
difference — the action shows up in the picker under "EXTERNAL APPS", the binding persists like
any other, and firing the gesture invokes the plugin within ~10–50 ms.

Under the hood: when the gesture is recognised, [`HaloRingService`'s `onGestureRecognized`](../app-project/app/src/main/kotlin/com/halo/ring/service/HaloRingService.kt)
listener intercepts `GlassAction.PluginAction` before the executor-backend chain, and
[`PluginTrigger`](../app-project/app/src/main/kotlin/com/halo/ring/plugin/PluginTrigger.kt) sends
a targeted `com.halo.ring.action.TRIGGER` broadcast (gated by the
`com.halo.ring.permission.SEND_PLUGIN_TRIGGER` permission so spoofers can't fire plugin actions).
The plugin's own `BroadcastReceiver` handles the trigger however it likes.

**Pushed profiles** (optional, for overlay apps) — an overlay plugin can temporarily push a
gesture-binding overlay via the `PROFILE_PUSH` broadcast: `SWIPE_UP/DOWN` becomes "move focus
across the overlay's options" etc., regardless of the wearer's underlying profile. The push is
LIFO-stacked, falls through for unbound gestures, and is auto-popped when the owning package is
uninstalled. System gestures (TRIPLE_TAP / QUAD_TAP / LP+SWIPE / DOUBLE_LP) always bypass the
push stack — the wearer can never be locked out of profile cycling, peek-HUD, or AI-assistant.

See [Doc/18 — External-App Plugin Protocol](18-plugin-protocol.md) for the full wire format
+ test matrix.

---

## 8. State machines summary

There are **five concurrent state machines** in the runtime. Knowing which is which helps debug
issues:

| # | State machine | States | Transitions | Side effects |
|---|---|---|---|---|
| 1 | `KeyMapProfile` (the active one) | Navigation / Media / Reader / user-defined (Fast dropped v0.4) | Triple-tap cycle, auto-switch, manual select | Re-binds the 13 gestures; updates HUD; flashes ring LED; updates `GestureConfig` of the synthesiser |
| 2 | `ConnectionState` | DISCONNECTED / SCANNING / CONNECTING / READY | BLE callbacks | HUD signal indicator; arm wake-swallow on transition to READY |
| 3 | `WearState` | WORN / OFF | Sensors (`RokidDoorReceiver` / Mercury 佩戴检测) + `ACTION_SCREEN_ON/OFF` fallback | Drives ring hand-over (§7); gates `TOUCH_ENABLE/DISABLE` (see [04](04-architecture.md) §7) |
| 4 | `ActiveMode` (BLE conn interval) | ACTIVE (short interval, ~15-30 ms) / IDLE (~100-200 ms) | recent-gesture timeout (~10 s) | BLE connection priority request |
| 5 | `WakeSwallow` | armed(N) / disarmed | armed on reconnect | Eats N raw TOUCHes |

Plus the synthesiser's internal sub-states (tapCount, inComboWindow, inLpFollowupWindow) — those
are sub-second internals, not user-visible.

---

## 9. End-to-end timeline example: "open AI chat in Reader mode"

```
t=0      ms    user puts on glasses
t=200    ms    WearStateProvider fires WORN; service starts BLE
t=400    ms    BLE scan finds ring R08_xxxx (autoConnect from cradle release)
t=900    ms    GATT services discovered; notify enabled
t=1700   ms    TOUCH_ENABLE sent
t=2200   ms    TOUCH_MODE sent
t=2400   ms    `73 2A 00` arrives (TouchStatus enabled); ring LED ack (two blinks)
t=2500   ms    BatteryQuery; first battery shown on HUD
t=2700   ms    User opens "Word Tips" via voice; foreground = WordTipsPageActivity
t=2750   ms    AccessibilityService fires WINDOW_STATE_CHANGED → ModeManager.onForegroundPackage()
t=2760   ms    Auto-switch: Navigation → Reader (matches triggerPackages); HUD flashes; ring LED blinks
t=12000  ms    User does: LONG_PRESS + SWIPE_UP
t=12000  ms    `73 2D 04` arrives; synthesiser starts LP follow-up window (400 ms)
t=12100  ms    `73 2D 01` arrives; synthesiser cancels LP timer; emits LONG_PRESS_SWIPE_UP
t=12110  ms    InteractionRouter checks system gestures (no match); checks modal (none); profile = Reader
t=12110  ms    Reader profile: LONG_PRESS_SWIPE_UP → OpenChat (a GlassAction)
t=12110  ms    ActionRouter dispatches to AppProcessAgentBackend (priority 100, ready)
t=12112  ms    Agent receives `AM start -n com.rokid.os.sprite.launcher/.page.chat.ChatPageActivity`
t=12130  ms    Activity launches; user sees AI chat UI
```

Total time from gesture to glasses-UI reaction: ~130 ms. Well within the §6 performance target
of <200 ms felt latency, mostly bounded by the launcher's own startup time, not our pipeline.
