# 03 — Target Platforms: Rokid Glasses & RayNeo X3 Pro

Both target glasses run **Android 12**, both use **focus-based** navigation in their system UI,
and both **don't allow third-party apps to inject input directly** (the privileged
`INJECT_EVENTS` permission isn't available to us). The differences are mostly transport: Rokid
turns its temple touchpad into DPAD key events; X3 Pro keeps it as MotionEvents and the system UI
recognises gestures from them.

This document is one stop for everything we know about both platforms relevant to our app.

---

## 1. Rokid Glasses (YodaOS-Sprite)

Source: [`../research/rokid-docs/`](../research/rokid-docs/) — community-maintained
`buildwithfenna/rokid-docs` (full firmware decompilation + system-app analysis + CXR SDK suite docs).

### 1.1 Platform

| Property | Value |
|---|---|
| Model | RG-glasses (sometimes "Rokid Glasses" in marketing) |
| Brand | Rokid |
| OS | **YodaOS** (Android 12, API 32, SDK 32) |
| Build ID | SKQ1.240613.001 |
| Build fingerprint | `Rokid/glasses/glasses:12/SKQ1.240613.001/1.12.009-…:user/release-keys` |
| Board / SoC | Qualcomm Neo / Kryo 300 (Cortex-A75), Adreno 620/621/650/740v3 |
| Primary ABI | arm64-v8a |
| Display | JBD JBD4020 Micro-LED, **right eye only** (mono), ≈480 px wide |
| IMU | InvenSense ICM-4x6xx (accel + gyro + motion/freefall detect + temperature) via I3C |
| Speech co-proc | NXP RT600 (iFlytek + Rokid KWS for wake word) |
| Low-RAM mode | Yes (Android "Go") |
| OTA | Virtual A/B |
| Default locale | zh-CN |

OEM variants: domestic / overseas / state-gift / carrier / Bolon AI Glasses (no display) /
Leqi-branded. All share platform + most apps; differ in display presence, auth keys, boot logo.

### 1.2 The system UI is **focus-based** with DPAD key transport

Source: `rokid-docs/yodaos/docs/apps/sprite-launcher.md`. The launcher
(`com.rokid.os.sprite.launcher`) handles only:
- **Left / Right** — move focus in horizontal lists (settings, app grid)
- **Up / Down** — move focus in vertical lists (translate text, word tips)
- **Enter / OK** — confirm selection
- **Back** — return / exit

The temple touchpad (Cypress PSoC capacitive sensor on the right temple — kernel module
`psoc_ts_drv_right.ko`) is translated by the system into the corresponding `KEYCODE_DPAD_*`
events that the launcher's focus framework consumes.

**Implication for us**: to drive the launcher, **inject DPAD key events**. `dispatchGesture`
coordinate taps via Accessibility won't help — the launcher isn't listening for taps.

> **Rokid has no touchscreen at all** (confirmed by `RokidSpriteLauncher` decomp:
> *"button-only navigation, no touchscreen"*). Any `Modifier.pointerInput { }` /
> `detectTapGestures` / drag composables in our shared UI code are dead on Rokid. Stay
> focus-driven: `Modifier.clickable()` (which DPAD_CENTER triggers automatically) is fine;
> raw pointer handlers are not.

### 1.3 Launching features via Intent (the right way)

The launcher has 21 exported page Activities, all launchable with `am start -n …`. So
"take a photo" / "open AI chat" don't need fake keypresses — just `am start` the right Activity.
From `sprite-launcher.md`:

| Feature | Component / broadcast |
|---|---|
| Camera | `am start -n com.rokid.os.sprite.launcher/.page.camera.CameraPageActivity` |
| AI chat | `…/.page.chat.ChatPageActivity` |
| Translate | `…/.page.translate.TranslatePageActivity` |
| Word tips (teleprompter) | `…/.page.wordtips.WordTipsPageActivity` |
| Music | `…/.page.music.MusicPageActivity` |
| Navigation (CN / overseas) | `…/.page.navigation.NavigationPageActivity` / `NavigationOverseaPageActivity` |
| Payment QR | `…/.page.payment.PaymentPageActivity` |
| Audio record | `…/.page.audio.AudioPageActivity` |
| Gallery | `…/.page.gallery.StorageImageShowActivity` |
| Settings | `…/.setting.SettingPageActivity` |
| **Open any installed app** | `am broadcast -a com.rokid.os.sprite.launcher.cmd --es cmd open_app --es pkg <pkg>` |
| **Open Visual AI** | `am broadcast -a com.rokid.visualaidemo.ACTION_START` |

