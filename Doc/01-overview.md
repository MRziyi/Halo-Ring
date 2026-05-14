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

`R08-Dev.md` in the repo root was the original community hand-off document. Its core open question
was "the R08's touch/gesture BLE protocol is unknown — someone needs to reverse-engineer it." That
turned out to be mostly already solved by a third-party app called `小猪遥控戒指` (`com.ring.r08remote`,
WeChat `qq889538`); decompiling its v2 APK gave us the full protocol. See
[12-research-and-references.md](12-research-and-references.md).

But the third-party app has serious shortcomings (latency, power, fragility, only-Rokid, no
configurability). This project is the from-scratch open redesign that **does the same thing,
better, on two platforms, with a clean architecture suitable for long-term maintenance.**

Key improvements over the reference app are catalogued in
[12-research-and-references.md §3](12-research-and-references.md). The TL;DR is:

| | `小猪遥控戒指` | This project |
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

**Hardware not yet acquired.** Both pairs of glasses + the ring are on order.

> **For a comprehensive status snapshot + priority-ordered TODO + handoff notes**, read
> [13-handoff.md](13-handoff.md). The summary below is a quick orientation; §13 is the canonical
> "where are we, what's next" document.

Already done (no hardware required):
- BLE protocol fully reverse-engineered ([02-hardware-and-protocol.md](02-hardware-and-protocol.md))
- Both target platforms researched and documented ([03-target-platforms.md](03-target-platforms.md))
- End-to-end architecture designed ([04-architecture.md](04-architecture.md))
- Full interaction design: 12 gestures, 4 profiles, system-level wake/sleep, modal layer,
  gesture-hint mode, in-app navigation ([05-interaction-design.md](05-interaction-design.md))
- Performance + power budget designed ([06-performance-and-power.md](06-performance-and-power.md))
- Sensor utilisation matrix and functional modules ([07-sensors-and-modules.md](07-sensors-and-modules.md))
- **UI design + HTML mockup** at full 1:1 fidelity ([08-ui-design.md](08-ui-design.md) +
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
- Phase-0 verification probe with `--tutorial` mode at [`../phase0/r08_probe.py`](../phase0/r08_probe.py)
- End-user manual ([09-user-manual.md](09-user-manual.md))
- Developer guide ([10-developer-guide.md](10-developer-guide.md)) + verification checklists
  ([11-verification-checklists.md](11-verification-checklists.md)) + research provenance
  ([12-research-and-references.md](12-research-and-references.md))

Outstanding work — see [13-handoff.md §2](13-handoff.md):
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
- **An end user**: just read [09-user-manual.md](09-user-manual.md). Maybe glance at
  [11-verification-checklists.md](11-verification-checklists.md) §1 to verify your hardware.
- **A reverse engineer of the ring**: read [02-hardware-and-protocol.md](02-hardware-and-protocol.md)
  + [12-research-and-references.md](12-research-and-references.md).
- **An Android developer fixing/extending the app**: read [04](04-architecture.md) +
  [05](05-interaction-design.md) + [10-developer-guide.md](10-developer-guide.md).
- **A reviewer doing handoff to another team**: read everything in order. Each doc is independent;
  no doc requires reading any other doc first.

## Key links outside the Doc/

- [`../app-project/`](../app-project/) — the Kotlin/Gradle skeleton project
- [`../phase0/`](../phase0/) — the Python protocol-verification probe
- [`../research/`](../research/) — cloned reference repos (rokid-docs, colmi_r02_client, ATC_RF03_Ring, RayDesk, …)
- [`../decompiled/v2/`](../decompiled/v2/) — jadx decompilation of `小猪遥控戒指` v2
- [`../remote-v1*/`](../remote-v1/) — the three versions of the reference APK
- [`../R08-Dev.md`](../R08-Dev.md) — the original community hand-off doc (kept as-is for historical
  reference; corrections live in [12](12-research-and-references.md))
- [`./_archive/`](./_archive/) — earlier monolithic versions of this design doc, preserved
