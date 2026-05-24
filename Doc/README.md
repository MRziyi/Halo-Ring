# Halo Ring · 环意 — Documentation

> **Halo Ring** · 环意 — *Where the ring goes, the world moves.* / 「环之所至，意之所达」
> by **Zack 紫意**

Use a **QRing R08 smart ring** as a single, wireless remote for **two pairs of AR glasses**:
**Rokid Glasses** and **RayNeo X3 Pro**. Same operations, same UI, automatic hand-over.

Brand assets — adaptive launcher icon master, monochrome notification icon, type tokens — live in
[`./brand/`](brand/). The Android package is `com.halo.ring` (suffixed `.rokid` / `.rayneo` per
flavor); the agent socket is `halo.agent`; the device codename used in source for the ring
hardware itself remains `R08` because that's the QRing model name.

This folder is the **canonical documentation** for the project. Each doc is independent — you
shouldn't need to read more than one in order to understand a topic, though cross-references via
markdown links exist for context. Reading the docs in order also works.

---

## Where to start, by who you are

| You are… | Read this first |
|---|---|
| **An end user** | [09 — User Manual](09-user-manual.md). Maybe glance at [11 §1 — Phase-0 Acceptance Criteria](11-verification-checklists.md) to verify your ring. |
| **A new contributor onboarding** | [01 — Overview](01-overview.md), then **[13 — Handoff & TODO](13-handoff.md)**, then [04 — Architecture](04-architecture.md), then [05 — Interaction Design](05-interaction-design.md). |
| **Taking the project handoff from the previous agent** | **[13 — Handoff & TODO](13-handoff.md)** first (covers state + priority-ordered TODO + recommended order). Then [04](04-architecture.md), [05](05-interaction-design.md), [08](08-ui-design.md). Then any doc as needed. |
| **Building / extending the Android app** | [04 — Architecture](04-architecture.md), [05 — Interaction Design](05-interaction-design.md), [10 — Developer Guide](10-developer-guide.md). For the TODO list, [13](13-handoff.md). |
| **Reverse-engineering or debugging the ring** | [02 — Hardware & Protocol](02-hardware-and-protocol.md), [16 — Phase-0 Test Plan](16-phase0-test-plan.md), [12 — Research & References](12-research-and-references.md). |
| **About to test on real R08 hardware** | [16 — Phase-0 Test Plan](16-phase0-test-plan.md) first; print it. [`../phase0/README.md`](../phase0/README.md) for the 10 scripts. [17 — Community Spec](17-community-protocol-spec.md) is the publish target. |
| **Bringing up a new pair of glasses** | [03 — Target Platforms](03-target-platforms.md) §2 or §3, [10 — Developer Guide](10-developer-guide.md) §7, [11 — Verification Checklists](11-verification-checklists.md) §B. |

---

## Document index

| # | Title | Focus | Length |
|---|---|---|---|
| **[01](01-overview.md)** | Project Overview | What this is, why, current state, reading order | ~5 min |
| **[02](02-hardware-and-protocol.md)** | Ring Hardware & BLE Protocol | The R08 ring: chips, sensors, full BLE protocol spec, init sequence, dedup, errata | ~12 min |
| **[03](03-target-platforms.md)** | Target Platforms: Rokid & RayNeo | Rokid Glasses + RayNeo X3 Pro details, what's the same, what's different | ~15 min |
| **[04](04-architecture.md)** | Architecture | Module graph, runtime data flow, the four device strategies, executor backends, threading | ~15 min |
| **[05](05-interaction-design.md)** | Interaction Design | The 12-gesture vocabulary, the state machine, 4 profiles, system-level gestures, modal layer, hand-over | ~20 min |
| **[06](06-performance-and-power.md)** | Performance & Power | Latency budget, two big levers, power state machine, debug HUD, acceptance criteria | ~10 min |
| **[07](07-sensors-and-modules.md)** | Sensor Utilisation & Functional Modules | Sensor matrix + the 9 functional modules | ~10 min |
| **[08](08-ui-design.md)** | UI Design | 3 jobs → 3 tabs; design tokens; principles; screen catalogue. Live mockup at [`ui-mockup.html`](ui-mockup.html) | ~10 min |
| **[09](09-user-manual.md)** | User Manual | End-user onboarding, gesture catalogue, troubleshooting | ~15 min |
| **[10](10-developer-guide.md)** | Developer Guide | Build, test, extend (new profile / gesture / platform / backend) | ~12 min |
| **[11](11-verification-checklists.md)** | Verification Checklists | Phase-0 (ring), per-glasses bring-up, end-to-end | ~10 min |
| **[12](12-research-and-references.md)** | Research, References & Errata | Where we got everything from; what `R08-Dev.md` got wrong | ~10 min |
| **[13](13-handoff.md)** | **Handoff & TODO** | Comprehensive status snapshot + priority-ordered TODO + recommended order for the next agent | ~12 min |
| **[14](14-pre-hardware-testing.md)** | Pre-Hardware Testing Guide | What you can verify on a laptop + Android phone before the ring + glasses arrive | ~10 min |
| **[15](15-A2-spake2-tls-guide.md)** | A-2 SPAKE2 + TLS Guide | Step-by-step guide for finishing the ADB-over-WiFi pairing flow when the glasses arrive | ~8 min |
| **[16](16-phase0-test-plan.md)** | **Phase-0 Test Plan** | Stage-by-stage hardware test plan (10 stages, time + power budget per stage) for the QRing-first protocol verification session. Pair with [`phase0/`](../phase0/). | ~15 min |
| **[17](17-community-protocol-spec.md)** | Community Protocol Spec (Draft) | The output target of phase-0 — gets filled in stage-by-stage; final form ships as a CC-BY 4.0 contribution to atc1441 / colmi_r02_client / community. | ~10 min |

