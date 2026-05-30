package com.halo.ring.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.halo.ring.core.gesture.Gesture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Per-gesture audio feedback. On AR glasses the wearer's eyes are often off the display, so an
 * audibly distinct confirmation per gesture lets them tell what was recognised without looking.
 *
 * Short sine **blips** on a C-major-pentatonic ladder (C D E G A) so any sequence sounds harmonious,
 * with a raised-cosine (Hann) envelope so there are no start/stop clicks. Direction is conveyed
 * musically: swipes glide up/down; "back"/cancel fall; confirmations rise.
 *
 * **Playback via [SoundPool] (2026-05-30).** Earlier designs built an [android.media.AudioTrack]
 * per gesture: that cost **~100 ms to create+start** on the glasses (a real "延迟感") and the
 * `getPlaybackHeadPosition` playout-poll was unreliable for short MODE_STATIC buffers, freeing the
 * one-at-a-time guard early so blips overlapped and pumped in volume ("忽大忽小"). SoundPool is the
 * right tool: each of the 16 blips is **pre-rendered + loaded once** at [init], so [play] is a
 * near-zero-latency in-memory trigger, and SoundPool's own mixer handles concurrency cleanly. A
 * small [MIN_INTERVAL_MS] rate-limit drops rapid-burst repeats so they don't stack.
 *
 * Output is **STREAM_MUSIC** (via [AudioAttributes.USAGE_MEDIA]) — the Rokid glasses mute every
 * other stream, so music is the only audible one. Gated by the "UI click sound" pref via [enabled];
 * loudness is [volume] (0..1), tunable live.
 */
object GestureSounds {

    @Volatile var enabled: Boolean = false
    /** Playback volume 0..1. Kept low — the blip was too loud at higher levels on the glasses. */
    @Volatile var volume: Float = 0.16f

    private const val SAMPLE_RATE = 44_100
    /** Peak amplitude of the rendered PCM (headroom below clipping); final loudness is [volume]. */
    private const val AMPLITUDE = 0.7
    private const val TAIL_SILENCE_MS = 16
    /** Drop a repeat that arrives within this of the previous play so rapid bursts don't stack. */
    private const val MIN_INTERVAL_MS = 45L

    @Volatile private var pool: SoundPool? = null
    private val soundIds = HashMap<Gesture, Int>()
    @Volatile private var lastPlayMs = 0L

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

    /** Pre-render + load every blip. Idempotent; call once on service start. */
    @Synchronized
    fun init(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)               // → STREAM_MUSIC (the audible stream)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        val dir = context.cacheDir
        for (g in Gesture.values()) {
            try {
                val f = File(dir, "blip_${g.name}.wav")
                writeWav(f, renderPcm(toneFor(g)))
                soundIds[g] = sp.load(f.absolutePath, 1)
            } catch (e: Exception) {
                Log.w("GestureSounds", "load ${g.name}: ${e.message}")
            }
        }
        pool = sp
    }

    fun play(gesture: Gesture) {
        if (!enabled) return
        val sp = pool ?: return
        val id = soundIds[gesture] ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayMs < MIN_INTERVAL_MS) return           // drop rapid-burst repeats
        lastPlayMs = now
        val v = volume.coerceIn(0f, 1f)
        sp.play(id, v, v, /* priority */ 1, /* loop */ 0, /* rate */ 1f)
    }

    fun release() {
        pool?.release()
        pool = null
        soundIds.clear()
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────────────

    private fun renderPcm(t: Tone): ShortArray {
        val toneFrames = SAMPLE_RATE * t.durMs / 1000
        val tailFrames = SAMPLE_RATE * TAIL_SILENCE_MS / 1000
        val total = (toneFrames + tailFrames).coerceAtLeast(1)
        val pcm = ShortArray(total)                              // [tone | silence]; tail stays zero
        var phase = 0.0
        val denom = (toneFrames - 1).coerceAtLeast(1)
        for (i in 0 until toneFrames) {
            val p = i.toDouble() / denom                         // [0,1] INCLUSIVE → Hann hits exactly
            val freq = t.from + (t.to - t.from) * p               // 0 at both ends (no onset/offset click)
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val env = 0.5 * (1.0 - cos(2.0 * PI * p))             // 0 → 1 → 0
            pcm[i] = (sin(phase) * env * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }
        return pcm
    }

    /** Minimal 16-bit mono PCM WAV (little-endian) for SoundPool to load. */
    private fun writeWav(file: File, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII)); buf.putInt(36 + dataLen)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII)); buf.putInt(16)
        buf.putShort(1)                                          // PCM
        buf.putShort(1)                                          // mono
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2)                              // byte rate (mono, 16-bit)
        buf.putShort(2)                                          // block align
        buf.putShort(16)                                         // bits/sample
        buf.put("data".toByteArray(Charsets.US_ASCII)); buf.putInt(dataLen)
        for (s in pcm) buf.putShort(s)
        file.writeBytes(buf.array())
    }
}
