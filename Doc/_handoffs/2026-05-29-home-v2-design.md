# Design — Home v2 (context gestures + IA + protocol polish), 2026-05-29

> Design + plan doc, written at Zack's request ("写一文档来深入考虑"). Drives the next
> implementation pass. Supersedes the first P1 home attempt (the 3-tab home shipped but the
> tab-switch interaction was "too confusing" and the IA dumped everything in MORE). Ground truth
> for protocol claims: [`R08-dev/phase0/SPEC v3.md`](../../../R08-dev/phase0/SPEC%20v3.md) §10.

---

## 0. The feedback this answers

1. Tab switching is confusing → use a **context-dependent gesture**: in-app, LONG_PRESS switches
   tab; out-of-app, LONG_PRESS still sleeps the screen.
2. Put **ring settings on the RING tab**, **vitals settings on the VITALS tab** — don't dump
   everything in MORE. MORE is overloaded; thin it out so it can hold genuinely-misc content.
3. Gestures + Profiles are fine as-is (keep, in MORE).
4. **Remove "export log"** — normal users don't need it; use debug tools.
5. **Implement every feature — no placeholders left.**
6. **Charging report**: ring charges in a dock; show "charging"; push a HUD at 90/95/100/full.
7. Other protocol-backed UX wins (see §5).

---

## 1. Navigation — context-dependent gestures (the core fix)

**Within a tab** (unchanged, already works): SWIPE_UP/DOWN move focus, TAP selects, DOUBLE_TAP
backs out. Each tab fits one screen — no scroll.

**Between tabs**: **LONG_PRESS cycles RING → VITALS → MORE → RING**, but *only while our Config
Activity is foreground*. Out-of-app, LONG_PRESS keeps its system meaning (screen sleep in the
Navigation/fallback profile). This is the "组合手势" idea: same gesture, different meaning by
context — and it removes the unreliable dual-axis focus juggling for the tab strip entirely.

### Mechanism (core stays Android-free)
- `InteractionRouter` gains two injected hooks (same pattern as `onPeekHud` / `systemKeyDispatcher`):
  - `var appForeground: () -> Boolean = { false }`
  - `var onAppTabCycle: () -> Unit = {}`
- New branch in `onGesture`, placed **after** the overlay check (§0a) and **before** the
  screen-sleep check (§0), so in-app LONG_PRESS never falls through to sleep:
  ```
  if (gesture == Gesture.LONG_PRESS && appForeground()) {
      onAppTabCycle()
      onGestureRecognized?.invoke(gesture, GlassAction.None)   // HUD/telemetry only
      return
  }
  ```
- `HaloRingService` wires `appForeground = { MainActivity.isInForeground.get() }` (the flag already
  exists, set in onResume/onPause) and `onAppTabCycle = { graph.homeTabIndexFlow.update { (it+1) % HomeTab.values().size } }`.
- `AppGraph` gains `homeTabIndexFlow: MutableStateFlow<Int>` — the single source of truth for the
  active home tab. Both UI taps and the LONG_PRESS gesture update it; `MainActivity` collects it and
  feeds `HaloRingApp(homeTabIndex = …, onSelectTab = { graph.homeTabIndexFlow.value = it.ordinal })`.
  (Tab state moves OUT of the local `AppState` so the gesture path can drive it.)

Why LONG_PRESS and not a combo: it's one deliberate gesture, hard to fire by accident, and Zack
named it explicitly. TRIPLE_TAP (screenshot) / DOUBLE_TAP (back) / TAP (select) stay as-is.

---

## 2. Information architecture

Three tabs, each self-contained. Settings live where they belong, not in one MORE dump.

### RING tab — everything about the ring
- **Status** (live): connected / disconnected, battery %, **charging state** (§4), RSSI.
- **Quick actions**: RECONNECT, FIND RING.
- **Ring settings** (drill-in rows, formerly the RING + POWER settings groups):
  pair / re-pair, firmware · MAC · signal (read-only info), Power & connection (BLE interval
  bands), forget ring. Capability list at the bottom.

### VITALS tab — everything physiological
- **Readout**: HR + SpO₂ (compact), measured-ago, MEASURE (parallel HR+SpO₂, §SPEC 4.5).
- **Today**: steps · distance (passive `0x73` sub-18), **step-goal progress** (§5).
- **Vitals settings** (drill-in, formerly VITALS_PREFS): HR-on-HUD, auto-snapshot interval, wear
  detection, **daily step goal**, spatial features. (Drops the CSV log toggle + EXPORT — §3.)

