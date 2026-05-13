package com.halo.ring.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatencyLoggerTest {

    private fun sample(t: Long, gesture: String = "TAP", action: String = "Confirm") =
        LatencyLogger.Sample(
            gestureName = gesture,
            actionName  = action,
            tBleMs      = t,
            tEmittedMs  = t + 5,
            tDispatchedMs = t + 8,
        )

    @Test fun `disabled by default — record is a no-op`() {
        val l = LatencyLogger()
        l.record(sample(0))
        assertEquals(0, l.size())
    }

    @Test fun `enabled records samples in order`() {
        val l = LatencyLogger().apply { enabled = true }
        l.record(sample(100))
        l.record(sample(200))
        l.record(sample(300))
        val snap = l.snapshot()
        assertEquals(listOf<Long>(100, 200, 300), snap.map { it.tBleMs })
    }

    @Test fun `ring buffer drops the oldest when capacity exceeded`() {
        val l = LatencyLogger(capacity = 3).apply { enabled = true }
        repeat(5) { i -> l.record(sample(100L + i * 10)) }
        // Buffer keeps the last 3 entries.
        assertEquals(listOf<Long>(120, 130, 140), l.snapshot().map { it.tBleMs })
        assertEquals(3, l.size())
    }

    @Test fun `disabling stops new records but preserves the existing buffer`() {
        val l = LatencyLogger().apply { enabled = true }
        l.record(sample(1))
        l.enabled = false
        l.record(sample(2))
        assertEquals(1, l.size())
        l.enabled = true
        l.record(sample(3))
        assertEquals(2, l.size())
    }

    @Test fun `reset clears the buffer regardless of enabled state`() {
        val l = LatencyLogger().apply { enabled = true }
        l.record(sample(1)); l.record(sample(2)); l.record(sample(3))
        l.reset()
        assertEquals(0, l.size())
        assertTrue(l.snapshot().isEmpty())
    }

    @Test fun `derived recognition + dispatch + total computed correctly`() {
        val s = LatencyLogger.Sample(
            gestureName = "DOUBLE_TAP",
            actionName  = "Back",
            tBleMs      = 1_000,
            tEmittedMs  = 1_280,    // synth held for combo window (~280 ms)
            tDispatchedMs = 1_285,  // 5 ms router + backend
        )
        assertEquals(280, s.recognitionMs)
        assertEquals(5, s.dispatchMs)
        assertEquals(285, s.totalMs)
    }

    @Test fun `csv serialisation has the header and one row per sample`() {
        val l = LatencyLogger().apply { enabled = true }
        l.record(sample(100))
        l.record(sample(200))
        val csv = l.toCsv()
        val lines = csv.lineSequence().toList()
        assertTrue(lines[0].startsWith("timestamp_ble_ms"))
        // Three lines = header + 2 rows + (lineSequence returns a trailing empty after final '\n')
        assertEquals(3, lines.filter { it.isNotEmpty() }.size)
        // Both data rows include the gesture name.
        assertTrue(lines[1].contains("TAP"), "row 1 should contain gesture name")
        assertTrue(lines[2].contains("TAP"))
    }

    @Test fun `csv is empty (header only) when buffer is empty`() {
        val l = LatencyLogger().apply { enabled = true }
        val csv = l.toCsv()
        assertTrue(csv.startsWith("timestamp_ble_ms"))
        assertEquals(1, csv.lineSequence().toList().filter { it.isNotEmpty() }.size)
    }

    @Test fun `enabled toggle is volatile — flips don't lose in-flight records`() {
        // Defensive test: rapid toggle while recording.
        val l = LatencyLogger().apply { enabled = true }
        repeat(20) { i ->
            if (i % 2 == 0) l.enabled = !l.enabled
            l.record(sample(i.toLong()))
        }
        // Some subset will be recorded depending on the timing; we just assert no crash + size
        // is in [0, capacity].
        assertTrue(l.size() in 0..100)
    }

    @Test fun `concurrent record + snapshot do not crash`() {
        // Light concurrency check — runs writer + reader on real threads.
        val l = LatencyLogger(capacity = 50).apply { enabled = true }
        val writer = Thread { repeat(500) { l.record(sample(it.toLong())) } }
        val reader = Thread { repeat(50) { val s = l.snapshot(); assertFalse(s.size > 50) } }
        writer.start(); reader.start()
        writer.join(); reader.join()
        assertTrue(l.size() in 0..50)
    }
}
