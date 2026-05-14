package com.halo.ring.core.gesture

import com.halo.ring.core.action.GlassAction

/**
 * Five global gestures that intercept *before* the active profile (and before any modal). Persisted
 * as user settings. Defaults from R08-Remote-Design.md §23.3.
 *
 * - [wake]            : when **screen off**, this gesture is recognised on a fast path and triggers
 *                       [GlassAction.ScreenWake]. All other gestures are silently dropped.
 *                       Default = [Gesture.LONG_PRESS] — fast (no synthesizer window), distinct,
 *                       and importantly doesn't collide with the ring's own "double-tap to wake"
 *                       (wake-swallow only consumes TOUCH events, not LONG_PRESS).
 * - [sleep]           : when **screen on**, this gesture turns the screen off. Defaults to
 *                       [Gesture.LONG_PRESS_SWIPE_DOWN] — deliberate, no false positives.
 * - [profileCycle]    : cycle to the next [com.halo.ring.core.action.KeyMapProfile]. Default
 *                       [Gesture.TRIPLE_TAP]. Also triggers the 5s manual-lock for auto-switch.
 * - [peekHud]         : show the status HUD overlay for ~2s. Read-only — no state change. Default
 *                       [Gesture.QUADRUPLE_TAP].
 * - [aiAssistant]     : wake the everyday voice / chat AI ([GlassAction.OpenAIAssistant]). Default
 *                       [Gesture.DOUBLE_LONG_PRESS] — high-value, deliberate, low-false-positive.
 *                       **Replaced the legacy `forceReconnect` system slot in audit-pass
 *                       2026-05-14w**: BLE auto-reconnect handles 99% of disconnects, so the slot
 *                       is better spent on a high-value daily action. Force-reconnect lives on as
 *                       a button in Settings → Ring (it's a once-in-a-blue-moon recovery action,
 *                       not a gesture-class operation).
 *
 * Empty `wake`/`sleep` ⇒ feature disabled (still possible via the OS auto-sleep timer).
 */
data class SystemGestures(
    val wake: Gesture?            = Gesture.LONG_PRESS,
    val sleep: Gesture?           = Gesture.LONG_PRESS_SWIPE_DOWN,
    val profileCycle: Gesture?    = Gesture.TRIPLE_TAP,
    val peekHud: Gesture?         = Gesture.QUADRUPLE_TAP,
    val aiAssistant: Gesture?     = Gesture.DOUBLE_LONG_PRESS,
) {
    /** Used by the settings UI for "is this slot pointing at <gesture>?" lookups. */
    fun gestureFor(slot: Slot): Gesture? = when (slot) {
        Slot.WAKE            -> wake
        Slot.SLEEP           -> sleep
        Slot.PROFILE_CYCLE   -> profileCycle
        Slot.PEEK_HUD        -> peekHud
        Slot.AI_ASSISTANT    -> aiAssistant
    }

    fun withSlot(slot: Slot, gesture: Gesture?): SystemGestures = when (slot) {
        Slot.WAKE            -> copy(wake = gesture)
        Slot.SLEEP           -> copy(sleep = gesture)
        Slot.PROFILE_CYCLE   -> copy(profileCycle = gesture)
        Slot.PEEK_HUD        -> copy(peekHud = gesture)
        Slot.AI_ASSISTANT    -> copy(aiAssistant = gesture)
    }

    /** The 5 slots in display order (Doc/05 §5). Kept here so the UI and core agree on ordering. */
    enum class Slot { WAKE, SLEEP, PROFILE_CYCLE, PEEK_HUD, AI_ASSISTANT }

    /**
     * Returns the slot that's already bound to [gesture], or null if it's free. Used by the
     * settings UI to surface a conflict warning before the user picks a duplicate.
     *
     * [exclude] lets the caller skip a slot (the one they're about to rebind) so picking the same
     * gesture for a slot it's already bound to doesn't fire a self-conflict.
     */
    fun conflict(gesture: Gesture, exclude: Slot? = null): Slot? {
        for (s in Slot.values()) {
            if (s == exclude) continue
            if (gestureFor(s) == gesture) return s
        }
        return null
    }
}
