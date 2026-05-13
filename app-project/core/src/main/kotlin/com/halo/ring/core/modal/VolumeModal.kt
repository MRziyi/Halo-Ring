package com.halo.ring.core.modal

import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.action.ModalSentinel
import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.Modal
import com.halo.ring.core.gesture.ModalExitReason

/**
 * "Volume" modal (Doc/05 §6, mockup §3 J). Entered via a profile binding to
 * [GlassAction.EnterVolumeModal]; while active the gesture vocabulary remaps to continuous
 * adjustment.
 *
 * - `SWIPE_UP`   → emit one `VolumeUp`
 * - `SWIPE_DOWN` → emit one `VolumeDown`
 * - `TAP`        → confirm (close the modal)
 * - `DOUBLE_TAP` → cancel (close the modal; routed via [ModalSentinel.Cancel])
 *
 * Every other gesture is ignored (returns [GlassAction.None]) so e.g. an accidental long-press
 * doesn't escape the modal.
 *
 * **Timeout**: 3 seconds of inactivity. The owner (typically the foreground service) calls
 * [Modal.onExit] when the timeout fires and treats the return value as the final action.
 *
 * Stateless apart from the optional `volumePerStep` knob, so trivially testable without scheduling.
 */
class VolumeModal(
    override val timeoutMs: Long = 3_000,
    private val volumePerStep: Int = 1,
) : Modal {

    override fun handle(gesture: Gesture): GlassAction = when (gesture) {
        Gesture.SWIPE_UP   -> GlassAction.VolumeUp
        Gesture.SWIPE_DOWN -> GlassAction.VolumeDown
        Gesture.TAP        -> ModalSentinel.Exit
        Gesture.DOUBLE_TAP -> ModalSentinel.Cancel
        else               -> GlassAction.None
    }

    /** Optional metadata in case a future modal wants finer-grained steps. */
    fun stepSize(): Int = volumePerStep
}

/**
 * Sibling of [VolumeModal] for brightness. Same shape; the keycode-difference happens in the
 * action mapper (`VolumeUp` → KEYCODE_VOLUME_UP; `BrightnessUp` → KEYCODE_BRIGHTNESS_UP / shell).
 */
class BrightnessModal(
    override val timeoutMs: Long = 3_000,
) : Modal {
    override fun handle(gesture: Gesture): GlassAction = when (gesture) {
        Gesture.SWIPE_UP   -> GlassAction.BrightnessUp
        Gesture.SWIPE_DOWN -> GlassAction.BrightnessDown
        Gesture.TAP        -> ModalSentinel.Exit
        Gesture.DOUBLE_TAP -> ModalSentinel.Cancel
        else               -> GlassAction.None
    }
}
