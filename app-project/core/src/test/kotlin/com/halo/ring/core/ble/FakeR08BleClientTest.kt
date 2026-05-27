package com.halo.ring.core.ble

import com.halo.ring.core.gesture.RawGesture
import com.halo.ring.core.power.PowerPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeR08BleClientTest {

    @Test fun `emit delivers events to all subscribers`() {
        val client = FakeR08BleClient { 0L }
        val received1 = mutableListOf<RingEvent>()
        val received2 = mutableListOf<RingEvent>()
        client.events().subscribe { ev, _ -> received1 += ev }
        client.events().subscribe { ev, _ -> received2 += ev }

        client.emit(RingEvent.Battery(percent = 73))
        client.emitRaw(RawGesture.TOUCH)

        assertEquals(2, received1.size)
        assertEquals(2, received2.size)
        assertEquals(RingEvent.Battery(73), received1[0])
        assertEquals(RingEvent.GestureEvent(RawGesture.TOUCH), received1[1])
    }

    @Test fun `unsubscribe stops delivery`() {
        val client = FakeR08BleClient { 0L }
        val received = mutableListOf<RingEvent>()
        val sub = client.events().subscribe { ev, _ -> received += ev }

        client.emit(RingEvent.Battery(50))
        sub.unsubscribe()
        client.emit(RingEvent.Battery(40))

        assertEquals(1, received.size)
    }

    @Test fun `connection state subscribers see current state on subscribe and on change`() {
        val client = FakeR08BleClient { 0L }
        val states = mutableListOf<ConnectionState>()
        client.connectionState().subscribe { states += it }
        // The Replay-on-subscribe behaviour delivers DISCONNECTED immediately.
        assertEquals(listOf(ConnectionState.DISCONNECTED), states)

        client.setConnectionState(ConnectionState.SCANNING)
        client.setConnectionState(ConnectionState.READY)
        assertEquals(
            listOf(ConnectionState.DISCONNECTED, ConnectionState.SCANNING, ConnectionState.READY),
            states,
        )
    }

    @Test fun `start and stop drive the connection state`() {
        val client = FakeR08BleClient { 0L }
        val states = mutableListOf<ConnectionState>()
        client.connectionState().subscribe { states += it }

        client.start()
        client.stop()

        assertEquals(
            listOf(
                ConnectionState.DISCONNECTED,  // initial replay
                ConnectionState.SCANNING,
                ConnectionState.DISCONNECTED,
            ),
            states,
        )
    }

    @Test fun `command-issuing helpers record what the service would send to the ring`() {
        val client = FakeR08BleClient { 0L }
        client.setTouchEnabled(true)
        client.setTouchEnabled(false)
        client.queryBattery()
        client.blinkLed()
        client.shutdownRing()
        client.setIntervalMode(PowerPolicy.IntervalMode.HIGH)
        client.setIntervalMode(PowerPolicy.IntervalMode.BALANCED)
        client.setIntervalMode(PowerPolicy.IntervalMode.SLOW)

        assertEquals(
            listOf(
                FakeR08BleClient.Command.TOUCH_ENABLE,
                FakeR08BleClient.Command.TOUCH_DISABLE,
                FakeR08BleClient.Command.BATTERY_QUERY,
                FakeR08BleClient.Command.BLINK_LED,
                FakeR08BleClient.Command.SHUTDOWN_RING,
                FakeR08BleClient.Command.INTERVAL_HIGH,
                FakeR08BleClient.Command.INTERVAL_BALANCED,
                FakeR08BleClient.Command.INTERVAL_SLOW,
            ),
            client.sentCommands,
        )
    }

    @Test fun `new spec-derived commands all record on the wire`() {
        val client = FakeR08BleClient { 0L }
        client.findRing()
        client.setHrAutoMonitor(enabled = false)
        client.setHrAutoMonitor(enabled = true, intervalMinutes = 15)
        client.setDailyTarget(steps = 5000, kcal = 350, distanceMeters = 4000)
        client.startSportSession(sportType = 1)
        client.stopSportSession(sportType = 1)
        client.startAccelStream(continuous = false)
        client.startAccelStream(continuous = true)
        client.stopAccelStream()

        assertEquals(
            listOf(
                FakeR08BleClient.Command.FIND_RING,
                FakeR08BleClient.Command.HR_AUTO_MONITOR_OFF,
                FakeR08BleClient.Command.HR_AUTO_MONITOR_ON,
                FakeR08BleClient.Command.DAILY_TARGET,
                FakeR08BleClient.Command.SPORT_START,
                FakeR08BleClient.Command.SPORT_STOP,
                FakeR08BleClient.Command.ACCEL_ONE_SHOT,
                FakeR08BleClient.Command.ACCEL_STREAM_START,
                FakeR08BleClient.Command.ACCEL_STREAM_STOP,
            ),
            client.sentCommands,
        )
        assertEquals(Triple(5000, 350, 4000), client.lastDailyTarget)
    }

    @Test fun `setDailyTarget coerces sub-floor step values to the 100-step minimum`() {
        // SPEC §4.9: firmware silently drops steps < 100. Fake mirrors this so tests catch the issue.
        val client = FakeR08BleClient { 0L }
        client.setDailyTarget(steps = 50, kcal = 100, distanceMeters = 500)
        assertEquals(Triple(100, 100, 500), client.lastDailyTarget)
    }

    @Test fun `emit uses provided timestamp`() {
        val client = FakeR08BleClient { 0L }
        val captured = mutableListOf<Long>()
        client.events().subscribe { _, t -> captured += t }

        client.emit(RingEvent.Battery(50), atMs = 1234L)
        client.emitRaw(RawGesture.LONG_PRESS, atMs = 5678L)

        assertEquals(listOf(1234L, 5678L), captured)
        assertTrue(captured.all { it > 0 })
    }

    // ── Idempotence (Doc/13 §audit-2026-05-13j) ──────────────────────────────────────────────────

    @Test fun `consecutive identical setTouchEnabled is deduped on the wire`() {
        val client = FakeR08BleClient { 0L }
        client.setTouchEnabled(true)
        client.setTouchEnabled(true)
        client.setTouchEnabled(true)
        // The reconcilePower loop fires on every gesture; without dedup the BLE link would see
        // a TOUCH_ENABLE write per gesture. With dedup, only the first request goes through.
        assertEquals(listOf(FakeR08BleClient.Command.TOUCH_ENABLE), client.sentCommands)
    }

    @Test fun `consecutive identical setIntervalMode is deduped`() {
        val client = FakeR08BleClient { 0L }
        client.setIntervalMode(PowerPolicy.IntervalMode.HIGH)
        client.setIntervalMode(PowerPolicy.IntervalMode.HIGH)
        client.setIntervalMode(PowerPolicy.IntervalMode.HIGH)
        assertEquals(listOf(FakeR08BleClient.Command.INTERVAL_HIGH), client.sentCommands)
    }

    @Test fun `state change still goes through after a stable run`() {
        val client = FakeR08BleClient { 0L }
        client.setTouchEnabled(true)
        client.setTouchEnabled(true)
        client.setTouchEnabled(false)   // genuine change
        client.setIntervalMode(PowerPolicy.IntervalMode.HIGH)
        client.setIntervalMode(PowerPolicy.IntervalMode.HIGH)
        client.setIntervalMode(PowerPolicy.IntervalMode.BALANCED)   // genuine change
        client.setIntervalMode(PowerPolicy.IntervalMode.SLOW)       // genuine change
        assertEquals(
            listOf(
                FakeR08BleClient.Command.TOUCH_ENABLE,
                FakeR08BleClient.Command.TOUCH_DISABLE,
                FakeR08BleClient.Command.INTERVAL_HIGH,
                FakeR08BleClient.Command.INTERVAL_BALANCED,
                FakeR08BleClient.Command.INTERVAL_SLOW,
            ),
            client.sentCommands,
        )
    }

    @Test fun `stop resets idempotence trackers so reconnect re-arms`() {
        val client = FakeR08BleClient { 0L }
        client.setTouchEnabled(true)
        client.setIntervalMode(PowerPolicy.IntervalMode.HIGH)
        // Simulate disconnect + reconnect — the next setX should re-issue, NOT be skipped.
        client.stop()
        client.start()
        client.setTouchEnabled(true)
        client.setIntervalMode(PowerPolicy.IntervalMode.HIGH)
        assertEquals(
            listOf(
                FakeR08BleClient.Command.TOUCH_ENABLE,    // initial
                FakeR08BleClient.Command.INTERVAL_HIGH,
                FakeR08BleClient.Command.TOUCH_ENABLE,    // re-armed after stop()
                FakeR08BleClient.Command.INTERVAL_HIGH,
            ),
            client.sentCommands,
        )
    }
}
