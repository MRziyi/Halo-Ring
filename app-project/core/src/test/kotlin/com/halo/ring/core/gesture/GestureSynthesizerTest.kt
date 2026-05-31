package com.halo.ring.core.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exhaustive behavioural tests for [GestureSynthesizer]. Every path documented in §5.2 / §15.1 /
 * §20.3 should be covered here. Failing tests = real bugs (or you intentionally changed the spec —
 * update both this file and the design doc).
 *
 * Recipe: drive a [ManualScheduler] forward and assert the emitted [Gesture] sequence.
 */
class GestureSynthesizerTest {

    private fun fixture(
        config: GestureConfig = GestureConfig(),
    ): Triple<GestureSynthesizer, ManualScheduler, MutableList<Gesture>> {
        val sched = ManualScheduler()
        val out = mutableListOf<Gesture>()
        val syn = GestureSynthesizer(config, sched) { out += it }
        return Triple(syn, sched, out)
    }

    // ── swipe / long-press are immediate, no window ──────────────────────────────────────────────

    @Test fun `swipe up passes through immediately`() {
        val (syn, _, out) = fixture()
        syn.onRaw(RawGesture.SWIPE_UP, nowMs = 0)
        assertEquals(listOf(Gesture.SWIPE_UP), out)
    }

    @Test fun `long press passes through immediately and clears all pending state (awaitLongPressCombos=off)`() {
        // With combos disabled (Fast profile / explicit opt-out), LONG_PRESS is immediate.
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, awaitLongPressCombos = false))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 10)
        assertEquals(listOf(Gesture.TAP, Gesture.LONG_PRESS), out)
        sched.advanceBy(1_000)
        assertEquals(listOf(Gesture.TAP, Gesture.LONG_PRESS), out)
    }

    // ── single tap (conservative) ────────────────────────────────────────────────────────────────

    @Test fun `single tap (conservative) commits after the multi-tap window`() {
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, multiTapWindowMs = 280))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        assertEquals(emptyList<Gesture>(), out)  // nothing yet
        sched.advanceBy(279)
        assertEquals(emptyList<Gesture>(), out)  // still nothing
        sched.advanceBy(2)                       // window crosses
        assertEquals(listOf(Gesture.TAP), out)
    }

    // ── single tap (optimistic) ──────────────────────────────────────────────────────────────────

    @Test fun `single tap (optimistic) commits immediately, no duplicate at window expiry`() {
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = true))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        assertEquals(listOf(Gesture.TAP), out)
        sched.advanceBy(1_000)
        assertEquals(listOf(Gesture.TAP), out)   // no second TAP from the window
    }

    @Test fun `optimistic single-tap followed by a real second tap yields TAP then DOUBLE_TAP`() {
        // documented trade-off of optimistic mode (§20.3)
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = true, enableTapSwipe = true, enableDoubleTapSwipe = true))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 150)
        // immediate TAP from optimistic; DOUBLE_TAP after the combo window
        assertEquals(listOf(Gesture.TAP), out)
        sched.advanceBy(400)
        assertEquals(listOf(Gesture.TAP, Gesture.DOUBLE_TAP), out)
    }

    // ── double tap (conservative, awaitCombos) ───────────────────────────────────────────────────

    @Test fun `double tap waits the combo window then emits DOUBLE_TAP`() {
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, enableTapSwipe = true, enableDoubleTapSwipe = true, comboWindowMs = 300))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 150)
        assertEquals(emptyList<Gesture>(), out)
        sched.advanceBy(299)
        assertEquals(emptyList<Gesture>(), out)
        sched.advanceBy(2)
        assertEquals(listOf(Gesture.DOUBLE_TAP), out)
    }

    @Test fun `double tap fires immediately on 2nd tap when no combo or triple to await`() {
        // DOUBLE_TAP commits on the 2nd tap only when there's nothing left to wait for: no 双击组合
        // swipe (enableDoubleTapSwipe) AND no 3rd tap (enableTripleTap/Quad).
        val (syn, _, out) = fixture(GestureConfig(
            optimisticSingleTap = false, enableTapSwipe = false, enableDoubleTapSwipe = false,
            enableTripleTap = false, enableQuadrupleTap = false,
        ))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 100)
        assertEquals(listOf(Gesture.DOUBLE_TAP), out)
    }

    // ── triple tap ───────────────────────────────────────────────────────────────────────────────

    @Test fun `triple tap emits TRIPLE_TAP and suppresses the bare DOUBLE_TAP`() {
        // TRIPLE_TAP is a double-tap extension → requires 双击组合 (enableDoubleTapSwipe) on.
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, enableDoubleTapSwipe = true))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 120)
        syn.onRaw(RawGesture.TOUCH, nowMs = 240)
        sched.advanceBy(500)
        assertEquals(listOf(Gesture.TRIPLE_TAP), out)
    }

    @Test fun `with double-combo OFF, three fast taps never make TRIPLE_TAP (double-tap is immediate)`() {
        // The latency promise: 双击组合 off → DOUBLE_TAP commits on the 2nd tap with no wait, so a 3rd
        // tap can't extend it to TRIPLE_TAP. (Confirms turning off 双击组合 actually speeds up double-tap.)
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false, enableDoubleTapSwipe = false, enableTripleTap = true,
        ))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 100)   // 2nd tap → DOUBLE_TAP fires immediately here
        syn.onRaw(RawGesture.TOUCH, nowMs = 200)   // 3rd tap → starts a fresh single tap
        sched.advanceBy(500)
        assertEquals(listOf(Gesture.DOUBLE_TAP, Gesture.TAP), out)
        assertTrue(Gesture.TRIPLE_TAP !in out, "triple-tap must NOT form while 双击组合 is off")
    }

    @Test fun `with enableTripleTap=false a 3rd tap collapses to DOUBLE_TAP`() {
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false, enableTapSwipe = true, enableDoubleTapSwipe = true, enableTripleTap = false,
        ))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 100)
        syn.onRaw(RawGesture.TOUCH, nowMs = 200)
        sched.advanceBy(500)
        assertEquals(listOf(Gesture.DOUBLE_TAP), out)
    }

    // ── combos ───────────────────────────────────────────────────────────────────────────────────

    @Test fun `double-tap then swipe up within combo window emits DOUBLE_TAP_SWIPE_UP only`() {
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, enableTapSwipe = true, enableDoubleTapSwipe = true, comboWindowMs = 300))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 120)
        syn.onRaw(RawGesture.SWIPE_UP, nowMs = 250)
        sched.advanceBy(1_000)
        assertEquals(listOf(Gesture.DOUBLE_TAP_SWIPE_UP), out)  // crucially: no DOUBLE_TAP alongside
    }

    @Test fun `swipe down after the combo window is just a plain SWIPE_DOWN`() {
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, enableTapSwipe = true, enableDoubleTapSwipe = true, comboWindowMs = 300))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 100)
        sched.advanceBy(350)                       // window expires → DOUBLE_TAP fires
        syn.onRaw(RawGesture.SWIPE_DOWN, nowMs = sched.nowMs())
        assertEquals(listOf(Gesture.DOUBLE_TAP, Gesture.SWIPE_DOWN), out)
    }

    // ── flush-before-definite-gesture (preserves ordering) ───────────────────────────────────────

    @Test fun `tap then swipe within the window forms a TAP_SWIPE combo`() {
        // 2026-05-28: single-tap-then-swipe is now the TAP_SWIPE_* combo (user "单击上/单击下").
        val (syn, _, out) = fixture(GestureConfig(optimisticSingleTap = false))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.SWIPE_UP, nowMs = 50)     // arrives before the multi-tap window expires
        assertEquals(listOf(Gesture.TAP_SWIPE_UP), out)
    }

    @Test fun `tap then swipe AFTER the window is a separate TAP then SWIPE`() {
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        sched.advanceBy(500)                            // tap window expires → bare TAP commits
        syn.onRaw(RawGesture.SWIPE_UP, nowMs = 600)
        assertEquals(listOf(Gesture.TAP, Gesture.SWIPE_UP), out)
    }

    @Test fun `bare swipe with no pending tap stays instant`() {
        val (syn, _, out) = fixture(GestureConfig(optimisticSingleTap = false))
        syn.onRaw(RawGesture.SWIPE_DOWN, nowMs = 0)
        assertEquals(listOf(Gesture.SWIPE_DOWN), out)   // no latency for base-nav swipes
    }

    // ── wake swallow (ring auto-sleep + double-tap to wake) ──────────────────────────────────────

    @Test fun `armWakeSwallow swallows the wake-tap - a single real tap afterwards is TAP, not DOUBLE_TAP`() {
        // wakeSwallowCount=1: one TOUCH is dropped after the wake. The real tap then counts as #1
        // (because reset() cleared tapCount, and armWakeSwallow doesn't otherwise pre-increment).
        // → confirms the user's intent: "I woke the ring, then tapped Confirm" → fires Confirm, not Back.
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, wakeSwallowCount = 1))
        syn.armWakeSwallow()
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)      // wake — swallowed
        syn.onRaw(RawGesture.TOUCH, nowMs = 200)    // real tap
        sched.advanceBy(500)
        assertEquals(listOf(Gesture.TAP), out)
    }

    @Test fun `armWakeSwallow doesn't break a deliberate double-tap right after wake`() {
        // wake (swallowed) → user actually wanted a double-tap as their first action.
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, wakeSwallowCount = 1))
        syn.armWakeSwallow()
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)      // swallowed
        syn.onRaw(RawGesture.TOUCH, nowMs = 200)    // tap #1
        syn.onRaw(RawGesture.TOUCH, nowMs = 350)    // tap #2 (within multi-tap window from #1)
        sched.advanceBy(500)
        assertEquals(listOf(Gesture.DOUBLE_TAP), out)
    }

    @Test fun `wakeSwallowCount=2 covers devices that report both wake-touches`() {
        // Some firmwares deliver BOTH touches of the double-tap-to-wake. Configure accordingly.
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, wakeSwallowCount = 2))
        syn.armWakeSwallow()
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)      // wake #1 — swallowed
        syn.onRaw(RawGesture.TOUCH, nowMs = 150)    // wake #2 — swallowed
        syn.onRaw(RawGesture.TOUCH, nowMs = 400)    // real tap (outside multi-tap window from previous swallow's lastTapAtMs)
        sched.advanceBy(500)
        assertEquals(listOf(Gesture.TAP), out)
    }

    @Test fun `reset clears all pending state`() {
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.reset()
        sched.advanceBy(500)
        assertEquals(emptyList<Gesture>(), out)
    }

    // ── dedup at the synth layer (the BLE layer is the primary place — this is defence in depth) ─

    @Test fun `minRawIntervalMs drops implausibly fast repeats`() {
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, minRawIntervalMs = 50))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 20)   // 20ms — implausibly fast, treated as dupe
        sched.advanceBy(500)
        assertEquals(listOf(Gesture.TAP), out)    // only one TAP (not DOUBLE_TAP)
    }

    // ── QUADRUPLE_TAP (§24) ──────────────────────────────────────────────────────────────────────

    // QUADRUPLE_TAP is disabled by default now (2026-05-28); these exercise the still-present
    // synth path by explicitly re-enabling the flag.
    @Test fun `quadruple tap emits QUADRUPLE_TAP and nothing else`() {
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, enableDoubleTapSwipe = true, enableQuadrupleTap = true))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 100)
        syn.onRaw(RawGesture.TOUCH, nowMs = 200)
        syn.onRaw(RawGesture.TOUCH, nowMs = 300)
        sched.advanceBy(500)
        assertEquals(listOf(Gesture.QUADRUPLE_TAP), out)
    }

    @Test fun `five taps cap to QUADRUPLE_TAP`() {
        // Counts above 4 are clamped — extra taps don't roll over into a fresh TAP.
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, enableDoubleTapSwipe = true, enableQuadrupleTap = true))
        repeat(5) { syn.onRaw(RawGesture.TOUCH, nowMs = it * 100L) }
        sched.advanceBy(500)
        assertEquals(listOf(Gesture.QUADRUPLE_TAP), out)
    }

    @Test fun `with double-combo on + quad disabled, 4 taps collapse to TRIPLE_TAP`() {
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, enableDoubleTapSwipe = true))
        repeat(4) { syn.onRaw(RawGesture.TOUCH, nowMs = it * 100L) }
        sched.advanceBy(500)
        assertEquals(listOf(Gesture.TRIPLE_TAP), out)
    }

    @Test fun `with enableQuadrupleTap=false a 4th tap collapses to TRIPLE_TAP`() {
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false, enableDoubleTapSwipe = true, enableTripleTap = true, enableQuadrupleTap = false,
        ))
        syn.onRaw(RawGesture.TOUCH, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 100)
        syn.onRaw(RawGesture.TOUCH, nowMs = 200)
        syn.onRaw(RawGesture.TOUCH, nowMs = 300)
        sched.advanceBy(500)
        assertEquals(listOf(Gesture.TRIPLE_TAP), out)
    }

    // ── LONG_PRESS_SWIPE_* combos (§24) ──────────────────────────────────────────────────────────

    @Test fun `long-press then swipe up within window emits LONG_PRESS_SWIPE_UP only`() {
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false, awaitLongPressCombos = true, longPressFollowupWindowMs = 400,
        ))
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 0)
        syn.onRaw(RawGesture.SWIPE_UP, nowMs = 200)
        sched.advanceBy(1_000)
        assertEquals(listOf(Gesture.LONG_PRESS_SWIPE_UP), out)   // no bare LONG_PRESS alongside
    }

    @Test fun `long-press then swipe down within window emits LONG_PRESS_SWIPE_DOWN only`() {
        // The default sleep gesture.
        val (syn, sched, out) = fixture(GestureConfig(
            optimisticSingleTap = false, awaitLongPressCombos = true, longPressFollowupWindowMs = 400,
        ))
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 0)
        syn.onRaw(RawGesture.SWIPE_DOWN, nowMs = 100)
        sched.advanceBy(1_000)
        assertEquals(listOf(Gesture.LONG_PRESS_SWIPE_DOWN), out)
    }

    @Test fun `bare long-press commits after follow-up window expires`() {
        val (syn, sched, out) = fixture(GestureConfig(awaitLongPressCombos = true, longPressFollowupWindowMs = 400))
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 0)
        sched.advanceBy(399)
        assertEquals(emptyList<Gesture>(), out)
        sched.advanceBy(2)
        assertEquals(listOf(Gesture.LONG_PRESS), out)
    }

    @Test fun `with awaitLongPressCombos=false long-press fires immediately (Fast profile)`() {
        val (syn, _, out) = fixture(GestureConfig(awaitLongPressCombos = false))
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 0)
        assertEquals(listOf(Gesture.LONG_PRESS), out)
    }

    @Test fun `long-press then late swipe is two separate gestures`() {
        // Swipe arriving AFTER the follow-up window → bare LONG_PRESS already committed, then SWIPE.
        val (syn, sched, out) = fixture(GestureConfig(awaitLongPressCombos = true, longPressFollowupWindowMs = 400))
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 0)
        sched.advanceBy(450)                            // window expires → LONG_PRESS fires
        syn.onRaw(RawGesture.SWIPE_UP, nowMs = sched.nowMs())
        assertEquals(listOf(Gesture.LONG_PRESS, Gesture.SWIPE_UP), out)
    }

    @Test fun `long-press then quick TOUCH commits LONG_PRESS first then counts the touch`() {
        val (syn, sched, out) = fixture(GestureConfig(optimisticSingleTap = false, awaitLongPressCombos = true))
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 0)
        syn.onRaw(RawGesture.TOUCH, nowMs = 100)        // arrives during LP follow-up window
        sched.advanceBy(1_000)
        // The TOUCH commits the bare LONG_PRESS (it's not a swipe → no combo), then becomes a TAP.
        assertEquals(listOf(Gesture.LONG_PRESS, Gesture.TAP), out)
    }

    // ── DOUBLE_LONG_PRESS (§24) ──────────────────────────────────────────────────────────────────

    @Test fun `two long-presses within follow-up window emit DOUBLE_LONG_PRESS`() {
        val (syn, sched, out) = fixture(GestureConfig(awaitLongPressCombos = true, enableDoubleLongPress = true))
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 0)
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 200)
        sched.advanceBy(1_000)
        assertEquals(listOf(Gesture.DOUBLE_LONG_PRESS), out)   // no bare LONG_PRESS alongside
    }

    @Test fun `with enableDoubleLongPress=false a 2nd long-press commits 1st and starts a new window`() {
        val (syn, sched, out) = fixture(GestureConfig(
            awaitLongPressCombos = true, enableDoubleLongPress = false, longPressFollowupWindowMs = 400,
        ))
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 0)
        syn.onRaw(RawGesture.LONG_PRESS, nowMs = 200)
        sched.advanceBy(1_000)
        // 1st committed when the 2nd arrived; 2nd's own follow-up window expired → bare LONG_PRESS.
        assertEquals(listOf(Gesture.LONG_PRESS, Gesture.LONG_PRESS), out)
    }
}
