# 06 — Performance & Power

The single most-important non-functional property of this product is "the ring feels like it's
**part of the glasses' own touch bar**." That decomposes into:

1. **Felt latency** ≤ ~80 ms for the common gestures, ≤ 200 ms for everything.
2. **Reliability** — no dropped gestures, no false positives, no random disconnects.
3. **Resident, low-power, long-battery** — the app runs continuously without measurably hurting
   either the ring's 5–7-day endurance or the glasses' day-long battery.

The reference app `小猪遥控戒指` is the lower bar; we beat it on every axis. Numbers below are
**targets** until phase-1 measurement confirms.

---

## 1. End-to-end latency budget

What happens between "finger touches the ring" and "glasses UI visibly reacts":

```
①  Ring firmware: touch IC → RF03 → BLE frame  ────────  ~5–20 ms       (fixed; not our control)
②  Frame waits for the next BLE connection event ──────  0–conn_interval (controllable! see §3)
③  BLE radio → glasses BT controller → HAL → stack ────  ~5–15 ms       (system; mostly fixed)
④  Our app's gesture synthesis window ─────────────────  0 ms / 280 ms  (mode-dependent — see §5 of [05])
⑤  ActionRouter → ExecutorBackend → injection ─────────  1–3 ms (agent) / 50–150 ms (input cmd)
⑥  System InputDispatcher → focused window → frame ────  ~16–50 ms      (1–2 frames + animation)
```

### 1.1 Per-gesture targets

| Gesture | Synthesis cost | Realistic target (agent backend, short conn-interval) |
|---|---|---|
| `SWIPE_UP/DOWN` | 0 | **50–80 ms** |
| `LONG_PRESS` (Fast profile, no LP combos) | 0 | **50–80 ms** |
| `LONG_PRESS` (Nav/Media/Reader, awaitLPcombos=on) | +400 ms | ~450–480 ms (the LP combo cost) |
| `TAP` (optimistic — Media/Reader/Fast) | 0 | **50–80 ms** |
| `TAP` (precise — Navigation) | +280 ms | ~330–360 ms |
| `DOUBLE_TAP` | +300 ms (combo window) | ~350–380 ms |
| `DOUBLE_TAP_SWIPE_*` | 0 (fires on the swipe) | 50–80 ms after swipe |
| `LONG_PRESS_SWIPE_*` | 0 (fires on the swipe) | 50–80 ms after swipe |
| `TRIPLE_TAP / QUADRUPLE_TAP` | +280 ms | ~310–360 ms |
| `DOUBLE_LONG_PRESS` | +400 ms (LP follow-up) | ~450 ms |
| **`ScreenWake` (long-press when off)** | **0 (fast path bypasses synth)** | **~50–80 ms** |

The wake gesture's number is the most user-impactful: that's "press the ring, screen lights up".
The fast-path bypass (see [05](05-interaction-design.md) §5.1) is specifically engineered to
make this <100 ms.

### 1.2 The two latency levers we control

**Lever A: BLE connection interval (step ②)**

Default Android `BluetoothGatt` interval after `connectGatt` is in the 30–50 ms range,
sometimes drifting to 100+ ms when idle. We do better:

- On connect, call `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` → ~15-30 ms.
- After 10 s of no gesture activity, request `_BALANCED` or `_LOW_POWER` → ~75-100 ms or ~100-200 ms.
- On the next gesture, snap back to HIGH.

This is the **biggest** latency win that's actually controllable. The synthesiser exposes a
hook so the BLE client can do this without the synthesiser caring.

**Lever B: Injection path (step ⑤)**

| Path | Latency | Why |
|---|---|---|
| `app_process` agent + `InputManager.injectInputEvent` (reflected) | ~1–3 ms | Persistent process, persistent connection, native syscall to InputDispatcher |
| Shizuku via its API | ~5–10 ms | One more IPC hop |
| `adb shell input keyevent` per call | ~50–150 ms | Spawns a JVM every time. **The reference app's main bottleneck.** |
| File + inotifyd shell handler | similar to `input` | At least it's event-driven instead of polled |
| File + 50 ms polling shell (`小猪遥控戒指`) | +25 ms median (polling) + 100 ms (`input`) | Worst of both |

