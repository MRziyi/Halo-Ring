# 10 — Halo Ring External-App Plugin Protocol

> **Status**: v1 — shipped. **Source**: this document is the canonical spec; the original draft
> handed off by the Constellation team lives at [`halo-ring-plugin-protocol.md`](halo-ring-plugin-protocol.md).
> Adapted to Halo Ring's house style (paths to actual code, where each piece is implemented,
> deviations from the draft).

A small Android-IPC protocol that lets any installed app expose its own actions to the wearer's
profile bindings. **Constellation (`com.constellation.glass`) is the first client**, but the
protocol is generic — a notes app, a media app, a Tasker-style automation, a research probe could
all conform with no Halo Ring code changes.

This is **not** a runtime extension API in the JVM sense. Halo Ring doesn't load anyone's code.
Each piece is a stock Android IPC primitive (ContentProvider query, Intent broadcast,
PackageManager scan); the protocol is just the agreed-upon names + schemas on the wire.

---

## 1. Overview

```
┌──────────────────────────────┐                  ┌──────────────────────────────┐
│         Halo Ring            │                  │   External plugin app        │
│                              │                  │                              │
│  Action Picker UI            │                  │  Manifest:                   │
│      │                       │                  │   <meta-data                 │
│      │ 1. discover           │  PackageManager  │     name="halo.ring          │
│      │                       │  queryBroadcast  │           .plugin_version"   │
│      │  PluginRegistry  ─────┼─────────────────►│     value="1"/>              │
│      │  ◄ candidate set      │  Receivers       │   <provider                  │
│      │                       │                  │     authority=".halo_        │
│      │ 2. list actions       │                  │            actions"/>        │
│      │                       │  ContentProvider │   <receiver                  │
│      │  PluginQuery     ─────┼─────────────────►│     <intent-filter>          │
│      │  ◄ cursor → List      │                  │       <action="com.halo      │
│      │                       │                  │         .ring.action.TRIGGER"│
│      │ user binds gesture    │                  │     /></receiver>            │
│      │  ▼                    │                  │                              │
│  Gesture recogniser          │                  │  TriggerReceiver             │
│      │                       │  3. Intent       │      │                       │
│      │  PluginTrigger ───────┼─────────────────►│      ▼                       │
│      │                       │  (TRIGGER)       │  app does its thing          │
│      │                       │                  │                              │
│      │   (optional)          │  4. Intent       │  pushHudProfile()            │
│      │   PluginBroadcast ◄───┼──────────────────┼─ (PROFILE_PUSH / POP)        │
│      │   → ProfileStack      │                  │                              │
└──────────────────────────────┘                  └──────────────────────────────┘
```

Four wire interfaces, all stock Android:

| # | Interface | Direction | Mechanism | Halo Ring impl |
|---|---|---|---|---|
| 1 | Discovery | Halo Ring → plugin | `PackageManager.queryBroadcastReceivers(TRIGGER)` + meta-data | [`PluginRegistry`](../app-project/app/src/main/kotlin/com/halo/ring/plugin/PluginRegistry.kt) |
| 2 | Action listing | Halo Ring → plugin | `ContentProvider.query()` | [`PluginQuery`](../app-project/app/src/main/kotlin/com/halo/ring/plugin/PluginQuery.kt) |
| 3 | Action trigger | Halo Ring → plugin | Targeted `Intent` broadcast | [`PluginTrigger`](../app-project/app/src/main/kotlin/com/halo/ring/plugin/PluginTrigger.kt) |
| 4 | Profile push/pop | plugin → Halo Ring | Intent broadcast (back the other way) | [`PluginBroadcastReceiver`](../app-project/app/src/main/kotlin/com/halo/ring/plugin/PluginBroadcastReceiver.kt) + [`ProfileStack`](../app-project/core/src/main/kotlin/com/halo/ring/core/plugin/ProfileStack.kt) |

---

## 2. Manifest contract (plugin side)

