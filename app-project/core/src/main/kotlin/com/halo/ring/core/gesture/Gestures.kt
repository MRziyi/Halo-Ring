package com.halo.ring.core.gesture

/**
 * The four raw events the R08 ring firmware actually reports (notify frame `73 2D <code>`,
 * see R08-Remote-Design.md §3.4). Everything richer than this — double-tap, triple-tap, the
 * long-press combos — is synthesised on our side by [GestureSynthesizer].
 */
enum class RawGesture {
    SWIPE_UP,    // 0x01
    SWIPE_DOWN,  // 0x02
    TOUCH,       // 0x03  (one touch / "tap candidate")
    LONG_PRESS,  // 0x04
}

/**
 * The vocabulary the rest of the app reasons about. 12 gestures total. Five of them are reserved
 * by [com.halo.ring.core.gesture.SystemGestures] as system-level (intercepted before the active
 * profile by [com.halo.ring.core.gesture.InteractionRouter]); the rest go through the profile
 * map ([com.halo.ring.core.action.KeyMapProfile]) or, when a modal is active, through the modal.
 */
enum class Gesture {
    TAP,
    DOUBLE_TAP,              // base = Back; when screen OFF, = system ScreenWake (synthesized)
    TRIPLE_TAP,              // preset default = Screenshot, per-profile rebindable (user 2026-05-29)
    QUADRUPLE_TAP,           // DISABLED (user 2026-05-28: too complex) — never emitted; GestureConfig.enableQuadrupleTap=false
    SWIPE_UP,
    SWIPE_DOWN,
    LONG_PRESS,              // when screen on (Navigation only), = system ScreenSleep
    // Single-tap-then-swipe (user 2026-05-28: "单击上/单击下"). Easier than the double-tap-swipe
    // combos; takes over the primary combo actions (Nav: camera / AI · Media: prev / next track).
    TAP_SWIPE_UP,
    TAP_SWIPE_DOWN,
    DOUBLE_TAP_SWIPE_UP,     // freed to user-custom (was camera)
    DOUBLE_TAP_SWIPE_DOWN,   // freed to user-custom (was AI)
    LONG_PRESS_SWIPE_UP,
    LONG_PRESS_SWIPE_DOWN,
    DOUBLE_LONG_PRESS,

    /**
     * Air gesture from the accelerometer. **Currently UNROUTED** (user 2026-05-28: "暂时去掉甩手").
     * The firmware-native shake (`0x73 sub=0x29`) requires appType=7, which is mutually exclusive
     * with the touch gestures (appType=9) on RT08 — so it can't coexist with the core 4 gestures.
     * The only coexisting source would be the `0xA1` accel stream (energy cost, lower rate); not
     * wired up for now. Kept in the enum (+ AccelProcessor still detects it) for easy future restore.
     * Not bound in any default profile and hidden from the picker.
     */
    WRIST_SHAKE,
}

/**
 * Tunable timing/feature flags for [GestureSynthesizer]. Stored per profile (see §15.1/§20.3/§24).
 *
 * Tap path
 * --------
 * @param multiTapWindowMs  After a TOUCH, how long to wait for the next TOUCH before committing the
 *                          tap-count. ~120ms is the human double-tap floor; ~280ms is comfortable.
 * @param comboWindowMs     After a *double*-tap, how long to wait for a following swipe (→
 *                          DOUBLE_TAP_SWIPE_*). Also delays a bare DOUBLE_TAP by this much if
 *                          `awaitCombos` is true, so keep it modest (~300ms).
 * @param optimisticSingleTap  Emit TAP immediately on the first TOUCH (latency ≈ 0) instead of
 *                          waiting out `multiTapWindowMs`. Trade-off: a fast double-tap then
 *                          produces TAP *then* DOUBLE_TAP.
 * @param awaitCombos       After a double-tap, wait `comboWindowMs` to see whether a swipe follows
 *                          before committing the bare DOUBLE_TAP. Off → DOUBLE_TAP fires
 *                          immediately on the 2nd tap and no DOUBLE_TAP_SWIPE_* is possible.
 * @param enableTripleTap   If false, ≥3 taps collapse to DOUBLE_TAP.
 * @param enableQuadrupleTap If false, ≥4 taps collapse to TRIPLE_TAP (or DOUBLE_TAP if Triple is
 *                          also disabled). Enabled by default — it's the system PeekHud handle.
 *
 * Long-press path
 * ---------------
 * @param awaitLongPressCombos  After a LONG_PRESS, wait `longPressFollowupWindowMs` to see whether
 *                          (a) a SWIPE follows → LONG_PRESS_SWIPE_UP/DOWN, or (b) another
 *                          LONG_PRESS follows → DOUBLE_LONG_PRESS. **Cost**: bare LONG_PRESS is
 *                          delayed by this much. Off → LONG_PRESS fires immediately, no combos
 *                          possible (Fast profile uses this).
 * @param longPressFollowupWindowMs  The follow-up window after a LONG_PRESS. ~400ms is a
 *                          comfortable default.
 * @param enableDoubleLongPress  If false, a 2nd LONG_PRESS within the follow-up window is treated
 *                          as a fresh LONG_PRESS (first one was already committed when the window
 *                          expired … wait, no — see [GestureSynthesizer] for the exact semantics).
 *
 * De-dup / wake
 * -------------
 * @param minRawIntervalMs  Defence-in-depth: drop a TOUCH that arrives within this of the previous
 *                          TOUCH (real fingers can't tap that fast). 0 = disabled. Real de-dup is
 *                          in the BLE layer.
 * @param wakeSwallowCount  After [GestureSynthesizer.armWakeSwallow], swallow this many TOUCH
 *                          events. LONG_PRESS is never swallowed (so it can serve as wake gesture
 *                          even right after ring auto-sleep). Verify count on real hardware.
 */
