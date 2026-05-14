package com.halo.ring.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VitalsLoggerTest {

    private fun hr(t: Long, value: Int = 72) =
        VitalsLogger.Sample(VitalsLogger.Kind.HEART_RATE, value, t)

    @Test fun `disabled by default — record is a no-op`() {
        val l = VitalsLogger()
        l.record(hr(0))
        assertEquals(0, l.size())
    }

    @Test fun `enabled records samples in order, mixed kinds`() {
        val l = VitalsLogger().apply { enabled = true }
        l.record(VitalsLogger.Sample(VitalsLogger.Kind.HEART_RATE, 72, 100))
        l.record(VitalsLogger.Sample(VitalsLogger.Kind.SPO2, 97, 200))
        l.record(VitalsLogger.Sample(VitalsLogger.Kind.STRESS, 23, 300))
        val snap = l.snapshot()
        assertEquals(listOf<Long>(100, 200, 300), snap.map { it.capturedAtMs })
        assertEquals(listOf(VitalsLogger.Kind.HEART_RATE, VitalsLogger.Kind.SPO2, VitalsLogger.Kind.STRESS),
            snap.map { it.kind })
    }

    @Test fun `ring buffer drops the oldest when capacity exceeded`() {
        val l = VitalsLogger(capacity = 3).apply { enabled = true }
        repeat(5) { i -> l.record(hr(100L + i * 10)) }
        assertEquals(listOf<Long>(120, 130, 140), l.snapshot().map { it.capturedAtMs })
        assertEquals(3, l.size())
    }

    @Test fun `reset clears the buffer`() {
        val l = VitalsLogger().apply { enabled = true }
        repeat(3) { l.record(hr(it.toLong())) }
        l.reset()
        assertEquals(0, l.size())
        assertTrue(l.snapshot().isEmpty())
    }

    @Test fun `csv has header and one row per sample with kind name`() {
        val l = VitalsLogger().apply { enabled = true }
        l.record(VitalsLogger.Sample(VitalsLogger.Kind.HEART_RATE, 72, 1000))
        l.record(VitalsLogger.Sample(VitalsLogger.Kind.SPO2, 97, 2000))
        val csv = l.toCsv()
        val lines = csv.lineSequence().toList().filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("captured_at_ms,kind,value"))
        assertTrue(lines[1].contains("HEART_RATE"))
        assertTrue(lines[1].contains("72"))
        assertTrue(lines[2].contains("SPO2"))
        assertTrue(lines[2].contains("97"))
    }

    @Test fun `csv is header only when buffer is empty`() {
        val l = VitalsLogger().apply { enabled = true }
        val nonEmpty = l.toCsv().lineSequence().toList().filter { it.isNotEmpty() }
        assertEquals(1, nonEmpty.size)
    }

    @Test fun `concurrent record + snapshot do not crash`() {
        val l = VitalsLogger(capacity = 50).apply { enabled = true }
        val writer = Thread { repeat(500) { l.record(hr(it.toLong())) } }
        val reader = Thread { repeat(50) { val s = l.snapshot(); assertFalse(s.size > 50) } }
        writer.start(); reader.start()
        writer.join(); reader.join()
        assertTrue(l.size() in 0..50)
    }
}
