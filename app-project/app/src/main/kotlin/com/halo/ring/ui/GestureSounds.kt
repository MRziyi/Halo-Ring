package com.halo.ring.ui

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.halo.ring.core.gesture.Gesture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Per-gesture audio feedback. On AR glasses the wearer's eyes are often off the display, so an
 * audibly distinct confirmation per gesture lets them tell what was recognised without looking.
 *
 * v0.5.x rewrite (user: "手势声不好听"): the old [android.media.ToneGenerator] DTMF / supervisory
 * tones sounded like a phone dial-pad / busy-signal. We now synthesise short **sine blips** on a
 * C-major-pentatonic ladder (C D E G A) so any sequence of gestures sounds harmonious, with a
 * raised-cosine envelope so there are no start/stop clicks. Direction is conveyed musically: swipes
 * glide up/down in pitch; "back"/cancel gestures fall; confirmations rise.
 *
 * Each [play] spins up a short-lived [AudioTrack] (static buffer, fire-and-forget) and releases it
 * on a main-looper [Handler] once playback finishes — no shared mutable audio session to leak.
 * Gated by the "UI click sound" feedback pref via [enabled].
 */
object GestureSounds {

    @Volatile var enabled: Boolean = false

    private const val SAMPLE_RATE = 44_100
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // C-major pentatonic across two octaves (Hz). Pleasant in any combination.
    private const val G4 = 392.00
    private const val A4 = 440.00
    private const val C5 = 523.25
    private const val E5 = 659.25
    private const val G5 = 783.99
    private const val A5 = 880.00
    private const val C6 = 1046.50
    private const val E6 = 1318.51

    /** A blip: glide from [from] Hz to [to] Hz over [durMs]. Single pitch when from == to. */
    private data class Tone(val from: Double, val to: Double, val durMs: Int)

    private fun toneFor(g: Gesture): Tone = when (g) {
        // ── base gestures ──
        Gesture.TAP                   -> Tone(E6, E6, 45)       // crisp high blip
        Gesture.DOUBLE_TAP            -> Tone(A5, E5, 90)       // back/cancel — falls
        Gesture.SWIPE_UP              -> Tone(C5, G5, 70)       // glides up
        Gesture.SWIPE_DOWN            -> Tone(G5, C5, 70)       // glides down
        // ── taps ──
        Gesture.TRIPLE_TAP            -> Tone(C5, C6, 110)      // profile cycle — big rise
        Gesture.QUADRUPLE_TAP         -> Tone(G5, G5, 70)       // peek HUD — neutral
        // ── long press ──
        Gesture.LONG_PRESS            -> Tone(A4, A4, 110)      // low, sustained
        Gesture.DOUBLE_LONG_PRESS     -> Tone(A4, E5, 140)      // low → rising, "strong"
        // ── tap-swipe (primary combos) ──
        Gesture.TAP_SWIPE_UP          -> Tone(E5, C6, 80)       // rising chirp
        Gesture.TAP_SWIPE_DOWN        -> Tone(E5, G4, 80)       // falling chirp
        // ── double-tap-swipe (custom) ──
        Gesture.DOUBLE_TAP_SWIPE_UP   -> Tone(G5, C6, 90)
        Gesture.DOUBLE_TAP_SWIPE_DOWN -> Tone(G5, G4, 90)
        Gesture.LONG_PRESS_SWIPE_UP   -> Tone(A4, A5, 110)
        Gesture.LONG_PRESS_SWIPE_DOWN -> Tone(A5, A4, 110)
        // ── air gesture ──
        Gesture.WRIST_SHAKE           -> Tone(G5, C6, 70)       // bright, distinctive flick-up
    }

    fun play(gesture: Gesture) {
        if (!enabled) return
        try {
            playBlip(toneFor(gesture))
        } catch (e: RuntimeException) {
            Log.w("GestureSounds", "blip failed: ${e.message}")
        }
    }

    private fun playBlip(t: Tone) {
        val frames = SAMPLE_RATE * t.durMs / 1000
        if (frames <= 0) return
        val pcm = ShortArray(frames)
        // Phase-continuous linear pitch glide (integrate instantaneous frequency) + raised-cosine
        // (Hann) amplitude envelope so there's no click at onset/offset.
        var phase = 0.0
        for (i in 0 until frames) {
            val p = i.toDouble() / frames
            val freq = t.from + (t.to - t.from) * p
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val env = 0.5 * (1.0 - cos(2.0 * PI * p))   // 0 → 1 → 0
            pcm[i] = (sin(phase) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
        }

        val track = AudioTrack(
            AudioManager.STREAM_NOTIFICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            frames * 2,
            AudioTrack.MODE_STATIC,
        )
        track.write(pcm, 0, frames)
        track.play()
        // Release just after the blip finishes; the static buffer plays out independently.
        handler.postDelayed({
            try { track.stop() } catch (_: IllegalStateException) {}
            try { track.release() } catch (_: Throwable) {}
        }, (t.durMs + 60).toLong())
    }

    /** No persistent resources in the synth path — tracks self-release. Kept for the existing
     *  service-teardown call site. */
    fun release() { /* no-op */ }
}
