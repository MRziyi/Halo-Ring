# 08 — UI Design

> **Status**: design complete. Most Compose composables are implemented in
> [`../app-project/app/src/main/kotlin/com/r08remote/app/ui/`](../app-project/app/src/main/kotlin/com/r08remote/app/ui/);
> wiring to the runtime (BLE / agent / service) is the remaining work — see
> [13-handoff.md](13-handoff.md) priorities A5 (HUD wiring), B1–B9 (detail settings screens).
>
> **Live visual mockup**: open [`ui-mockup.html`](ui-mockup.html) in a browser. It renders every
> screen at 1:1 actual size (480×480) and includes a binocular preview, the design tokens, and
> all the HUD overlay variants. The mockup is the canonical reference for *what it looks like*;
> this markdown is the *why*; the Compose code is the *what*.

The UI lives on the glasses (we don't ship a mobile companion). Three jobs:

1. **Show the wearer's biometrics** that the ring streams in the background (HR, SpO2, stress,
   activity, ring battery).
2. **Configure the ring** — profiles, gesture mappings, system gestures, power preferences.
3. **Host the resident background service** — the user can verify it's alive and lean.

Two display constraints from the platforms:

- **Rokid Glasses**: single projector drives both eyes — both eyes see the same image
- **RayNeo X3 Pro**: binocular optics, but for unity we render the same content to both eyes
  (no stereo, no parallax). The X3 Pro's `BaseMirrorActivity` handles this for free.

Result: **we design and ship one 480×480 composition; both glasses display it identically.**

---

## 1. Three jobs → three tabs

```
┌──────────────────────────────────────┐
│  VITALS    SETTINGS    STATUS        │ ← tabs at top, current underlined
│  ━━━━━━━                             │
│                                      │
│  [content of selected tab scrolls]   │
│                                      │
└──────────────────────────────────────┘
```

| Tab | Function | Owner data | Visit frequency |
|---|---|---|---|
| **VITALS** | Job 1 — biometrics dashboard | HR / SpO2 / stress / steps / cal / distance / ring battery | Occasional (a few times a day at most) |
| **SETTINGS** | Job 2 — configuration | Profiles, gestures, system gestures, ring, power, vitals prefs, advanced, about | Rarely (after onboarding) |
| **STATUS** | Job 3 — resident-service info | Connection, BLE interval, profile, last gesture latency, background draw, BLE quality | Occasionally (when curious / debugging) |

The top-tab layout is justified because all three jobs **are equally important to the user's
trust** (especially #3 — they need to know the resident service isn't draining the battery
silently). Hiding STATUS in a settings sub-menu would betray that.

## 2. Visual language

Six tokens. Anything more is too much for a 480×480 see-through canvas.

| Token | Hex | Use |
|---|---|---|
| `--ui-bg` | `#000` | Background — minimises light leak through the optic |
| `--ui-fg` | `#fff` | Primary text and content |
| `--ui-mute` | `#8a8a8a` | Secondary text, dividers, inactive items |
| `--ui-accent` | `#5ee08c` | Focus indicator, primary action — matches the ring's green LED for visual coherence |
| `--ui-warn` | `#ffb84d` | Low battery, conflict warnings |
| `--ui-bad` | `#ff7c7c` | Error, destructive action (Forget & Re-pair, disconnect) |
| `--ui-line` | `#2a2a2a` | Dividers, card outlines |

**No solid coloured fills**. No gradients. No shadows. No glass-morphism. The display is
see-through; every lit pixel leaks into the wearer's view of the world AND costs display power.

> **APL ≤ 13%** (RayNeo design spec). The X3 Pro display thermal-throttles brightness when
> average picture level exceeds ~13%. Practical rule: large white panels dim within seconds.
> Stay on the black canvas; only use `--ui-fg` for text and `--ui-accent` for focus / actions.
> Our actual implementation is dominated by black + small white text, well under the cap. The
> HUD pill uses `Color(0xCC000000)` (80% black) deliberately.

> **Black = transparent** on additive see-through displays. `#000000` emits zero photons. Use
> it as default canvas, not as "dark grey" for sections — sections should be delimited by
> 1 px `--ui-line` dividers, not by tinted backgrounds.

### Type scale

