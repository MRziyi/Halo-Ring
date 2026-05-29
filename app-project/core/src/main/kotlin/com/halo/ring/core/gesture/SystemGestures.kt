package com.halo.ring.core.gesture

import com.halo.ring.core.action.GlassAction

/**
 * Five global gestures that intercept *before* the active profile (and before any modal). Persisted
 * as user settings. Defaults from R08-Remote-Design.md §23.3.
 *
 * - [wake]            : when **screen off**, triggers [GlassAction.ScreenWake]; everything else is
 *                       dropped. Default = [Gesture.DOUBLE_TAP] (user 2026-05-28). DOUBLE_TAP can't
 *                       match on the raw fast path, so the router lets the synthesizer run while the
 *                       screen is off and fires wake when it emits DOUBLE_TAP (see InteractionRouter).
 * - [sleep]           : when **screen on**, this gesture turns the screen off. Default
 *                       [Gesture.LONG_PRESS].
 * - [profileCycle]    : cycle profiles. Default `null` — profiles are inferred fully automatically
 *                       from the foreground app; there is no manual switch (user 2026-05-28).
 * - [peekHud]         : show the status HUD overlay. Default `null` (QUADRUPLE_TAP removed 2026-05-28).
 * - [aiAssistant]     : if set, [GlassAction.WakeSystemAI] (Sprite AI via ACTION_AI_START). Default
 *                       `null` — TRIPLE_TAP is reserved for the user's own future custom binding.
 *
 * Empty `wake`/`sleep` ⇒ feature disabled (still possible via the OS auto-sleep timer).
 */
data class SystemGestures(
    // user 2026-05-28: wake the screen with DOUBLE_TAP (not long-press). When the screen is off the
    // synthesizer runs and a DOUBLE_TAP fires ScreenWake; when on, DOUBLE_TAP is the base Back. (A
    // double-tap can't match on the raw fast path, so the router lets the synth handle it — see
    // InteractionRouter.onRawWhileScreenOff / onGesture.)
    val wake: Gesture?            = Gesture.DOUBLE_TAP,
    // LONG_PRESS turns the screen off (when on). Sleep is very common; LONG_PRESS→Menu was a no-op
    // on Rokid anyway. The system layer runs before the profile, so this SHADOWS any per-profile
    // LONG_PRESS binding while the screen is on (rebind the SLEEP slot to restore it).
    val sleep: Gesture?           = Gesture.LONG_PRESS,
    // No gesture for profile-cycle: profiles are inferred fully automatically from the foreground
    // app (user 2026-05-28: "全部由系统自动推断，用户手动不能切换"). Peek-HUD's gesture (QUADRUPLE_TAP)
    // was removed too. TRIPLE_TAP is intentionally left UNBOUND — reserved for the user's own future
    // custom binding ("三击操作留空吧，我之后会预定义"). The everyday AI lives on DOUBLE_TAP_SWIPE_DOWN.
    val profileCycle: Gesture?    = null,
    val peekHud: Gesture?         = null,
    val aiAssistant: Gesture?     = null,
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
