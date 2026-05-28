# Halo Ring · 环意 — Documentation

> **Halo Ring** · 环意 — *Where the ring goes, the world moves.* / 「环之所至，意之所达」
> by **Zack 紫意**

Use a **QRing R08 smart ring** as a single, wireless remote for **two pairs of AR glasses**:
**Rokid Glasses** and **RayNeo X3 Pro**. Same operations, same UI, automatic hand-over.

The Android package is `com.halo.ring` (suffixed `.rokid` / `.rayneo` per flavor); the agent
socket is `halo.agent`; the device codename used in source for the ring hardware itself remains
`R08` because that's the QRing model name. Brand assets in [`./brand/`](brand/).

**Last doc reorganisation**: 2026-05-27 — v0.4 prune pass. 20 files / ~7400 lines → 10 active
files / ~2800 lines. Pre-v0.4 versions are kept under [`_archive/`](_archive/). Forward-looking
plan is [Doc/20 v0.4 design](20-v0.4-design.md).

---

## Where to start, by who you are

| You are… | Read this first |
|---|---|
| **An end user** | [09 — User Manual](09-user-manual.md) |
| **A new contributor onboarding** | [01 — Overview](01-overview.md) → **[20 — v0.4 Design](20-v0.4-design.md)** → [04 — Architecture](04-architecture.md) → [05 — Interaction Design](05-interaction-design.md) |
| **Taking the project handoff from the previous agent** | **[13 — Handoff state snapshot](13-handoff.md)** → **[20 — v0.4 Design](20-v0.4-design.md)** → any doc as needed |
| **Building / extending the Android app** | [04 — Architecture](04-architecture.md) → [05 — Interaction Design](05-interaction-design.md) → [10 — Developer Guide](10-developer-guide.md) |
| **Adding gestures or actions** | [05 — Interaction Design](05-interaction-design.md) + [10 — Developer Guide](10-developer-guide.md) §4-§6 |
| **Reverse-engineering or debugging the ring protocol** | [02 — Hardware & Protocol](02-hardware-and-protocol.md) → canonical [`R08-dev/phase0/SPEC v3.md`](../../R08-dev/phase0/SPEC%20v3.md) |
| **Integrating an external app via the plugin protocol** | [18 — Plugin Protocol](18-plugin-protocol.md) |

---

## Active document index (10 files)

| # | Title | Focus | Length |
|---|---|---|---|
| **[01](01-overview.md)** | Project Overview | What this is, why, current state, reading order | ~5 min |
| **[02](02-hardware-and-protocol.md)** | Ring Hardware & BLE Protocol — Integration Notes | The opcodes Halo Ring uses + dedup constants + errata vs heritage doc. Points at SPEC v3 for the canonical bytes. | ~5 min |
| **[04](04-architecture.md)** | Architecture (+ Platforms + Perf & Power + Sensor matrix) | Module graph, runtime data flow, the four device strategies, executor backends, threading, perf/power, Rokid + RayNeo specifics, sensor utilisation matrix. Merged doc — absorbs former Doc/03, Doc/06, Doc/07. | ~15 min |
| **[05](05-interaction-design.md)** | Interaction Design | The 12-gesture vocabulary, state machine, 4 profiles, system-level gestures, modal layer, hand-over, **base-passthrough §3.8**, **plugin actions §4.4**. The gem. | ~15 min |
| **[08](08-ui-design.md)** | UI Design (v0.4) | HUD-first daily UX + Config-Activity for deep editing. HUD events catalogue, Config screen catalogue, design tokens. | ~10 min |
| **[09](09-user-manual.md)** | User Manual | End-user setup + gesture catalogue + 4 profiles + hand-over + troubleshooting | ~5 min |
| **[10](10-developer-guide.md)** | Developer Guide | Build, test, extend (new profile / gesture / platform / backend) | ~10 min |
| **[13](13-handoff.md)** | Handoff State Snapshot | Where the project is right now + forward plan pointer + threading discipline | ~5 min |
| **[18](18-plugin-protocol.md)** | External-App Plugin Protocol | Wire format for any installed app to expose actions to Halo Ring's profile bindings (Constellation is the first client). | ~10 min |
| **[20](20-v0.4-design.md)** | **v0.4 Design** | The canonical decision record for the next refactor pass — Service spine + HUD-first daily UX + deep-config-allowed; base-gestures hard-locked; sequencing C1-C7. | ~10 min |

