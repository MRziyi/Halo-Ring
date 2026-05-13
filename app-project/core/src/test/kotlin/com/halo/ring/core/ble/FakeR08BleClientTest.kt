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

    @Test fun `emit uses provided timestamp`() {
        val client = FakeR08BleClient { 0L }
        val captured = mutableListOf<Long>()
        client.events().subscribe { _, t -> captured += t }

        client.emit(RingEvent.Battery(50), atMs = 1234L)
        client.emitRaw(RawGesture.LONG_PRESS, atMs = 5678L)

        assertEquals(listOf(1234L, 5678L), captured)
        assertTrue(captured.all { it > 0 })
    }
}
