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
 *     - `TAP`        → "I want to say a sentence" — caller would start a mic capture. The modal
 *                       returns [GlassAction.OpenChat] as a placeholder; the actual mic capture is
 *                       the caller's job (this modal doesn't have audio APIs).
 *     - `DOUBLE_TAP` → end the conversation ([ModalSentinel.Exit])
 *     - `SWIPE_UP`   → cancel the conversation ([ModalSentinel.Cancel])
 *  - 30-second timeout (longer than other modals because dictation is naturally bursty).
 *
 * ## NOT FULLY WIRED — kept user-invisible
 *
 * The audio-pipeline integration is deferred (no `SpeechRecognizer` or AudioRecord pipe). To stop
 * the action picker from advertising this as a working binding, both flavor mappers
 * ([com.halo.ring.device.rokid.RokidActionMapper.supportLevel] and
 * [com.halo.ring.device.rayneo.RayNeoActionMapper.supportLevel]) override
 * `EnterAIDictateModal` → [com.halo.ring.core.device.GlassActionMapper.SupportLevel.UNSUPPORTED`,
 * which greys it out and shows "(coming soon)" in the picker. When the audio path lands, drop the
 * override and the modal becomes bindable again — the gesture vocabulary above is already correct.
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
