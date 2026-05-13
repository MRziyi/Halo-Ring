package com.halo.ring.core.ble

import com.halo.ring.core.gesture.RawGesture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class R08FrameTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun `gesture frames decode to RawGesture`() {
        assertEquals(RingEvent.GestureEvent(RawGesture.SWIPE_UP),   R08Frame.parse(bytes(0x73, 0x2D, 0x01)))
        assertEquals(RingEvent.GestureEvent(RawGesture.SWIPE_DOWN), R08Frame.parse(bytes(0x73, 0x2D, 0x02)))
        assertEquals(RingEvent.GestureEvent(RawGesture.TOUCH),      R08Frame.parse(bytes(0x73, 0x2D, 0x03)))
        assertEquals(RingEvent.GestureEvent(RawGesture.LONG_PRESS), R08Frame.parse(bytes(0x73, 0x2D, 0x04)))
    }

    @Test fun `touch-status frame decodes`() {
        assertEquals(RingEvent.TouchStatus(enabled = true),  R08Frame.parse(bytes(0x73, 0x2A, 0x00)))
        assertEquals(RingEvent.TouchStatus(enabled = false), R08Frame.parse(bytes(0x73, 0x2A, 0x01)))
    }

    @Test fun `battery frame decodes`() {
        assertEquals(RingEvent.Battery(78), R08Frame.parse(bytes(0x03, 0x4E)))
    }

    @Test fun `health frames decode for HR SpO2 and stress`() {
        assertEquals(RingEvent.Health(HealthKind.HEART_RATE, 72), R08Frame.parse(bytes(0x69, 0x01, 0x00, 0x48)))
        assertEquals(RingEvent.Health(HealthKind.SPO2,       97), R08Frame.parse(bytes(0x69, 0x03, 0x00, 0x61)))
        assertEquals(RingEvent.Health(HealthKind.STRESS,     34), R08Frame.parse(bytes(0x69, 0x08, 0x00, 0x22)))
    }

    @Test fun `activity frame decodes steps cals dist`() {
        // steps=12345 (0x003039), cals=1.500 (0x0005DC), dist=200.000 → 200000 (0x030D40)
        val frame = bytes(0x73, 0x12, 0x00, 0x30, 0x39, 0x00, 0x05, 0xDC, 0x03, 0x0D, 0x40)
        val e = R08Frame.parse(frame) as RingEvent.Activity
        assertEquals(12345, e.steps)
        assertEquals(1.5f, e.calories)
        assertEquals(200f, e.distanceMeters)
    }

    @Test fun `unknown bytes survive as Unknown for debug HUD`() {
        val e = R08Frame.parse(bytes(0x99, 0x88, 0x77))
        assertEquals(true, e is RingEvent.Unknown)
    }

    @Test fun `accel frame is preserved verbatim pending decode`() {
        val raw = bytes(0xA1, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F)
        val e = R08Frame.parse(raw) as RingEvent.AccelRaw
        assertEquals(raw.toList(), e.payload.toList())
    }

    // ── defensive parsing (D10): every malformed frame must produce a RingEvent.Unknown ──────────

    @Test fun `empty notify is Unknown`() {
        assertEquals(true, R08Frame.parse(ByteArray(0)) is RingEvent.Unknown)
    }

    @Test fun `single byte 0x73 (no subcode) is Unknown`() {
        assertEquals(true, R08Frame.parse(bytes(0x73)) is RingEvent.Unknown)
    }

    @Test fun `gesture frame with truncated payload is Unknown`() {
        // `73 2D` is missing the gesture code → parser must reject, not crash, not guess.
        assertEquals(true, R08Frame.parse(bytes(0x73, 0x2D)) is RingEvent.Unknown)
    }

    @Test fun `gesture frame with unrecognised code is Unknown`() {
        // Reserved / future codes — must round-trip as Unknown so the debug HUD shows them.
        assertEquals(true, R08Frame.parse(bytes(0x73, 0x2D, 0x99)) is RingEvent.Unknown)
    }

    @Test fun `touch-status frame missing the state byte is Unknown`() {
        assertEquals(true, R08Frame.parse(bytes(0x73, 0x2A)) is RingEvent.Unknown)
    }

    @Test fun `73-prefix with unknown sub-code is Unknown`() {
        // `73 99 ...` — known ring-namespace but unknown sub-routing byte.
        assertEquals(true, R08Frame.parse(bytes(0x73, 0x99, 0x01)) is RingEvent.Unknown)
    }

    @Test fun `battery frame with only the prefix byte is Unknown`() {
        assertEquals(true, R08Frame.parse(bytes(0x03)) is RingEvent.Unknown)
    }

    @Test fun `health frame with unknown kind is Unknown`() {
        assertEquals(true, R08Frame.parse(bytes(0x69, 0x02, 0x00, 0x48)) is RingEvent.Unknown)
    }

    @Test fun `health frame with zero value is Unknown`() {
        // value == 0 means "no reading available yet"; don't surface it as a real Health event.
        assertEquals(true, R08Frame.parse(bytes(0x69, 0x01, 0x00, 0x00)) is RingEvent.Unknown)
    }

    @Test fun `truncated activity frame is Unknown`() {
        // Real activity frame is 11 bytes — 10 should refuse to parse, not partially decode.
        assertEquals(true, R08Frame.parse(bytes(0x73, 0x12, 0x00, 0x30, 0x39, 0x00, 0x05, 0xDC, 0x03, 0x0D)) is RingEvent.Unknown)
    }

    @Test fun `truncated steps frame is Unknown`() {
        assertEquals(true, R08Frame.parse(bytes(0x51, 0xAB)) is RingEvent.Unknown)
    }

    @Test fun `command builder produces correct checksum`() {
        // TOUCH_ENABLE per spec: 3B 01 00 01 01 00*10 3E (sum = 3B+01+00+01+01 = 3E)
        assertEquals(0x3E.toByte(), R08Protocol.TOUCH_ENABLE[15])
        // TOUCH_DISABLE: ...00 → 3D
        assertEquals(0x3D.toByte(), R08Protocol.TOUCH_DISABLE[15])
        // TOUCH_MODE: 3B 02 00 09 01 → 47
        assertEquals(0x47.toByte(), R08Protocol.TOUCH_MODE[15])
        // BATTERY_QUERY: 03 + 14×00 → 03
        assertEquals(0x03.toByte(), R08Protocol.BATTERY_QUERY[15])
        // All commands are 16 bytes
        listOf(R08Protocol.TOUCH_ENABLE, R08Protocol.TOUCH_MODE, R08Protocol.TOUCH_DISABLE,
               R08Protocol.BATTERY_QUERY, R08Protocol.FIND_DEVICE, R08Protocol.BLINK_TWICE,
               R08Protocol.SHUTDOWN).forEach { assertEquals(16, it.size) }
    }
}
