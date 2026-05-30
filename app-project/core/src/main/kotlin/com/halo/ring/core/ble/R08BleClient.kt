package com.halo.ring.core.ble

import com.halo.ring.core.power.PowerPolicy

/**
 * Abstraction over the Android `BluetoothGatt` central role. The concrete implementation lives in
 * :app (`com.halo.ring.ble.AndroidR08BleClient`); this interface keeps :core JVM-only and lets
 * tests drive the pipeline with a fake client.
 *
 * Implementations MUST:
 * - Filter scans by service UUID + (optional) MAC whitelist.
 * - Use `connectGatt(autoConnect=true)` for steady-state reconnect (NOT app-level continuous scan; §14.3 P2).
 * - On connect: enable notify on [R08Protocol.NOTIFY_CHAR_UUID], wait ~800ms, write
 *   [R08Protocol.TOUCH_ENABLE], wait ~500ms, write [R08Protocol.TOUCH_MODE]. See §3.3.
 * - Request a short connection interval (15–30ms) during active interaction; relax when idle (§14.3 L4).
 * - De-dup notify frames: drop a frame whose bytes are identical to the previous within ~50ms
 *   (and *only* if identical — see §20.3 for why a wider window is dangerous).
 * - Deliver every (non-deduped) frame as a parsed [RingEvent] on a single dedicated looper/thread
 *   so the downstream synthesizer sees no races. Carry the monotonic timestamp.
 * - **Idempotence for power control**: [setTouchEnabled] and [setIntervalMode] are driven by
 *   the [PowerPolicy] reconcile loop, which fires on every BLE event. Implementations MUST
 *   suppress redundant writes when the requested state is unchanged since the last call on the
 *   same connection. State trackers reset on disconnect / [stop] so the next connection re-arms
 *   the writes. (Doc/13 §audit-2026-05-13j.)
 */
interface R08BleClient {

    /** Stream of decoded frames. Each carries a monotonic timestamp from the same clock the
     *  [com.halo.ring.core.gesture.Scheduler] uses. */
    fun events(): RingEventSource

    /** Current connection lifecycle. */
    fun connectionState(): ConnectionStateSource

    /** Start trying to hold a connection (will autoConnect on the bonded address / whitelist). */
    fun start()
    /** Tear down — release the GATT, stop scanning. */
    fun stop()

    /** Set (or clear, with `null`) the MAC address to lock onto for future connections. After
     *  setting, [start] will only connect to this address; scan results not matching it are
     *  surfaced through [discoveredDevices] but not auto-connected. Resets internal state. */
    fun setPairedMac(mac: String?)

    /** Live stream of devices seen during a scan, regardless of MAC. Used by the picker UI so the
     *  user can choose which ring to pair with. Each item is `(mac, advertisedName, rssiDbm)`. */
    fun discoveredDevices(): DiscoveredDevicesSource

    /** Begin a *discovery* scan that emits to [discoveredDevices] but never auto-connects. The
     *  caller picks a device and calls [setPairedMac] + [start] to commit. Caller should
     *  [stopDiscovery] when done. */
    fun startDiscovery()
    fun stopDiscovery()

    /** Send `TOUCH_ENABLE` / `TOUCH_DISABLE` (and `TOUCH_MODE` with [sleepMin] on enable). [sleepMin]
     *  is the touch-IC idle-sleep timeout in minutes; a change while already enabled re-sends only
     *  `TOUCH_MODE`. */
    fun setTouchEnabled(enabled: Boolean, sleepMin: Int = R08Protocol.DEFAULT_TOUCH_SLEEP_MIN)
    /** Query battery now (response arrives async via [events]). */
    fun queryBattery()
    /** Blink the ring LED ("find my ring"). */
    fun blinkLed()
    /** Power off the ring. */
    fun shutdownRing()
    /**
     * Hint to the BLE stack which connection-priority band to request.
     * Maps to `BluetoothGatt.CONNECTION_PRIORITY_HIGH / BALANCED / LOW_POWER`.
     * Driven by [PowerPolicy] — see Doc/06 §3.2.
     */
    fun setIntervalMode(mode: PowerPolicy.IntervalMode)

    /**
     * Trigger an on-demand vitals snapshot. Writes `0x69 [type, 0x01]` to start, waits for the
     * `0x69 [type] [err] [val] …` notify stream to converge (val ≠ 0; see SPEC v3 §4.5), then
     * writes `0x6A [type, 4, 0]` to stop and put the PPG LED out.
     *
     * Default flow on RT08_3.10.46: HEALTHCHECK composite (type=0x05) gives BPM + SBP + DBP in a
     * single PPG pass; if that's unsupported, fall back to sequential HR → SpO2. STRESS is
     * progress-only on this firmware (`val` stays 0) — kept for forward-compat.
     *
     * Implementations should be idempotent: calling twice while a snapshot is in flight is a
     * no-op. Results arrive via the normal [events] stream as [RingEvent.Health] and
     * [RingEvent.WearDetectFail] (the latter on `err=0x02`).
     */
    fun requestVitalsSnapshot()

    /** Send the "Find my ring" magic (`0x50 [0x55, 0xAA]`). Ring LED flashes briefly; no reply. */
    fun findRing()

    /** Configure the ring's autonomous PPG monitoring (`0x16 HR-auto`). When enabled (factory
     *  default), the ring runs PPG every [intervalMinutes] minutes on its own — observable as the
     *  green/red LED blinking. Disable to save ring battery; users only get on-demand readings. */
    fun setHrAutoMonitor(enabled: Boolean, intervalMinutes: Int = 30)

    /** Write the daily step target (`0x21 TargetSetting`). Firmware silently drops steps < 100; the
     *  command builder coerces to the floor and we read back after to confirm persistence. */
    fun setDailyTarget(steps: Int, kcal: Int, distanceMeters: Int)

    /** Start an [R08Protocol.OP_PHONE_SPORT] workout session. The ring will push `0x78` ticks with
     *  duration + HR estimate; the parallel `0x73 sub=0x12` ACTIVITY_TOTAL stream provides
     *  step/kcal/distance. */
    fun startSportSession(sportType: Int = 1)

    /** Stop the active workout session. */
    fun stopSportSession(sportType: Int = 1)

    /**
     * Start the `0xA1` telemetry stream (3-axis accelerometer + opaque PPG channels).
     * - [continuous] = false → one-shot snapshot (single 4-frame burst, then quiet).
     * - [continuous] = true → ~4 cycles/s sustained stream (~64 B/s).
     *
     * Continuous mode costs BLE bandwidth and battery — only enable when needed (spatial mode,
     * live HUD vitals). Caller MUST [stopAccelStream] to stop a continuous stream.
     */
    fun startAccelStream(continuous: Boolean = false)

    /** Stop a continuous `0xA1` stream. No-op for one-shot. */
    fun stopAccelStream()
}

/** Tiny callback-shaped source so :core can stay free of Coroutines/RxJava dependencies. */
fun interface RingEventSource {
    fun subscribe(onEvent: (RingEvent, nowMs: Long) -> Unit): Subscription
}

fun interface ConnectionStateSource {
    fun subscribe(onState: (ConnectionState) -> Unit): Subscription
}

fun interface DiscoveredDevicesSource {
    fun subscribe(onUpdate: (List<DiscoveredDevice>) -> Unit): Subscription
}

/** A BLE device seen during a discovery scan. Sorted by RSSI desc when surfaced to UI. */
data class DiscoveredDevice(
    val macAddress: String,
    val advertisedName: String?,
    val rssiDbm: Int,
    val lastSeenMs: Long,
)

fun interface Subscription { fun unsubscribe() }

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, READY }
