# Halo Ring — External-App Plugin Protocol

**Version**: v0.1 (draft for Halo Ring agent)
**Author**: Constellation team (Zack)
**Status**: hand-off to Halo Ring agent for implementation
**Purpose**: Extend Halo Ring so any installed Android app can register its own actions, surface them in Halo Ring's Profile / Action Picker, and receive triggers when a wearer fires the bound gesture. Constellation (`com.constellation.glass`) is the first client.

---

## 0. Hand-off message (paste this into the Halo Ring agent)

> Halo Ring needs a generic **external-app plugin protocol** so that any other glasses-side app (starting with `com.constellation.glass`) can:
>
> 1. Declare a list of named actions it can be triggered to perform
> 2. Have those actions show up in Halo Ring's existing Profile / Action Picker UI under a new **EXTERNAL APPS** group
> 3. Receive an Android `Intent` when the wearer fires a gesture bound to one of its actions
> 4. (Optional) Tell Halo Ring to temporarily push a different gesture profile while its own overlay is shown
>
> The protocol below is finalised — you do not need to negotiate it. Your job is to implement the Halo-Ring side (discovery, Action Picker integration, Intent dispatch, optional profile-stack API), update Halo Ring's `ui-mockup.html` to show the new EXTERNAL APPS group, and document the change in Halo Ring's `Doc/` set.
>
> The Constellation app will implement the corresponding client side (`ContentProvider`, `BroadcastReceiver`, manifest meta-data). You don't need to coordinate Constellation's implementation — just make Halo Ring compliant with this spec, and Constellation will work as a drop-in client.

---

## 1. Why this is generic, not Constellation-specific

Halo Ring's current model assumes all actions live in a built-in `SettingsCatalog.ActionGroup` enum (NAV, MEDIA_VOL, FEATURE, MODAL, SYSTEM, NONE). This locks every new use-case to "modify Halo Ring's source". The plugin protocol lets the wearer install any app that conforms to the spec, and have its actions appear in Profile bindings — with no Halo Ring code changes per app.

Imagined future clients beyond Constellation:
- a notes app that wants "quick capture" on a gesture
- a media app that wants its own play / skip
- a custom Tasker-style automation
- experimental research apps

Build the protocol to fit a general "external action provider", not a Constellation special case.

---

## 2. Protocol overview

```
┌──────────────────────────────┐                  ┌──────────────────────────────┐
│         Halo Ring            │                  │   External app (e.g.         │
│                              │                  │   com.constellation.glass)   │
│  Action Picker UI            │                  │                              │
│      │                       │                  │  Manifest:                   │
│      │ list actions          │                  │   <meta-data                 │
│      │                       │  query           │     name="halo.ring.plugin   │
│      │  pluginRegistry  ─────┼─────────────────►│           _version"          │
│      │                       │  ContentProvider │     value="1"/>              │
│      │                       │                  │   <provider                  │
│      │                       │                  │     authority=".halo_        │
│      │                       │                  │            actions"/>        │
│      │ user binds gesture    │                  │   <receiver                  │
│      │                       │                  │     <intent-filter>          │
│      │  Halo writes binding  │                  │       <action= "com.halo     │
│      │  → SharedPreferences  │                  │         .ring.action.TRIGGER"│
│      │                       │                  │       />                     │
│      ▼                       │                  │     </intent-filter></rec>   │
│  Gesture recogniser          │                  │                              │
│      │                       │  Intent.TRIGGER  │  TriggerReceiver             │
│      │  fires action ────────┼─────────────────►│      │                       │
│      │                       │                  │      ▼                       │
│      │                       │                  │  app does its thing          │
│      │  (optional)           │  AIDL or Intent  │                              │
│      │  ◄ profile-push       │ ◄────────────────┼─ pushProfile("HUD")          │
│      │  ◄ profile-pop        │                  │                              │
└──────────────────────────────┘                  └──────────────────────────────┘
```