data class GestureConfig(
    // On-glasses tuning (2026-05-29): 300 → 220 ms. Every bare TAP waits this long to disambiguate
    // from a forming DOUBLE_TAP (= Back), so it's the single biggest everyday-gesture latency. 220 ms
    // shaves 80 ms off every select while still leaving comfortable slack for an intentional
    // double-tap; only an unusually slow double-tap (220–300 ms between taps) risks being read as two
    // singles. Profiles that need instant taps set optimisticSingleTap = true instead (see FAST).
    val multiTapWindowMs: Long = 220,
    // Widened 300 → 400 ms (2026-05-27): the window after a DOUBLE_TAP to land a follow-up swipe.
    // Combos (DOUBLE_TAP_SWIPE) were hard to train at 300 ms — the double-tap committed (= Back!)
    // before the user got the swipe out. 400 ms gives comfortable slack.
    val comboWindowMs: Long = 400,
    val optimisticSingleTap: Boolean = false,
    val awaitCombos: Boolean = true,
    val enableTripleTap: Boolean = true,
    // Disabled by default (user 2026-05-28: "4击用不上，操作太复杂"). ≥4 taps now collapse to
    // TRIPLE_TAP. QUADRUPLE_TAP is no longer emitted, bound, or offered in the picker.
    val enableQuadrupleTap: Boolean = false,

    val awaitLongPressCombos: Boolean = true,
    // On-glasses tuning: 400 → 120 → 280 → 60 → 30 ms. The user kept finding bare LONG_PRESS too
    // slow and asked to halve the follow-up window each time; now 30 ms — effectively instant after
    // the firmware's hold-to-register floor (which is the real, unchangeable latency: the ring only
    // emits 0x73 sub=0x2D LONG_PRESS after the hold completes — SPEC v3 §5.2.1). Trade-off:
    // LONG_PRESS_SWIPE / DOUBLE_LONG_PRESS combos need the follow-up within 30 ms, i.e. off in
    // practice. DOUBLE_TAP_SWIPE combos are unaffected — they use the separate `comboWindowMs`.
    val longPressFollowupWindowMs: Long = 30,
    val enableDoubleLongPress: Boolean = true,

    val minRawIntervalMs: Long = 0,
    val wakeSwallowCount: Int = 1,

    /**
     * v0.3 P1: when true, the 4 BASE gestures (`TAP` / `DOUBLE_TAP` / `SWIPE_UP` / `SWIPE_DOWN`)
     * fire **system KeyEvents** instead of routing through the per-profile [GlassAction] map.
     * This makes the ring a thin extension of the Rokid temple touchpad — wherever a temple
     * gesture works, the ring gesture works identically, no app-specific focus management
     * required.
     *
     * Mapping (per Rokid bare-metal-docs §01-key-events.md):
     * ```
     *   TAP        → KEYCODE_DPAD_CENTER
     *   DOUBLE_TAP → KEYCODE_BACK            (system-reserved on right-temple key)
     *   SWIPE_UP   → KEYCODE_DPAD_RIGHT      (temple "swipe forward" = "next")
     *   SWIPE_DOWN → KEYCODE_DPAD_LEFT       (temple "swipe back"    = "previous")
     * ```
     *
     * **Custom gestures (everything except those four)** continue through the profile mapping
     * unchanged — that's the project's flagship customisation layer.
     *
     * Default `true`: matches the user's "ring = temple extension" invariant for v0.3+.
     * See Doc/19 §3.P1 for the design decision.
     */
    val useSystemKeyEvents: Boolean = true,

    /**
     * v0.3 P1: when [useSystemKeyEvents] is true AND this is true, SWIPE_UP / SWIPE_DOWN swap
     * their KeyEvent mapping:
     * ```
     *   SWIPE_UP   → KEYCODE_DPAD_LEFT
     *   SWIPE_DOWN → KEYCODE_DPAD_RIGHT
     * ```
     * For users who read "up" as "scroll up the page" (prior) rather than the temple's
     * "next-in-sequence" semantics. Default `false`.
     */
    val reverseSwipeSemantics: Boolean = false,
) {
    init {
        require(multiTapWindowMs > 0) { "multiTapWindowMs must be > 0" }
        require(comboWindowMs >= 0)
        require(longPressFollowupWindowMs >= 0)
        require(wakeSwallowCount >= 0)
    }
}