We default to the agent. Fallback ladder: agent → Shizuku → inotifyd → polling.

### 1.3 What's NOT controllable

Steps ①, ③, ⑥ are mostly system-determined. We can:
- Tune the swipe MotionEvent's duration (X3 Pro) — the shortest that's reliably recognised is
  what we want. Verify on device.
- Not block the BT callback thread — post to our scheduler immediately.

---

## 2. Reliability

### 2.1 De-duplication, done right

The BLE stack may re-deliver an identical notify packet within a few milliseconds. We need to
drop those — but we mustn't drop legitimate fast double-taps.

`com.ring.r08remote`'s 100 ms window is right at the human double-tap floor (~120–300 ms) —
works most of the time, fails in some edge cases.

Our plan: phase-0 measures the inter-tap interval distribution on the actual ring (see
[11-verification-checklists.md](11-verification-checklists.md) §A1). We also check whether the
notify packets contain a varying byte (counter / timestamp). Decision tree:

- If varying byte found → drop only **byte-for-byte identical** packets, any window.
- Else → use the minimum measured inter-tap gap minus 10 ms as the dedup window. Probably
  40–60 ms.

This lives in `AndroidR08BleClient`, before the parsed frame goes to the synthesiser.

### 2.2 Threading discipline

The pipeline runs on **one HandlerThread** (`AndroidScheduler`). BT callbacks (binder thread)
post events onto it. No shared mutable state between threads → no races, no locks.

Agent socket I/O is on its own thread but writes only (commands flow one way; ACKs are
small/non-blocking).

### 2.3 Connection robustness

- `connectGatt(autoConnect = true)` — the BT stack handles reconnect on its own low-duty-cycle
  scan.
- No app-level continuous scanning (that's a power killer; `小猪遥控戒指`'s 2 s scan loop is one of
  its three big power wastes).
- MAC whitelist — once a ring is paired, we filter by MAC. Multi-user environments don't
  cross-connect.
- Auto-sleep handling: on every reconnect, arm wake-swallow.

### 2.4 No gesture misjudgement

Each profile carries its own `GestureConfig`. Defaults:
- `multiTapWindowMs = 280` — slow taps stay separate; fast taps combine.
- `comboWindowMs = 300` — slow "tap-tap-swipe" is two actions; fast is one combo.
- `longPressFollowupWindowMs = 400`.

All tunable per profile if users find them too aggressive or too lax.

The ordering-preservation logic (`flushPendingTapBeforeDefiniteGesture()`) means a real "tap
then swipe" produces TAP then SWIPE, not the other way round.

---

## 3. Power budget

Two devices to worry about: the ring (17 mAh, 5–7 days nominal) and the glasses (much larger
battery but a wearable).

### 3.1 The ring's dominant drain: touch IC duty cycle

The single biggest cost on the ring is the touch IC being "on" listening for touches. Our
strategy: **`TOUCH_DISABLE` whenever it doesn't need to be on.**

| Wear state | Screen | Touch IC | BLE | Why |
|---|---|---|---|---|
| Not worn | (n/a) | **DISABLE** | optionally disconnect after 5 min | The user isn't using it; save everything |
| Worn | OFF | **ENABLE** (keep on!) | conn interval = SLOW (200–500 ms) | User might wake the screen via the ring's wake gesture — must be listening |
| Worn | ON | ENABLE | conn interval = HIGH on gesture, BALANCED idle | Active use |

The "worn + screen-off keeps TOUCH_ENABLE on" rule is **the correction to §20.2 of the original
design** — without it, the wake gesture wouldn't work. The cost is a short-lived "worn but
screen off" period, which is typically brief.

### 3.2 BLE connection interval adaptation