These are wired into our `RokidFeatureIntents` (in
[`../app-project/app/src/rokid/.../RokidStrategies.kt`](../app-project/app/src/rokid/kotlin/com/r08remote/app/device/rokid/RokidStrategies.kt)).

### 1.4 Camera key handling

From `rokid-docs/yodaos/docs/apps/camera2.md`: the Camera2 Activity treats both
`KEYCODE_DPAD_CENTER`(23), `KEYCODE_CAMERA`(27), and `KEYCODE_VOLUME_UP/DOWN`(24/25) as shutter
keys. So "take a photo" = launch the Camera Activity + send a DPAD_CENTER.

### 1.5 Other system services (mostly not used by us)

- **CXR-S SDK** (`com.rokid.cxr:cxr-service-bridge`) — phone↔glasses message channel. Doesn't
  help us inject input.
- **CXR-M SDK** — for building a companion mobile app.
- **CXR-L SDK** — for "standalone apps that replace the default Rokid apps". Not relevant.
- **Speech / TTS** — runs on the NXP RT600 SPI co-processor. Wake words are iFlytek + Rokid's
  own KWS.

### 1.6 Sideload + ADB bootstrap

- ADB needs the **5-pin development cable** + the Rokid companion phone app (one-time).
- Alternative: WiFi APK upload via CXR-M SDK (`Miniontoby/RokidApkUploader` does this with just
  the serial number).
- Once ADB is alive, we `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` and the app can
  toggle wireless debugging on subsequent reboots — the Shizuku-style trick the `小猪遥控戒指` v2
  uses (cloned into our design as the AppProcessAgent bootstrap).

### 1.7 Wear / proximity detection

`rokid-docs/yodaos/docs/apps/sys-config.md` describes a `SysConfig` proximity sensor observer
that watches:
- **psensor** (forehead proximity) — whether the user is wearing the glasses
- **leg-spread** (hinge open/closed)
- **Hall sensor** (magnet-based; charging dock?)

There's a `RokidDoorReceiver` broadcast for "glasses open/closed" — we listen for this in
`RokidWearStateProvider` to drive the power state machine and the ring hand-over. Action string
needs on-device verification.

---

## 2. RayNeo X3 Pro (RayNeo AIOS)

Source: official RayNeo developer docs at https://rayneo.gitbook.io/rayneo-devdoc/ (GitBook,
fetchable). Practice references: cloned third-party apps at [`../research/RayDesk/`](../research/RayDesk/)
(Quad-Labs RayDesk — real X3 Pro Moonlight streaming app using the official SDK).

### 2.1 Platform

