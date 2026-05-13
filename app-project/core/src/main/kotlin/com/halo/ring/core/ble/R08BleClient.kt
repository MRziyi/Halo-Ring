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

    /** Send `TOUCH_ENABLE` / `TOUCH_DISABLE` (and re-send `TOUCH_MODE` on enable). */
    fun setTouchEnabled(enabled: Boolean)
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
     * Trigger an on-demand vitals snapshot (HR / SpO2 / stress). The BLE client writes the
     * appropriate `0x69 <kind> 01` start commands, listens on the notify channel for
     * `0x69 <kind> ?? <value>` responses, then writes `0x6A` to stop the PPG LED. Real-time
     * measurement is intentionally **not continuous** (Doc/06 §3.4 — PPG LED kills the ring's
     * battery). The default sequence is HR → SpO2 → stress with ~3 s allowed for each reading.
     *
     * Implementations should be idempotent: calling twice while a snapshot is in flight is a
     * no-op. Results arrive via the normal [events] stream as [RingEvent.Health].
     */
    fun requestVitalsSnapshot()
}

/** Tiny callback-shaped source so :core can stay free of Coroutines/RxJava dependencies. */
fun interface RingEventSource {
    fun subscribe(onEvent: (RingEvent, nowMs: Long) -> Unit): Subscription
}

fun interface ConnectionStateSource {
    fun subscribe(onState: (ConnectionState) -> Unit): Subscription
}

fun interface Subscription { fun unsubscribe() }

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, READY }
