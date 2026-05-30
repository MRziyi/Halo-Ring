package com.halo.ring.core.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * D11 — boundary cases the existing GestureSynthesizerTest doesn't cover:
 *  - timing windows AT THE EXACT BOUNDARY (`<` vs `<=`)
 *  - five-or-more rapid taps (cap)
 *  - wake-swallow's interaction with the multi-tap window (lastTapAtMs update path)
 *  - LP follow-up timing at the exact window edge
 *
 * These are the edge cases real hardware will hit; a regression here looks like "fast multi-tap
 * misses combos by 1 ms" — invisible until phase-1.
 */
class GestureSynthesizerBoundaryTest {

    private fun fixture(
        config: GestureConfig = GestureConfig(),
    ): Triple<GestureSynthesizer, ManualScheduler, MutableList<Gesture>> {
        val sched = ManualScheduler()
        val out = mutableListOf<Gesture>()
        val syn = GestureSynthesizer(config, sched) { out += it }
        return Triple(syn, sched, out)
    }

    // ── multi-tap window edge: gap == multiTapWindowMs is NOT part of the same multi-tap ──────────

    @Test fun `two TOUCHes exactly multiTapWindowMs apart are two separate taps`() {
        // Strict `<` in the policy means equality is OUT. In production the first tap's timer fires
        // at scheduler-time 280 BEFORE a BLE callback can reach this thread at the same instant
        // (Handler ordering); the second TOUCH then arrives as a fresh tap-1. We mirror that with
        // ManualScheduler by advancing time first.
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false, enableTapSwipe = true, enableDoubleTapSwipe = true,
            multiTapWindowMs = 280, comboWindowMs = 300,
        ))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        sched.advanceBy(280)        // first tap's timer fires here → TAP
        assertEquals(listOf(Gesture.TAP), out)
        syn.onRaw(RawGesture.TOUCH, nowMs = sched.nowMs())  // second TOUCH at the boundary
        sched.advanceBy(280 + 1)
        assertEquals(listOf(Gesture.TAP, Gesture.TAP), out, "two distinct taps, NOT a DOUBLE_TAP")
    }

    @Test fun `two TOUCHes one millisecond inside the window become a DOUBLE_TAP`() {
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false, enableTapSwipe = true, enableDoubleTapSwipe = true,
            multiTapWindowMs = 280, comboWindowMs = 300,
        ))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 279)  // 1 ms inside the window
        sched.advanceBy(400)
        assertEquals(listOf(Gesture.DOUBLE_TAP), out)
    }

    // ── combo window edge: timer fires AT comboWindowMs; a swipe arriving on/after that is plain ──

    @Test fun `swipe arriving after the combo timer fires is a plain SWIPE not a combo`() {
        // Sequence: TOUCH, TOUCH (DOUBLE_TAP pending in the combo window), advance to window expiry
        // (timer fires → bare DOUBLE_TAP commits), then SWIPE_UP arrives → plain SWIPE.
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false, enableTapSwipe = true, enableDoubleTapSwipe = true,
            multiTapWindowMs = 280, comboWindowMs = 300,
        ))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 100)
        sched.advanceBy(300)  // combo window fully expires; DOUBLE_TAP commits
        syn.onRaw(RawGesture.SWIPE_UP, nowMs = sched.nowMs())
        assertEquals(listOf(Gesture.DOUBLE_TAP, Gesture.SWIPE_UP), out)
    }

    @Test fun `swipe arriving one ms before combo timer fires becomes DOUBLE_TAP_SWIPE_UP`() {
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false, enableTapSwipe = true, enableDoubleTapSwipe = true,
            multiTapWindowMs = 280, comboWindowMs = 300,
        ))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 100)
        sched.advanceBy(299)  // 1 ms before the combo timer fires
        syn.onRaw(RawGesture.SWIPE_UP, nowMs = sched.nowMs())
        assertEquals(listOf(Gesture.DOUBLE_TAP_SWIPE_UP), out)
    }

    // ── LP follow-up window edge ──────────────────────────────────────────────────────────────────

    @Test fun `swipe arriving after the LP follow-up window fires is plain — not LONG_PRESS_SWIPE_UP`() {
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false,
            awaitLongPressCombos = true,
            longPressFollowupWindowMs = 400,
        ))
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 0)
        sched.advanceBy(400)  // LP timer fires → bare LONG_PRESS commits
        syn.onRaw(RawGesture.SWIPE_UP, nowMs = sched.nowMs())
        assertEquals(listOf(Gesture.LONG_PRESS, Gesture.SWIPE_UP), out)
    }

    @Test fun `swipe one ms inside the LP follow-up window upgrades to LONG_PRESS_SWIPE_UP`() {
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false,
            awaitLongPressCombos = true,
            longPressFollowupWindowMs = 400,
        ))
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 0)
        sched.advanceBy(399)
        syn.onRaw(RawGesture.SWIPE_UP, nowMs = sched.nowMs())
        assertEquals(listOf(Gesture.LONG_PRESS_SWIPE_UP), out)
    }

    // ── 5+ rapid taps: capped at QUADRUPLE_TAP ────────────────────────────────────────────────────

    @Test fun `five rapid taps are capped at QUADRUPLE_TAP, no extra emit on the 5th`() {
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false, enableTapSwipe = true, enableDoubleTapSwipe = true,
            enableTripleTap = true, enableQuadrupleTap = true,
            multiTapWindowMs = 280,
        ))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 100)
        syn.onRaw(RawGesture.TOUCH, nowMs = 200)
        syn.onRaw(RawGesture.TOUCH, nowMs = 300)  // 4 — within window of 3rd
        syn.onRaw(RawGesture.TOUCH, nowMs = 400)  // 5 — within window of 4th, must NOT emit again
        sched.advanceBy(400)
        assertEquals(listOf(Gesture.QUADRUPLE_TAP), out)
    }

    // ── wake-swallow keeps lastTapAtMs in sync ────────────────────────────────────────────────────

    @Test fun `TOUCH right after a swallow within the multi-tap window is still a single TAP`() {
        // After armWakeSwallow(1) eats the first TOUCH, the second TOUCH should be a fresh tap-1,
        // NOT a DOUBLE_TAP — even though lastTapAtMs was advanced. The synth resets tapCount on
        // arm via the public contract (reset()), so this is the expected behaviour.
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false, enableTapSwipe = true, enableDoubleTapSwipe = true,
            multiTapWindowMs = 280, wakeSwallowCount = 1,
        ))
        syn.armWakeSwallow()
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)      // swallowed
        syn.onRaw(RawGesture.TOUCH, nowMs = 50)     // first real tap
        sched.advanceBy(300)
        assertEquals(listOf(Gesture.TAP), out)
    }

    @Test fun `LONG_PRESS while wake-swallow is armed clears the swallow and processes the LP`() {
        // Documented: LONG_PRESS is never swallowed (it can serve as the wake gesture immediately
        // after the ring resumes). Subsequent TOUCHes are NO LONGER swallowed.
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false,
            awaitLongPressCombos = false,
            wakeSwallowCount = 2,
        ))
        syn.armWakeSwallow()
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 50)
        sched.advanceBy(300)
        assertEquals(listOf(Gesture.LONG_PRESS, Gesture.TAP), out)
    }
}
