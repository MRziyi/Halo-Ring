package com.halo.ring.core.ble

import com.halo.ring.core.gesture.RawGesture
import com.halo.ring.core.power.PowerPolicy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pure-JVM stand-in for [R08BleClient]. Lets the gesture pipeline be exercised without real hardware
 * — useful for both unit tests and as a development driver for the foreground service before the
 * actual ring arrives (or while testing on a phone).
 *
 * Drive it by calling [emit] / [emitRaw] / [setConnectionState] from test code or a debug UI.
 */
class FakeR08BleClient(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : R08BleClient {

    private val eventSubs = CopyOnWriteArrayList<(RingEvent, Long) -> Unit>()
    private val stateSubs = CopyOnWriteArrayList<(ConnectionState) -> Unit>()

    @Volatile var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    /** Track of every command the service sends, so tests can assert behaviour. */
    val sentCommands: MutableList<Command> = mutableListOf()

    /** Idempotence trackers — mirror the real BLE client (Doc/13 §audit-2026-05-13j). */
    private var lastTouchEnabledRequested: Boolean? = null
    private var lastIntervalModeRequested: PowerPolicy.IntervalMode? = null

    enum class Command {
        TOUCH_ENABLE, TOUCH_DISABLE, BATTERY_QUERY, BLINK_LED, SHUTDOWN_RING,
        INTERVAL_HIGH, INTERVAL_BALANCED, INTERVAL_SLOW, VITALS_SNAPSHOT,
    }

    override fun events(): RingEventSource = RingEventSource { onEvent ->
        eventSubs.add(onEvent)
        Subscription { eventSubs.remove(onEvent) }
    }

    override fun connectionState(): ConnectionStateSource = ConnectionStateSource { onState ->
        stateSubs.add(onState)
        // Replay current state on subscribe so consumers don't need a separate query.
        onState(connectionState)
        Subscription { stateSubs.remove(onState) }
    }

    // ── test driver API ──────────────────────────────────────────────────────────────────────────

    fun emit(ev: RingEvent, atMs: Long = nowMs()) {
        eventSubs.forEach { it(ev, atMs) }
    }

    fun emitRaw(raw: RawGesture, atMs: Long = nowMs()) {
        emit(RingEvent.GestureEvent(raw), atMs)
    }

    fun setConnectionState(s: ConnectionState) {
        connectionState = s
        stateSubs.forEach { it(s) }
    }

    // ── R08BleClient interface — record-only for the fake ────────────────────────────────────────

    override fun start() { setConnectionState(ConnectionState.SCANNING) }
    override fun stop()  {
        // Reset idempotence trackers so a subsequent reconnect re-arms the writes — same
        // contract as the production [AndroidR08BleClient].
        lastTouchEnabledRequested = null
        lastIntervalModeRequested = null
        setConnectionState(ConnectionState.DISCONNECTED)
    }

    override fun setTouchEnabled(enabled: Boolean) {
        if (lastTouchEnabledRequested == enabled) return
        lastTouchEnabledRequested = enabled
        sentCommands.add(if (enabled) Command.TOUCH_ENABLE else Command.TOUCH_DISABLE)
    }
    override fun queryBattery()    { sentCommands.add(Command.BATTERY_QUERY) }
    override fun blinkLed()        { sentCommands.add(Command.BLINK_LED) }
    override fun shutdownRing()    { sentCommands.add(Command.SHUTDOWN_RING) }
    override fun setIntervalMode(mode: PowerPolicy.IntervalMode) {
        if (lastIntervalModeRequested == mode) return
        lastIntervalModeRequested = mode
        sentCommands.add(when (mode) {
            PowerPolicy.IntervalMode.HIGH     -> Command.INTERVAL_HIGH
            PowerPolicy.IntervalMode.BALANCED -> Command.INTERVAL_BALANCED
            PowerPolicy.IntervalMode.SLOW     -> Command.INTERVAL_SLOW
        })
    }

    override fun requestVitalsSnapshot() {
        sentCommands.add(Command.VITALS_SNAPSHOT)
    }
}