### MORE tab — the genuinely-misc rest, grouped (not a flat 10-item dump)
- **Gestures & Profiles**: Profiles, System gestures, Test Arena. (Keep — Zack: "还行".)
- **System**: Bluetooth-internet, Language, Connection status, External plugins, Advanced.
- **About**.

So MORE drops from ~10 flat items to 3 groups. Ring/Vitals settings left MORE entirely (they're
on their tabs), freeing MORE for future important content as Zack asked.

---

## 3. Remove placeholders, export-log; implement everything

- **Export log — remove**: `AdvancedScreen` EXPORT_LATENCY_LOG + EXPORT_VITALS_LOG actions and
  their strings; `VitalsPrefs` "Log to CSV" toggle + "EXPORT 7-DAY LOG". (Latency *measurement*
  toggle can stay in Advanced as a dev switch, but no file export from the UI.)
- **`EnterAIDictateModal`** is greyed "(coming soon)" — mic capture unimplemented. Decision:
  **remove the action** (honest "no placeholder") rather than ship a dead modal. The everyday-AI
  gesture (`OpenAIAssistant`) and ask-visual-AI already cover the AI need.
- **`HaloSwitch(disabled=…)`** "coming soon" path: after the above, no toggle passes `disabled=true`
  in the Rokid path → the dead branch can stay in the widget (harmless) but no UI reaches it.