| Token | Size / weight | Use |
|---|---|---|
| Title | 24 / 600 | Screen headers (Settings, Profiles) |
| Body | 17 / 400 | Default for content rows |
| Caption | 16 / 400 (mute) | Secondary information, hints |
| Tab | 16 / 600 (1px letter-spacing) | Tab labels — small, set-back |
| Mono | 16 / 600 (SF Mono) | MAC addresses, gesture names, command snippets |
| Metric | 56 / 700 (tabular nums, -2px letter-spacing) | Big numbers on Vitals (HR, SpO2, stress) |
| MetricKey | 14 / 400 (uppercase, mute) | Tiny grouping labels above big metrics |
| RowKey | 16 / 400 (mute) | Left-aligned row labels |

System sans-serif (`SF Pro` on Apple, `Roboto` on Android) — no custom font bundled. Saves APK
size and benefits from system optimisation.

> **16 sp is the floor.** RayNeo's design guide warns anything smaller renders with sub-pixel
> artifacts on the see-through panels (and `HarmonyOS_Sans_SC` is their system font). On our
> 480×480 canvas mapped to a ~30° FOV, smaller fonts also become genuinely unreadable. The
> only intentional exception is `MetricKey` (14 sp, all-caps short labels above 56 sp metric
> numbers — reads as a label, not body copy).

### Focus indicator

Critical: the wearer has no touch input. They navigate via the ring (or temple touchpad), so the
focused element must be **unmistakable**.

```
┌─ row, not focused ──────────────────┐
│  Profiles & Gestures           ›    │
└─────────────────────────────────────┘

┌──┰── row, focused ──────────────────┐
│  ┃ Profiles & Gestures         ›    │  ← 2 px green left bar
│  ┃                                  │  ← 7% green tint background
└──┸──────────────────────────────────┘
```

Both elements (bar + tint) together — either alone is too subtle on a small display in bright
ambient light.

### Six design principles

1. **Black canvas.** Default pixel = off. Light only what carries information.
2. **Transient by default.** Persistent UI only while the user is actively in a Settings screen.
   Everything else flashes for ~2 s then auto-hides.
3. **One column, one focus.** No multi-pane. Lists max ~5 items before scroll.
4. **Big text, generous space.** Equivalent of a 43" virtual screen at viewing distance —
   intentional overshoot is safer than under-sizing.
5. **Audio + ring LED, not just visual.** The wearer may be looking past the display. Mode
   switch → click + LED flash + brief HUD; wake → LED double-flash; etc.
6. **Same image to both eyes.** No stereo composition. Same `Composable` rendered identically.

---

## 3. Screen catalogue

(All rendered at actual size in [`ui-mockup.html`](ui-mockup.html). This is the index.)

### Top-level tabs

| # | Screen | Purpose | When seen |
|---|---|---|---|
| A | **VITALS dashboard** | HR / SpO2 / stress (big numerals), MEASURE NOW button, activity below | Default tab on launch |
| B | **SETTINGS root** | 7-item list: Profiles, System Gestures, Ring, Power & Connection, Vitals, Advanced, About | After-onboarding occasional visit |
| C | **STATUS** | Connection, BLE interval, profile, last gesture latency, background draw bar, BLE-quality sparkline | "Is the service OK?" |

### Settings detail screens

| # | Screen | Purpose |
|---|---|---|
| D | **Profiles list** | The 4 default profiles + add custom; current shown with green bullet |
| E | **Gesture mapping editor** | Per-profile, 12 rows (gesture name on left, action on right). 4 system slots greyed out |
| F | **System gestures** | The 5 always-on overrides (wake / sleep / cycle / peek / reconnect) and their bindings |
| G | **Ring** | MAC + firmware + signal + battery, FIND MY RING / SHUT DOWN / FORGET buttons |
| H | **Power & Connection** | Sliders for the three timing windows + BLE interval policy + keepalive toggle |
| I | **Feedback** *(new)* | Toggles for the user-facing feedback channels: gesture-hint HUD (see §10), audio click, ring-LED patterns, HUD position |
| J | **Vitals preferences** | What to show on HUD, opt-in for auto-snapshot, CSV export |
| K | Advanced (TBD) | Debug HUD toggle, backend status, latency-measurement mode, re-run ADB bootstrap |
| L | About (TBD) | Version, detected device profile, credits, links to Doc/ |

### HUD overlays

