package com.halo.ring.core.sensor

import com.halo.ring.core.ble.RingEvent
import kotlin.math.abs

/**
 * Pure-JVM stateful processor for the 3-axis accelerometer telemetry stream from the ring
 * (`0xA1` ch3 — SPEC v3 §6.3). Fed one [RingEvent.AccelSample] at a time; emits higher-level
 * events ([SpatialEvent]) for posture / free-fall / wrist-shake.
 *
 * No coroutines, no Android — depends only on Kotlin stdlib so it's trivially unit-testable.
 *
 * ## Detection algorithms
 *
 * **Posture** — classify the dominant gravity axis. A ring is worn on a finger, so:
 *   - `palmDown` = +X gravity (finger held horizontally, palm down)
 *   - `palmUp`   = -X gravity (finger horizontally, palm up — checking a watch)
 *   - `pointForward` = +Z (pointing finger ahead — gestural direction)
 *   - `pointBack`    = -Z
 *   - `pointDown`    = +Y (arm relaxed at side, finger pointing down)
 *   - `pointUp`      = -Y (arm raised, finger pointing up)
 *
 * The axis-to-feature mapping is provisional per SPEC §6.3 — the 6-orientation calibration only
 * narrowed it down approximately. UI exposes a one-tap calibration that recalibrates the +X axis
 * to whatever orientation the user is in when they tap.
 *
 * **Free-fall** — magnitude < [FREE_FALL_MAG_THRESHOLD] for at least [FREE_FALL_MIN_SAMPLES] in a
 * row. At ~4 Hz stream rate that's ~100 ms — physically valid for "ring dropped from finger".
 *
 * **Impact** — magnitude spike above [IMPACT_MAG_THRESHOLD] in a single sample. Often follows
 * free-fall but can also fire from a sharp tap on a surface.
 *
 * **Wrist-shake** — rapid sign changes in a single axis across [WRIST_SHAKE_WINDOW] consecutive
 * samples. Bridges to the firmware-side `0x73 sub=0x29 RING_GAME_KEY` (when `appType=7` is set);
 * we run our own detector too in case the user wants shake input without enabling Game mode.
 */