- **RayNeo best-effort intents** (empty-list openTranslate/openSubtitle/etc.): these are device gaps
  on un-validated hardware, not Rokid placeholders. Leave the BEST_EFFORT label (it's honest); not
  in scope for "implement everything" until RayNeo is on-device.
- **No "—/coming soon" on the home**: the VITALS "Today" line already hides when there's no data.

Net: zero "(coming soon)" rows reachable on the Rokid daily path.

---

## 4. Battery & charging (the requested protocol feature)

Decode already exists: `0x03` resp and the autonomous `0x73 sub=0x0C` BATTERY_STATE_PUSH both
parse to `RingEvent.Battery(pct, charging)` (R08Frame). SPEC-verified push pattern: one push
seconds after dock-in; ~60 s heartbeat while % rises; silent at 100 %; two `charging=false` pushes
~50 ms apart on unplug.

- `RingInfo` gains `charging: Boolean?`. Service sets it from every `RingEvent.Battery`.
- **Status bar + RING tab** show a charging glyph / "Charging 94 %" when `charging == true`.
- **Charging-milestone HUD** (new `HudEvent.Charging(pct, full)`, or reuse `Notice`): post when
  `charging` crosses thresholds, debounced so the 60 s heartbeats don't spam:
  - dock-in (`charging` false→true): "Charging…"
  - first heartbeat at/over **90 %**, **95 %**
  - **100 %** / full ("Fully charged ✓")
  - unplug (`charging` true→false): silent (or brief "Unplugged" — TBD; lean silent).
  - Thresholds fire once each per charging session (reset on unplug). Logic lives in the service's
    `RingEvent.Battery` handler with a small `lastChargeMilestone` tracker.

Low-battery (`LowBattery` HUD) already exists for the discharge side; this completes the picture.

---

## 5. Protocol-backed UX features (locked with Zack 2026-05-29)

All verified-working per SPEC §10. Final scope after Zack's refinements:

1. **Step milestones — every 500 steps** (NOT a goal). Steps auto-stream via `0x73` sub-18, so no
   `0x21` goal-setting and no setter UI. Track the last-crossed 500 boundary; each time today's
   steps cross the next multiple of 500 (500, 1000, 1500…), push a HUD ("500 steps 👣"). Reset the
   tracker on a step-counter reset (new day / lower value).
2. **Charging milestones** — §4. Dock-in, 90 %, 95 %, full. (Confirmed good.)
3. **Wear sensing** — `0x2A` TOUCH_STATUS_ECHO (DISABLED on dock-in) + the existing wear pipeline.
   On change: **HUD notice** ("Ring on" / "Ring removed") AND a **persistent wear-state display in
   the app** (RING tab status line: worn / not worn / charging). Also: MEASURE shows "Put the ring
   on" rather than grinding to wear-detect-fail when not worn.
4. **Drop alert** — accel (`0xA1` ch3) + existing `RingDropped` HUD. (Confirmed good; needs
   spatial-features ON for the accel stream.)
5. **Passive HR — last reading + age on the VITALS tab.** `0x16` auto-monitor already runs PPG
   ~every 30 min; capture the latest passive HR (and SpO₂ if it arrives) and show it as the VITALS
   readout with "measured N min ago", so the tab usually has *something* before the user taps
   MEASURE. "Just take it" — passive data fills the readout; MEASURE forces a fresh one.
6. **Reconnected HUD** (bonus, free): `HudEvent.Reconnected` exists but the connect side is quiet —
   fire a brief "Ring connected" on (re)connect to pair with the existing Disconnected HUD.

Out of scope (firmware can't): BP, HRV/stress, temperature, blood-sugar (val stays 0 — §10.5),
deep step history (`0x43` returns no-data sentinel on this FW).

---

## 5b. HUD wake-and-auto-sleep (Zack 2026-05-29, important)

**Any HUD pushed while the screen is OFF must briefly light the screen, then auto-sleep.** So a
charging/step/wear/drop notice reaches the user even if they've put the display to sleep, without
leaving the screen on to drain battery or distract.

- On `hud.show(event)` (the service's single HUD owner), if `interactionRouter.screenOn == false`:
  dispatch `GlassAction.ScreenWake`, show the HUD, and schedule a `GlassAction.ScreenSleep` after a
  **HUD_AUTO_SLEEP_MS (≈7 s)** timeout.
- Only auto-sleep if *we* woke it (track `wokeForHud`). If the user wakes/interacts in the window,
  cancel the pending sleep (don't yank the screen from under them).
- Coalesce: a second HUD within the window resets the timer rather than stacking sleeps.
- Gate to "real" notices (charging/step/wear/drop/low-battery/disconnect) — NOT every gesture-hint,
  which would wake the screen constantly.

---

## 6. Phased implementation plan — ALL SHIPPED 2026-05-29 (pending on-glasses verify)

- **P-A — context gesture (tab switch)** ✅ Router `appForeground`/`onAppTabCycle` hooks +
  `AppGraph.homeTabIndexFlow` + service wiring + MainActivity collect + HaloRingApp external tab
  state. Two `:core` tests (in-app cycles / out-of-app sleeps).
- **P-B — IA reorg** ✅ RING tab = status + reconnect/find + drill-ins (Ring details, Power);
  VITALS tab = readout + Vitals-settings drill-in; MORE = two groups (Gestures & Profiles, System).
  `SettingsGroup` → {GESTURES, SYSTEM}; ring/power/vitals sections became tab drill-ins.
- **P-C — remove export-log + AI-dictate placeholder** ✅ Dropped Advanced EXPORT_LATENCY/VITALS
  actions + the `exportCsv` helper + the Vitals CSV-export toggle + the AI-dictate picker entry.
  No "(coming soon)" reachable on the Rokid path (the disabled-switch / UNSUPPORTED-picker branches
  are now dead code).
- **P-D — battery/charging** ✅ `RingInfo.charging` + StatusBar green-% cue + RING-tab "Charging ·
  N%" line + charging-milestone HUD (dock-in / 90 / 95 / full, once per session, debounced vs the
  ~60 s heartbeat; low-battery suppressed while charging).
- **P-E — protocol features** ✅ Step-every-500 HUD (no goal); wear sensing (`RingInfo.worn` +
  wear-change HUD + RING-tab "not worn" + MEASURE "put the ring on" guard); auto-snapshot on the
  worn-transition ("戴上即测"); passive-HR age shown via the persisted `capturedAtMs`. Drop alert +
  Reconnected HUD were already wired — confirmed.
- **P-F — HUD wake-and-auto-sleep** ✅ `showAlert()` wraps alert-class HUDs: if the screen is off it
  dispatches `ScreenWake`, shows, then `ScreenSleep` after `HUD_AUTO_SLEEP_MS` (7 s); a recognised
  gesture cancels the pending sleep. Frequent HUDs (gesture-hint / profile / sport) bypass it.

`:core` tests green (275 incl. 2 new router tests); both flavor debug APKs build. **Open: on-glasses
verification** — esp. the LONG_PRESS tab-switch feel, charging-milestone HUD timing, and the
screen-wake/auto-sleep behaviour (screen power control is the one part I couldn't bench-test).

---

## 7. Risks / notes

- **Router change is in the delicate file** (`InteractionRouter`, §3.5 of Doc/13's "careful"
  list). The new branch is additive + guarded by `appForeground()`; out-of-app behavior is
  unchanged. Add a `:core` test: LONG_PRESS with `appForeground=true` calls `onAppTabCycle` and
  does NOT sleep; with `false` it sleeps in fallback.
- **Tab state moves to AppGraph** — make sure the wizard / first-run path doesn't read it before
  init (default 0 = RING).
- **Charging HUD spam** — must debounce on the 60 s heartbeats; fire each milestone once per
  session, reset on unplug.
- RayNeo unchanged (shares the UI); landscape already forced there.
