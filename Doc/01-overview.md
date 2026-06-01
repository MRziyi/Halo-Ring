# 01 — Project Overview

## What this is

Use a **QRing R08 smart ring** as a single, wireless remote for **two pairs of AR glasses**:

- **Rokid Glasses** (RG-glasses, YodaOS, Android 12)
- **RayNeo X3 Pro** (ARGF20, RayNeo AIOS, Android 12)

One ring, one design, two flavor builds (one APK per glasses platform). The user can wear either
pair of glasses; the ring connects to whichever is being worn, with **automatic hand-over** when
the user takes off one and puts on the other. End-user gestures, UI, and operations are
**identical** on both — the only differences live below the surface (DPAD-key injection on Rokid,
swipe-MotionEvent injection on X3 Pro).

## Why this project exists

The original community hand-off document (`R08-Dev.md`, archived in the private R08-dev
research workspace) posed the core open question: "the R08's touch/gesture BLE protocol is
unknown — someone needs to reverse-engineer it." This project answers it directly: the full
touch/gesture BLE protocol was reverse-engineered **first-hand, on real R08 hardware**, by
capturing and replaying BLE traffic and verifying every opcode on-device — no other project
was used as a reference. The complete result is published as
[09-r08-ble-protocol-spec.md](09-r08-ble-protocol-spec.md).

On top of that protocol work, this project is a from-scratch open design: a clean,
configurable, low-latency remote that runs on **two platforms** with an architecture suitable
for long-term maintenance.

The design choices below are deliberate — each avoids a common pitfall of a naïve ring-remote
implementation:

| | Naïve approach | This project |
|---|---|---|
| Platforms | Rokid only | Rokid + RayNeo (shared core) |
| Tap latency | Fixed 400ms multi-tap window | Tunable (default 280ms) + optional optimistic tap (~0ms) per profile |
| Injection latency | `input keyevent` per gesture (~100ms+ JVM spawn) | `app_process` agent with direct `InputManager.injectInputEvent` (~1–3ms) |
| Power | Persistent CPU wakelock + 2s scan loop + 50ms polling script | No wakelock + `autoConnect` + event-driven inotifyd/agent + `TOUCH_DISABLE` when not worn |
| Mappings | 3 modes hard-coded in `when` branches | User-configurable per profile, 4 built-in defaults + custom |
| Mode switch | Manual triple-tap only | Triple-tap + auto-switch by foreground app |
| Reliability | 100ms de-dup can eat real fast double-taps | De-dup window calibrated by phase-0 measurement; thread-disciplined gesture pipeline |
| Screen on/off | Not handled (relies on Android timeout) | Dedicated wake gesture (long-press) + sleep gesture (long-press + swipe-down) |
| Sleep / wake feedback | None | Ring LED feedback patterns |
| Observability | None | Latency-measurement debug HUD, connection-quality stats |

## Project status

**Shipped + validated on real hardware** (Rokid Glasses + R08_E600 ring) — current release
**v1.1.5**. The "already done / outstanding" lists below are **pre-v0.4 and kept for historical
orientation only**; trust the handoff snapshots for the live state.

> **For the current state snapshot + priority TODO + handoff notes**, read the newest dated
> snapshot in [`_handoffs/`](_handoffs/) — that's the canonical "where are we, what's next".

Already done (no hardware required):
- BLE protocol fully reverse-engineered ([02-hardware-and-protocol.md](02-hardware-and-protocol.md))
- Both target platforms researched and documented ([03-target-platforms.md](03-target-platforms.md))
- End-to-end architecture designed ([03-architecture.md](03-architecture.md))
- Full interaction design: 12 gestures, 4 profiles, system-level wake/sleep, modal layer,
  gesture-hint mode, in-app navigation ([04-interaction-design.md](04-interaction-design.md))
- Performance + power budget designed ([06-performance-and-power.md](06-performance-and-power.md))
- Sensor utilisation matrix and functional modules ([07-sensors-and-modules.md](07-sensors-and-modules.md))
- **UI design + HTML mockup** at full 1:1 fidelity ([05-ui-design.md](05-ui-design.md) +
  [ui-mockup.html](ui-mockup.html))
