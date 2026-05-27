package com.halo.ring.core.ble

import com.halo.ring.core.gesture.RawGesture

/** A decoded notify frame from the ring. See [R08Frame.parse]. */
sealed interface RingEvent {

    /** Atomic touch-IC gesture — feed [raw] to the GestureSynthesizer. Hardware does NOT debounce
     *  taps (inter-tap as short as 0.14 s observed), so multi-tap/combo synthesis is app-layer. */
    data class GestureEvent(val raw: RawGesture) : RingEvent

    /** Touch-IC enabled state echo (`0x73 sub=0x2A`). Fires after the 2-step TouchControl init
     *  AND on charging-dock insertion. `enabled = false` is also our **off-finger/charging** signal
     *  (SPEC v3 §4.10 — byte 1 = 1 reported when ring is removed from the user OR docked). */
    data class TouchStatus(val enabled: Boolean) : RingEvent

    /** Battery percentage. [charging] true when on cradle; null on legacy (single-byte) frames. */
    data class Battery(val percent: Int, val charging: Boolean? = null) : RingEvent

    /** Live PPG measurement progress / converged value. [value] is bpm for HR, % for SpO2, etc.
     *  Convergence is detected by [value] > 0 (the firmware emits `val=0` progress frames first,
     *  then begins carrying the locked-on reading in the same `err=0x00` frame). */
    data class Health(
        val kind: HealthKind,
        val value: Int,
        /** Only meaningful for [HealthKind.HEALTHCHECK] (`0x69 type=0x05`) — systolic mmHg. */
        val systolic: Int? = null,
        /** Only meaningful for [HealthKind.HEALTHCHECK] — diastolic mmHg. */
        val diastolic: Int? = null,
    ) : RingEvent

    /** Wear-detect failure — the ring's PPG sensor can't see a finger. Show "adjust ring" UI. */
    data class WearDetectFail(val kind: HealthKind) : RingEvent

    /** Cumulative activity counters (`0x73 sub=0x12 ACTIVITY_TOTAL` push during walking).
     *  - [steps] — daily step count.
     *  - [calories] — kcal (raw / 1000). NB: on RT08_3.10.46 this is `steps × 36 mcal` exactly,
     *    not a physiological estimate — don't display as "calories burned" without that caveat.
     *  - [distanceMeters] — integer meters (NOT km). */
    data class Activity(val steps: Int, val calories: Float, val distanceMeters: Int) : RingEvent

    /** Daily step target reached (`0x73 sub=0x10`). NB: spec says trigger is unclear on RT08 —
     *  empirically not always fired even when ACTIVITY_TOTAL crosses the target. */
    data object TargetReached : RingEvent

    /** Wrist-shake event (`0x73 sub=0x29`). Only fires when [R08Protocol.TOUCH_APP_GAME] (=7) is
     *  configured via `0x3B [2, 0, 7, sleepMin]` instead of the standard appType=9. */
    data object RingGameKey : RingEvent

    /** 3-axis accelerometer sample, decoded from the `0xA1 03` telemetry channel.
     *  Components in units of *g* (gravity). Sum-of-squares ≈ 1.0 when stationary. */
    data class AccelSample(val xG: Float, val yG: Float, val zG: Float) : RingEvent {
        val magnitude: Float get() = kotlin.math.sqrt(xG * xG + yG * yG + zG * zG)
    }

    /** Opaque firmware-internal channel from the `0xA1` telemetry stream (ch1 PPG-accumulator,
     *  ch2 PPG-accumulator-2, ch4 motion-stability). Kept so debug tooling can render the bytes;
     *  do not compute medical values from these. */
    data class AccelOpaque(val channel: Int, val payload: ByteArray) : RingEvent {
        override fun equals(other: Any?) = other is AccelOpaque && channel == other.channel && payload.contentEquals(other.payload)
        override fun hashCode() = channel * 31 + payload.contentHashCode()
    }

    /** PhoneSport `0x78` tick during an active workout. [hrEstimate] from byte 4 (live-verified
     *  layout — the SDK-claimed "sport status byte" never matched the wire data).
     *  [durationSeconds] from bytes 2..3 BE u16. */
    data class SportTick(
        val sportType: Int,
        val durationSeconds: Int,
        val hrEstimate: Int,
    ) : RingEvent

    /** The firmware refused this opcode (`(op | 0x80) → [op|0x80, 0xEE, ...]`). Silent-skip; never
     *  surface to user — capability discovery is meant to gate the call beforehand. */
    data class UnsupportedOp(val opcode: Int) : RingEvent

    /** Decoded capability bitmaps from the bootstrap. Use as a single dict for UI gating. */
    data class Capability(val flags: Set<String>, val screenWidth: Int = 0, val screenHeight: Int = 0) : RingEvent

    /** Anything we didn't recognise — keep the bytes so the debug HUD / logs can show them. */
    data class Unknown(val bytes: ByteArray) : RingEvent {
        override fun equals(other: Any?) = other is Unknown && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
}

enum class HealthKind {
    HEART_RATE,
    SPO2,
    STRESS,
    /** `0x69 type=0x05` — HR + BP composite. [RingEvent.Health.value] = bpm; systolic/diastolic populated. */
    HEALTHCHECK,
    /** `0x69 type=0x02` — BP solo. On RT08 `value` stays 0; use HEALTHCHECK instead. */
    BLOOD_PRESSURE,
    HRV,
    TEMPERATURE,
    FATIGUE,
    BLOOD_SUGAR,
}
