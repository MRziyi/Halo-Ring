package com.halo.ring.core.ble

import com.halo.ring.core.gesture.RawGesture

/** A decoded notify frame from the ring. See [R08Frame.parse]. */
sealed interface RingEvent {
    /** A control gesture — feed [raw] to the GestureSynthesizer. */
    data class GestureEvent(val raw: RawGesture) : RingEvent
    /** Touch-IC enabled state echo (response to TOUCH_ENABLE/DISABLE). */
    data class TouchStatus(val enabled: Boolean) : RingEvent
    /** Battery percentage. */
    data class Battery(val percent: Int) : RingEvent
    /** Real-time health reading. [kind] ∈ {HEART_RATE, SPO2, STRESS}. */
    data class Health(val kind: HealthKind, val value: Int) : RingEvent
    /** Cumulative activity counters (push or in a 73 12 frame). */
    data class Activity(val steps: Int, val calories: Float, val distanceMeters: Float) : RingEvent
    /** Step count only (51 frame). */
    data class Steps(val steps: Int) : RingEvent
    /** Raw accelerometer frame — payload not yet decoded; kept for future / debugging. */
    data class AccelRaw(val payload: ByteArray) : RingEvent {
        override fun equals(other: Any?) = other is AccelRaw && payload.contentEquals(other.payload)
        override fun hashCode() = payload.contentHashCode()
    }
    /** Anything we didn't recognise — keep the bytes so the debug HUD / logs can show them. */
    data class Unknown(val bytes: ByteArray) : RingEvent {
        override fun equals(other: Any?) = other is Unknown && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
}

enum class HealthKind { HEART_RATE, SPO2, STRESS }