Four wire interfaces:

| # | Interface | Direction | Mechanism |
|---|---|---|---|
| 1 | **Discovery** | Halo Ring → external app | PackageManager scan for manifest meta-data |
| 2 | **Action listing** | Halo Ring → external app | `ContentProvider` query |
| 3 | **Action trigger** | Halo Ring → external app | `Intent` broadcast |
| 4 | **Profile push/pop** (optional) | External app → Halo Ring | `Intent` broadcast (back the other way) |

---

## 3. Spec — Discovery

### 3.1 External app manifest declaration

The external app declares itself as a Halo Ring plugin via a `<meta-data>` tag on the application element:

```xml
<application ...>
  <meta-data
    android:name="halo.ring.plugin_version"
    android:value="1" />
  ...
</application>
```

### 3.2 Halo Ring discovery routine

When Halo Ring opens the Action Picker (or on a manual "refresh plugins" trigger from Settings), it scans installed packages:

```kotlin
val pm = context.packageManager
val candidates = pm.getInstalledApplications(PackageManager.GET_META_DATA)
val plugins = candidates.filter { app ->
    app.metaData?.getInt("halo.ring.plugin_version", 0) == 1
}
```

For each plugin, Halo Ring then queries its `ContentProvider` (§4).

### 3.3 Caching

Cache the discovered plugin list with a short TTL (e.g. 30 s) so the Action Picker is snappy but reflects newly-installed apps reasonably soon. Invalidate on `PACKAGE_ADDED` / `PACKAGE_REMOVED` broadcasts.

---

## 4. Spec — Action listing (ContentProvider)

### 4.1 Provider authority

Each plugin app exposes a `ContentProvider` at:

```
content://{package_name}.halo_actions
```

For Constellation: `content://com.constellation.glass.halo_actions`

### 4.2 Manifest declaration (external app side)

```xml
<provider
  android:name=".HaloActionsProvider"
  android:authorities="com.constellation.glass.halo_actions"
  android:exported="true"
  android:readPermission="com.halo.ring.permission.READ_PLUGIN_ACTIONS" />
```