class AccelProcessor(
    private val emit: (SpatialEvent) -> Unit,
) {
    sealed interface SpatialEvent {
        /** Posture changed — published only when the dominant-axis classification crosses a band
         *  (avoids spamming an event every sample). */
        data class PostureChanged(val posture: Posture) : SpatialEvent

        /** Ring is in free-fall — sustained magnitude near zero. The HUD can warn "did you drop
         *  the ring?" since the ring has no on-finger sensor to confirm. */
        data object FreeFall : SpatialEvent

        /** Impact / shock — sharp magnitude spike. Often follows [FreeFall]. */
        data class Impact(val peakG: Float) : SpatialEvent

        /** Wrist shake — rapid axis-sign reversal over the recent window. */
        data object WristShake : SpatialEvent
    }

    enum class Posture {
        UNKNOWN, PALM_UP, PALM_DOWN, POINT_UP, POINT_DOWN, POINT_FORWARD, POINT_BACK
    }

    private var lastPosture: Posture = Posture.UNKNOWN
    private var freeFallSamples = 0
    /** Ring buffer of recent X-sign values, used by [updateShakeDetector]. */
    private val xSignWindow = IntArray(WRIST_SHAKE_WINDOW)
    private var xSignWindowIdx = 0
    private var xSignWindowFilled = 0
    /** -∞ sentinel: ensures the cooldown check passes for the very first detected shake. */
    private var lastShakeEmittedAtSample = Long.MIN_VALUE / 2
    private var sampleCount = 0L

    fun onSample(s: RingEvent.AccelSample) {
        sampleCount++
        val mag = s.magnitude

        // Free-fall: sustained near-zero magnitude.
        if (mag < FREE_FALL_MAG_THRESHOLD) {
            freeFallSamples++
            if (freeFallSamples == FREE_FALL_MIN_SAMPLES) emit(SpatialEvent.FreeFall)
        } else {
            freeFallSamples = 0
        }

        // Impact: single-sample magnitude spike (typically right after a free-fall).
        if (mag > IMPACT_MAG_THRESHOLD) emit(SpatialEvent.Impact(mag))

        // Posture classification.
        val newPosture = classifyPosture(s)
        if (newPosture != Posture.UNKNOWN && newPosture != lastPosture) {
            lastPosture = newPosture
            emit(SpatialEvent.PostureChanged(newPosture))
        }

        // Wrist-shake detector.
        updateShakeDetector(s)
    }

    private fun classifyPosture(s: RingEvent.AccelSample): Posture {
        // Find the axis with the largest absolute g — that's the one aligned with gravity.
        val absX = abs(s.xG); val absY = abs(s.yG); val absZ = abs(s.zG)
        // Require a confident dominant axis (>= 0.7 g) so transient motion doesn't flip the
        // classification on every sample.
        val dominant = maxOf(absX, absY, absZ)
        if (dominant < POSTURE_MIN_DOMINANT_G) return Posture.UNKNOWN
        return when {
            absX >= absY && absX >= absZ -> if (s.xG > 0) Posture.PALM_DOWN else Posture.PALM_UP
            absY >= absZ                  -> if (s.yG > 0) Posture.POINT_DOWN else Posture.POINT_UP
            else                          -> if (s.zG > 0) Posture.POINT_FORWARD else Posture.POINT_BACK
        }
    }

    private fun updateShakeDetector(s: RingEvent.AccelSample) {
        // Only the X axis used for now — the ring's natural shake axis is along the finger.
        val sign = when {
            s.xG >  SHAKE_AXIS_THRESHOLD -> 1
            s.xG < -SHAKE_AXIS_THRESHOLD -> -1
            else                          -> 0
        }
        xSignWindow[xSignWindowIdx] = sign
        xSignWindowIdx = (xSignWindowIdx + 1) % WRIST_SHAKE_WINDOW
        if (xSignWindowFilled < WRIST_SHAKE_WINDOW) xSignWindowFilled++

        if (xSignWindowFilled < WRIST_SHAKE_WINDOW) return
        // Count sign reversals: how many adjacent samples differ in non-zero sign.
        var reversals = 0
        var prev = 0
        for (i in 0 until WRIST_SHAKE_WINDOW) {
            val idx = (xSignWindowIdx + i) % WRIST_SHAKE_WINDOW
            val cur = xSignWindow[idx]
            if (cur != 0 && prev != 0 && cur != prev) reversals++
            if (cur != 0) prev = cur
        }
        if (reversals >= WRIST_SHAKE_MIN_REVERSALS &&
            sampleCount - lastShakeEmittedAtSample > WRIST_SHAKE_COOLDOWN_SAMPLES
        ) {
            lastShakeEmittedAtSample = sampleCount
            emit(SpatialEvent.WristShake)
        }
    }

    /** Reset all internal state — useful on disconnect / reconnect so stale signals don't fire. */
    fun reset() {
        lastPosture = Posture.UNKNOWN
        freeFallSamples = 0
        xSignWindow.fill(0)
        xSignWindowIdx = 0
        xSignWindowFilled = 0
        lastShakeEmittedAtSample = Long.MIN_VALUE / 2
        sampleCount = 0L
    }

    companion object {
        /** Free-fall threshold — at rest mag ≈ 1.0; in free-fall ≈ 0; choose 0.3 as conservative. */
        const val FREE_FALL_MAG_THRESHOLD = 0.3f
        /** Min consecutive samples below threshold to confirm free-fall (rejects accel noise). */
        const val FREE_FALL_MIN_SAMPLES = 3   // at ~4 Hz that's ~750 ms — adjust on hardware tune
        /** Magnitude spike that indicates an impact / sudden stop. */
        const val IMPACT_MAG_THRESHOLD = 2.5f
        /** Min |x-axis g| to count as a directional sample for shake detection. */
        const val SHAKE_AXIS_THRESHOLD = 0.5f
        /** Sliding window length for the shake detector (in samples). */
        const val WRIST_SHAKE_WINDOW = 8
        /** Sign reversals within the window to fire a shake event. */
        const val WRIST_SHAKE_MIN_REVERSALS = 4
        /** Don't fire another shake within this many samples (debounce). */
        const val WRIST_SHAKE_COOLDOWN_SAMPLES = 16
        /** Threshold for "definitely gravity-aligned" vs "moving" — avoids transient flips. */
        const val POSTURE_MIN_DOMINANT_G = 0.7f
    }
}
