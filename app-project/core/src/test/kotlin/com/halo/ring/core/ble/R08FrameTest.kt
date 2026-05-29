package com.halo.ring.core.ble

import com.halo.ring.core.gesture.RawGesture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class R08FrameTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    // ── 0x73 sub-id dispatch (the ring's event bus) ──────────────────────────────────────────

    @Test fun `gesture frames decode to RawGesture (sub=0x2D)`() {
        assertEquals(RingEvent.GestureEvent(RawGesture.SWIPE_UP),   R08Frame.parse(bytes(0x73, 0x2D, 0x01)))
        assertEquals(RingEvent.GestureEvent(RawGesture.SWIPE_DOWN), R08Frame.parse(bytes(0x73, 0x2D, 0x02)))
        assertEquals(RingEvent.GestureEvent(RawGesture.TOUCH),      R08Frame.parse(bytes(0x73, 0x2D, 0x03)))
        assertEquals(RingEvent.GestureEvent(RawGesture.LONG_PRESS), R08Frame.parse(bytes(0x73, 0x2D, 0x04)))
    }

    @Test fun `touch-status frame decodes inverted polarity (sub=0x2A)`() {
        // byte 1 = 0 → IC ACTIVE (enabled = true)
        assertEquals(RingEvent.TouchStatus(enabled = true),  R08Frame.parse(bytes(0x73, 0x2A, 0x00)))
        // byte 1 = 1 → IC DISABLED (charging dock / ring off-finger)
        assertEquals(RingEvent.TouchStatus(enabled = false), R08Frame.parse(bytes(0x73, 0x2A, 0x01)))
    }

    @Test fun `battery push (sub=0x0C) carries charging state`() {
        // Charging: 99%, charging=1
        assertEquals(RingEvent.Battery(99, charging = true),  R08Frame.parse(bytes(0x73, 0x0C, 0x63, 0x01)))
        // Just unplugged at 100%
        assertEquals(RingEvent.Battery(100, charging = false), R08Frame.parse(bytes(0x73, 0x0C, 0x64, 0x00)))
    }

    @Test fun `activity push decodes steps + kcal + meters (sub=0x12)`() {
        // SPEC v3 §5.3 wire example: 73 12 00 00 19 00 03 84 00 00 14 00 00 00 00 39
        // → 25 steps, 900 mcal (= 0.9 kcal), 20 meters
        val frame = bytes(0x73, 0x12, 0x00, 0x00, 0x19, 0x00, 0x03, 0x84, 0x00, 0x00, 0x14)
        val e = R08Frame.parse(frame) as RingEvent.Activity
        assertEquals(25, e.steps)
        assertEquals(0.9f, e.calories)
        assertEquals(20, e.distanceMeters)   // INTEGER METERS, not km
    }

    @Test fun `target-reached fires as a singleton event (sub=0x10)`() {
        assertEquals(RingEvent.TargetReached, R08Frame.parse(bytes(0x73, 0x10)))
    }

    @Test fun `ring game key (wrist shake) fires as a singleton (sub=0x29)`() {
        assertEquals(RingEvent.RingGameKey, R08Frame.parse(bytes(0x73, 0x29, 0x00)))
    }

    // ── 0x03 polled battery (still supported even though we now prefer push) ─────────────────

    @Test fun `legacy single-byte battery decodes`() {
        // Just `03 4E` (no charging byte) → percent only, charging = null
        assertEquals(RingEvent.Battery(78, charging = null), R08Frame.parse(bytes(0x03, 0x4E)))
    }

    @Test fun `polled battery with charging byte decodes both`() {
        // `03 4E 01` → 78%, charging
        assertEquals(RingEvent.Battery(78, charging = true), R08Frame.parse(bytes(0x03, 0x4E, 0x01)))
    }

    // ── 0x69 health frames ───────────────────────────────────────────────────────────────────

    @Test fun `health progress (val=0) is null and surfaces as Unknown`() {
        // Pre-convergence progress frame — `err=0`, `val=0`. Per SPEC §4.5 we wait for val ≠ 0.
        val e = R08Frame.parse(bytes(0x69, 0x01, 0x00, 0x00))
        assertTrue(e is RingEvent.Unknown)
    }

    @Test fun `health converged (val nonzero) decodes for HR SpO2 and stress`() {
        assertEquals(RingEvent.Health(HealthKind.HEART_RATE, 72), R08Frame.parse(bytes(0x69, 0x01, 0x00, 0x48)))
        assertEquals(RingEvent.Health(HealthKind.SPO2,       97), R08Frame.parse(bytes(0x69, 0x03, 0x00, 0x61)))
        assertEquals(RingEvent.Health(HealthKind.STRESS,     34), R08Frame.parse(bytes(0x69, 0x08, 0x00, 0x22)))
    }

    @Test fun `healthcheck composite carries HR + SBP + DBP`() {
        // type=0x05, err=0, bpm=72 (0x48), sbp=120 (0x78), dbp=80 (0x50)
        val e = R08Frame.parse(bytes(0x69, 0x05, 0x00, 0x48, 0x78, 0x50)) as RingEvent.Health
        assertEquals(HealthKind.HEALTHCHECK, e.kind)
        assertEquals(72, e.value)
        assertEquals(120, e.systolic)
        assertEquals(80, e.diastolic)
    }

    @Test fun `wear-detect failure (err=0x02) surfaces as WearDetectFail`() {
        val e = R08Frame.parse(bytes(0x69, 0x01, 0x02, 0x00))
        assertEquals(RingEvent.WearDetectFail(HealthKind.HEART_RATE), e)
    }

    // ── 0xA1 telemetry channels ──────────────────────────────────────────────────────────────

    @Test fun `accel ch3 decodes BE int16 triplet into g`() {
        // SPEC v3 §6.3: A1 03 [X_hi X_lo Y_hi Y_lo Z_hi Z_lo]; 8192 LSB/g
        // Use 1.0g on Z axis: 8192 = 0x2000, so Z_hi=0x20 Z_lo=0x00
        val e = R08Frame.parse(bytes(0xA1, 0x03, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00)) as RingEvent.AccelSample
        assertEquals(0f, e.xG)
        assertEquals(0f, e.yG)
        assertEquals(1f, e.zG)
        assertEquals(1f, e.magnitude)
    }

    @Test fun `accel ch3 decodes negative axes via two's complement`() {
        // X = -1g: 0x10000 - 0x2000 = 0xE000 → X_hi=0xE0 X_lo=0x00
        val e = R08Frame.parse(bytes(0xA1, 0x03, 0xE0, 0x00, 0x00, 0x00, 0x00, 0x00)) as RingEvent.AccelSample
        assertEquals(-1f, e.xG)
    }

    @Test fun `accel opaque channels survive as AccelOpaque for debug HUD`() {
        val ch1 = R08Frame.parse(bytes(0xA1, 0x01, 0x00, 0xD2)) as RingEvent.AccelOpaque
        assertEquals(R08Protocol.TELEMETRY_CH_PPG1, ch1.channel)
    }

    // ── 0x78 PhoneSport tick ─────────────────────────────────────────────────────────────────

    @Test fun `sport tick decodes duration BE u16 and hr estimate`() {
        // sport_type=1, status=1, duration=0x003D=61, hr=0x6F=111
        val e = R08Frame.parse(bytes(0x78, 0x01, 0x01, 0x00, 0x3D, 0x6F)) as RingEvent.SportTick
        assertEquals(1, e.sportType)
        assertEquals(61, e.durationSeconds)
        assertEquals(111, e.hrEstimate)
    }

    // ── 0xEE unsupported sentinel ────────────────────────────────────────────────────────────

    @Test fun `unsupported opcode sentinel surfaces with the original opcode stripped`() {
        // `1B | 0x80 = 0x9B`, then `0xEE` body → opcode 0x1B (brightness, unsupported on RT08)
        val e = R08Frame.parse(bytes(0x9B, 0xEE, 0x00, 0x00)) as RingEvent.UnsupportedOp
        assertEquals(0x1B, e.opcode)
    }

    // ── Defensive parsing (every malformed frame → Unknown, never crash) ─────────────────────

    @Test fun `empty notify is Unknown`() {
        assertTrue(R08Frame.parse(ByteArray(0)) is RingEvent.Unknown)
    }

    @Test fun `single byte 0x73 (no sub) is Unknown`() {
        assertTrue(R08Frame.parse(bytes(0x73)) is RingEvent.Unknown)
    }

    @Test fun `gesture frame with truncated payload is Unknown`() {
        assertTrue(R08Frame.parse(bytes(0x73, 0x2D)) is RingEvent.Unknown)
    }

    @Test fun `gesture frame with unrecognised code is Unknown`() {
        assertTrue(R08Frame.parse(bytes(0x73, 0x2D, 0x99)) is RingEvent.Unknown)
    }

    @Test fun `0x73 with unknown sub-id is Unknown`() {
        assertTrue(R08Frame.parse(bytes(0x73, 0x99, 0x01)) is RingEvent.Unknown)
    }

    @Test fun `truncated activity frame is Unknown`() {
        assertTrue(R08Frame.parse(bytes(0x73, 0x12, 0x00, 0x30, 0x39, 0x00, 0x05, 0xDC, 0x03, 0x0D)) is RingEvent.Unknown)
    }

    @Test fun `health frame with unknown kind is Unknown`() {
        assertTrue(R08Frame.parse(bytes(0x69, 0x77, 0x00, 0x48)) is RingEvent.Unknown)
    }

    @Test fun `truncated accel ch3 frame is Unknown`() {
        assertTrue(R08Frame.parse(bytes(0xA1, 0x03, 0x00, 0x00)) is RingEvent.Unknown)
    }

    // ── Command builder + checksum (sum-mod-256, NOT CRC) ────────────────────────────────────

    @Test fun `command builder produces correct sum-mod-256 checksum`() {
        // TOUCH_ENABLE: 3B 01 00 01 01 → sum = 3E
        assertEquals(0x3E.toByte(), R08Protocol.TOUCH_ENABLE[15])
        // TOUCH_DISABLE: 3B 01 00 01 00 → sum = 3D
        assertEquals(0x3D.toByte(), R08Protocol.TOUCH_DISABLE[15])
        // TOUCH_MODE: 3B 02 00 09 0A (sleepMin bumped 1→10) → sum = 3B+02+09+0A = 0x50
        assertEquals(0x50.toByte(), R08Protocol.TOUCH_MODE[15])
        // BATTERY_QUERY: 03 + 14×00 → sum = 03
        assertEquals(0x03.toByte(), R08Protocol.BATTERY_QUERY[15])
        // FIND_DEVICE (FindRing): 50 55 AA → sum = 0x50+0x55+0xAA = 0x14F → 0x4F
        assertEquals(0x4F.toByte(), R08Protocol.FIND_DEVICE[15])
        // SHUTDOWN: 08 01 → sum = 09
        assertEquals(0x09.toByte(), R08Protocol.SHUTDOWN[15])
    }

    @Test fun `all command frames are 16 bytes`() {
        listOf(R08Protocol.TOUCH_ENABLE, R08Protocol.TOUCH_MODE, R08Protocol.TOUCH_DISABLE,
               R08Protocol.BATTERY_QUERY, R08Protocol.FIND_DEVICE, R08Protocol.BLINK_TWICE,
               R08Protocol.SHUTDOWN, R08Protocol.PING, R08Protocol.SET_ANCS,
               R08Protocol.TELEMETRY_ONE_SHOT_CMD, R08Protocol.TELEMETRY_BASIC_START_CMD,
               R08Protocol.TELEMETRY_STOP_CMD, R08Protocol.REAL_TIME_HEALTHCHECK_START,
               R08Protocol.REAL_TIME_HR_START, R08Protocol.REAL_TIME_SPO2_START,
               R08Protocol.REAL_TIME_STOP, R08Protocol.HR_AUTO_MONITOR_READ,
               R08Protocol.TARGET_SETTING_READ, R08Protocol.DEVICE_SUPPORT_QUERY,
               R08Protocol.GET_MESSAGE_PUSH, R08Protocol.BIND_SUCCESS,
        ).forEach { assertEquals(16, it.size, "size 16 expected, got ${it.size} for ${R08Frame.hex(it)}") }
    }

    @Test fun `verifyChecksum accepts a well-formed frame and rejects a tampered one`() {
        assertTrue(R08Protocol.verifyChecksum(R08Protocol.TOUCH_ENABLE))
        val tampered = R08Protocol.TOUCH_ENABLE.copyOf().also { it[15] = 0 }
        assertTrue(!R08Protocol.verifyChecksum(tampered))
    }

    // ── isUnsupported helper ─────────────────────────────────────────────────────────────────

    @Test fun `isUnsupported recognises the EE shape and rejects normal frames`() {
        assertTrue(R08Protocol.isUnsupported(bytes(0x9B, 0xEE, 0x00, 0x00)))
        assertTrue(!R08Protocol.isUnsupported(bytes(0x1B, 0x01, 0x00, 0x00)))
        assertTrue(!R08Protocol.isUnsupported(bytes(0x73, 0x2D, 0x03)))
    }

    // ── BCD helpers ──────────────────────────────────────────────────────────────────────────

    @Test fun `BCD round-trip for SetTime fields`() {
        assertEquals(0x26, R08Protocol.decimalToBcd(26))
        assertEquals(0x05, R08Protocol.decimalToBcd(5))
        assertEquals(0x00, R08Protocol.decimalToBcd(0))
        assertEquals(0x99, R08Protocol.decimalToBcd(99))
        assertEquals(26, R08Protocol.bcdToDecimal(0x26.toByte()))
        assertEquals(0,  R08Protocol.bcdToDecimal(0x00.toByte()))
    }

    @Test fun `u24 little and big endian round-trips`() {
        // Steps target 5000 LE: 88 13 00
        val leBytes = R08Protocol.u24Le(5000)
        assertEquals(0x88.toByte(), leBytes[0])
        assertEquals(0x13.toByte(), leBytes[1])
        assertEquals(0x00.toByte(), leBytes[2])
        // Activity steps 12345 BE: 00 30 39
        val beBytes = R08Protocol.u24Be(12345)
        assertEquals(0x00.toByte(), beBytes[0])
        assertEquals(0x30.toByte(), beBytes[1])
        assertEquals(0x39.toByte(), beBytes[2])
        // Round-trip read
        assertEquals(12345, R08Protocol.readU24Be(byteArrayOf(0x00, 0x30, 0x39), 0))
    }

    // ── SetTime command ──────────────────────────────────────────────────────────────────────

    @Test fun `setTime builds verified bootstrap frame`() {
        // SPEC v3 Appendix B: 01 26 05 27 03 17 24 01 ... checksum 92
        // year=2026 → 26, month=5, day=27, hour=3, min=23 (0x17 BCD → 17 dec), sec=36 (0x24 BCD → 24)
        // Actually re-derive from spec: hex `01 26 05 27 03 17 24 01` = SetTime(yr=26, mo=05, dy=27, hr=03, mn=17_dec_via_BCD=17, sec=24, lang=01)
        val frame = R08Protocol.setTime(yearMinus2000 = 26, month = 5, day = 27,
                                         hour = 3, minute = 17, second = 24, language = 1)
        assertEquals(0x01.toByte(), frame[0])
        assertEquals(0x26.toByte(), frame[1]) // year BCD
        assertEquals(0x05.toByte(), frame[2]) // month
        assertEquals(0x27.toByte(), frame[3]) // day
        assertEquals(0x03.toByte(), frame[4]) // hour
        assertEquals(0x17.toByte(), frame[5]) // min BCD
        assertEquals(0x24.toByte(), frame[6]) // sec BCD
        assertEquals(0x01.toByte(), frame[7]) // language en
        assertTrue(R08Protocol.verifyChecksum(frame))
    }

    // ── Capability decode (SetTime 14-byte + 0x3C 9-byte) ────────────────────────────────────

    @Test fun `SetTime 14-byte capability extension decodes the tested-unit bitmap`() {
        // Tested-unit payload (SPEC §3.1 verified 3×): 01 00 00 02 00 00 00 00 01 00 20 00 00 30
        val payload = bytes(0x01, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00,
                            0x01, 0x00, 0x20, 0x00, 0x00, 0x30)
        val caps = R08Frame.parseSetTimeCapabilities(payload)
        assertTrue("temperature" in caps.flags)
        assertTrue("bloodOxygen" in caps.flags)
        assertTrue("newSleepProtocol" in caps.flags)
        assertTrue("appMeasure" in caps.flags)
        assertTrue("pressure" in caps.flags)
        assertTrue("hrv" in caps.flags)
        // No display
        assertEquals(0, caps.screenWidth)
        assertEquals(0, caps.screenHeight)
    }

    @Test fun `0x3C 9-byte capability bitmap decodes the tested-unit value`() {
        // Tested-unit value (SPEC §3.2): 2F AF 2E 00 00 00 00 00 00
        val flags = R08Frame.parseDeviceSupportCapabilities(bytes(0x2F, 0xAF, 0x2E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        assertTrue("touch" in flags)
        assertTrue("moslin" in flags)
        assertTrue("appRevision" in flags)
        assertTrue("blePair" in flags)
        assertTrue("ringMusic" in flags)
        assertTrue("ringVideo" in flags)
        assertTrue("ringEbook" in flags)
        assertTrue("ringCamera" in flags)
        assertTrue("ringGame" in flags)
        assertTrue("longSit" in flags)
        assertTrue("drink" in flags)
        assertTrue("notification" in flags)
    }
}
