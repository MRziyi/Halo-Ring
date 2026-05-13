package com.halo.ring.core.modal

import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.action.ModalSentinel
import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.Modal

/**
 * "Continuous AI dictate" modal (Doc/05 §6, mockup §3 J).
 *
 *  - on entry → open the AI chat session (caller emits [GlassAction.OpenChat] via [onEnter])
 *  - while active:
 *     - `TAP`        → "I want to say a sentence" — caller starts a mic capture. The modal returns
 *                       [GlassAction.OpenChat] as a no-op placeholder; the actual mic capture is the
 *                       caller's job (this modal doesn't have audio APIs).
 *     - `DOUBLE_TAP` → end the conversation ([ModalSentinel.Exit])
 *     - `SWIPE_UP`   → cancel the conversation ([ModalSentinel.Cancel])
 *  - 30-second timeout (longer than other modals because dictation is naturally bursty).
 *
 * **Not fully wired**: the audio-pipeline integration is deferred — the modal here is a skeleton
 * that handles the gesture vocabulary correctly. The service layer doesn't yet open a mic.
 */
class AIDictateModal(
    override val timeoutMs: Long = 30_000,
) : Modal {

    override fun onEnter(): GlassAction = GlassAction.OpenChat

    override fun handle(gesture: Gesture): GlassAction = when (gesture) {
        Gesture.TAP        -> GlassAction.OpenChat        // placeholder — see kdoc
        Gesture.DOUBLE_TAP -> ModalSentinel.Exit
        Gesture.SWIPE_UP   -> ModalSentinel.Cancel
        else               -> GlassAction.None
    }
}
