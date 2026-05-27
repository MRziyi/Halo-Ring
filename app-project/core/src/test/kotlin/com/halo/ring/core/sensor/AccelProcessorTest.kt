package com.halo.ring.core.sensor

import com.halo.ring.core.ble.RingEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccelProcessorTest {

    private fun sample(x: Float, y: Float, z: Float) = RingEvent.AccelSample(x, y, z)

    @Test fun `palm-down classifies on positive X gravity`() {
        val events = mutableListOf<AccelProcessor.SpatialEvent>()
        val p = AccelProcessor { events += it }
        p.onSample(sample(1f, 0f, 0f))
        assertTrue(events.any { it is AccelProcessor.SpatialEvent.PostureChanged && it.posture == AccelProcessor.Posture.PALM_DOWN })
    }

    @Test fun `palm-up classifies on negative X gravity`() {
        val events = mutableListOf<AccelProcessor.SpatialEvent>()
        val p = AccelProcessor { events += it }
        p.onSample(sample(-1f, 0f, 0f))
        assertTrue(events.any { it is AccelProcessor.SpatialEvent.PostureChanged && it.posture == AccelProcessor.Posture.PALM_UP })
    }

    @Test fun `posture change emits only when band crosses`() {
        val events = mutableListOf<AccelProcessor.SpatialEvent>()
        val p = AccelProcessor { events += it }
        // Same posture across 3 samples → only one event
        p.onSample(sample(1f, 0f, 0f))
        p.onSample(sample(0.95f, 0.1f, 0.05f))
        p.onSample(sample(1.0f, 0f, 0f))
        val postureEvents = events.filterIsInstance<AccelProcessor.SpatialEvent.PostureChanged>()
        assertEquals(1, postureEvents.size)
    }

    @Test fun `weak gravity vector classifies as UNKNOWN and does NOT emit`() {
        val events = mutableListOf<AccelProcessor.SpatialEvent>()
        val p = AccelProcessor { events += it }
        p.onSample(sample(0.2f, 0.2f, 0.2f))  // tumbling — no dominant axis
        assertEquals(0, events.size)
    }

    @Test fun `free-fall fires after sustained near-zero magnitude`() {
        val events = mutableListOf<AccelProcessor.SpatialEvent>()
        val p = AccelProcessor { events += it }
        repeat(AccelProcessor.FREE_FALL_MIN_SAMPLES) {
            p.onSample(sample(0.05f, 0.05f, 0.05f))
        }
        assertTrue(events.any { it is AccelProcessor.SpatialEvent.FreeFall })
    }

    @Test fun `free-fall does NOT fire on a single transient near-zero sample`() {
        val events = mutableListOf<AccelProcessor.SpatialEvent>()
        val p = AccelProcessor { events += it }
        p.onSample(sample(0.05f, 0.05f, 0.05f))  // just one — below the consecutive-sample threshold
        assertTrue(events.none { it is AccelProcessor.SpatialEvent.FreeFall })
    }

    @Test fun `impact fires on a large magnitude spike`() {
        val events = mutableListOf<AccelProcessor.SpatialEvent>()
        val p = AccelProcessor { events += it }
        p.onSample(sample(3f, 0f, 0f))
        val impacts = events.filterIsInstance<AccelProcessor.SpatialEvent.Impact>()
        assertEquals(1, impacts.size)
        assertTrue(impacts[0].peakG > AccelProcessor.IMPACT_MAG_THRESHOLD)
    }

    @Test fun `wrist shake fires after enough sign reversals`() {
        val events = mutableListOf<AccelProcessor.SpatialEvent>()
        val p = AccelProcessor { events += it }
        // Alternate +X / -X above the threshold, enough times to fill the window with reversals.
        repeat(AccelProcessor.WRIST_SHAKE_WINDOW) { i ->
            p.onSample(sample(if (i % 2 == 0) 0.9f else -0.9f, 0.1f, 0.1f))
        }
        assertTrue(events.any { it is AccelProcessor.SpatialEvent.WristShake })
    }

    @Test fun `wrist shake does NOT fire on steady-state palm-down`() {
        val events = mutableListOf<AccelProcessor.SpatialEvent>()
        val p = AccelProcessor { events += it }
        repeat(AccelProcessor.WRIST_SHAKE_WINDOW * 2) {
            p.onSample(sample(1f, 0f, 0f))
        }
        assertTrue(events.none { it is AccelProcessor.SpatialEvent.WristShake })
    }

    @Test fun `reset clears all internal state`() {
        val events = mutableListOf<AccelProcessor.SpatialEvent>()
        val p = AccelProcessor { events += it }
        p.onSample(sample(1f, 0f, 0f))     // emits PALM_DOWN
        p.reset()
        events.clear()
        p.onSample(sample(1f, 0f, 0f))     // re-emits PALM_DOWN because state reset
        assertTrue(events.any { it is AccelProcessor.SpatialEvent.PostureChanged })
    }
}
