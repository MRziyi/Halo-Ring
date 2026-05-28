package com.halo.ring.core.gesture

/**
 * Strategy for delivering an Android `KeyEvent` (identified by its `KEYCODE_*` int) into the
 * active app's key-event pipeline. Implementations:
 *
 *  - **In-app foreground** ([com.halo.ring.ui.AndroidActivitySystemKeyDispatcher]): dispatches
 *    via `Activity.window.decorView.dispatchKeyEvent` — Compose handles `DPAD_CENTER` /
 *    `DPAD_LEFT/RIGHT` / `BACK` on the focused element natively, no agent required.
 *  - **Out-of-app** (future): routes through the [com.halo.ring.core.inject.ExecutorBackend]
 *    ladder so the agent injects via `InputManager.injectInputEvent` system-wide.
 *  - **JVM fake** ([NoopSystemKeyDispatcher]): default for tests.
 *
 * The KeyEvent itself is described by the `KEYCODE_*` integer (e.g. `0x17` for
 * `KEYCODE_DPAD_CENTER`, `0x04` for `KEYCODE_BACK`, `0x15` for `KEYCODE_DPAD_LEFT`, `0x16` for
 * `KEYCODE_DPAD_RIGHT`). We keep `:core` Android-free, so we don't import `android.view.KeyEvent`
 * here — caller passes the integer.
 *
 * Returns true if any dispatcher in the chain consumed the event. Most callers can ignore the
 * return — the gesture pipeline doesn't fall back.
 */
fun interface SystemKeyDispatcher {
    fun dispatch(keyCode: Int): Boolean
}

/** Default — used by [com.halo.ring.core.ble.FakeR08BleClient]-driven tests and during the
 *  brief window between [com.halo.ring.MainActivity.onPause] and the next `onResume` when no
 *  activity is attached. */
object NoopSystemKeyDispatcher : SystemKeyDispatcher {
    override fun dispatch(keyCode: Int): Boolean = false
}

/**
 * Maps a `RawGesture` to the corresponding Android `KEYCODE_*` integer per the v0.3 P1 design
 * (Doc/19 §3.P1). Honours [GestureConfig.useSystemKeyEvents] (caller checks) and
 * [GestureConfig.reverseSwipeSemantics] (this function reads).
 *
 * Returns `null` when the gesture has no system-KeyEvent mapping — caller should fall through
 * to the per-profile [com.halo.ring.core.action.GlassAction] dispatch. Today: only the 4 BASE
 * gestures have mappings; everything else (triple/quadruple-tap, long-press, all combos) is
 * profile-bound.
 */
object SystemKeyMapping {
    // Mirrors android.view.KeyEvent constants. Hard-coded here so :core stays JVM-only.
    const val KEYCODE_BACK         = 4
    const val KEYCODE_DPAD_UP      = 19
    const val KEYCODE_DPAD_DOWN    = 20
    const val KEYCODE_DPAD_LEFT    = 21
    const val KEYCODE_DPAD_RIGHT   = 22
    const val KEYCODE_DPAD_CENTER  = 23

    /**
     * Base-gesture → KEYCODE(s) mapping. Returns a **list** because a swipe dispatches both a
     * vertical and a horizontal DPAD key (see below); TAP / DOUBLE_TAP are single-key.
     *
     *   - TAP        → [DPAD_CENTER]  (Compose `clickable` fires onClick; on Rokid the temple
     *                  click actually emits KEYCODE_ENTER which clickable ALSO handles — verified
     *                  on R08_E600 / RG_glasses 2026-05-27)
     *   - DOUBLE_TAP → [BACK]
     *   - SWIPE_UP   → [DPAD_UP, DPAD_LEFT]    ("前滑 = 上一项 / 左一项")
     *   - SWIPE_DOWN → [DPAD_DOWN, DPAD_RIGHT] ("下滑 = 下一项 / 右一项")
     *
     * **Why both axes** (on-glasses fix 2026-05-27): the ring physically has only up/down swipes,
     * but the foreground UI may be a vertical list (home, notifications, in-app) OR a horizontal
     * grid (the Sprite Launcher app list). Sending one vertical + one horizontal "previous"/"next"
     * key per swipe navigates either orientation — only the axis with a focusable neighbour moves,
     * the other no-ops. This matches the Rokid temple swipe, which emits the same dual-axis
     * sequence; without the horizontal key, swiping in the app list did nothing.
     * [GestureConfig.reverseSwipeSemantics] flips both axes for users who read "swipe up = next".
     *
     * Caller must check [GestureConfig.useSystemKeyEvents] before calling — when false, every
     * gesture (including the 4 base ones) goes through the profile map instead.
     */
    fun forBaseGesture(gesture: Gesture, config: GestureConfig): List<Int> = when (gesture) {
        Gesture.TAP        -> listOf(KEYCODE_DPAD_CENTER)
        Gesture.DOUBLE_TAP -> listOf(KEYCODE_BACK)
        Gesture.SWIPE_UP   -> if (config.reverseSwipeSemantics) listOf(KEYCODE_DPAD_DOWN, KEYCODE_DPAD_RIGHT)
                              else                              listOf(KEYCODE_DPAD_UP, KEYCODE_DPAD_LEFT)
        Gesture.SWIPE_DOWN -> if (config.reverseSwipeSemantics) listOf(KEYCODE_DPAD_UP, KEYCODE_DPAD_LEFT)
                              else                              listOf(KEYCODE_DPAD_DOWN, KEYCODE_DPAD_RIGHT)
        else               -> emptyList()   // custom gestures stay on the profile path
    }
}