| Property | Value |
|---|---|
| Model code | **ARGF20** |
| Brand | RayNeo (TCL / FFALCON; the SDK uses the `com.ffalcon.*` namespace) |
| OS | **RayNeo AIOS 2.0** — custom Android 12+ (API ≥ 31 confirmed via RayDesk's `minSdk`) |
| SoC | Qualcomm Snapdragon AR1 Gen 1 |
| RAM / ROM | 4 GB / 32 GB |
| Display | Full-colour MicroLED + diffractive waveguide; **dual-eye 1280×480 binocular** (640×480 per eye, 1.2× magnified vs X2); ~30° diagonal FOV; recommended safe area: 16 px on all sides (we use 24 dp `ScreenPadding`) |
| Cameras | 12 MP main (RGB) + VGA spatial; 4K photos / 1440p video |
| Sensors | Standard `SensorManager` `TYPE_GAME_ROTATION_VECTOR` at 219 Hz; gyro; accel; via the SDK an opaque "wear detection" signal |
| Mic | 3 mics (X3 Pro) |
| GPS | None (cf. X2 has it) |
| Weight | 76 g |
| Firmware OTA | https://ota.rayneo.cn (same site as Air series) |

### 2.2 System UI is also focus-based, but transport is MotionEvent

Source: https://rayneo.gitbook.io/rayneo-devdoc/x-xi-lie/android-kai-fa/neng-li-jie-shao/jiao-dian-guan-li

Official doc verbatim: "前滑和后滑手势用来切换焦点，单击手势用来触发焦点 View 的事件响应，双击手势退出焦点
返回上一级" — "forward/backward swipe = switch focus; single tap = trigger focused view's event;
double tap = exit, return to previous".

**Same focus model as Rokid** — but the temple touchpad delivers events as raw Android
`MotionEvent`s, and the system UI's `TouchDispatcher` (in the ARSDK) recognises gestures from
them and drives focus.

**Implication for us**: to drive the X3 Pro launcher, inject **swipe `MotionEvent`s** (`input
swipe x1 y1 x2 y2 ms` or `InputManager.injectInputEvent(MotionEvent)`). The X3 Pro **might** also
accept DPAD key events (some Android focus frameworks fall through to both); needs on-device
verification — [11](11-verification-checklists.md) §B5.

> **Critical for in-app focus**: `Modifier.focusable()` (Compose) is NOT enough on X3 Pro. The
> temple touchpad's `MotionEvent`s are intercepted by Mercury SDK's `TouchDispatcher` *before*
> Compose sees them. Each focusable Compose element must register a `FocusInfo` via
> `focusHolder.addFocusTarget(...)` (Mercury SDK) so the SDK's focus tracker can drive it. On
> Rokid, `Modifier.focusable()` works natively (DPAD events go through standard Android focus
> traversal). Implication: same Compose code, but `rayneo` flavor needs a focus-bridge layer.
> See [08 §9.2](08-ui-design.md#92-temple-touchpad-of-the-glasses-themselves) for the bridge plan.

> **User-controllable "Natural mode" inverts swipe direction**: under Settings → Touchpad, X3 Pro
> users can toggle "Natural" — when on, forward swipe means "previous focus" (vs default
> "next"). Mercury SDK applies the inversion BEFORE emitting `TempleAction.SlideForward /
> SlideBackward`, so consumers (us) get the post-toggle abstract direction and don't need to
> handle it. Apps that hand-parse raw `MotionEvent` must respect the toggle themselves.

### 2.3 Mercury SDK — RayNeo's official Android development kit

Package: `com.ffalcon.mercury.android.sdk`. Distributed as an AAR; download via
https://open.rayneo.com/'s "ARDK download" section (registration required).

The 9 capability modules documented at https://rayneo.gitbook.io/rayneo-devdoc/x-xi-lie/android-kai-fa/neng-li-jie-shao :

| Module | What it gives |
|---|---|
| 合目处理 (binocular render) | `BaseMirrorActivity` / `BaseMirrorFragment` / `MirrorContainerView` — auto-mirrors content to both eyes; `make3DEffect` for parallax depth |
| 焦点管理 (focus mgmt) | `FocusHolder` / `FocusInfo` / `FixPosFocusTracker` / `RecyclerViewSlidingTracker` / `RecyclerViewFocusTracker` / `IFocusable` + `addFocusView()` |
| 触控板管理 (touchpad) | `BaseTouchActivity` → `BaseEventActivity` → `BaseMirrorActivity`; `TouchDispatcher` → `CommonTouchCallback` → Kotlin Flow `TempleActionViewModel.state`. **TempleAction** subclasses: `Click`, `LongClick`, `DoubleClick`, `TripleClick`, `SlideForward`, `SlideBackward`, `SlideUpwards` (X3-only), `SlideDownwards` (X3-only), `TpSlideContinuous`. X3-only: `onTPDoubleFingerClick()` / `onTPDoubleFingerLongClick()`. `DeviceUtil.isX3Device()` to differentiate from X2. |
| 3D 效果 | parallax helpers |
| 音频 | recording + camera audio mode (via `AudioManager.setParameters`) |
| camera | Android camera2 wrapper |
| IMU 数据获取 | head pose, motion detection |
| 手机连接 & GPS 推流 | phone link / GPS pass-through |
| **佩戴检测** (wear detection) | direct API for "is the user wearing the glasses?" — drives our power-saving (§5 in [06](06-performance-and-power.md)) without needing to guess from screen on/off |

**For our app**:
- X3 Pro flavor builds against the Mercury AAR (drop in `app/libs/`, uncomment the dependency in
  [`build.gradle.kts`](../app-project/app/build.gradle.kts)).
- We base our Compose UI Activity on `BaseMirrorActivity` so binocular mirroring is free.
- Optional fallback if we can't get the AAR: render content twice into a 1280×480 surface with
  parallax — degrades cleanly. See [04](04-architecture.md) §3.

**What Mercury SDK does NOT give us**: input injection into the system UI, launching system
apps, or AI/voice. We still need ADB for those, same as Rokid.

### 2.4 Other RayNeo SDKs (mostly not relevant)

- **IPC SDK for Android** — multi-process camera APIs (the two-camera setup on X3 Pro).
- **Unity ARDK** — Creator Mode + 6DoF + SLAM. Spatial AR apps; not us.
- Qualcomm has a "Get Started with RayNeo X3 Pro AR Development" page on `qualcomm.com` (mostly
  Snapdragon Spaces / AR1 stack).

### 2.5 Sideload + ADB bootstrap

- **Settings → swipe left 10 times** to unlock developer mode.
- Plug a USB-C **data** cable (not charge-only) → `adb devices`.
- Windows: install the WinUSB driver via Zadig if `adb devices` doesn't see the glasses.
- Sideloaded apps appear in "App Lab".
- Same `pm grant WRITE_SECURE_SETTINGS` + wireless-debugging trick applies for the embedded ADB
  agent post-bootstrap.

### 2.6 Bluetooth peripheral support (caveat)

RayNeo X3 Pro markets support for the "RayNeo Ring" Bluetooth HID controller, suggesting it acts
as a BT host. RayDesk's README (as of late 2025) notes that **third-party BT peripheral pairing
is not yet enabled** — users were waiting on RayNeo to ship it. RayDesk also has a `UsbHidTest`
test activity hinting USB-C HID input might work.

**For us**: we keep alternative architecture "phone-as-BT-HID-keyboard" as a future option but
the main implementation is the on-device app per platform.

### 2.7 RayNeo X3 Pro Intent map (TBD)

Unlike Rokid, the X3 Pro launcher / camera / AI Intent strings **are not publicly documented** —
there's no public system-app decompilation analogous to rokid-docs. We discover them on-device
during phase-1 — see [11-verification-checklists.md](11-verification-checklists.md) §B6 for the
recipe. Until then `RayNeoFeatureIntents` is mostly empty placeholders (with `monkey` fallback
for "open arbitrary app").

---

## 3. What's the same, what's different (cheat sheet)

| | Rokid Glasses | RayNeo X3 Pro |
|---|---|---|
| Android | 12 (API 32) | 12+ (API ≥ 31) |
| Display | mono 480px (right eye) | binocular 1280×480 (640/eye) |
| IMU API | standard `SensorManager` | standard + Mercury SDK |
| System UI | focus-based | focus-based |
| Temple touch transport | converted to DPAD keys at system level | raw MotionEvent + ARSDK gesture detection |
| **To inject Nav** | `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT` | swipe `MotionEvent` (or maybe also DPAD — verify) |
| Official SDK | CXR-S (msg channel) — no input help | **Mercury SDK** (binocular render + temple events + focus mgmt + 佩戴检测) — used for UI shell |
| Feature Intent map | Documented in rokid-docs, fully wired | TBD — discover on device |
| Accessibility DPAD inject | Not available (API 33+) | Not available (API 33+) |
| **Conclusion** | ADB is required for nav; accessibility tops up Back/Home | Same |
| ADB unlock | 5-pin dev cable + companion app | Settings → swipe left ×10 |
| Wear detection | proximity + hinge + `RokidDoorReceiver` broadcast | Mercury SDK 佩戴检测 module |
| Public system-app dump | Yes (`rokid-docs`) | No |

The "same-ness" is what lets us share ~85% of the code in `:core`. The "different-ness" is what
the four per-flavor strategies (`DisplayAdapter`, `GlassActionMapper`, `WearStateProvider`,
`FeatureIntents`) abstract over — see [04-architecture.md](04-architecture.md) §4.
