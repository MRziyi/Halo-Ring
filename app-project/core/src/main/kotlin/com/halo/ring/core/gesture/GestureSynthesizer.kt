package com.halo.ring.core.gesture

/**
 * Turns the ring's four raw events ([RawGesture]) into the 12-gesture [Gesture] vocabulary.
 *
 * Design refs: R08-Remote-Design.md §5.2 (original state machine), §15.1 / §20.3 (latency, de-dup),
 * §24 (long-press follow-up combos + quadruple-tap).
 *
 * ## Two follow-up windows
 * - **Tap window** ([GestureConfig.multiTapWindowMs], ~280ms): accumulates consecutive TOUCH events
 *   into a tap count (1=TAP, 2=DOUBLE_TAP, 3=TRIPLE_TAP, 4=QUADRUPLE_TAP).
 * - **Combo window** ([GestureConfig.comboWindowMs], ~300ms): after a DOUBLE_TAP, waits for a
 *   SWIPE → emits DOUBLE_TAP_SWIPE_UP/DOWN and *suppresses* the bare DOUBLE_TAP.
 * - **Long-press follow-up window** ([GestureConfig.longPressFollowupWindowMs], ~400ms): after a
 *   LONG_PRESS, waits for either (a) a SWIPE → LONG_PRESS_SWIPE_UP/DOWN, or (b) another LONG_PRESS
 *   → DOUBLE_LONG_PRESS. Suppresses the bare LONG_PRESS in either case.
 *
 * ## Latency knobs (per profile, via [GestureConfig])
 * - `optimisticSingleTap`: fire TAP on the *first* TOUCH (latency ≈ 0) instead of after the tap
 *   window. Fast double-tap then yields TAP + DOUBLE_TAP — accepted trade-off.
 * - `enableTapSwipe` (单击组合): off → a swipe after a single tap is just the bare swipe (no
 *   TAP_SWIPE_*). DOUBLE_TAP is unaffected — always recognised.
 * - `enableDoubleTapSwipe` (双击组合): off → DOUBLE_TAP fires immediately on the 2nd tap, no
 *   DOUBLE_TAP_SWIPE_*.
 * - `awaitLongPressCombos`: off → LONG_PRESS fires immediately, no long-press combos. Fast profile
 *   uses this.
 *
 * ## Wake swallow
 * [armWakeSwallow] makes the next N TOUCH events disappear (they're the user's "double-tap to wake
 * the ring", not intent). LONG_PRESS is **never** swallowed — that's why it's the wake gesture.
 *
 * ## Threading
 * Not thread-safe. Run [onRaw] / [armWakeSwallow] / [reset] and all [Scheduler] callbacks on the
 * same single looper (in production: [com.halo.ring.runtime.AndroidScheduler]'s HandlerThread).
 */