Total: ~2.5 hours of careful reading for an end-to-end handoff.

---

## Code & related folders

| Path | What |
|---|---|
| [`../app-project/`](../app-project/) | The Android multi-module Kotlin project — `:core` (pure JVM), `:app` (Android, rokid/rayneo flavors), `:agent` (injection agent) |
| [`../phase0/`](../phase0/) | Python BLE protocol-verification probe (bleak-based); includes `--tutorial` mode for end-user onboarding |
| [`../research/`](../research/) | Cloned reference repositories — `rokid-docs`, `colmi_r02_client`, `ATC_RF03_Ring`, `RayDesk`, `moonlight-android-RayNeoX3` |
| [`../refs/`](../refs/) | All external reference material — vendor SDKs, reference APKs, decompilations, tools. See [`refs/README.md`](../refs/README.md) for the index + SDK source URLs. |
| [`../refs/r08remote-decompiled-v2/`](../refs/r08remote-decompiled-v2/) | jadx decompilation of `小猪遥控戒指` v2 — the source of truth for the BLE protocol |
| [`../refs/r08remote-apk-v1/`](../refs/r08remote-apk-v1/), [`v1.1`](../refs/r08remote-apk-v1.1/), [`v2`](../refs/r08remote-apk-v2/) | The three reference APK versions (`com.ring.r08remote`) |
| [`../R08-Dev.md`](../R08-Dev.md) | Original community hand-off doc (historical; corrections in [12 §4](12-research-and-references.md)) |
| [`./_archive/`](./_archive/) | Pre-split monolithic design docs preserved for history |

---

## Doc maintenance

When code changes invalidate something here, update the relevant doc(s):

| You changed… | Update |
|---|---|
| Anything in `core/.../ble/` (protocol, frame parser) | [02](02-hardware-and-protocol.md) §3–4 |
| The gesture state machine | [05](05-interaction-design.md) §3, possibly tests |
| Default profiles or `KeyMapProfile` semantics | [05](05-interaction-design.md) §4, [09](09-user-manual.md) §5 |
| System gestures or the InteractionRouter | [05](05-interaction-design.md) §5, [09](09-user-manual.md) §6 |
| Per-flavor strategies | [03](03-target-platforms.md) and / or [04](04-architecture.md) §4 |
| New executor backend | [04](04-architecture.md) §5 |
| UI screen | [08](08-ui-design.md) + [ui-mockup.html](ui-mockup.html) |
| Build process | [10](10-developer-guide.md) §3, §10 |
| Phase-0 probe | [11](11-verification-checklists.md) §A, [`phase0/README.md`](../phase0/README.md) |
| Finished or started a TODO | [13](13-handoff.md) §2 |
| Project status changed | [01](01-overview.md) §"Project status" + [13](13-handoff.md) §1 |

`Doc/README.md` (this file) gets updated when a new top-level doc is added.

---

**Last reorganisation**: 2026-05-13. Pre-split version archived at
[`_archive/R08-Remote-Design-v0.7.md`](_archive/R08-Remote-Design-v0.7.md).