The HUD is **transient** — appears for ~2 s on events, or on QUADRUPLE_TAP. Top-right corner; a
small dark pill with a subtle backdrop-blur.

| Variant | Trigger | Look | Duration |
|---|---|---|---|
| **Default Peek** | QUADRUPLE_TAP / connection change | `●  Navigation  87%` | 2 s |
| **Mode switched** | TRIPLE_TAP | accent-bordered: `↻ →  Navigation  cycle` | 2 s |
| **Gesture recognised** | Any recognised gesture *while gesture-hint mode is on* (see §10) | small pill: `Double tap → Back` | 800 ms |
| **Low battery** | Ring ≤ 20% | warn-bordered: `●  R08_2A3F  18%` | 2 s |
| **Disconnected** | Lost BLE link | **center-positioned**, bad-bordered: `●  Ring disconnected` (only situation we use centre) | until reconnect |

### First-run wizard

Five full-screen steps:

1. **Welcome** — "Setup takes about a minute."
2. **ADB bootstrap** — one-shot `pm grant` shown as a monospace command to run on the computer.
3. **Accessibility** (optional) — deep-link to system Accessibility settings.
4. **Battery exemption** — Android prompt.
5. **Pair the ring** — auto-discovers `R08_xxxx`, shows MAC + RSSI, confirms with a ring-LED
   double-flash.

After step 5, drop the wearer into the **Vitals** tab.

---

## 4. Per-platform realisation