- Skeleton Kotlin/Gradle multi-module project at [`../app-project/`](../app-project/) with:
  - **Full `:core`**: gesture state machine + frame parser + ~25 JVM tests
    (`./gradlew :core:test`)
  - **Full UI layer in Compose** at `app/src/main/.../ui/`: theme, atomic components, 3 tab
    screens (Vitals / Settings root / Status), Feedback sub-screen, TabBar, HUD overlay with 6
    variants (including the new gesture-hint), InAppFocusController for the in-app fast path
  - Per-flavor strategy implementations (Rokid + RayNeo)
  - Fully implemented runtime: `:agent`, `HaloRingService`, `AndroidR08BleClient`,
    `AppProcessAgentBackend`, `AccessibilityBackend`
- Phase-0 verification probe with `--tutorial` mode at [`../Doc/02-hardware-and-protocol.md`](02-hardware-and-protocol.md)
- End-user manual ([06-user-manual.md](06-user-manual.md))
- Developer guide ([07-developer-guide.md](07-developer-guide.md)) + verification checklists
  ([11-verification-checklists.md](11-verification-checklists.md)) + research provenance
  ([12-research-and-references.md](12-research-and-references.md))

Outstanding work — see the newest snapshot in [`_handoffs/`](_handoffs/):
- **Critical path (priority A) — DONE**: `AndroidR08BleClient`, `:agent` body,
  `AppProcessAgentBackend`, `HaloRingService` body, HUD wiring to the InteractionRouter
  callback, foreground bypass, DataStore prefs persistence
- **Feature completeness (priority B) — DONE**: detail settings screens (Profiles, System Gestures,
  Ring, Power, Advanced, About), modal layer state machines, AccessibilityBackend, ADB
  bootstrap wizard, first-run wizard
- **Hardware verification (priority C, when ring + glasses arrive)**: phase-0 protocol
  verification, de-dup window measurement, `0xA1` accel decode, wear-frame search, keepalive
  vs auto-sleep, LED behaviour, Rokid + X3 Pro bring-ups, end-to-end perf & power, cross-glasses
  hand-over
- **Phase-3 / nice-to-have (priority D)**: spatial mode, head-gaze cursor, mobile companion,
  Shizuku, HID-keyboard-from-phone topology

## Reading order

If you are…

- **A new contributor onboarding to the project**: read 01 (this), then 04 (architecture), then
  05 (interaction design). Skim everything else.
- **An end user**: just read [06-user-manual.md](06-user-manual.md). Maybe glance at
  [11-verification-checklists.md](11-verification-checklists.md) §1 to verify your hardware.
- **A reverse engineer of the ring**: read [02-hardware-and-protocol.md](02-hardware-and-protocol.md)
  + [12-research-and-references.md](12-research-and-references.md).
- **An Android developer fixing/extending the app**: read [04](03-architecture.md) +
  [05](04-interaction-design.md) + [07-developer-guide.md](07-developer-guide.md).
- **A reviewer doing handoff to another team**: read everything in order. Each doc is independent;
  no doc requires reading any other doc first.

## Key links outside the Doc/

- [`../app-project/`](../app-project/) — the Kotlin/Gradle multi-module project
- [`Doc/02-hardware-and-protocol.md`](02-hardware-and-protocol.md) — the BLE protocol spec
- [`Doc/10-plugin-protocol.md`](10-plugin-protocol.md) — the external-app plugin protocol (Constellation
  is the first client)
- **Private research material** (separate `R08-Dev` repo) — the first-hand BLE
  reverse-engineering work: Python BLE protocol-validation probes, on-device capture logs, the
  glasses-platform SDKs (Rokid / RayNeo Mercury), and the original community hand-off doc.
  Public contributors don't need these — verified protocol details get published into Doc/02 above.
- [`./_archive/`](./_archive/) — earlier monolithic versions of this design doc, preserved
