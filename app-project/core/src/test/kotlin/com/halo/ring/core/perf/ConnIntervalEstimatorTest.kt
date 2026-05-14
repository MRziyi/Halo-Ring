package com.halo.ring.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnIntervalEstimatorTest {

    @Test fun `null until min samples`() {
        val e = ConnIntervalEstimator()
        assertNull(e.estimate(), "no samples → null")
        e.record(0)
        e.record(20)
        e.record(40)
        // Only 2 deltas — needs at least 3.
        assertNull(e.estimate(), "<3 deltas → null")
    }

    @Test fun `recovers exact interval at HIGH band`() {
        val e = ConnIntervalEstimator()
        // 8 samples at perfect 22 ms cadence (HIGH band midpoint).
        for (i in 0..7) e.record(i * 22L)
        assertEquals(22, e.estimate())
    }

    @Test fun `recovers exact interval at BALANCED band`() {
        val e = ConnIntervalEstimator()
        for (i in 0..7) e.record(i * 87L)
        assertEquals(87, e.estimate())
    }

    @Test fun `recovers exact interval at SLOW band`() {
        val e = ConnIntervalEstimator()
        for (i in 0..7) e.record(i * 300L)
        assertEquals(300, e.estimate())
    }

    @Test fun `filters intra-conn-event bursts`() {
        // Pattern: one notify, then two more 1 ms later (burst inside same conn-event), then
        // 20 ms gap to the next conn-event, etc.
        val e = ConnIntervalEstimator()
        var t = 0L
        repeat(5) {
            e.record(t); t += 1
            e.record(t); t += 1   // burst — deltas of 1ms each, filtered out
            e.record(t); t += 19  // jump to next conn-event boundary at 20ms
        }
        // The kept deltas should all be ≥ 5ms, mostly 19s.
        val v = e.estimate()
        assertNotNull(v)
        assertEquals(19, v)
    }

    @Test fun `median ignores outliers`() {
        // 7 deltas of 30 ms + 1 outlier 200 ms (e.g. a missed conn-event). Median should remain 30.
        val e = ConnIntervalEstimator()
        var t = 0L
        repeat(7) {
            t += 30; e.record(t)
        }
        t += 200; e.record(t)   // outlier
        repeat(3) {
            t += 30; e.record(t)
        }
        assertEquals(30, e.estimate())
    }

    @Test fun `reset clears samples`() {
        val e = ConnIntervalEstimator()
        repeat(10) { e.record(it * 20L) }
        assertNotNull(e.estimate())
        e.reset()
        assertEquals(0, e.sampleSize())
        assertNull(e.estimate())
    }

    @Test fun `bounded buffer drops oldest`() {
        val e = ConnIntervalEstimator(sampleCount = 4)
        // Record 10 samples — buffer should retain only the last 4.
        repeat(10) { e.record(it * 100L) }
        assertEquals(4, e.sampleSize())
    }

    @Test fun `band transition estimate shifts within sampleCount events`() {
        val e = ConnIntervalEstimator(sampleCount = 16)
        // 8 samples at HIGH band (22 ms).
        for (i in 0..7) e.record(i * 22L)
        assertEquals(22, e.estimate())

        // Now 8 more at BALANCED band (87 ms). After 16 total samples, the oldest HIGH ones get
        // pushed out; estimator should reflect the new mode.
        var t = 8 * 22L
        for (i in 0..15) {
            t += 87
            e.record(t)
        }
        // All 16 samples are now in the BALANCED band; median should be 87.
        assertEquals(87, e.estimate())
    }

    @Test fun `negative delta clamped to zero`() {
        // Defensive: SystemClock-based timestamps are monotonic so this should never happen, but
        // a misbehaving caller mustn't crash the estimator.
        val e = ConnIntervalEstimator()
        e.record(100)
        e.record(50)   // would yield a delta of -50
        e.record(150)
        e.record(200)
        e.record(250)
        e.record(300)
        // The delta=-50 is clamped to 0, then filtered out by burstFloor. Remaining deltas are
        // all 50 → median 50.
        assertEquals(50, e.estimate())
    }

    @Test fun `concurrent record and estimate is safe`() {
        // Smoke test: hammer the estimator from two threads briefly; no exceptions allowed.
        val e = ConnIntervalEstimator()
        val producer = Thread {
            var t = 0L
            repeat(1000) {
                t += 30
                e.record(t)
            }
        }
        val consumer = Thread {
            repeat(1000) { e.estimate() }
        }
        producer.start(); consumer.start()
        producer.join(); consumer.join()
        // Final estimate should be sensible.
        val v = e.estimate()
        assertNotNull(v)
        assertTrue(v in 25..35, "expected ~30 ms, got $v")
    }
}
