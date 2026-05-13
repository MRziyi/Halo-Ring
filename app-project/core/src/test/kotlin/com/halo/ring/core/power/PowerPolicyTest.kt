package com.halo.ring.core.power

import com.halo.ring.core.power.PowerPolicy.IntervalMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerPolicyTest {

    private fun inputs(
        worn: Boolean = true,
        screenOn: Boolean = true,
        lastActivityMs: Long = 0L,
        lastWornMs: Long = 0L,
        nowMs: Long = 0L,
    ) = PowerPolicy.Inputs(worn, screenOn, lastActivityMs, lastWornMs, nowMs)

    @Test fun `not worn keeps touch IC off`() {
        val d = PowerPolicy.decide(inputs(worn = false, screenOn = true, nowMs = 1_000))
        assertFalse(d.touchEnabled)
        assertEquals(IntervalMode.BALANCED, d.intervalMode)
    }

    @Test fun `not worn for less than 5 min keeps connection`() {
        val d = PowerPolicy.decide(inputs(
            worn = false, screenOn = false,
            lastWornMs = 0L,
            nowMs = 4L * 60_000L,
        ))
        assertFalse(d.disconnect)
    }

    @Test fun `not worn for more than 5 min disconnects`() {
        val d = PowerPolicy.decide(inputs(
            worn = false, screenOn = false,
            lastWornMs = 0L,
            nowMs = 5L * 60_000L + 1,
        ))
        assertTrue(d.disconnect)
    }

    @Test fun `not worn at exactly 5 min boundary still holds connection`() {
        // Boundary fairness — the strict `>` in the policy means equality stays connected.
        val d = PowerPolicy.decide(inputs(
            worn = false, screenOn = false,
            lastWornMs = 0L,
            nowMs = PowerPolicy.NOT_WORN_DISCONNECT_MS,
        ))
        assertFalse(d.disconnect)
    }

    @Test fun `worn with screen off uses SLOW interval (worn but wake-ready)`() {
        // Doc/06 §3.1: "Touch IC stays on when worn even if screen is off." Doc/06 §3.2: BLE
        // interval drops to 200–500 ms in this band. This is the largest power band of the day for
        // a wearer who has the glasses on intermittently — must be SLOW, not BALANCED.
        val d = PowerPolicy.decide(inputs(worn = true, screenOn = false, nowMs = 60_000L))
        assertTrue(d.touchEnabled)
        assertEquals(IntervalMode.SLOW, d.intervalMode)
    }

    @Test fun `worn screen on with recent gesture uses HIGH interval`() {
        val d = PowerPolicy.decide(inputs(
            worn = true, screenOn = true,
            lastActivityMs = 5_000L,
            nowMs = 6_000L,   // 1s since activity → within 10s window
        ))
        assertTrue(d.touchEnabled)
        assertEquals(IntervalMode.HIGH, d.intervalMode)
    }

    @Test fun `worn screen on with idle for over 10 s drops to BALANCED`() {
        val d = PowerPolicy.decide(inputs(
            worn = true, screenOn = true,
            lastActivityMs = 0L,
            nowMs = 11_000L,   // 11s idle
        ))
        assertTrue(d.touchEnabled)
        assertEquals(IntervalMode.BALANCED, d.intervalMode)
    }

    @Test fun `worn flips back to HIGH on a fresh gesture`() {
        val idle = PowerPolicy.decide(inputs(
            worn = true, screenOn = true,
            lastActivityMs = 0L, nowMs = 30_000L,
        ))
        assertEquals(IntervalMode.BALANCED, idle.intervalMode)

        val afterGesture = PowerPolicy.decide(inputs(
            worn = true, screenOn = true,
            lastActivityMs = 30_000L, nowMs = 30_010L,
        ))
        assertEquals(IntervalMode.HIGH, afterGesture.intervalMode)
    }

    @Test fun `transition boundary at exactly the active window`() {
        // At exactly nowMs - lastActivityMs == ACTIVE_WINDOW_MS the gesture is no longer "recent"
        // (strict `<` in the policy) → drop out of HIGH.
        val d = PowerPolicy.decide(inputs(
            worn = true, screenOn = true,
            lastActivityMs = 0L,
            nowMs = PowerPolicy.ACTIVE_WINDOW_MS,
        ))
        assertEquals(IntervalMode.BALANCED, d.intervalMode)
    }

    @Test fun `lastWornMs of MIN_VALUE means never-worn — do not disconnect`() {
        val d = PowerPolicy.decide(inputs(
            worn = false, screenOn = false,
            lastWornMs = Long.MIN_VALUE,
            nowMs = 10L * 60_000L,
        ))
        assertFalse(d.disconnect)
    }

    @Test fun `screen off forces SLOW even if a gesture arrived recently`() {
        // The previous "screen-off cancels active mode" test now upgrades to "screen-off forces
        // SLOW" — a more aggressive power saving than the old BALANCED behaviour.
        val d = PowerPolicy.decide(inputs(
            worn = true, screenOn = false,
            lastActivityMs = 5_000L,
            nowMs = 6_000L,
        ))
        assertEquals(IntervalMode.SLOW, d.intervalMode)
        assertTrue(d.touchEnabled)
    }

    @Test fun `screen flicks back on with recent gesture immediately re-enters HIGH`() {
        // Wake gesture lands at t=0 while screen was off; service flips screenOn=true and re-runs
        // the policy at t=50 ms. Must promote from SLOW to HIGH inside the same tick — otherwise
        // the user's first post-wake action streams at 200-500 ms intervals.
        val d = PowerPolicy.decide(inputs(
            worn = true, screenOn = true,
            lastActivityMs = 0L,
            nowMs = 50L,
        ))
        assertEquals(IntervalMode.HIGH, d.intervalMode)
    }

    @Test fun `lastActivityMs of MIN_VALUE means never-active and returns BALANCED idle`() {
        // Doc/13 §audit-2026-05-13j: the original `(nowMs - Long.MIN_VALUE) < ACTIVE_WINDOW_MS`
        // expression overflowed Long and accidentally classified "never-active" as
        // "recently-active" → HIGH band on first connection. Now explicitly guarded; first
        // connection lands in BALANCED, then HaloRingService seeds lastActivityMs on READY so
        // the very-first reconcile after connect goes back to HIGH.
        val d = PowerPolicy.decide(inputs(
            worn = true, screenOn = true,
            lastActivityMs = Long.MIN_VALUE,   // never observed a ring event yet
            nowMs = 10_000_000L,                // arbitrary positive uptime
        ))
        assertEquals(IntervalMode.BALANCED, d.intervalMode)
        assertTrue(d.touchEnabled)
    }
}
