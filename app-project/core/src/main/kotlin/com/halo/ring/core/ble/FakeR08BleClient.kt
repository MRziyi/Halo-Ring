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
        FIND_RING, HR_AUTO_MONITOR_ON, HR_AUTO_MONITOR_OFF,
        DAILY_TARGET, SPORT_START, SPORT_STOP,
        ACCEL_ONE_SHOT, ACCEL_STREAM_START, ACCEL_STREAM_STOP,
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

    override fun setTouchEnabled(enabled: Boolean, sleepMin: Int) {
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

    override fun findRing() {
        sentCommands.add(Command.FIND_RING)
    }

    override fun setHrAutoMonitor(enabled: Boolean, intervalMinutes: Int) {
        sentCommands.add(if (enabled) Command.HR_AUTO_MONITOR_ON else Command.HR_AUTO_MONITOR_OFF)
    }

    /** Records the most recent target so tests can assert the values, not just that a write fired. */
    @Volatile var lastDailyTarget: Triple<Int, Int, Int>? = null
        private set

    override fun setDailyTarget(steps: Int, kcal: Int, distanceMeters: Int) {
        lastDailyTarget = Triple(steps.coerceAtLeast(100), kcal, distanceMeters)
        sentCommands.add(Command.DAILY_TARGET)
    }

    override fun startSportSession(sportType: Int) { sentCommands.add(Command.SPORT_START) }
    override fun stopSportSession(sportType: Int)  { sentCommands.add(Command.SPORT_STOP) }

    override fun startAccelStream(continuous: Boolean) {
        sentCommands.add(if (continuous) Command.ACCEL_STREAM_START else Command.ACCEL_ONE_SHOT)
    }
    override fun stopAccelStream() {
        sentCommands.add(Command.ACCEL_STREAM_STOP)
    }

    // ── Pairing / discovery (burn-in fix 2026-05-27) ─────────────────────────────────────────

    @Volatile var pairedMac: String? = null
        private set
    private val discoveredSubs = CopyOnWriteArrayList<(List<DiscoveredDevice>) -> Unit>()
    private val discoveredCache = LinkedHashMap<String, DiscoveredDevice>()
    @Volatile var inDiscoveryMode: Boolean = false
        private set

    override fun discoveredDevices(): DiscoveredDevicesSource = DiscoveredDevicesSource { onUpdate ->
        discoveredSubs.add(onUpdate)
        onUpdate(discoveredCache.values.toList())
        Subscription { discoveredSubs.remove(onUpdate) }
    }

    override fun setPairedMac(mac: String?) {
        pairedMac = mac?.uppercase()
    }

    override fun startDiscovery() {
        inDiscoveryMode = true
        setConnectionState(ConnectionState.SCANNING)
    }

    override fun stopDiscovery() {
        inDiscoveryMode = false
        setConnectionState(ConnectionState.DISCONNECTED)
    }

    /** Test driver: inject a discovered device into the picker stream. */
    fun emitDiscovered(d: DiscoveredDevice) {
        discoveredCache[d.macAddress.uppercase()] = d
        val snap = discoveredCache.values.toList()
        discoveredSubs.forEach { it(snap) }
    }
}
