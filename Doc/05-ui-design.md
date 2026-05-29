# 05 — UI Design (v0.4)

The Halo Ring UI has **two surfaces** in v0.4:

1. **HUD overlay** (`WindowManager.TYPE_APPLICATION_OVERLAY`) — the daily UX. Transient pips
   fired by gesture-recognition, profile-switch, vitals, sport-tick, spatial alerts, ring status.
   Owned by `HaloRingService`; visible regardless of which app is foreground.
2. **Config Activity** (`MainActivity`) — opened occasionally (icon tap or `ACTION_SETTINGS_KEY`
   two-finger long-press). Hosts pairing, the custom-gesture editor, Vitals dashboard, ring info,
   plugin management. Allowed to be deep — but **never the daily entry point**.

Pre-v0.4 design had a 3-tab top-level (Vitals / Settings / Status). v0.4 retires the tab strip
and the `InAppFocusController` because they were the root cause of "ring 点不出来" on glasses
([Doc/11 §2.1](11-v0.4-design.md)). Compose's standard `FocusManager` + system KeyEvents
([Doc/04 §3.8](04-interaction-design.md#38-base-gesture-passthrough-v04)) handle navigation
natively.

> Live visual mockup: [`ui-mockup.html`](ui-mockup.html). Note that the mockup still renders
> 480×480 — update to 480×640 portrait per [Rokid bare-metal §00](../../Constellation/reference/rokid-glass/bare-metal-docs/00-overview.md).

---

## 1. Display constants

| Platform | Canvas | Anchor for HUD pip | Notes |
|---|---|---|---|
| **Rokid Glasses** | **480×640 portrait**, right-eye only, mono green | Upper-right or upper-left (user-configurable `hudPosition`) | Lower-right reserved for Constellation pip when both apps run (see [Constellation-Glass §1.4](~/Code/Projects/Constellation-Glass/Doc/GLASS-CLIENT-DESIGN.md)) |
| **RayNeo X3 Pro** | 1280×480 binocular (640×480/eye), full colour | Re-anchored to right-eye region via `Gravity.END` + ~160 px x-inset when `DisplayAdapter.isBinocular = true` | `HudOverlay.setBinocular(...)` called from `HaloRingService` |

**APL ≤ 13%** (RayNeo design spec; Rokid green-only is universally safe under this). Black canvas
+ small accent + small text dominates — well under the cap.

**Safe area**: 16 px on all sides (RayNeo) → `ScreenPadding` = 24 dp covers both.

## 2. Visual language — six tokens

| Token | Hex | Use |
|---|---|---|
| `--ui-bg` | `#000` | Default canvas. On additive see-through displays, unlit pixels are transparent |
| `--ui-fg` | `#fff` | Primary text |
| `--ui-mute` | `#8a8a8a` | Secondary text, dividers |
| `--ui-accent` | `#5ee08c` | Focus indicator, primary action — matches the ring's green LED |
| `--ui-warn` | `#ffb84d` | Low battery, conflict warnings |
| `--ui-bad` | `#ff7c7c` | Errors, destructive actions |
| `--ui-line` | `#2a2a2a` | Dividers; never tinted backgrounds for sections |

No fills, no gradients, no shadows, no glass-morphism. Black = transparent.

### Type scale (16 sp floor per RayNeo spec)

`Title 24/600` · `Body 17/400` · `Caption 16/400 (mute)` · `Tab 16/600` · `Mono 16/600 (SF Mono)`
· `Metric 56/700 (tabular nums)` · `MetricKey 14/400 (uppercase mute — sole sub-16 sp exception)`
· `RowKey 16/400 (mute)`.

### Focus indicator

```
┌─ row, not focused ──────────────────┐
│  Profiles & Gestures           ›    │
└─────────────────────────────────────┘

┌──┰── row, focused ──────────────────┐
│  ┃ Profiles & Gestures         ›    │  ← 2 px green left bar
│  ┃                                  │  ← 7% green tint background
└──┸──────────────────────────────────┘
```

Shared `Modifier.haloFocus()` extension applies both elements. Required (either alone is too
subtle on a small display in bright ambient).

## 3. HUD overlay — the daily UX surface

`HudOverlay.kt` + `HudEvent.kt` (`:app/.../ui/hud/`). All HUD events fire from `HaloRingService`.

### 3.1 Existing event variants (v0.3, kept)

| Event | Trigger | Pip look | Duration |
|---|---|---|---|
| `GesturePip` | Gesture-hint mode on AND any recognised gesture | `Double tap → Back` | 800 ms |
| `ProfileSwitched` | `ModeManager.cycleNext()` / auto-switch / manual select | accent-bordered: `↻ → Navigation` | 2 s |
| `LowBattery` | Ring ≤ 20% | warn-bordered: `● R08_E600 18%` | 2 s |
| `Disconnected` | BLE link lost | bad-bordered, 2 lines: `● Ring disconnected` + hint `Open app → Settings → Ring → Reconnect` | 4 s, re-displays every 60 s while still disconnected |
| `Reconnecting` | Re-attempt in progress | accent-bordered: `↻ Reconnecting…` | until READY |
| `Connected` | BLE link came up | accent-bordered: `● Connected` | 1 s |

### 3.2 v0.4-added events

| Event | Trigger | Pip look | Duration |
|---|---|---|---|
| `VitalsSnapshot` | User-bound `MeasureVitals` action completes | `❤ 72 bpm  •  SpO₂ 97%` | 3 s |
| `SportTick` | Active sport session (during) | `🏃 12:34  •  ❤ 138` | 8 s |
| `SpatialAlert` | `AccelProcessor.FreeFall` | bad-bordered: `Ring dropped?` | 2 s |
| `PluginCard` | Plugin pushes via PROFILE_PUSH | plugin-rendered runs (title + body) | Plugin-controlled |

### 3.3 HUD design rules

- **AR rule**: never persist centred (would occlude line of sight). Default `hudPosition =
  TopRight`. Disconnected is the one event that previously broke this rule (centred + persistent)
  — fixed in audit-pass-l (transient pip + 60-s re-nudge).
- **No animations** beyond appear/disappear. Animation costs CPU + lit pixels and AR users don't
  expect mobile-app fluidity.
- **Audio + LED reinforcement**: most state changes also fire `ToneGenerator` click + ring LED
  blink. Visual is for "user happens to be looking at the canvas"; audio + LED handles "user is
  looking through it".

## 4. Config Activity — the editing surface

Opened by: app icon tap, `ACTION_SETTINGS_KEY` two-finger long-press (Rokid), launcher icon
from Sprite. **Never the daily entry point.**

### 4.1 Top-level (after v0.4 reorg: 10 flat items → 5 groups)

| Group | Members |
|---|---|
| **Ring** | Pair/Re-pair, Find Ring, MAC/FW/RSSI/battery, Forget, Reconnect, Capabilities (gated list) |
| **Vitals** | Auto-snapshot interval, CSV export, HR-on-HUD, Step target, Sport session, "Pause when off-finger", Spatial features (opt-in) |
| **Gestures** | Profiles list → Profile editor / Action picker / System gestures / Gesture picker / **Test Arena** (custom-gesture training) |
| **Plugins** | External plugins (Doc/10) |
| **More** | Power & Connection (collapsed defaults), Feedback, Language, Advanced (slim), About (3 rows) |

### 4.2 Headline screens — the custom-gesture editor

These are the **value-add UI**. Custom gestures + plugin protocol are pillar #1 of the project
([Doc/11 §1](11-v0.4-design.md)). None of these get deleted in v0.4.

| Screen | Purpose |
|---|---|
| **Profiles list** | 4 default profiles (Navigation / Media / Reader / Fast) + user-defined; active marked with green bullet; "duplicate to create" |
| **Profile editor** | 12-row gesture → action map for one profile. v0.4: **4 base rows shown as `(system)` and not editable**; the 8 custom rows are fully editable. `triggerPackages` and `GestureConfig` knobs below the map. |
| **Action picker** | ~35 entries grouped (Nav / Media / Camera / AI / System / Modal / Plugin). Unsupported actions on the active flavor greyed out via `GlassActionMapper.supports()` |
| **System gestures** | The 5 always-on slots: ScreenWake (default LONG_PRESS), ScreenSleep (LP+SwipeDown), ProfileCycle (TripleTap), PeekHUD (QuadrupleTap), AIAssistant (DoubleLongPress). Reassignable; inline conflict warnings |
| **Gesture picker** | 12-gesture list; "in use by Slot X" markers; "(disable this slot)" row |
| **Test Arena** | Gesture-training surface. Rows light up when recognised. Exit = universal DOUBLE_TAP (works regardless of how the user rebound DOUBLE_TAP — hardcoded inside the recognised-flow collector). No exit button (glasses have no touchscreen). |
| **External plugins** (Doc/10) | Read-only directory: app name + package + protocol version + action count + status. REFRESH PLUGINS CTA. Plugin actions surface in the Action Picker under "EXTERNAL APPS". |

### 4.3 SPEC v3 protocol surface

Pillar #2 of the project ([Doc/11 §1](11-v0.4-design.md)) is to expose the full protocol surface.

| Screen | Surface |
|---|---|
| **Vitals dashboard** | Big metrics: HR / SpO2 / steps / cal / distance / ring battery. MEASURE NOW button → on-demand snapshot. Sub-section for active sport session (Start / Stop + duration + live HR). Spatial features toggle. |
| **Ring screen** | MAC / FW / HW rev / RSSI / battery / advertised name. CTAs: Find Ring (`0x50 [0x55, 0xAA]`), Forget, Reconnect. **No Shutdown** (would brick — `0x0F` is OTA-mode entry). Capabilities expandable row. |
| **HUD pip** | Transient surfaces for VitalsSnapshot / SportTick / SpatialAlert (§3.2) |

### 4.4 Pairing — the only blocking screen

**1 step** (was 5 in pre-v0.4 FirstRunWizard):

```
┌──────────────────────────────────────┐
│ Pair your ring                       │
│                                      │
│  ● R08_E600     -54 dBm    [SELECT]  │
│  ● R08_2A3F     -71 dBm    [SELECT]  │
│  ● Colmi 4D     -88 dBm    [SELECT]  │
│                                      │
│  RESCAN                              │
└──────────────────────────────────────┘
```

After a ring is selected: persist MAC → start `HaloRingService` → ring LED double-flash ack →
`finish()`. The remaining ADB / A11y / battery permissions surface lazily only when an action
that needs them is invoked.

## 5. Per-platform realisation

| | Rokid Glasses | RayNeo X3 Pro |
|---|---|---|
| Activity host | Plain `ComponentActivity` with Compose | Mercury SDK `BaseMirrorActivity` (free binocular mirror) |
| Input path (Service) | **System ordered broadcasts** (`ACTION_SPRITE_BUTTON_*` + `ACTION_TWO_FINGER_*`) — see [Doc/03 §8.1](03-architecture.md#81-rokid-glasses-yodaos-sprite-android-12-go) | Mercury SDK `TouchDispatcher` → `TempleAction` Flow |
| Input path (Activity) | Standard `onKeyDown` (DPAD events) | Mercury SDK + `FocusInfo` registration per focusable |
| Touch input | **None** — no `pointerInput` / drag in shared code | Available but consumed by Mercury SDK |
| Content area | 480×640 portrait | 1280×480 logical (640/eye); centre our 480×640 portrait composition |

**One Compose tree, two Activity hosts.** Shared screens have no per-platform logic; the RayNeo
flavor wires a small Mercury bridge that maps `TempleAction` → Compose focus.

## 6. Implementation notes (highlights)

- **HUD overlay** owned by `HaloRingService` via `HudServiceHost` (Lifecycle / ViewModelStore /
  SavedStateRegistry bundle so a plain Service can host Compose).
- **`HaloSwitch` pill widget** (`Components.kt`) — green-tinted ON dot pinned right vs grey OFF
  pinned left. Replaces the pre-v0.3 colour-only `ON`/`OFF` text that the waveguide rendered
  imperceptibly.
- **`FocusableRow.content: @Composable RowScope.() -> Unit`** — required `RowScope` so long
  descriptions can `.weight(1f)` and not push the switch off-screen.
- **Placeholder rows** (DataStore-persisted but no runtime consumer yet) render as `disabled =
  true` with localised "coming soon / 即将推出" caption. We never lie about functionality.
- **No images / icons bundled**. Unicode glyphs (●, ›, ⤓, ⌖, ❤, 🏃) + Compose line drawing.
- **Sound feedback** — `ToneGenerator(STREAM_NOTIFICATION)` click on focus-move + Confirm. Gated
  by `FeedbackPrefs.clickSoundOnModeSwitch`. Mirrors Sprite Launcher's per-focus beep.
- **Ring LED feedback** via `R08BleClient.findRing()` etc. Mode switch = `BLINK_TWICE`-equivalent
  pattern.

## 7. What's NOT in the v0.4 design

- ❌ Top tabs (Vitals / Settings / Status) — retired with `InAppFocusController`
- ❌ `StatusScreen` as a full-screen panel — converted to a HUD-overlay trigger via PEEK_HUD
- ❌ `GuidedTour` — Test Arena does the job
- ❌ 5-step FirstRunWizard — collapsed to 1 step (pair only)
- ❌ Per-profile colour theming — one green accent
- ❌ Light theme — black canvas always
- ❌ Mobile companion app — deferred (Doc/08 D3); v0.4 ships glasses-native only
- ❌ Charts / sparklines in Vitals — display resolution insufficient
- ❌ Animations beyond appear/disappear

---

For pre-v0.4 longer UI design (pre-merge of platform notes, tab-strip nav design, audit pass
detail), see [`_archive/`](_archive/).