```xml
<application ...>
  <!-- 2.1 Declares the app as a Halo Ring plugin. Required. -->
  <meta-data
    android:name="halo.ring.plugin_version"
    android:value="1" />

  <!-- 2.2 Action listing. Authority must be "{your.package}.halo_actions". -->
  <provider
    android:name=".HaloActionsProvider"
    android:authorities="com.constellation.glass.halo_actions"
    android:exported="true"
    android:readPermission="com.halo.ring.permission.READ_PLUGIN_ACTIONS" />

  <!-- 2.3 Receives triggers from Halo Ring. Required if you bind any of your actions. -->
  <receiver
    android:name=".HaloTriggerReceiver"
    android:exported="true"
    android:permission="com.halo.ring.permission.SEND_PLUGIN_TRIGGER">
    <intent-filter>
      <action android:name="com.halo.ring.action.TRIGGER" />
    </intent-filter>
  </receiver>

  <!-- 2.4 Optional: only if your app pushes overlay profiles. -->
  <uses-permission android:name="com.halo.ring.permission.PUSH_PROFILE" />
</application>
```

The three permissions are declared by Halo Ring (in our `AndroidManifest.xml`); plugins just
reference them by name. No declaration on the plugin side — Android's permission system handles
the cross-package binding.

---

## 3. Cursor schema (Doc §4.4 in the draft)

The plugin's `ContentProvider` answers queries at `content://{pkg}.halo_actions/list` with a
cursor whose columns are:

| Column | Type | Required | Notes |
|---|---|---|---|
| `action_id` | String | yes | Stable per plugin. Used as the `action_id` Intent extra at trigger time. |
| `label` | String | yes | Short user-visible name. Rendered as `"{app}: {label}"` in our HUD. |
| `description` | String | no | One-line caption shown under the picker row. |
| `group` | String | no | Sub-group within the plugin (e.g. `"shortcuts"`). Not used by v1 UI; reserved. |
| `icon_res_id` | int | no | Reserved; not consumed in v1 (see §8 Q3). |

Halo Ring's [`PluginQuery.readAll`](../app-project/app/src/main/kotlin/com/halo/ring/plugin/PluginQuery.kt)
is **forgiving**: missing optional columns, null cells, and unknown extra columns are all
tolerated. Missing `action_id` or empty `label` rows are silently skipped (not the whole list).

---

## 4. Trigger Intent contract

When the wearer fires a gesture bound to a plugin action, Halo Ring sends:

```
Intent("com.halo.ring.action.TRIGGER")
  .setPackage(pluginPackage)
  .putExtra("action_id", actionId)
  .putExtra("trigger_gesture", "DOUBLE_TAP_SWIPE_UP")   // canonical Gesture.name
  .putExtra("trigger_ts_ms", System.currentTimeMillis())
  + flag FLAG_INCLUDE_STOPPED_PACKAGES                  // wake force-stopped plugins

context.sendBroadcast(intent, "com.halo.ring.permission.SEND_PLUGIN_TRIGGER")
```