(The permission is defined by Halo Ring; see §7. External apps don't need to declare it — they just reference it as their `readPermission`.)

### 4.3 Query URI

Halo Ring queries:

```
content://{package}.halo_actions/list
```

### 4.4 Returned cursor schema

The cursor must contain these columns (`String` unless noted):

| Column | Type | Required | Description |
|---|---|---|---|
| `action_id` | String | yes | Stable identifier, unique within the app. Used in Intent extras. e.g. `"voice_invoke"`, `"shortcut_1"` |
| `label` | String | yes | User-visible label. Should be short. Will be displayed as `"{app name}: {label}"` in Halo Ring's picker |
| `description` | String | no | One-line longer description. May show as a sub-label in the picker. |
| `group` | String | no | Sub-group within the app's actions (e.g. `"shortcuts"`, `"core"`). Halo Ring may use this to subdivide if an app has many actions. |
| `icon_res_id` | int | no | Drawable resource ID in the external app's package. Halo Ring resolves via `pm.getResourcesForApplication()`. Optional — falls back to a generic plugin icon. |

### 4.5 Example cursor (Constellation)

```
action_id      | label                          | description                   | group
───────────────┼────────────────────────────────┼───────────────────────────────┼──────────
voice_invoke   | Voice invoke (mic + photo)    | open mic, send to Cortex      | core
shortcut_1     | Quick capture person          | identify + log                | shortcuts
shortcut_2     | OCR & save to today           | scan text, store to twin      | shortcuts
shortcut_3     | Drop a thought                | mic only, no photo            | shortcuts
```

### 4.6 Dynamic action lists

The plugin app can return a different list at any time (e.g. Constellation adds a shortcut → the next Halo Ring query sees the new action). Halo Ring should re-query when the Action Picker is opened, not cache aggressively.

---

## 5. Spec — Action trigger (Intent broadcast)

### 5.1 Intent contract

When the wearer fires a gesture that Halo Ring has bound to an external action, Halo Ring sends a targeted broadcast:

```kotlin
val intent = Intent("com.halo.ring.action.TRIGGER").apply {
    setPackage(pluginPackage)  // e.g. "com.constellation.glass"
    putExtra("action_id", actionId)  // e.g. "voice_invoke"
    putExtra("trigger_gesture", gestureName)  // e.g. "LP+SWIPE_UP" — informational, optional
    putExtra("trigger_ts_ms", System.currentTimeMillis())  // optional
}
context.sendBroadcast(intent, "com.halo.ring.permission.SEND_PLUGIN_TRIGGER")
```

### 5.2 Receiver declaration (external app side)

```xml
<receiver
  android:name=".HaloTriggerReceiver"
  android:exported="true"
  android:permission="com.halo.ring.permission.SEND_PLUGIN_TRIGGER">
  <intent-filter>
    <action android:name="com.halo.ring.action.TRIGGER" />
  </intent-filter>
</receiver>
```

### 5.3 Latency expectation

End-to-end latency from gesture recognition to broadcast delivery should be well under 50 ms in normal conditions (Android broadcasts are fast for foreground apps; external app's foreground service should be running). No retry or ACK is required — fire and forget. If the external app isn't listening, the action is dropped silently.

---

## 6. Spec — Profile push/pop (optional, for overlay apps)

This is an **optional** capability for apps that draw their own HUD overlays and want gestures interpreted differently while their overlay is visible.

### 6.1 Use case (Constellation)

When Constellation shows a preview card, it needs `SWIPE_UP/DOWN` to mean "move focus across options", `TAP` to mean "activate", `DOUBLE_TAP` to mean "dismiss" — regardless of what those gestures are bound to in the wearer's active profile.

### 6.2 Mechanism — Intent broadcast back to Halo Ring

```kotlin
// External app pushes a temporary profile:
val push = Intent("com.halo.ring.action.PROFILE_PUSH").apply {
    setPackage("com.halo.ring")
    putExtra("profile_id", "constellation_hud")  // app-defined name
    putExtra("bindings_json", bindingsJson)  // see §6.3
    putExtra("owner_package", "com.constellation.glass")
}
context.sendBroadcast(push)

// On overlay dismiss, external app pops:
val pop = Intent("com.halo.ring.action.PROFILE_POP").apply {
    setPackage("com.halo.ring")
    putExtra("profile_id", "constellation_hud")
}
context.sendBroadcast(pop)
```

### 6.3 `bindings_json` schema

A JSON object mapping gesture name to an action descriptor. Actions can be either external-app `action_id`s or Halo Ring's own action enum names.

```json
{
  "SWIPE_UP":    { "type": "external", "package": "com.constellation.glass", "action_id": "hud_focus_prev" },
  "SWIPE_DOWN":  { "type": "external", "package": "com.constellation.glass", "action_id": "hud_focus_next" },
  "TAP":         { "type": "external", "package": "com.constellation.glass", "action_id": "hud_activate" },
  "DOUBLE_TAP":  { "type": "external", "package": "com.constellation.glass", "action_id": "hud_dismiss" }
}
```

Gestures **not present in the map** fall through to the underlying profile. System gestures (TRIPLE_TAP, QUAD_TAP, LP+SWIPE_DOWN, 2× LP) always pass through regardless of pushed profiles (Halo Ring's existing system-gesture priority must not be broken).

### 6.4 Stack semantics

Pushed profiles stack. Multiple apps can push their own profile on top of each other (rare, but should work). `PROFILE_POP` removes the topmost matching `profile_id` for that `owner_package`. Halo Ring should also auto-pop if the owner package's process dies (so a crashed overlay app doesn't strand gestures).

### 6.5 Permission

Same as trigger:
```xml
<permission
  android:name="com.halo.ring.permission.PUSH_PROFILE"
  android:protectionLevel="signature|privileged" />
```

External apps need this permission to push; user can revoke if they distrust an app.

---

## 7. Permissions (Halo Ring defines these)

In Halo Ring's `AndroidManifest.xml`:

```xml
<!-- External plugins must hold this to read action lists from us... wait no. Reverse:
     Halo Ring needs to read from external apps' ContentProviders. So:
     - readPermission on plugin's provider = Halo Ring needs to hold it.
     - Halo Ring declares + holds the permission.  -->

<permission
  android:name="com.halo.ring.permission.READ_PLUGIN_ACTIONS"
  android:protectionLevel="signature" />
<uses-permission android:name="com.halo.ring.permission.READ_PLUGIN_ACTIONS" />

<permission
  android:name="com.halo.ring.permission.SEND_PLUGIN_TRIGGER"
  android:protectionLevel="normal" />
<!-- Halo Ring uses this when sending the TRIGGER broadcast; receivers in
     external apps require it to filter out spoofed triggers. -->

<permission
  android:name="com.halo.ring.permission.PUSH_PROFILE"
  android:protectionLevel="signature|privileged" />
<!-- External apps that want to push HUD profiles must declare uses-permission
     for this. Signature level → user implicitly trusts Halo Ring; privileged
     for system-installed plugin apps. -->
```

Permission stance: **trust the plugin apps the wearer chose to install**. Don't try to sandbox heavily — if a malicious app is installed, the wearer has bigger problems. Permissions are mostly to keep "well-behaved" boundaries clear and to prevent accidental cross-talk.

---

## 8. Halo Ring UI changes (required)

### 8.1 Action Picker — new EXTERNAL APPS group

The existing Action Picker groups (`NAV`, `MEDIA_VOL`, `FEATURE`, `MODAL`, `SYSTEM`, `NONE`) gain a new sibling: **`EXTERNAL APPS`**. Within this group, each installed plugin app is its own sub-section, listing its actions.

Mock-up (in Halo Ring's `ui-mockup.html`):

```
NAVIGATION
  Confirm · Back · Home …

MEDIA / VOLUME
  Play / pause · Volume + / − …

FEATURE
  Take photo · Visual AI …

MODAL · SYSTEM · NONE …

EXTERNAL APPS                       ← new group
  Constellation
    ● Voice invoke (mic + photo)    ← currently bound
    ○ Quick capture person
    ○ OCR & save to today
    ○ Drop a thought
  (Other plugin apps appear here as they're installed)
```

Visual treatment: same as existing groups — single-column rows, focused row gets the green left bar + 7% accent tint. App name is a small caps subheading (same as group headings); actions are body rows.

### 8.2 Settings — new plugin status entry

Add a Settings row: **"External plugins (1 active)"** that opens a page listing discovered plugin apps, their action counts, and a "Refresh plugins" button.

```
SETTINGS
  Profiles & Gestures      ›
  System Gestures          ›
  Ring                     ›
  ...
  External plugins  1 ›    ← new
  Language                 ›
  ...
```

The plugin-detail page shows each app: package, app name, action count, plugin protocol version, status (alive / dead).

### 8.3 First-run wizard — no change

Plugin discovery is silent; users discover EXTERNAL APPS naturally when they open the Action Picker after installing a plugin app. No tutorial needed.

---

## 9. Halo Ring code changes (suggested module layout)

```
:app
  └─ plugin/
       ├─ PluginRegistry.kt           # discovery + cache
       ├─ PluginAction.kt             # data class for one action
       ├─ PluginQuery.kt              # ContentProvider query wrapper
       ├─ PluginTrigger.kt            # Intent broadcast wrapper
       └─ ProfileStack.kt             # extended: now supports pushed profiles

  └─ ui/screens/
       └─ ActionPicker.kt             # extend to load EXTERNAL APPS group
       └─ ExternalPluginsScreen.kt    # new settings page

  └─ service/
       └─ HaloRingService.kt          # extend: register BroadcastReceiver
                                      #   for PROFILE_PUSH/POP
```

Keep the plugin layer **self-contained** in `:app/plugin/` so it can later be extracted to a `:plugin` module if needed.

---

## 10. Halo Ring documentation changes (required)

Update these files in `R08-dev/Doc/`:

1. **`ui-mockup.html`** — add the EXTERNAL APPS group to the Action Picker mockup (§2 "Settings root" frame and §3 "Action Picker" frame); add the External plugins settings entry to the Settings list (§2 "SETTINGS tab — configuration root" frame, where the 10 sections live)
2. **`08-ui-design.md`** — document the new EXTERNAL APPS group rendering, the External plugins settings screen, and how pushed profiles affect the focus indicator (no change to indicator itself)
3. **`05-interaction-design.md`** — add a section on "External plugin actions" describing how a plugin action is treated identically to a built-in action from the wearer's perspective, plus a paragraph on the optional profile push/pop mechanism
4. **`17-community-protocol-spec.md`** — promote this protocol document into Halo Ring's own protocol spec set (alongside the BLE protocol), so plugin authors have one canonical place to read
5. Add a new file: **`Doc/18-plugin-protocol.md`** — the full protocol spec (copy of this document, adapted to Halo Ring's house style + voice)

---

## 11. Test cases to validate the implementation

### 11.1 Plugin discovery

| # | Setup | Expected |
|---|---|---|
| T1 | Constellation installed, manifest has plugin_version=1 | Action Picker shows "Constellation" under EXTERNAL APPS |
| T2 | Constellation installed, manifest has plugin_version=2 | Constellation does NOT show up (version mismatch — Halo Ring v1 only knows version 1) |
| T3 | Constellation installed, no manifest meta-data | Constellation does NOT show up |
| T4 | Constellation installed, but no ContentProvider | Constellation appears in EXTERNAL APPS with "(no actions)" placeholder |
| T5 | Install a second plugin app while Halo Ring is running | Action Picker refreshes within ~1 s (via PACKAGE_ADDED broadcast) and shows both |

### 11.2 Action binding + trigger

| # | Setup | Expected |
|---|---|---|
| T6 | Bind DT+SWIPE_UP to Constellation/voice_invoke; fire gesture | Constellation receives Intent within 50 ms; foreground service launches voice-invoke flow |
| T7 | Plugin app not running (no foreground service) | Intent still delivered (Android broadcast); plugin's BroadcastReceiver wakes it |
| T8 | Plugin app uninstalled while binding still references its action | Binding shows greyed-out in Action Picker with "missing" label; firing gesture does nothing (no crash) |
| T9 | Two profiles bind same gesture to different actions — switch profiles via TRIPLE_TAP | Correct profile's action fires after switch |

### 11.3 Profile push/pop

| # | Setup | Expected |
|---|---|---|
| T10 | Constellation pushes constellation_hud profile; SWIPE_UP fires | Routed to hud_focus_prev, not the wearer's profile binding |
| T11 | Constellation pushes profile then crashes | Halo Ring auto-pops within 5 s; gestures return to underlying profile |
| T12 | Two apps push profiles simultaneously; second app pops | Order maintained; remaining stack still active |
| T13 | TRIPLE_TAP while constellation_hud pushed | Halo Ring's system gesture (cycle profile) wins; constellation_hud unaffected |

---

## 12. Out of scope for v1

- Cross-app action chaining (one plugin invokes another)
- Per-profile plugin enable/disable
- Plugin-specified icon overrides at the gesture level
- Telemetry / usage stats exposed back to plugin
- Plugin signing verification beyond Android's standard package signature

---

## 13. Versioning

`halo.ring.plugin_version = 1` is the only supported version. Future incompatible changes increment this and require plugin apps to declare matching version. Backwards-compatible additions (new optional cursor columns, new optional intent extras) do not require a version bump — implementations ignore unknown fields.

---

## 14. Reference: Constellation's side of the wire

For your reference (Halo Ring agent doesn't implement this — Constellation does — but it helps to see the matching ends):

### Constellation manifest
```xml
<application ...>
  <meta-data android:name="halo.ring.plugin_version" android:value="1"/>

  <provider
    android:name=".HaloActionsProvider"
    android:authorities="com.constellation.glass.halo_actions"
    android:exported="true"
    android:readPermission="com.halo.ring.permission.READ_PLUGIN_ACTIONS" />

  <receiver
    android:name=".HaloTriggerReceiver"
    android:exported="true"
    android:permission="com.halo.ring.permission.SEND_PLUGIN_TRIGGER">
    <intent-filter>
      <action android:name="com.halo.ring.action.TRIGGER" />
    </intent-filter>
  </receiver>

  <uses-permission android:name="com.halo.ring.permission.PUSH_PROFILE"/>
</application>
```

### Constellation's TriggerReceiver dispatch logic
```kotlin
class HaloTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.getStringExtra("action_id") ?: return
        val service = Intent(context, ConstellationService::class.java).apply {
            putExtra("invoke_kind", actionId)  // "voice_invoke" / "shortcut_1" / …
        }
        ContextCompat.startForegroundService(context, service)
    }
}
```

### Constellation's profile push (when HUD opens)
```kotlin
fun pushHudProfile() {
    val bindings = """
        {
          "SWIPE_UP":    {"type":"external","package":"com.constellation.glass","action_id":"hud_focus_prev"},
          "SWIPE_DOWN":  {"type":"external","package":"com.constellation.glass","action_id":"hud_focus_next"},
          "TAP":         {"type":"external","package":"com.constellation.glass","action_id":"hud_activate"},
          "DOUBLE_TAP":  {"type":"external","package":"com.constellation.glass","action_id":"hud_dismiss"}
        }
    """.trimIndent()
    sendBroadcast(Intent("com.halo.ring.action.PROFILE_PUSH").apply {
        setPackage("com.halo.ring")
        putExtra("profile_id", "constellation_hud")
        putExtra("bindings_json", bindings)
        putExtra("owner_package", "com.constellation.glass")
    })
}
```

---

## 15. Open questions for the Halo Ring agent

If anything below blocks implementation, surface to Zack before deciding:

1. **Profile push performance.** The push/pop broadcast happens on every HUD open/close — potentially many times per minute when Constellation is active. Acceptable, or should we use AIDL service binding instead of broadcasts for this specific channel?
2. **Action Picker scalability.** If a wearer installs 5–10 plugin apps each with 5–10 actions, the EXTERNAL APPS group could be ~50–100 rows. Acceptable as a flat list, or should we add per-app expand/collapse?
3. **Icon rendering.** External app's drawable resolved via `pm.getResourcesForApplication()` is straightforward but adds an IPC cost on Action Picker open. Acceptable, or should we cache the resolved bitmaps?
4. **Intent vs AIDL.** This spec uses broadcasts everywhere for simplicity. If you find broadcasts too lossy / too slow in practice, propose an AIDL alternative — but keep the broadcast path as a fallback for simple plugin apps that don't want to implement AIDL.

---

## 16. Document status

- **Version**: v0.1
- **Date**: 2026-05-23
- **Author**: Constellation team (Zack), with assistance
- **Companion**: [Constellation/Doc/ui-mockup.html](Doc/ui-mockup.html), [Constellation/UI-UX.md](UI-UX.md)
- **For**: Halo Ring agent
- **Next**: Halo Ring agent implements + reports back. Iteration via the two project repos.

---

*End of Halo Ring Plugin Protocol v0.1.*