Per [§1.2](#12-the-two-latency-levers-we-control) above, plus the wear state:

```
not worn       → DISCONNECT after 5 min
worn + off     → SLOW (200–500 ms)             ← saves power between wake gestures
worn + on,    
  recent activity (<10 s) → HIGH (15–30 ms)    ← low latency for the gesture stream
  idle (>10 s)            → BALANCED (75-100 ms)
```

Ring firmware may reject our preferred parameters; if so, we accept what it offers.

### 3.3 Glasses-side power: avoiding the reference app's three wastes

| Waste in `com.ring.r08remote` | Our fix |
|---|---|
| Persistent `PARTIAL_WAKE_LOCK` (CPU can't deep-sleep) | **No wakelock**. BLE notifies wake the CPU via the BT controller's interrupt; that's enough. Our foreground service holds the *process*, not the CPU. |
| App-level 2 s BLE scan loop (always scanning) | **`connectGatt(autoConnect = true)`** — the BT stack does low-duty-cycle background scanning when needed. App-level scan happens only on the explicit "find ring" / `ForceReconnect` flow. |
| 50 ms polling shell script | **Event-driven** — agent's LocalSocket OR inotifyd. Zero CPU when no gestures. |

Plus our foreground notification is low-priority + silent (not the user's primary content).

### 3.4 Health-data measurement: never continuous

Real-time HR/SpO2/stress would mean the PPG LED is on continuously — kills the ring battery.
We **only** take snapshots: the user opens the vitals page → one measurement → display → stop.

The ring's firmware-driven background activity tracking (steps, etc.) is much cheaper and
always-on; we just read what's there.

### 3.5 Power state machine (in code)

The five state machines from [05](05-interaction-design.md) §8 jointly drive the power state.
Pseudocode:

```kotlin
fun reconcile() {
    val worn = wearStateProvider.isWorn()
    val screen = if (powerManager.isInteractive) ON else OFF
    val recentlyActive = (now - lastGestureMs) < 10_000

    if (!worn && (now - lastWornMs) > 5 * 60_000) {
        bleClient.disconnect()   // long not-worn → release
    } else {
        bleClient.setTouchEnabled(worn)               // off only if not worn
        bleClient.setActiveMode(active = worn && screen == ON && recentlyActive)
    }
}
```

Called on every: wear-state change, screen-state change, after-gesture (with a 10 s debounce).

---

## 4. Observability — the debug HUD

When user (or developer) wants to see the real numbers:

**Settings → Advanced → Debug HUD** toggles an overlay that displays:

- **Connection state** (DISCONNECTED / READY / etc.)
- **RSSI** in dBm (live, refreshes every 5 s)
- **Actual negotiated BLE connection interval** (in ms)
- **Round-trip time** for the last gesture (from `onCharacteristicChanged` to backend ACK)
- **Drop count** (de-duped frames, missed reconnects)
- **Active executor backend** (which one is currently being used)
- **Tap-window / combo-window** (the current effective values)

Plus a separate **Latency measurement mode** that, for each next 20 gestures, logs the
detailed per-stage breakdown (steps ② through ⑤) to a CSV — for tuning.

Default: both off. Performance debug is opt-in.

---

## 5. Performance acceptance criteria

These must be true before we ship:

- [ ] **End-to-end SWIPE / optimistic-TAP / LONG_PRESS gesture → glasses UI reaction ≤ 100 ms
      at the 95th percentile** (measured via the Latency measurement mode over 100 gestures at
      60 cm ring↔glasses distance).
- [ ] **End-to-end ScreenWake (LONG_PRESS while off) ≤ 150 ms** (specifically — this is the
      headline UX moment for putting on the glasses and starting to use them).
- [ ] **No dropped gestures during a 10-of-each test on each gesture** (12 gestures × 10 each =
      120 events, all recognised, none false-positive).
- [ ] **Steady-state idle (worn, screen on, no interaction) draw on the glasses ≤ 5 mA** beyond
      baseline. (Measure via the glasses' battery stats overnight.)
- [ ] **Ring autonomy** ≥ 80% of advertised when our app is the central (event mode, no raw
      IMU). Measure via daily battery readings over a week.
- [ ] **Reconnect after take-off-glasses-A → put-on-glasses-B** ≤ 3 s.
- [ ] **Resilience to spurious BLE disconnects**: 1 hour at 60 cm with the ring on hand, < 1
      drop per hour.

Failures here drive the de-dup window adjustment, connection-interval tuning, executor-backend
fallback testing, etc.