| | Rokid Glasses | RayNeo X3 Pro |
|---|---|---|
| Display | Single projector, right-eye-only (Doc/03 §1.1) | Binocular, dual eye-pieces |
| Native Activity | Plain `ComponentActivity` with a Compose root | Mercury SDK `BaseMirrorActivity` mirrors the Compose root to both eye-pieces automatically |
| Focus traversal | Standard Android (Compose `Modifier.focusable()`); driven by DPAD key events from the temple bar | Mercury SDK's `FocusHolder` + `FocusInfo` framework. **`Modifier.focusable()` alone is NOT enough** — Mercury's `TouchDispatcher` swallows the temple `MotionEvent`s before Compose sees them. Each focusable composable must register a `FocusInfo` via `focusHolder.addFocusTarget(...)`. The bridge layer wraps `Modifier.focusable()` so screen code stays platform-agnostic (Doc/03 §2.2). |
| Touch input | **None.** No `pointerInput { }` / drag composables — they're dead. Use `Modifier.clickable()` (DPAD_CENTER auto-triggers it) | Yes (temple touchpad), but consumed by Mercury SDK; apps should consume `TempleAction.SlideForward/Backward/UpwardsDownwards/Click/DoubleClick` from the SDK's `Flow`, not raw `MotionEvent`s (avoids fighting the user's "Natural mode" inversion toggle) |
| Content area | ~480 × 480 px usable, right-eye optic | 640 × 480 per eye; we centre our 480 × 480 composition with ~80 px black pad each side |
| Safe area | Standard Android insets (status bar managed by `PageActivity`) | 16 px on all sides at 5 m depth; our 24 dp `ScreenPadding` covers it |
| Min font | 16 sp (shared floor; see §2 type scale callout) | 16 sp (RayNeo design spec) |
| APL ceiling | No published cap; black canvas is universally safe | ≤ 13% (above that, thermal throttling dims the panel mid-session) |
| Anti-light-leakage | Inherit system setting (Rokid's "anti-light-leakage mode" dims brightness to min and hides status bar) | Inherit system setting (same concept) |

**One Compose tree, two Activity hosts.** No per-platform UI logic — but the RayNeo bridge
layer (`focusable` ↔ `FocusInfo`, `TempleAction` ↔ `InAppFocusController`) is a NON-trivial
flavor-specific module. As of 2026-05-13 it's planned but not yet wired (Doc/03 §2.3); needs
the Mercury AAR. Verification deferred to [11 §B8](11-verification-checklists.md).

---

## 5. Decisions on previously-open questions

(From the v0.7 draft — resolved.)

| Question | Decision | Rationale |
|---|---|---|
| HUD position | **Top-right corner** for normal events; **centre + enlarged** only for disconnect | Top-right is the least-intrusive area for the dominant hand-eye coordination (most users are right-handed and naturally glance right); centre reserved for "you should know about this right now" events |
| Brightness control | **Inherit system; never override** | Wearers configure their preferred brightness once; we shouldn't surprise them. Anti-light-leakage mode propagates naturally |
| Accent colour | **Single green** (`#5ee08c`), matches the ring LED | Visual + tactile coherence; rejects per-profile colour-coding (would clutter a small canvas) |
| Settings depth | **2 levels max** (Settings root → individual screen → at most one editor below) | Anything requiring deeper navigation gets redesigned |
| Custom profile creation | **Copy from existing** (default behaviour) + rename + tweak | Less laborious than a blank form; covers 95% of intent |
| Color tabs vs underline | **Underline + green accent on active**; mute label otherwise | Underline is the lightest tab pattern; chrome-mass minimised |
| Show binocular preview to user | No, we render the same content to both eyes; the user never sees a "binocular preview" | The binocular preview in the mockup is for design/dev review only |

---

## 6. Implementation notes

| Concern | Approach |
|---|---|
| **HUD overlay** | A `WindowManager` `TYPE_APPLICATION_OVERLAY` view hosted by `R08RemoteService` so it appears above any app, not just ours. Compose composable internally. Auto-hide via `delay()` in a coroutine. |
| **System bar / anti-light-leakage** | We respect the system flag (`Settings.System.someBrightnessMode`); never override. Apps like Rokid's Translate use it; our HUD should too |
| **Tabs** | A `TabRow`-equivalent custom composable (Material `TabRow` has unwanted padding). 3 fixed tabs; selected state simply changes the underline + bold. |
| **Focus indicator** | A single shared `Modifier.r08Focus()` extension that applies 2 px left border + 7% green tint. One implementation; every focusable item uses it |
| **No animations** | Just appear / disappear. Animation has cost (CPU + lit pixels) and AR users don't expect mobile-app fluidity |
| **Sound** | `ToneGenerator(STREAM_NOTIFICATION).startTone(TONE_PROP_BEEP, 30)` for mode-switch click. Same for HUD-shown events |
| **Ring LED feedback** | Via `R08BleClient.blinkLed()` (writes `0x10` or pattern via repeated commands). See [05](05-interaction-design.md) §4.3 for the patterns |
| **No images / icons bundled** | Use unicode glyphs (●, ›, ⤓, ⌖) and CSS-style line drawing in Compose. Saves APK size + keeps consistent line weight |

---

## 7. What's NOT in the design

(Things we explicitly decided against.)

- **No per-profile colour theming.** One green accent, period.
- **No charts in Vitals.** Big numbers + (optional, later) a tiny sparkline. No D3-style anything.
- **No "swipe down" or "long-press" gestures *on the UI itself*.** The user navigates the UI
  through whatever the ring's current profile binds — same gestures, no separate UI vocabulary.
- **No "tap target" sizing in the conventional sense.** No touchscreen.
- **No tutorials within the app (after first-run).** Help text inside screens is two lines max.
- **No notifications from this app** beyond the persistent foreground-service notification (which
  is required by Android).
- **No light theme.** Black canvas always.
- **No multi-account, no profiles-per-user.** One wearer.

---

## 9. In-app navigation — how the wearer drives our own UI

The whole product premise is "use the ring to drive the glasses". That includes **our own
Settings UI**: the wearer must be able to configure the ring using the ring itself (eating our
own dog food).

### 9.1 The injection path

When our Activity is foreground, gestures route as follows:

```
Ring TAP (= Confirm GlassAction in profile)
  → InteractionRouter resolves to GlassAction.Confirm
  → InteractionRouter checks: is *our* Activity foreground?
  → YES → InAppFocusController.confirm()       (direct call — no agent, no ADB)
  → NO  → ActionRouter → executor backend → injection → focused window
```

The "foreground detection" comes from a simple `MainActivity.isInForeground` flag set in
`onResume`/`onPause`. No accessibility needed for this path.

`InAppFocusController` calls Compose's `FocusManager` directly:

| GlassAction | InAppFocusController call | Compose effect |
|---|---|---|
| `NavPrev` (default = SWIPE_UP) | If focus is on the tab strip: `tabs.prev()`. Otherwise: `moveFocus(FocusDirection.Up)`. | Move focus up one row, or cycle to previous tab if at the top |
| `NavNext` (default = SWIPE_DOWN) | If focus is on the tab strip: `tabs.next()`. Otherwise: `moveFocus(FocusDirection.Down)`. | Move focus down or to next tab |
| `Confirm` (default = TAP) | Compose's `Modifier.clickable()` already handles DPAD_CENTER → onClick; no special routing needed | Activate focused row / tab |
| `Back` (default = DOUBLE_TAP) | `navigator.pop()` (or `finish()` at root) | Back-stack handling |
| `Home` | `finish()` | Leaves the app |
| `NavLeft` / `NavRight` | `moveFocus(Left / Right)` — useful for user-defined profiles that bind a gesture to these | (no default profile binds these, since the ring has no left/right swipes) |
| Everything else (Volume, Camera, etc.) | falls through to the executor backend (agent) | Same as outside our app |

### 9.1.1 Tab strip navigation

The ring only emits **vertical swipes** (`SWIPE_UP` / `SWIPE_DOWN`) — there are no left/right
swipes. So the tab strip is navigated by:

1. **Swipe up past the top content row** → focus moves onto the active tab in the strip.
2. While focused on a tab, **SWIPE_UP** moves to the previous tab; **SWIPE_DOWN** moves to the
   next tab (and one more SWIPE_DOWN moves back down into the content of the newly-selected tab).
3. **TAP** while focused on a tab activates / confirms it (most relevant when you've cycled
   to a different tab and want to commit).
4. **DOUBLE_TAP** still means Back regardless of where focus is.

The InAppFocusController distinguishes "focus on tab strip" from "focus on content" via a
state flag fed by `onFocusChanged` on the tab strip's focusable container.

This means: when the wearer is in Settings doing `Swipe up / Swipe down / Tap / Double-tap`,
the UI responds to ring gestures in ~50 ms (zero-cost — direct in-process call), not ~80 ms
(via the agent). Bonus speed.

### 9.2 Temple touchpad of the glasses themselves

The glasses' own temple touchpad should also drive the UI — useful for first-time setup
*before* the ring is paired, and as a fallback if the ring is dead.

| Glasses | How it works |
|---|---|
| **Rokid** | Temple bar → system DPAD key events → Android focus traversal → Compose `Modifier.focusable()` picks them up natively. **Zero extra code.** |
| **RayNeo X3 Pro** | Temple bar → MotionEvents → ARSDK `TouchDispatcher` → `TempleAction` Flow. Bridged: our `Activity` extends `BaseEventActivity`, subscribes to `templeActionViewModel.state`, translates each `TempleAction` to the equivalent `InAppFocusController` call. Same end behaviour. |

### 9.3 Profile-aware navigation

Different profiles map the same gestures to different actions, but **inside our UI** we want
consistent behaviour regardless of profile (otherwise Settings becomes profile-dependent — bad).
Solution: the `InteractionRouter` short-circuits to `InAppFocusController` with a **fixed
in-app mapping** when our Activity is foreground:

| Gesture | In-app action (constant) |
|---|---|
| TAP / SWIPE_UP / SWIPE_DOWN / LONG_PRESS / DOUBLE_TAP | Use the active profile's mapping translated to in-app focus (Confirm = activate, NavPrev/Next = up/down focus, Back = pop screen, Menu = open context menu if any) |
| TRIPLE_TAP / QUADRUPLE_TAP / LONG_PRESS_SWIPE_DOWN / DOUBLE_LONG_PRESS | **System-level still applies** (cycle profile, peek HUD, sleep screen, reconnect) — the wearer can switch profile / sleep screen from inside our UI just like outside |

This keeps the wearer's mental model unified: same gesture means the same thing everywhere.

### 9.4 Focus indicators always present

Every focusable element gets the standard 2 px green-bar + 7% tint indicator (the `Modifier.r08Focus()`
extension). The default Compose focus indicator (a thin border) is too subtle for the
low-resolution see-through display.

---

## 10. Gesture-hint mode (new)

> An opt-in feedback mode that flashes "what just got recognised" in the HUD after every gesture.
> Useful for: learning gestures during onboarding, verifying mappings after a profile change,
> debugging when something feels "off".

### 10.1 Behaviour

When **Settings → Feedback → Show recognised gesture (HUD)** is ON:

- Every recognised `Gesture` (output of the synthesiser, after the InteractionRouter resolves it)
  triggers a brief HUD overlay.
- Overlay format:
  ```
  Double tap → Back
  Swipe up → Nav prev
  Triple tap → Profile: Media
  Long-press + swipe down → Sleep screen
  Quadruple tap → Peek
  Swipe up → (no action)              ← if unmapped
  ```
- Position: top-right (same as default Peek HUD).
- Duration: **800 ms** (deliberately shorter than the 2 s informational HUD — this fires often
  and we don't want it lingering).
- New gesture **replaces** any already-showing gesture-hint HUD (no queueing).
- If a different HUD variant (mode-switched, low-battery, disconnected) is already showing, the
  gesture-hint HUD waits.

### 10.2 Default: OFF

Off by default. Most wearers don't want their field of view interrupted on every gesture.

But the **first-run wizard** auto-enables it for ~5 minutes after the user completes pairing
(so they have built-in feedback while learning the gesture vocabulary), then turns it off
automatically. The wearer can re-enable it at any time.

### 10.3 Friendly names

The HUD shows human-readable names, not enum identifiers:

| Identifier | Friendly name |
|---|---|
| `TAP` | "Tap" |
| `DOUBLE_TAP` | "Double tap" |
| `TRIPLE_TAP` | "Triple tap" |
| `QUADRUPLE_TAP` | "4× tap" |
| `SWIPE_UP` | "Swipe up" (or "Swipe forward" — calibrate after device verification) |
| `SWIPE_DOWN` | "Swipe down" / "Swipe backward" |
| `LONG_PRESS` | "Long press" |
| `LONG_PRESS_SWIPE_UP` | "Long-press + swipe up" |
| `LONG_PRESS_SWIPE_DOWN` | "Long-press + swipe down" |
| `DOUBLE_LONG_PRESS` | "Long-press × 2" |
| `DOUBLE_TAP_SWIPE_UP` | "Double-tap + swipe up" |
| `DOUBLE_TAP_SWIPE_DOWN` | "Double-tap + swipe down" |

Actions also get friendly names: `Back`, `Confirm`, `Nav prev`, `Profile: Media`, `Wake screen`,
`Sleep screen`, `Peek`, `Reconnect`, `(no action)`, `Camera`, `Visual AI`, `Notifications`, etc.

### 10.4 Implementation hook

In `:core`, `InteractionRouter` exposes a callback:

```kotlin
class InteractionRouter(
    // ... existing params ...
    /** Fires after a gesture is routed. (gesture, finalAction-or-null-for-system-handled) */
    var onGestureRecognized: ((Gesture, GlassAction?) -> Unit)? = null,
)
```

The `:app` layer wires this to the `HudOverlay` only when the user preference is ON.

---

## 11. Implementation status

The work is now mostly **execution** rather than design. See [13-handoff.md §2](13-handoff.md)
for the full priority-ordered TODO list. Brief status:

### Done (in `app/src/main/.../ui/`)
- `R08Theme.kt` — the 8 design tokens + type scale + `Modifier.r08Focus()` focus indicator
- `Components.kt` — `StatusBar`, `FocusableRow`, `ListRow`, `Cta`, `AccentBar`, `MetricCell`
- `TabBar.kt` — 3-tab strip with focus tracking for in-app navigation
- `R08App.kt` — root composable; manages tab state + sub-screen stack; attaches/detaches
  `InAppFocusController`
- `InAppFocusController.kt` + `BackController`/`TabController` — in-app fast path
- `screens/VitalsScreen.kt`, `SettingsRootScreen.kt`, `StatusScreen.kt`, `FeedbackScreen.kt`
- `hud/HudEvent.kt`, `hud/HudOverlay.kt` — overlay + all 6 event variants including
  `GestureRecognised` (the gesture-hint mode)

### To do
- A5 (in §2 of [13](13-handoff.md)): wire HudOverlay to `InteractionRouter.onGestureRecognized`
  + BLE connection state + ModeManager profile-changed
- A7: persist `FeedbackPrefs` via DataStore + auto-on-after-pairing 5-min timer
- B1–B9: detail settings screens (Profiles list/edit, System Gestures, Ring, Power & Connection,
  Vitals prefs, Advanced, About, first-run wizard)
- B10: modal layer state machines (Volume / Brightness / Recents / AIDictate)

For each new screen: extend [`ui-mockup.html`](ui-mockup.html) with the layout first, review,
then Compose. The mockup is the design source of truth for visual decisions; the Compose code
implements what the mockup shows.
