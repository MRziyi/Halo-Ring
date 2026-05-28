# 13 — Handoff & State Snapshot

> **For the next agent picking up this project.** This is a **point-in-time state snapshot**.
> Keep it short. Forward planning lives in [Doc/20 v0.4 design](20-v0.4-design.md). Chronological
> audit history lives in [`_archive/13-handoff-pre-v0.4.md`](_archive/13-handoff-pre-v0.4.md) +
> the git log + the audit-pass memories.

Last updated: 2026-05-28 (v0.4 implemented + validated on real Rokid Glasses; on-glasses iteration
round done — see [Doc/20 §17](20-v0.4-design.md) for the shipped status + deltas).

---

## 1. State snapshot — where the project is right now

### 1.1 What's the project

A **QRing R08 smart ring** acting as a wireless remote for **Rokid Glasses** (primary) and
**RayNeo X3 Pro** (secondary). One ring, one app, two flavor builds. See
[01-overview.md](01-overview.md).

The project's twin pillars (locked 2026-05-27 by Zack):

1. **Gesture device** — 4 base gestures mirror the temple touchpad (system KeyEvents); a
   **13-gesture vocabulary** on top includes custom taps/long-press/combos **plus the v0.4
   `WRIST_SHAKE` air-gesture** (accelerometer), editable per-profile + extendable via the Doc/18
   Plugin Protocol. **Custom gestures are the project's headline value-add — none get removed.**
2. **Full SPEC v3 protocol coverage** — every BLE capability reverse-engineered in
   [`R08-dev/phase0/SPEC v3.md`](../../R08-dev/phase0/SPEC%20v3.md) (1797 lines) is exposed:
   vitals, sport, accel, find, battery, activity, capability bitmap, wear-state.

### 1.2 Hardware status

