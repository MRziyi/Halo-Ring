package com.halo.ring.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.halo.ring.core.gesture.Gesture

/**
 * v0.4 — distinct audio feedback per gesture (user ask 2026-05-27: "不同操作要有不同的音效").
 *
 * On AR glasses the wearer's eyes are often off the display, so an **audibly distinct** confirmation
 * per gesture lets them tell what was recognised without looking. Each gesture gets its own
 * [ToneGenerator] tone (pitch/length differ enough to distinguish by ear). Gated by the
 * "UI click sound" feedback pref.
 *
 * The [ToneGenerator] reserves an audio session, so it's lazily created on first use and released
 * via [release]. All playback is fire-and-forget and non-blocking (ToneGenerator plays async).
 */
object GestureSounds {

    @Volatile var enabled: Boolean = false

    private var tone: ToneGenerator? = null

    private fun gen(): ToneGenerator? {
        tone?.let { return it }
        return try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, /* volume 0-100 */ 60).also { tone = it }
        } catch (e: RuntimeException) {
            Log.w("GestureSounds", "ToneGenerator unavailable: ${e.message}")
            null
        }
    }

    /**
     * Play the tone for [gesture]. Distinct, short tones — the wearer learns "high blip = tap,
     * descending = back, ascending = forward swipe", etc. Tones chosen from ToneGenerator's
     * DTMF/supervisory set for audible separation.
     */
    fun play(gesture: Gesture) {
        if (!enabled) return
        val g = gen() ?: return
        // Direct startTone per branch (avoids a Pair-destructure inference quirk). Tones chosen
        // from ToneGenerator's DTMF/supervisory set for audible separation — the wearer learns
        // "high blip = tap, low = swipe-down, sustained = long-press", etc.
        try {
            when (gesture) {
                Gesture.TAP                   -> g.startTone(ToneGenerator.TONE_PROP_BEEP, 40)    // single crisp blip
                Gesture.DOUBLE_TAP            -> g.startTone(ToneGenerator.TONE_PROP_BEEP2, 80)   // back — double blip
                Gesture.TRIPLE_TAP            -> g.startTone(ToneGenerator.TONE_PROP_ACK, 120)    // profile cycle — ack chirp
                Gesture.QUADRUPLE_TAP         -> g.startTone(ToneGenerator.TONE_PROP_PROMPT, 120) // peek HUD — prompt
                Gesture.SWIPE_UP              -> g.startTone(ToneGenerator.TONE_DTMF_1, 50)        // forward/up — higher
                Gesture.SWIPE_DOWN            -> g.startTone(ToneGenerator.TONE_DTMF_4, 50)        // back/down — lower
                Gesture.LONG_PRESS            -> g.startTone(ToneGenerator.TONE_SUP_RINGTONE, 90)  // distinct sustained
                Gesture.DOUBLE_LONG_PRESS     -> g.startTone(ToneGenerator.TONE_PROP_NACK, 140)    // strong
                Gesture.DOUBLE_TAP_SWIPE_UP   -> g.startTone(ToneGenerator.TONE_DTMF_3, 70)
                Gesture.DOUBLE_TAP_SWIPE_DOWN -> g.startTone(ToneGenerator.TONE_DTMF_6, 70)
                Gesture.LONG_PRESS_SWIPE_UP   -> g.startTone(ToneGenerator.TONE_DTMF_7, 70)
                Gesture.LONG_PRESS_SWIPE_DOWN -> g.startTone(ToneGenerator.TONE_DTMF_9, 70)
                Gesture.WRIST_SHAKE           -> g.startTone(ToneGenerator.TONE_DTMF_5, 150)       // air gesture — distinctive
            }
        } catch (_: RuntimeException) {}
    }

    fun release() {
        try { tone?.release() } catch (_: Throwable) {}
        tone = null
    }
}
