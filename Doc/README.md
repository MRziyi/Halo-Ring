# Halo Ring · 环意 — Documentation

> **Halo Ring** · 环意 — *Where the ring goes, the world moves.* / 「环之所至，意之所达」
> by **Zack 紫意**

Use a **QRing R08 smart ring** as a single, wireless remote for **two pairs of AR glasses**:
**Rokid Glasses** and **RayNeo X3 Pro**. Same operations, same UI, automatic hand-over.

The Android package is `com.halo.ring` (suffixed `.rokid` / `.rayneo` per flavor); the agent
socket is `halo.agent`; the device codename used in source for the ring hardware itself remains
`R08` because that's the QRing model name. Brand assets in [`./brand/`](brand/).

Docs are numbered **01–11**. Pre-v0.4 / superseded versions are kept under
[`_archive/`](_archive/). The forward-looking design record is [Doc/11 v0.4 design](11-v0.4-design.md).
The **08 "Handoff" slot is now a folder of dated, per-version snapshots** — [`_handoffs/`](_handoffs/)
(read the newest); the old single `08-handoff.md` was retired 2026-06-01.

---

## Where to start, by who you are

| You are… | Read this first |
|---|---|
| **An end user** | [06 — User Manual](06-user-manual.md) |
| **A new contributor onboarding** | [01 — Overview](01-overview.md) → **[11 — v0.4 Design](11-v0.4-design.md)** → [03 — Architecture](03-architecture.md) → [04 — Interaction Design](04-interaction-design.md) |
| **Taking the project handoff** | **the newest dated snapshot in [`_handoffs/`](_handoffs/)** → **[11 — v0.4 Design](11-v0.4-design.md)** → any doc as needed |
| **Building / extending the Android app** | [03 — Architecture](03-architecture.md) → [04 — Interaction Design](04-interaction-design.md) → [07 — Developer Guide](07-developer-guide.md) |
| **Adding gestures or actions** | [04 — Interaction Design](04-interaction-design.md) + [07 — Developer Guide](07-developer-guide.md) §4-§6 |
| **Reverse-engineering or debugging the ring protocol** | [02 — Hardware & Protocol](02-hardware-and-protocol.md) → full spec [09 — R08 BLE Protocol Spec](09-r08-ble-protocol-spec.md) |
| **Integrating an external app via the plugin protocol** | [10 — Plugin Protocol](10-plugin-protocol.md) |

---

## Active document index (11 files)

| # | Title | Focus | Length |
|---|---|---|---|
| **[01](01-overview.md)** | Project Overview | What this is, why, current state, reading order | ~5 min |
| **[02](02-hardware-and-protocol.md)** | Ring Hardware & BLE Protocol — Integration Notes | The opcodes Halo Ring uses + dedup constants + errata. Points at Doc/09 for the full byte tables. | ~5 min |
| **[03](03-architecture.md)** | Architecture (+ Platforms + Perf & Power + Sensor matrix) | Module graph, runtime data flow, the four device strategies, executor backends, threading, perf/power, Rokid + RayNeo specifics, sensor utilisation matrix. | ~15 min |
| **[04](04-interaction-design.md)** | Interaction Design | The gesture vocabulary, state machine, profiles, system-level gestures, modal layer, hand-over, base-passthrough, plugin actions. The gem. | ~15 min |
| **[05](05-ui-design.md)** | UI Design | HUD-first daily UX + Config-Activity for deep editing. HUD events catalogue, Config screen catalogue, design tokens. | ~10 min |
| **[06](06-user-manual.md)** | User Manual | End-user setup + gesture catalogue + profiles + hand-over + troubleshooting | ~5 min |
| **[07](07-developer-guide.md)** | Developer Guide | Build, test, extend (new profile / gesture / platform / backend) | ~10 min |
| **[08](_handoffs/)** | Handoff Snapshots | Point-in-time state snapshots, one per shipped version, in [`_handoffs/`](_handoffs/) — read the newest. (The old single `08-handoff.md` was retired 2026-06-01.) | — |
| **[09](09-r08-ble-protocol-spec.md)** | **R08 BLE Protocol Spec** | The full reverse-engineered QRing R08 BLE protocol — every opcode, frame format, capability bitmap, verification status. Verified on `RT08_3.10.46`. | ~30 min |
| **[10](10-plugin-protocol.md)** | External-App Plugin Protocol | Wire format for any installed app to expose actions to Halo Ring's profile bindings (Constellation is the first client). | ~10 min |
| **[11](11-v0.4-design.md)** | **v0.4 Design** | The canonical decision record — Service spine + HUD-first daily UX + deep-config-allowed; base-gestures hard-locked. | ~10 min |

---

## Archive (`_archive/`)

Superseded / pre-v0.4 docs are kept under [`_archive/`](_archive/) for history (their filenames
retain their original numbers). Highlights: the pre-v0.4 chronological handoff history, the
verification checklists (replaced by burn-in evidence in Doc/08), the SPAKE2+TLS bring-up guide,
the v0.3 refactor plan (superseded by [Doc/11](11-v0.4-design.md)), and the pre-split monolithic
design doc `R08-Remote-Design-v0.7.md`. The former performance / sensor / target-platform docs were
merged into [Doc/03](03-architecture.md) (§7 / §8 / §9).

---

## Code & related folders

| Path | What |
|---|---|
| [`../app-project/`](../app-project/) | The Android multi-module Kotlin project — `:core` (pure JVM, 275 tests), `:app` (rokid/rayneo flavors), `:agent` (injection agent), `:test-plugin` (Doc/10 reference plugin) |
| [`../.github/`](../.github/) | CI workflows — `build-apks` + `core-tests` |

---

## Doc maintenance

When code changes invalidate something here, update the relevant doc(s):

| You changed… | Update |
|---|---|
| `core/.../ble/` (protocol, frame parser) | [02](02-hardware-and-protocol.md) §4-§7 + [09](09-r08-ble-protocol-spec.md) |
| The gesture state machine | [04](04-interaction-design.md) §3 + tests |
| Default profiles or `KeyMapProfile` semantics | [04](04-interaction-design.md) §4 + [06](06-user-manual.md) §5 |
| System gestures or InteractionRouter | [04](04-interaction-design.md) §5 + [06](06-user-manual.md) §7 |
| Per-flavor strategies | [03](03-architecture.md) §4 + §8 |
| New executor backend | [03](03-architecture.md) §5 |
| UI screen / HUD event | [05](05-ui-design.md) + [`ui-mockup.html`](ui-mockup.html) |
| Build process | [07](07-developer-guide.md) §3 |
| Finished or started a TODO | a new dated snapshot in [`_handoffs/`](_handoffs/) + [11](11-v0.4-design.md) §11 |
| Project status changed | [01](01-overview.md) + a new [`_handoffs/`](_handoffs/) snapshot |

`Doc/README.md` (this file) gets updated when a top-level doc is added or removed.