| Device | Status |
|---|---|
| **R08_E600 ring** (MAC `30:35:47:33:E6:00`, FW `RT08_3.10.46_250621`) | ✅ on hand, verified end-to-end |
| **Rokid Glasses** (`RG_glasses`, adb serial `1906092606090906`) | ✅ **on hand — v0.4 validated on it** |
| **RayNeo X3 Pro** | On hand; rayneo flavor builds clean, not yet on-device validated |
| OnePlus 9 Pro burn-test rig (adb serial `854afb6b`) | ✅ protocol/agent rig (don't run the app here while testing on glasses — BLE is one-central-at-a-time) |

### 1.3 Build state

- `app-project/core/src/test/...` — **282 unit tests, all passing** (0 failures)
- `:app:assembleRokidDebug` / `:app:assembleRayneoDebug` — both build clean (~15 MB)
- `:app:assembleRokidRelease` — R8 shrink, ~4 MB
- `.github/workflows/build-apks.yml` + `core-tests.yml` — CI on push / PR / `v*` tag
- **The entire v0.4 refactor + on-glasses iteration is UNCOMMITTED in the working tree** (HEAD is
  still `19f2824` audit-pass-y). This is the big closeout commit. See [Doc/20 §17](20-v0.4-design.md)
  for the full change list. `git status` in Halo-Ring shows ~40 modified + 6 new + 5 deleted +
  9 doc renames.

### 1.4 What's verified on the actual Rokid Glasses (2026-05-28)

✅ App runs as a Service; ring pairs + **fast connect ~3 s** (`autoConnect=false`) → READY + **17
capabilities** decoded; base gestures drive the system (TAP=DPAD_CENTER, swipes dual-axis nav incl.
the horizontal app list); LONG_PRESS toggles screen sleep/wake; per-gesture sounds; ring nav
highlight works (FocusRequester + dual-axis swipe).

✅ **Agent proven on real Rokid** (`app_process` → `InputManager.injectInputEvent`, abstract
`LocalServerSocket("halo.agent")` listening, heartbeat fresh, `WRITE_SECURE_SETTINGS` granted via
`pm grant`). **BUT bootstrapped via USB as a dev shortcut** — see §1.5 open item.

✅ Hardware facts learned: Rokid temple click = `KEYCODE_ENTER`, double-click = `KEYCODE_BACK`,
single-finger temple swipe = `KEYCODE_NOTIFICATION` (not DPAD → the **ring**, not the temple, is the
nav device). Sprite app list is laid out **horizontally**.

🟡 WRIST_SHAKE wired (default → AI assistant) but needs Spatial-features ON + worn + screen-on; not
yet user-confirmed firing on-glasses.

### 1.5 TOP OPEN ITEM — wireless pairing + reboot survival on Rokid

The agent currently runs because it was **USB-bootstrapped** (dev shortcut). For a real no-computer
user, the wizard's **wireless `AdbPairingOverlay` 6-digit flow** must work on Rokid — that's the
one piece of the bootstrap chain **never validated on Rokid** (only OnePlus loopback). And
**reboot-survival** (`bootRecoverAgent`) needs that wireless trust (persisted keypair + adbd
`adb_keys` entry) established first — so it won't survive a reboot until the wireless flow is run
once. **Validate this before claiming "no computer, survives reboot".**

⚠️ Firmware limitations (per SPEC v3, not our bugs): BP / Stress / HRV / Temp via `0x69` are
progress-only on this firmware; Healthcheck composite (`0x69 type=05`) only lights green LED on
this unit.

---

## 2. What to do next

### 2.1 v0.4 is SHIPPED — what's left

**[Doc/20 v0.4 design](20-v0.4-design.md)** is the canonical record. Its phases **C1–C7 are all
done + glasses-validated** (see Doc/20 §17 for the shipped status + the on-glasses deltas:
dual-axis swipe, LONG_PRESS screen-toggle, WRIST_SHAKE, per-gesture sounds, energy backoff + accel
gating, CompositeSystemKeyDispatcher, wizard rework, Fast dropped).

**The three things left, in priority order:**

1. **🔴 Validate wireless pairing + reboot-survival on Rokid** (§1.5 above) — the only un-validated
   piece of the no-computer story. Run the wizard's wireless `AdbPairingOverlay` flow on the glasses
   (not USB), confirm the agent bootstraps, then reboot and confirm `bootRecoverAgent` revives it.
2. **More sensor gestures** — palm-up/down orientation + flick/tilt as new bindable `Gesture`s.
   `AccelProcessor` already classifies posture (PALM_UP/DOWN/POINT) + impact; only the routing into
   new `Gesture` enum values + `InteractionRouter.onGesture` + UI (picker/Test Arena) is missing —
   same pattern WRIST_SHAKE already uses (`HaloRingService` accelProcessor emit-lambda).
3. **Home dashboard redesign + IA reorg** (decided: dashboard home ✅, plugins → More ✅). The
   Config Activity root is still a settings menu; restore the old designed Vitals-hero landing (big
   HR/SpO₂ + ring status + MEASURE NOW) per [`ui-mockup.html`](ui-mockup.html), and move Plugins out
   of the top-level group into More. See Doc/20 §17 "still queued".

### 2.2 Phone vs glasses debugging discipline (v0.4)

| Use phone (OnePlus) for | Use glasses for |
|---|---|
| BLE protocol, `:core` JVM tests | HUD overlay visual / layout |
| Plugin protocol cross-process (`:test-plugin`) | "How does the ring feel as a daily remote?" |
| Agent KeyEvent injection path | Sprite Launcher integration |

Once Rokid Glasses arrive, **UI iteration moves to the glasses**.

---

## 3. Important context the next agent should know

### 3.1 Design errors that have been corrected

- The ring's BLE `0x08` is **reboot**, not battery (battery is `0x03`). Original `R08-Dev.md` got
  this wrong; SPEC v3 and [Doc/02](02-hardware-and-protocol.md) are correct.
- The ring has **no left/right swipes** — only up, down, touch, long-press.
- v0.3 mental model was "ring = remap any gesture to anything"; v0.4 mental model is
  "**ring = wireless extension of the temple touchpad**" — 4 base gestures hard-locked to system
  KeyEvents, 8 custom gestures fully programmable. See [Doc/20 §2.3](20-v0.4-design.md#23-the-mental-model-invariant).

### 3.2 Decisions that look small but matter

- **Pure black background**, single green accent matching the ring's LED. APL ≤ 13% by design.
- **`:core` is dependency-free except Kotlin stdlib.** No coroutines in the test path, no Android
  imports. Keeps the gesture state machine JVM-testable.
- **No persistent CPU wakelock** — BLE controller IRQ wakes the CPU on every notify.
- **Touch IC stays on when worn even if screen is off** so wake-gesture works.
- **HUD overlay uses `WindowManager` `TYPE_APPLICATION_OVERLAY`** so it appears above any app.
- **4 base gestures hard-locked, not surfaced in Settings UI** (v0.4 invariant — Zack 2026-05-27).
- **Beijing-locked SetTime helper** — SPEC §4.8 verified the fw treats SetTime BCD as UTC+8.

### 3.3 Threading discipline (read before changing the service)

The whole pipeline runs on **one** thread: `AppGraph.scheduler`'s HandlerThread. `serviceScope`
is bound to `scheduler.coroutineDispatcher`. Don't `launch` on `Dispatchers.Default` from inside
the service unless you've thought through what you might race on.

Designed exceptions:
- `AppProcessAgentBackend.perform` hops to `Dispatchers.IO` for blocking socket I/O.
- BLE callbacks land on a binder thread but immediately repost via `scheduler.post`.
- HUD overlay `show/hide/setPosition` are `runOnMain`-wrapped internally.

### 3.4 Where to look when something feels wrong

| Symptom | First place to look |
|---|---|
| Gesture not recognised | `GestureSynthesizerTest.kt` — every documented behaviour is asserted |
| Action not firing | `InteractionRouter` layer ordering — see [Doc/05 §5](05-interaction-design.md) |
| HUD not appearing | `SYSTEM_ALERT_WINDOW` permission granted? `HudOverlay.ensureViewInstalled` falls gracefully on `WindowManager.BadTokenException` |
| Build issues | Compose / Kotlin / AGP version drift; bump in `build.gradle.kts` |
| Ring connects then drops | SPEC §6.5 fw quirk — auto-reconnect handles it; check intervals in `PowerPolicy` |

### 3.5 Files to be careful of

- `GestureSynthesizer.kt` — order of operations in `onTouch` / `onLongPress` / `onSwipe` is
  subtle. The test suite catches regressions. Change with care.
- `InteractionRouter.kt` — the 4-layer routing (screen gateway → system → modal → profile) is
  delicate. Don't add layers without explicit rationale.
- `R08Protocol.kt` constants — verified against SPEC v3. Changing any byte value is a bug magnet;
  defer to phase-0 re-verification on real hardware first.
- `AndroidR08BleClient.kt` — idempotence trackers (`lastTouchEnabledRequested`,
  `lastIntervalModeRequested`) prevent BLE write storms. Reset to null on disconnect / stop.

---

## 4. Out of scope (don't do)

| Don't | Why |
|---|---|
| Continuous heart-rate streaming | PPG LED draws too much; on-demand only |
| Always-on raw-IMU mode | <1 day battery; spatial mode off by default with a warning |
| Per-profile colour theming | Clutters the small canvas; one green accent, period |
| Light theme | Wastes pixels and leaks light; black canvas only |
| Mobile companion app as the primary surface | Product is "ring as remote for glasses" — phone-in-the-middle defeats the value |
| Multi-account / multi-user features | One wearer per device |
| Real-time charts / sparklines in vitals | The display can't render them well at this resolution |
| Network features (cloud sync of profiles) | Local-only on the glasses |
| Restore the 5-step FirstRunWizard | v0.4 collapses to 1 step (pair → done) |
| Re-introduce `InAppFocusController` for base-gesture routing | v0.4 explicitly deletes it; Compose `FocusManager` + system KeyEvents handle DPAD natively |

---

## 5. Where to look for…

- **Forward plan / next sprint**: [Doc/20 v0.4 design](20-v0.4-design.md)
- **BLE protocol bytes**: [`R08-dev/phase0/SPEC v3.md`](../../R08-dev/phase0/SPEC%20v3.md) (canonical) + [Doc/02](02-hardware-and-protocol.md) (Halo-Ring integration notes)
- **Gesture vocabulary + state machine**: [Doc/05](05-interaction-design.md)
- **UI design + HUD**: [Doc/08](08-ui-design.md)
- **Plugin protocol** (Constellation integrates here): [Doc/18](18-plugin-protocol.md)
- **Architecture (modules / strategies / backends)**: [Doc/04](04-architecture.md)
- **Audit-pass chronology + pre-v0.4 priority tables**: [`_archive/13-handoff-pre-v0.4.md`](_archive/13-handoff-pre-v0.4.md) + git log + auto-memory at `~/.claude/projects/-Users-Zack-Code-Projects-R08-dev/memory/`
- **Rokid bare-metal SDK** (KeyEvent constants, broadcast actions): `~/Code/Projects/Constellation/reference/rokid-glass/bare-metal-docs/`
- **Constellation-Glass design** (the philosophy v0.4 borrows from): `~/Code/Projects/Constellation-Glass/Doc/GLASS-CLIENT-DESIGN.md`

---

## 6. Questions you'll probably ask

**Q: Why two flavors (rokid + rayneo) instead of one APK with runtime detection?**
A: Build-time selection prevents accidental crossover and the "one APK" benefit doesn't
materially matter when each user has at most one or two pairs of glasses. Runtime detection
remains as a sanity check + GENERIC_ANDROID dev fallback.

**Q: Why an `app_process` agent instead of `am`/`input` shell commands?**
A: `input keyevent` spawns a JVM each time (~50–150 ms). The agent is persistent (one process,
one socket, one connection) and calls `InputManager.injectInputEvent` directly via reflection
(~1-3 ms). **The biggest performance win in the whole project.** See [Doc/06 §1.2](06-performance-and-power.md).

**Q: Why not just use Shizuku?**
A: Supported as a secondary backend (priority 90) — if the user installs Shizuku, we'll use it.
We don't require it because it's an extra install. The `app_process` agent ships with our APK.

**Q: Can the ring be used without the ADB bootstrap (just via Accessibility)?**
A: Partially. AccessibilityService on Android 12 exposes only BACK / HOME / RECENTS /
NOTIFICATIONS — no DPAD key injection (API 33+). System-UI gesture navigation needs ADB.

**Q: What's the dependency between BLE client and the rest?**
A: One-direction pipeline: `R08BleClient.events() → GestureSynthesizer → InteractionRouter →
ActionRouter → ExecutorBackend → injection`. Synthesizer is testable with `FakeR08BleClient`.

**Q: How did v0.4 decide what to keep vs cut?**
A: Two pillars (gesture device + SPEC v3 coverage). Anything serving them stays (Profile Editor,
Action Picker, Vitals dashboard, Plugin Protocol). Anything that's phone-style-app cruft on
glasses goes (5-step Wizard, GuidedTour, InAppFocusController, full-screen Status/About). See
[Doc/20 §3-4](20-v0.4-design.md).