Plugin-side receiver pattern (from the spec's reference):

```kotlin
class HaloTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val actionId = intent.getStringExtra("action_id") ?: return
        // dispatch to your service / activity
    }
}
```

End-to-end latency budget: **< 50 ms** in normal conditions, dominated by the broadcast
delivery latency (which for foreground / recently-running apps is single-digit ms). No retry,
no ACK — fire and forget per Doc/10 §5.3.

---

## 5. Exclusive overlay (HUD takeover) — Doc/10 §7 model

**Supersedes the old `PROFILE_PUSH` binding-stack** (removed 2026-05-29 with `ProfileStack` /
`PluginBindingsParser`). Use when your app shows an on-glasses HUD (over any app or the home) and
the ring must drive **only that HUD** while it's up.

### The model
While your overlay is active it behaves like an exclusive profile:
- it's **not** inferred from the foreground app — **you signal** activation/deactivation;
- it is **exclusive**: [`InteractionRouter`](../app-project/core/src/main/kotlin/com/halo/ring/core/gesture/InteractionRouter.kt)
  (§0a) forwards **every** ring gesture to you and lets **nothing** leak to the underlying app — no
  base-key passthrough, no page-flip, no system gestures (even `TRIPLE_TAP`/`LONG_PRESS` are
  captured). Profile auto-inference is frozen meanwhile.
- **you own all semantics + on-HUD prompts.** Halo Ring forwards **raw gesture names**, never
  `action_id`s — so you may remap a gesture's meaning per HUD-state without telling us.

State lives in [`OverlayController`](../app-project/core/src/main/kotlin/com/halo/ring/core/plugin/OverlayController.kt)
(single-active; pure-JVM, unit-tested).

### Wire protocol
All gated by `PUSH_PROFILE` (signature|privileged — co-signed plugins only).

**You → Halo Ring** (`setPackage("com.halo.ring.rokid")` / `.rayneo`):

| Action | Extras | When |
|---|---|---|
| `com.halo.ring.action.OVERLAY_ACTIVATE` | `owner_package` (req), `profile_id` (opt, default `overlay`), `display_name` (opt) | HUD shown. **Re-send every ~20–30 s as keepalive.** |
| `com.halo.ring.action.OVERLAY_DEACTIVATE` | `owner_package` (req), `profile_id` (opt) | HUD closed. |

**Halo Ring → You** (explicit broadcast to your package):

| Action | Extras |
|---|---|
| `com.halo.ring.action.OVERLAY_GESTURE` | `gesture` = a [`Gesture`](../app-project/core/src/main/kotlin/com/halo/ring/core/gesture/Gestures.kt) name; `from_package` |

```kotlin
// Activate when your HUD opens (refresh ~every 25 s while up):
context.sendBroadcast(Intent("com.halo.ring.action.OVERLAY_ACTIVATE").apply {
    setPackage("com.halo.ring.rokid")
    putExtra("owner_package", "com.constellation.glass")
    putExtra("profile_id", "constellation_hud")
    putExtra("display_name", "Constellation")
})
// Deactivate when it closes:
context.sendBroadcast(Intent("com.halo.ring.action.OVERLAY_DEACTIVATE").apply {
    setPackage("com.halo.ring.rokid"); putExtra("owner_package", "com.constellation.glass")
})
// Receive forwarded gestures:
class OverlayGestureReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getStringExtra("gesture")) { "TAP" -> approve(); "DOUBLE_TAP" -> dismiss(); /* … */ }
    }
}
```

The forwardable gesture vocabulary is the [`Gesture`](../app-project/core/src/main/kotlin/com/halo/ring/core/gesture/Gestures.kt)
enum (TAP, DOUBLE_TAP, SWIPE_UP/DOWN, LONG_PRESS, TAP_SWIPE_*, DOUBLE_TAP_SWIPE_*, LONG_PRESS_SWIPE_*,
TRIPLE_TAP, DOUBLE_LONG_PRESS). No left/right swipe (SPEC v3). Map a dismiss gesture (suggest
`DOUBLE_TAP`) so the wearer can always exit.

### Lifecycle / safety
- **Single-active**: a new activate from another owner replaces the prior.
- **Keepalive timeout 60 s** (`OverlayController.DEFAULT_TIMEOUT_MS`): no re-activate within the
  window → auto-release (a crashed/hung plugin can't lock the wearer out).
- **Uninstall**: `ACTION_PACKAGE_REMOVED` of the owner releases the overlay.
- **HUD cue**: `display_name` flashes on activate; the underlying mode name on release.

### Legacy alias
The removed `PROFILE_PUSH` / `PROFILE_POP` actions are still accepted and mapped onto
activate/deactivate (so an un-migrated plugin still toggles the overlay), **but `bindings_json` is
ignored** and the old `hud_*` TRIGGERs are no longer sent — migrate to `OVERLAY_GESTURE`.

---

## 6. Permissions

| Permission | Level | Owner | Notes |
|---|---|---|---|
| `com.halo.ring.permission.READ_PLUGIN_ACTIONS` | `signature` | Halo Ring | Plugin's CP `readPermission` references this; Halo Ring is the only holder. |
| `com.halo.ring.permission.SEND_PLUGIN_TRIGGER` | `normal` | Halo Ring | Plugin receiver `android:permission` references this; prevents arbitrary apps spoofing triggers. |
| `com.halo.ring.permission.PUSH_PROFILE` | `signature|privileged` | Halo Ring | Pushers `uses-permission` this. High bar — same-cert plugins only on stock devices, plus OEM-privileged plugins. |

Stance: **we trust the plugin apps the wearer chose to install.** The permissions exist to keep
well-behaved boundaries clear, not to sandbox a hostile actor — if the wearer installs malware
they have bigger problems than gesture spoofing.

---

## 7. UI surfaces

### 7.1 Action Picker — EXTERNAL APPS group

New group between `MODAL/SYSTEM/NONE` and the end of the list. Renders one sub-heading per
installed plugin (small all-caps, like a regular group header), then a row per action. Empty
state ("No plugin apps installed.") always shows so the wearer learns the section exists.

Implementation: [`ActionPickerScreen`](../app-project/app/src/main/kotlin/com/halo/ring/ui/screens/ActionPickerScreen.kt)
reads `pluginRegistry.plugins` (StateFlow) reactively.

### 7.2 Settings → External plugins

New row in [Settings root](05-ui-design.md) between Test Arena and Advanced. Trailing "N
active" summary when at least one plugin is installed. Drills into
[`ExternalPluginsScreen`](../app-project/app/src/main/kotlin/com/halo/ring/ui/screens/ExternalPluginsScreen.kt)
which lists each plugin's app name, package, action count, protocol version, and status
("● alive" / "⚠ {error}"). One CTA: "REFRESH PLUGINS" forces a re-scan.

No per-plugin enable/disable toggle in v1 (Doc/10 §12). To stop a plugin firing, unbind its
actions in the picker.

---

## 8. Open questions — decisions

From [`halo-ring-plugin-protocol.md`](halo-ring-plugin-protocol.md) §15:

1. **Profile push/pop = broadcast (not AIDL).** HUD open/close cadence is order ~seconds, not
   ms. The AIDL service binding overhead (linkage, async returns, lifecycle) costs more
   complexity than the 1–5 ms it would save. Revisit if Constellation telemetry shows broadcast
   loss in practice.
2. **Picker scalability = flat list.** At ≤50 actions across all installed plugins the flat
   FlowRow / scroll fits cleanly. Per-plugin expand/collapse is a follow-up if real-world data
   shows >5 plugins per wearer.
3. **Icon rendering = skipped in v1.** Plugin actions render as text rows only (same visual
   weight as built-in actions). Adding a `pm.getResourcesForApplication` IPC per row + bitmap
   cache is a nontrivial perf cost on Action Picker open; punted until a plugin author requests it.
4. **Trigger = broadcast (not AIDL).** Already meets the "< 50 ms" target; AIDL would add bind
   lifecycle for no measurable win.

---

## 9. Testing

JVM unit tests (`./gradlew :core:test`):

- `GlassActionCodecTest` — PluginAction round-trip including pipe / backslash escape, empty
  field tolerance, malformed input → `None` (9 new cases on top of the existing codec suite).
- `ProfileStackTest` — push / pop / LIFO / fall-through / re-push-replaces / dropOwner /
  defensive snapshot (10 cases).
- `PluginBindingsParserTest` — single + multi binding parse, unknown gesture skip,
  non-external skip, compact aliases, missing fields, label fallback, JSON escape (10 cases).

Total: **+36 :core tests** specific to this protocol.

Spec test matrix (Doc/10 §11) for on-device verification on the OnePlus burn rig:

| # | Setup | Expected | Verified |
|---|---|---|---|
| T1 | Stub plugin installed, plugin_version=1 | Action Picker shows it | ✅ |
| T2 | plugin_version=2 | Does NOT show | ✅ |
| T3 | No meta-data | Does NOT show | ✅ |
| T4 | No ContentProvider | Shows with "(no actions)" placeholder | ✅ |
| T5 | Install while running | Surfaces within 30 s (TTL) | ✅ |
| T6 | Bind + fire gesture | Plugin receives Intent within 50 ms | ✅ |
| T7 | Plugin not running | Intent still delivered | ✅ |
| T8 | Plugin uninstalled, binding stale | "(missing)" label; no crash | ✅ |
| T9 | Two profiles bind same gesture; cycle | Correct action after switch | ✅ |
| T10 | Plugin pushes profile; SWIPE_UP fires | Routed to overlay's `hud_focus_prev` | ✅ |
| T11 | Plugin pushes then crashes / uninstalls | Stack auto-pops | ✅ |
| T12 | Two apps push; second pops | Order maintained | ✅ |
| T13 | TRIPLE_TAP while overlay pushed | System gesture wins | ✅ |

---

## 10. Out of scope (v1)

- Cross-app action chaining (one plugin invokes another).
- Per-profile plugin enable/disable.
- Plugin-specified icon overrides at the gesture level (column reserved in cursor schema).
- Telemetry / usage stats exposed back to plugins.
- Signature verification beyond Android's standard package signature check.

---

## 11. Versioning

`halo.ring.plugin_version = 1` is the only version Halo Ring v0.2.x reads. Future
**incompatible** changes increment this — plugins matching an older version still appear in
Settings → External plugins (marked with an "unsupported plugin protocol version N" error) so
the wearer knows to update one side or the other.

Backwards-compatible additions (new optional cursor columns, new optional intent extras,
new aliases in `PluginBindingsParser`) **do not** require a version bump — Halo Ring ignores
unknown fields, plugins ignore unknown extras.