class GestureSynthesizer(
    config: GestureConfig,
    private val scheduler: Scheduler,
    private val sink: (Gesture) -> Unit,
) {
    var config: GestureConfig = config
        /** Hot-swappable (e.g. when the active profile changes). Pending timers keep their old delays. */
        set(value) { field = value }

    // ── tap state ────────────────────────────────────────────────────────────────────────────────
    private var tapCount = 0
    private var lastTapAtMs = Long.MIN_VALUE
    private var tapTimer: Cancellable = NoopCancellable        // alive while count == 1 (await single) or count ≥ 3 (await triple/quadruple)

    // ── double-tap combo state ──────────────────────────────────────────────────────────────────
    private var inComboWindow = false
    private var comboTimer: Cancellable = NoopCancellable      // alive while a double-tap awaits a possible swipe

    // ── long-press follow-up state ──────────────────────────────────────────────────────────────
    private var inLpFollowupWindow = false
    private var lpFollowupTimer: Cancellable = NoopCancellable // alive while a long-press awaits a possible swipe / 2nd long-press

    // ── wake-swallow ─────────────────────────────────────────────────────────────────────────────
    private var pendingWakeSwallows = 0

    /** Call when the ring has just reconnected / been woken from auto-sleep: the next
     *  [GestureConfig.wakeSwallowCount] TOUCH events will be dropped. LONG_PRESS is not swallowed. */
    fun armWakeSwallow() {
        pendingWakeSwallows = config.wakeSwallowCount
        reset()
    }

    /** Drop all in-flight state (timers, counts). Use on disconnect / mode reset. */
    fun reset() {
        tapTimer.cancel(); tapTimer = NoopCancellable
        comboTimer.cancel(); comboTimer = NoopCancellable
        lpFollowupTimer.cancel(); lpFollowupTimer = NoopCancellable
        tapCount = 0
        lastTapAtMs = Long.MIN_VALUE
        inComboWindow = false
        inLpFollowupWindow = false
    }

    /** Feed one raw event. [nowMs] is a monotonic timestamp from the caller. */
    fun onRaw(raw: RawGesture, nowMs: Long = scheduler.nowMs()) {
        when (raw) {
            RawGesture.LONG_PRESS -> onLongPress()
            RawGesture.SWIPE_UP   -> onSwipe(plain = Gesture.SWIPE_UP,   tapCombo = Gesture.TAP_SWIPE_UP,   doubleTapCombo = Gesture.DOUBLE_TAP_SWIPE_UP,   longPressCombo = Gesture.LONG_PRESS_SWIPE_UP)
            RawGesture.SWIPE_DOWN -> onSwipe(plain = Gesture.SWIPE_DOWN, tapCombo = Gesture.TAP_SWIPE_DOWN, doubleTapCombo = Gesture.DOUBLE_TAP_SWIPE_DOWN, longPressCombo = Gesture.LONG_PRESS_SWIPE_DOWN)
            RawGesture.TOUCH      -> onTouch(nowMs)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun onTouch(nowMs: Long) {
        if (pendingWakeSwallows > 0) {
            pendingWakeSwallows--
            lastTapAtMs = nowMs
            return
        }
        if (config.minRawIntervalMs > 0 && lastTapAtMs != Long.MIN_VALUE &&
            nowMs - lastTapAtMs < config.minRawIntervalMs
        ) return  // implausibly fast repeat → likely BLE re-delivery, ignore

        // A real TOUCH arriving while a long-press is in its follow-up window means the user did
        // "long-press, then tap". Commit the bare LONG_PRESS first, then count the TOUCH.
        if (inLpFollowupWindow) commitPendingLongPress()

        tapTimer.cancel(); tapTimer = NoopCancellable

        tapCount = if (lastTapAtMs != Long.MIN_VALUE && nowMs - lastTapAtMs < config.multiTapWindowMs) {
            tapCount + 1
        } else 1
        lastTapAtMs = nowMs

        when {
            tapCount == 1 -> {
                if (config.optimisticSingleTap) emit(Gesture.TAP)
                tapTimer = scheduler.postDelayed(config.multiTapWindowMs) { onTapWindowExpired(1) }
            }
            tapCount == 2 -> {
                // 双击组合 (enableDoubleTapSwipe) is the master switch for ALL double-tap extensions —
                // both DOUBLE_TAP_SWIPE_* AND TRIPLE_TAP (Zack 2026-05-31). When it's OFF, a DOUBLE_TAP
                // commits the INSTANT the 2nd tap lands: no wait for a follow-up swipe, no wait for a
                // 3rd tap → DOUBLE_TAP = Back is snappy and turning off 双击组合 actually saves time.
                // When ON, hold the window so a swipe → DOUBLE_TAP_SWIPE or a 3rd tap → TRIPLE_TAP.
                if (config.enableDoubleTapSwipe) {
                    inComboWindow = true
                    comboTimer = scheduler.postDelayed(config.comboWindowMs) {
                        if (inComboWindow && tapCount == 2) {
                            inComboWindow = false
                            comboTimer = NoopCancellable
                            tapCount = 0
                            emit(Gesture.DOUBLE_TAP)
                        }
                    }
                } else {
                    tapCount = 0
                    emit(Gesture.DOUBLE_TAP)   // immediate — no triple, no swipe
                }
            }
            tapCount == 3 -> {
                comboTimer.cancel(); comboTimer = NoopCancellable
                inComboWindow = false
                if (config.enableTripleTap || config.enableQuadrupleTap) {
                    // wait for possible 4th
                    tapTimer = scheduler.postDelayed(config.multiTapWindowMs) { onTapWindowExpired(3) }
                } else {
                    tapCount = 0
                    emit(Gesture.DOUBLE_TAP)   // both higher counts disabled — collapse to DT
                }
            }
            tapCount == 4 -> {
                if (config.enableQuadrupleTap) {
                    tapTimer = scheduler.postDelayed(config.multiTapWindowMs) { onTapWindowExpired(4) }
                } else if (config.enableTripleTap) {
                    tapCount = 0; emit(Gesture.TRIPLE_TAP)
                } else {
                    tapCount = 0; emit(Gesture.DOUBLE_TAP)
                }
            }
            else -> {  // tapCount >= 5: ignore further counts (cap at 4)
                tapCount = 4
                tapTimer = scheduler.postDelayed(config.multiTapWindowMs) { onTapWindowExpired(4) }
            }
        }
    }

    private fun onTapWindowExpired(expectedAtLeast: Int) {
        tapTimer = NoopCancellable
        val n = tapCount
        if (n < expectedAtLeast) return  // a faster timer already committed it
        when {
            n == 1 -> {
                tapCount = 0
                if (!config.optimisticSingleTap) emit(Gesture.TAP)  // optimistic already emitted
            }
            n == 2 -> { if (!config.enableDoubleTapSwipe) { tapCount = 0; emit(Gesture.DOUBLE_TAP) } }
            n == 3 -> {
                tapCount = 0
                // Window timed out at count 3 — no 4th coming. Commit the best we can recognize.
                if (config.enableTripleTap) emit(Gesture.TRIPLE_TAP)
                else emit(Gesture.DOUBLE_TAP)
            }
            else -> { // n >= 4
                tapCount = 0
                if (config.enableQuadrupleTap) emit(Gesture.QUADRUPLE_TAP)
                else if (config.enableTripleTap) emit(Gesture.TRIPLE_TAP)
                else emit(Gesture.DOUBLE_TAP)
            }
        }
    }

    private fun onSwipe(plain: Gesture, tapCombo: Gesture, doubleTapCombo: Gesture, longPressCombo: Gesture) {
        // Long-press combo takes priority over double-tap combo (more "specific" sequence).
        if (inLpFollowupWindow) {
            lpFollowupTimer.cancel(); lpFollowupTimer = NoopCancellable
            inLpFollowupWindow = false
            // suppress bare LONG_PRESS; suppress any pending tap/combo too — this is one gesture
            tapTimer.cancel(); tapTimer = NoopCancellable
            comboTimer.cancel(); comboTimer = NoopCancellable
            tapCount = 0; inComboWindow = false
            emit(longPressCombo)
            return
        }
        if (inComboWindow) {
            comboTimer.cancel(); comboTimer = NoopCancellable
            inComboWindow = false
            tapCount = 0
            tapTimer.cancel(); tapTimer = NoopCancellable
            emit(doubleTapCombo)
            return
        }
        // Single-tap-then-swipe → TAP_SWIPE_* (user 2026-05-28). Fires when exactly one TAP is still
        // pending (its multi-tap window hasn't expired) and a swipe arrives. Only when combos are on
        // and the tap is NOT optimistic (optimistic already emitted the bare TAP, so a tap-combo
        // would double-fire). A bare swipe with no pending tap falls through to `plain` (instant —
        // keeps base-gesture nav snappy).
        if (tapCount == 1 && config.enableTapSwipe && !config.optimisticSingleTap) {
            tapTimer.cancel(); tapTimer = NoopCancellable
            tapCount = 0
            lastTapAtMs = Long.MIN_VALUE
            emit(tapCombo)
            return
        }
        flushPendingTapBeforeDefiniteGesture()
        emit(plain)
    }

    private fun onLongPress() {
        if (pendingWakeSwallows > 0) {
            // LONG_PRESS is never swallowed — that's why it can serve as ScreenWake right after
            // the ring's auto-sleep recovers. Just clear the swallow count and proceed.
            pendingWakeSwallows = 0
        }

        // A second LONG_PRESS within the follow-up window of the first → DOUBLE_LONG_PRESS.
        if (inLpFollowupWindow) {
            lpFollowupTimer.cancel(); lpFollowupTimer = NoopCancellable
            inLpFollowupWindow = false
            if (config.enableDoubleLongPress) {
                emit(Gesture.DOUBLE_LONG_PRESS)
                return
            }
            // disabled: commit the first long-press, then fall through and treat this as a new one
            emit(Gesture.LONG_PRESS)
        }

        // Fresh long-press. Flush any pending tap first (preserve order).
        comboTimer.cancel(); comboTimer = NoopCancellable
        inComboWindow = false
        flushPendingTapBeforeDefiniteGesture()

        if (config.awaitLongPressCombos) {
            inLpFollowupWindow = true
            lpFollowupTimer = scheduler.postDelayed(config.longPressFollowupWindowMs) {
                if (inLpFollowupWindow) {
                    inLpFollowupWindow = false
                    lpFollowupTimer = NoopCancellable
                    emit(Gesture.LONG_PRESS)
                }
            }
        } else {
            emit(Gesture.LONG_PRESS)
        }
    }

    /** Commit the bare LONG_PRESS the follow-up window was holding. */
    private fun commitPendingLongPress() {
        lpFollowupTimer.cancel(); lpFollowupTimer = NoopCancellable
        inLpFollowupWindow = false
        emit(Gesture.LONG_PRESS)
    }

    private fun flushPendingTapBeforeDefiniteGesture() {
        tapTimer.cancel(); tapTimer = NoopCancellable
        when {
            tapCount == 1 -> { if (!config.optimisticSingleTap) emit(Gesture.TAP) }
            tapCount == 2 -> { if (!config.enableDoubleTapSwipe) emit(Gesture.DOUBLE_TAP) }
            tapCount == 3 -> { if (config.enableTripleTap) emit(Gesture.TRIPLE_TAP) }
            tapCount >= 4 -> { if (config.enableQuadrupleTap) emit(Gesture.QUADRUPLE_TAP)
                              else if (config.enableTripleTap) emit(Gesture.TRIPLE_TAP) }
        }
        tapCount = 0
    }

    private fun emit(g: Gesture) = sink(g)
}
