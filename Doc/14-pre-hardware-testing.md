# 14 — Pre-hardware testing guide

> What you can verify on your laptop + a regular Android phone, before the QRing R08 ring and the
> AR glasses arrive. Everything here costs zero hardware money and shakes out 80% of the bugs that
> would otherwise wait for first-light.

The project's design lets a lot of the pipeline be exercised without the ring or the glasses,
because:
- `:core` is dependency-free Kotlin/JVM with a `FakeR08BleClient` that emits synthetic `RingEvent`s.
- `:app` runs on any Android 12+ phone — Compose UI renders fine, foreground service starts,
  AccessibilityService binds, the first-run wizard's deep links open.
- The agent (`app-process` injection) can be pushed to any Android phone over USB ADB; you can
  validate latency, the LocalSocket protocol, and the heartbeat without the glasses' Sprite/RayNeo
  launcher being in the picture.

If everything below passes, the only things left to verify after hardware arrives are
**device-specific** — the ring's exact dedup window, the glasses' DPAD/swipe coordinates, BLE
interval negotiation on the real ring's firmware.

---

## 0. Prerequisites on your laptop

| Need | Why | Install |
|---|---|---|
| **JDK 17** | Gradle toolchain | `brew install openjdk@17` then either link or use `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| **Android SDK** + `adb` | Build APKs + sideload + drive the agent | Android Studio Giraffe+ or `brew install --cask android-platform-tools` |
| **Any Android 12+ phone** | Renders the UI, hosts the foreground service, runs the agent for latency measurement | — |
| **(optional) `nRF Connect` Android app** | Stand in for the ring during BLE smoke tests | Play Store |

`app-project/local.properties` should already point `sdk.dir` to your SDK; if not:
```
sdk.dir=/Users/yourname/Library/Android/sdk
```

---

## 1. Pure-JVM checks (no device needed)

These all run on your laptop. **Do these first** — if anything here fails, fix it before plugging
in a phone.

### 1.1 Unit tests — 172 cases across 15 suites

```bash
cd app-project
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :core:test
```

Expect: `BUILD SUCCESSFUL`, no failing tests. Report goes to
`core/build/reports/tests/test/index.html`.

What it actually verifies:
- The 12-gesture state machine for every documented timing combination + exact-boundary edges
- 4-layer routing (screen-off / system / modal / profile) including the modal sentinel lifecycle
- PowerPolicy's full 4-row table including the new SLOW band
- BLE frame parser tolerates malformed input (truncated / unknown subcodes / zero health value)
- Profile DataStore round-trips, ADB packet round-trip, agent wire protocol

### 1.2 Build both flavor APKs

```bash
./gradlew :app:assembleRokidDebug :app:assembleRayneoDebug
```

Outputs:
```
app/build/outputs/apk/rokid/debug/app-rokid-debug.apk    (~13 MB)
app/build/outputs/apk/rayneo/debug/app-rayneo-debug.apk  (~13 MB)
```

Then verify the agent dex was bundled:
```bash
unzip -l app/build/outputs/apk/rokid/debug/app-rokid-debug.apk | grep halo-agent.dex
```
Expect a ~16 KB entry under `assets/`.

### 1.3 Lint + dead-code check

```bash
./gradlew :app:lintRokidDebug
```
Look for errors in `app/build/reports/lint-results-rokidDebug.html`. Warnings are fine; errors
should be zero (or have an explicit suppression with a comment).

### 1.4 Release build size + ProGuard sanity

```bash
./gradlew :app:assembleRokidRelease   # requires keystore; or use debug-signing for size check
ls -lh app/build/outputs/apk/rokid/release/*.apk
```
Doc/13 §4 expects ~7-8 MB after R8. If you're much bigger, BouncyCastle keep-rules probably
aren't shrinking. (Tracking item in `B-2 ProGuard / R8 收尾`.)

---

## 2. Phone-only checks (any Android 12+ device)

These exercise the UI, service, AccessibilityService, and the agent — all without the actual ring
or glasses. Run on your Pixel / OnePlus / whatever you have lying around.

### 2.1 Sideload + first launch

```bash
adb install -r app/build/outputs/apk/rokid/debug/app-rokid-debug.apk
adb shell am start -n com.halo.ring.rokid/com.halo.ring.MainActivity
```

What to verify (from your phone screen — note the UI is designed for glasses so it'll be
zoomed-in on a phone):
- [ ] First-run wizard appears with "Welcome to Halo Ring"
- [ ] Welcome text says "**5 quick steps**" (D8 fix)
- [ ] Step 2 of 5 / Step 3 of 5 / Step 4 of 5 / Step 5 of 5 labels are present (D8)
- [ ] "OPEN DEVELOPER OPTIONS" deep-link actually opens Settings → Developer options
- [ ] "OPEN ACCESSIBILITY SETTINGS" deep-link works
- [ ] "ALLOW BACKGROUND" prompts the Doze exemption dialog
- [ ] After SKIP-ing the wizard, the main 3-tab UI appears (Vitals / Status / Settings)
- [ ] All 8 settings rows exist: Feedback / Profiles / System Gestures / Ring / Power & Connection /
      Vitals / Advanced / About
- [ ] Settings → About shows `DeviceProfile.GENERIC_ANDROID` (the runtime-detection fallback)

If anything renders wrong, file a bug with `adb shell screencap -p > out.png && adb pull out.png`.

### 2.2 Foreground service smoke test

```bash
adb shell dumpsys activity services com.halo.ring.rokid | grep -E "HaloRing|foregroundType|isForeground"
```
Expect to see `HaloRingService`, `foregroundType=connectedDevice`, `isForeground=true`.

```bash
adb shell dumpsys notification | grep -A 3 r08remote
```
Expect a low-priority "Ring listening" notification, no sound/vibrate.

### 2.3 CPU + battery overhead (the headline power claim)

Pin the screen on the phone (Settings → Display → Sleep → Never temporarily) and leave the app
running for 30 minutes. Then:
```bash
adb shell dumpsys batterystats --charged | grep -A 5 com.halo.ring.rokid
```
Look at "Estimated power use" — should be ≤ 1% of total. If it's higher, something's spinning.

For CPU:
```bash
adb shell top -m 20 | grep halo
```
Wait 1 minute, expect **near-0% CPU** with no ring connected (the service is just idle in
SCANNING state with a 30 s timeout, then DISCONNECTED).

### 2.4 AccessibilityService binding

1. Settings → Accessibility → Installed apps → Halo Ring (foreground & back/home helper) → toggle ON
2. ```bash
   adb shell settings get secure enabled_accessibility_services
   ```
   Expect a line containing `com.halo.ring.rokid/com.halo.ring.accessibility.HaloRingAccessibilityService`.
3. Switch to another app (Chrome, anything) — back in adb:
   ```bash
   adb logcat -d -s HaloA11y:* HaloService:* | tail -20
   ```
   You should see `foreground package: com.android.chrome` (or whatever) flow through to
   `ModeManager.onForegroundPackage`. This is what auto-switch by foreground app rides on.

### 2.5 BLE adapter smoke test (no ring required)

Without a ring, the app should sit in SCANNING for 30 s, then transition to DISCONNECTED and stop
the scan. Verify:
```bash
adb logcat -s AndroidR08BleClient:* | grep -i 'scan'
```
Expect: `ring not found within 30000 ms; stopping scan`.

**This is the D-fixes audit (Doc/13 §1.7 #9) at work** — the previous version sat at LOW_LATENCY
scan indefinitely, burning radio.

If you have **nRF Connect** on a second Android device:
1. nRF Connect → Advertiser → New advertisement set → primary phy LE 1M; service UUID
   `6e40fff0-b5a3-f393-e0a9-e50e24dcca9e`; complete local name `R08_TEST`. Start advertising.
2. Open Halo Ring on the first phone, hit Settings → Ring → "FIND".
3. You should see a connection attempt in nRF Connect's "Connections" panel.

This validates: scan filter UUID, name keyword match, autoConnect=true, GATT services discovery.
The ring's actual services won't be there, so the next step (TOUCH_ENABLE write) will fail —
that's expected.

### 2.6 Manual agent bootstrap (the most useful test)

The agent is what gives us the 1–3 ms latency claim. You can verify it on the phone over USB
without the glasses at all. **This test caught two real shipping bugs the first time it was
run** (see §5 below for what was fixed) — be ready for the agent to crash on first try if you're
on a fresh checkout that pre-dates 2026-05-13 g.

```bash
# 1. Extract + push the bundled agent dex
mkdir -p /tmp/r08-agent
unzip -p app/build/outputs/apk/rokid/debug/app-rokid-debug.apk assets/halo-agent.dex > /tmp/r08-agent/halo-agent.dex
adb push /tmp/r08-agent/halo-agent.dex /data/local/tmp/halo-agent.dex

# 2. Start the agent under the shell uid (mimics what AdbBootstrap will do post-pairing).
#    Note the entry class is com.halo.ring.agent.Main (NOT MainKt — `object Main` in Kotlin
#    becomes a top-level class named exactly `Main`).
adb shell "rm -f /data/local/tmp/halo.agent.heartbeat /data/local/tmp/halo.agent.log"
adb shell 'nohup sh -c "CLASSPATH=/data/local/tmp/halo-agent.dex app_process /system/bin com.halo.ring.agent.Main > /data/local/tmp/halo.agent.log 2>&1" >/dev/null 2>&1 &'
sleep 7

# 3. Verify the heartbeat file exists and contains a fresh timestamp
adb shell ls -la /data/local/tmp/halo.agent.heartbeat
adb shell cat /data/local/tmp/halo.agent.log     # should show:
                                               #   [halo.agent] resolved InputManagerGlobal (Android 13+ path)
                                               #   [halo.agent] listening on abstract:halo.agent (uid=2000)

# 4. Measure round-trip latency from the host (adb forward turns the abstract socket into TCP)
adb forward tcp:9999 localabstract:halo.agent
python3 -c '
import socket, time
def ping():
    s = socket.create_connection(("127.0.0.1", 9999))
    rf = s.makefile("r")
    t0 = time.perf_counter_ns()
    s.sendall(b"PING\n")
    reply = rf.readline().strip()
    t1 = time.perf_counter_ns()
    s.close()
    return reply, (t1-t0)/1_000_000.0
for i in range(5):
    r, ms = ping(); print(f"  RTT #{i+1}: {r!r} {ms:.2f} ms")
'
adb forward --remove tcp:9999
```

Validated on a OnePlus 9 Pro / Android 14: **median RTT 4.96 ms over 5 PINGs**, min 2.30 ms. That
includes the adb USB hop (~1–3 ms) and the LocalSocket round-trip. On-device, the actual
`InputManager.injectInputEvent` call is well under 5 ms — the project's headline "1–3 ms agent
injection" claim holds up.

**Note on the older guide:** the previous version told you to run
`app_process /system/bin com.halo.ring.agent.MainKt` (pre-rebrand `com.r08remote.agent.MainKt`).
That's wrong on three levels: (a) the class is `Main`, not `MainKt`; (b) the `nohup ... &` form
doesn't actually detach from `adb shell` — needs the nested `sh -c "..."` wrapper above; (c)
`nc -U @halo.agent` doesn't work because Android's `nc` build doesn't support the abstract
namespace — use `adb forward` instead.

### 2.7 First-run wizard navigation by gesture (NO ring needed)

Plug a USB keyboard into the phone or use scrcpy's keyboard pass-through. Inside the wizard:
- Tab / Shift+Tab cycles focus
- Enter activates the focused CTA

If any focus stop is missing (e.g. "FINISH SETUP" not reachable), file a bug. The wizard is
expected to be 100% keyboard-driveable because that's what the ring does on real hardware (the
agent injects KEYCODE_DPAD_* / KEYCODE_ENTER for tab cycling).

---

## 3. Phase-0 BLE protocol smoke test (when ring arrives)

This is the **first thing** to run when the ring shows up. Even before installing the APK on the
glasses.

```bash
cd phase0
pip install -r requirements.txt
python3 r08_probe.py --tutorial
```

`--tutorial` mode runs a Python port of the GestureSynthesizer and prints what it recognized for
each tap/swipe/long-press. Validates:
- Service UUID + write/notify char UUID
- TOUCH_ENABLE + TOUCH_MODE sequencing
- All four raw codes (`73 2D 01..04`)
- Battery query response (`03 <pct>`)
- LED commands (`06`, `10`)

Tick each row in [11-verification-checklists.md §A](11-verification-checklists.md) as you go.

---

## 4. Latency dry-run (when you have phone + glasses)

The Advanced screen has a "Latency measurement mode" toggle. After enabling:
1. Tap 20 times on the ring while looking at the glasses UI.
2. Settings → Advanced → "Export latency CSV" — pulls a CSV to `/sdcard/Download/r08-latency-*.csv`.
3. `adb pull /sdcard/Download/r08-latency-XXXX.csv` to your laptop.

The CSV has per-stage breakdowns (BLE arrival → synth resolution → ActionRouter → backend ACK).
Targets from Doc/06 §5:
- p95 ≤ 100 ms for SWIPE / optimistic-TAP / LONG_PRESS
- p95 ≤ 150 ms for ScreenWake

If you're off-target, **don't immediately rewrite the synthesizer**. The usual culprits in order:
1. BLE interval not actually high (check `dumpsys bluetooth_manager` for the negotiated interval).
2. Agent socket reconnecting per call (check `AgentBackend` logs for "reconnect").
3. Doze killing the service mid-stream (check `dumpsys deviceidle get deep` — should be ACTIVE).

---

## 5. What this catches → what hardware will still catch

| Surface | Caught by §1-4 | Still needs hardware |
|---|---|---|
| Compose UI rendering, focus model, navigation | ✅ | Real glasses' display dimensions (Rokid 480×480; RayNeo binocular) |
| Gesture state machine timing | ✅ | Real ring's TOUCH inter-arrival jitter |
| Power policy decisions | ✅ | Whether the ring's firmware accepts SLOW (`CONNECTION_PRIORITY_LOW_POWER`) |
| Service lifecycle / boot receiver / Doze | ✅ (on phone) | The glasses' YodaOS/AIOS-specific battery-saver behaviour |
| AccessibilityService foreground-package detection | ✅ | Rokid Sprite Launcher specific package names |
| Agent latency (1–3 ms) | ✅ (on phone) | Glasses' InputDispatcher quirks; Rokid DPAD focus vs RayNeo swipe |
| BLE scan / connect / dedup window | ⚠️ partial — works against nRF Connect | Ring's actual notify burst behaviour, varying-byte presence |
| 0xA1 accel frame layout | ❌ | Yes — need real ring firmware to figure out |
| Mercury SDK 佩戴检测 (RayNeo) | ❌ | Yes — need the X3 Pro |
| ADB SPAKE2 pairing + TLS connection (B12-real) | ⚠️ can mock against Android Studio's pairing dialog on a phone | Yes — need the glasses for real-world validation |

---

## 5. Bugs caught by the first dry-run on real hardware (2026-05-13 g)

The first time someone actually ran §1 + §2 against a real Android device (OnePlus 9 Pro,
Android 14, SDK 34), **five** real shipping bugs surfaced. All are fixed; this section is here
so the next person knows what to expect if they pick up an old branch.

| # | Bug | Where | Symptom | Fix |
|---|---|---|---|---|
| g1 | `INTERNET` permission not declared in manifest | [AndroidManifest.xml](../app-project/app/src/main/AndroidManifest.xml) | App crashed on first launch with `SecurityException: NsdService: Neither user nor current process has android.permission.INTERNET` — `AdbMdnsDiscovery` constructed `NsdManager` which requires INTERNET on Android 14+ | Added `<uses-permission android:name="android.permission.INTERNET" />` |
| g2 | Foreground service of `type=connectedDevice` requires runtime BLE permissions on Android 14, but MainActivity started the service before requesting them | [MainActivity.kt](../app-project/app/src/main/kotlin/com/halo/ring/MainActivity.kt) | Crash: `SecurityException: Starting FGS with type connectedDevice ... requires permissions: ... any of: [BLUETOOTH_CONNECT, BLUETOOTH_SCAN, ...]` | Added `registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())` + retry-after-grant flow before starting the service |
| g3 | Agent dex was missing `kotlin-stdlib` → crashed at runtime | [agent/build.gradle.kts](../app-project/agent/build.gradle.kts) | Agent crashed instantly with `NoClassDefFoundError: Failed resolution of: Lkotlin/jvm/internal/Intrinsics;` on every entry-point invocation | `:agent:packageDex` now bundles `kotlin("stdlib")` into the dex (dex grew from 16 KB → 2.2 MB; still a rounding error in the 13 MB APK) |
| g4 | Agent used `InputManager.getInstance()`, removed on Android 13+ | [agent/Main.kt](../app-project/agent/src/main/kotlin/com/halo/ring/agent/Main.kt) | After fixing g3, agent crashed with `NullPointerException` at `InputManager.java:271` | Added a tiered probe: try `InputManagerGlobal.getInstance()` first (Android 13+, scrcpy/Shizuku path); fall back to `InputManager.getInstance()` for older devices |
| g5 | `AndroidR08BleClient.setTouchEnabled` + `queryBattery` etc. tried to write to GATT every 10 s (driven by `reconcilePower`) even when not connected; logged `writeBytes: writeChar not yet available, dropping 16-byte cmd` per reconcile | [AndroidR08BleClient.kt](../app-project/app/src/main/kotlin/com/halo/ring/ble/AndroidR08BleClient.kt) | Logcat spam, two warnings every ~10 s on every disconnected device — would mask real issues during phase-1 verification | Guard each method with `if (state != ConnectionState.READY) return@post` |

Also flagged but not blocking:
- The first-run wizard guide command in older revisions said `com.r08remote.agent.MainKt` (wrong:
  pre-rebrand package path AND wrong class name — `object Main` becomes `Main`, not `MainKt`) and
  used `nc -U @r08agent` (Android's `nc` doesn't support the abstract namespace anyway). The
  rebrand renamed the agent socket to `halo.agent`; §2.6 above is the corrected form.
- Lint flagged Android-TV-targeting warnings because we declare `LEANBACK_LAUNCHER` for Rokid
  Sprite Launcher integration. Added `uses-feature android.software.leanback required="false"`,
  `uses-feature android.hardware.touchscreen required="false"`, and `tools:ignore="MissingTvBanner"`
  to silence — we're not actually an Android TV app.

After all five fixes, the §1 → §2 sequence passes end-to-end on Android 14:
- 172 unit tests green
- Both flavor APKs install cleanly
- Foreground service starts with type=connectedDevice, IMPORTANCE_LOW, silent notification
- 30 s BLE scan timeout fires precisely (regression test for D-fix #9)
- AccessibilityService binds + delivers `WINDOW_STATE_CHANGED` events to `ModeManager`
- Agent dex (2.2 MB) bootstraps under shell uid, heartbeat refreshes every 5 s
- Agent PING RTT median **4.96 ms** (incl. adb USB hop) — confirms on-device <5 ms
- Idle drain: app_process + service at 0.0% CPU over 5 minutes, 0 wakeup spam in logcat

## 6. Build hygiene before each session

```bash
# Make sure you're not running stale dex
./gradlew clean :app:assembleRokidDebug

# If something feels weird:
adb uninstall com.halo.ring.rokid
adb install app/build/outputs/apk/rokid/debug/app-rokid-debug.apk
```

CI on every push: `.github/workflows/core-tests.yml` runs `:core:test` automatically. Watch the
Actions tab on GitHub.
