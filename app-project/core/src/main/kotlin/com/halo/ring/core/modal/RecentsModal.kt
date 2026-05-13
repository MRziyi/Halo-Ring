package com.halo.ring.core.modal

import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.action.ModalSentinel
import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.Modal

/**
 * "Recent tasks" modal (Doc/05 §6).
 *
 * The shell sequence we expect:
 *  - on entry → emit one `Recents` (so the system recents screen is showing)
 *  - while active:
 *     - `SWIPE_UP`   → `NavPrev` (focus the previous task)
 *     - `SWIPE_DOWN` → `NavNext`
 *     - `TAP`        → close + activate (the focused task is selected; we emit a final
 *                       `Confirm` then exit)
 *     - `DOUBLE_TAP` → cancel (no task selected; route via [ModalSentinel.Cancel])
 *  - 5-second timeout (longer than Volume because picking a recent task takes more effort).
 */
class RecentsModal(
    override val timeoutMs: Long = 5_000,
) : Modal {

    override fun onEnter(): GlassAction = GlassAction.Recents

    override fun handle(gesture: Gesture): GlassAction = when (gesture) {
        Gesture.SWIPE_UP   -> GlassAction.NavPrev
        Gesture.SWIPE_DOWN -> GlassAction.NavNext
        // Fire Confirm AND exit the modal so the user is dropped into the selected task. See
        // [ModalSentinel.FireAndExit] for the router-side handling.
        Gesture.TAP        -> ModalSentinel.FireAndExit(GlassAction.Confirm)
        Gesture.DOUBLE_TAP -> ModalSentinel.Cancel
        else               -> GlassAction.None
    }
}