Total active reading: ~90 min for end-to-end handoff.

---

## Archive (`_archive/`)

| File | Why archived |
|---|---|
| `11-verification-checklists.md` | Replaced by actual burn-in evidence in Doc/13 |
| `12-research-and-references.md` | Errata against `R08-Dev.md` heritage, superseded by SPEC v3 |
| `13-handoff-pre-v0.4.md` | Chronological audit-pass history (1236 lines) — current state moved to slim Doc/13 |
| `14-pre-hardware-testing.md` | Pre-hardware testing guide; hardware has arrived |
| `15-A2-spake2-tls-guide.md` | A-2 SPAKE2+TLS bring-up — completed |
| `17-community-protocol-spec.md` | Was a publish-target placeholder for SPEC v3 — now superseded by `R08-dev/phase0/SPEC v3.md` itself |
| `19-v0.3-refactor-plan.md` | Superseded by [Doc/20](20-v0.4-design.md); roll-forward mapping documented there |
| `03-target-platforms.md` | Merged into [Doc/04 §8](04-architecture.md#8-target-platforms) |
| `06-performance-and-power.md` | Merged into [Doc/04 §7](04-architecture.md#7-performance--power) |
| `07-sensors-and-modules.md` | Sensor matrix merged into [Doc/04 §9](04-architecture.md#9-sensor-utilisation-matrix-formerly-doc07-11); module list folded into Doc/04 §2 |
| `halo-ring-plugin-protocol-v0.1-draft.md` | Original Constellation handoff draft; superseded by shipped Doc/18 |

`_archive/` also holds [`R08-Remote-Design-v0.7.md`](_archive/R08-Remote-Design-v0.7.md) — the
pre-split monolithic design doc from 2026-05-13 (kept as historical reference).

---

## Code & related folders

| Path | What |
|---|---|
| [`../app-project/`](../app-project/) | The Android multi-module Kotlin project — `:core` (pure JVM, 282 tests), `:app` (rokid/rayneo flavors), `:agent` (injection agent), `:test-plugin` (Doc/18 reference plugin) |
| [`../.github/`](../.github/) | CI workflows — `build-apks` + `core-tests` |
| **Private research workspace** (`R08-dev`, not this repo) | BLE protocol validation (`phase0/SPEC v3.md` is the canonical), vendor SDKs (`refs/`), third-party clones (`research/`) |

---

## Doc maintenance

When code changes invalidate something here, update the relevant doc(s):

| You changed… | Update |
|---|---|
| `core/.../ble/` (protocol, frame parser) | [02](02-hardware-and-protocol.md) §4-§7 + `R08-dev/phase0/SPEC v3.md` |
| The gesture state machine | [05](05-interaction-design.md) §3 + tests |
| Default profiles or `KeyMapProfile` semantics | [05](05-interaction-design.md) §4 + [09](09-user-manual.md) §5 |
| System gestures or InteractionRouter | [05](05-interaction-design.md) §5 + [09](09-user-manual.md) §7 |
| Per-flavor strategies | [04](04-architecture.md) §4 + §8 |
| New executor backend | [04](04-architecture.md) §5 |
| UI screen / HUD event | [08](08-ui-design.md) + [`ui-mockup.html`](ui-mockup.html) |
| Build process | [10](10-developer-guide.md) §3 |
| Finished or started a TODO | [13](13-handoff.md) + [20](20-v0.4-design.md) §11 |
| Project status changed | [01](01-overview.md) + [13](13-handoff.md) §1 |

`Doc/README.md` (this file) gets updated when a new top-level doc is added or removed.
