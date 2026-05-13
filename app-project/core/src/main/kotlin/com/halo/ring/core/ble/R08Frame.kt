package com.halo.ring.core.ble

import com.halo.ring.core.gesture.RawGesture

/**
 * Pure decoder for the ring's notify frames. Stateless — feed bytes in, get a [RingEvent] out.
 *
 * The byte-level de-dup ("drop identical frame within Nms") is the BLE client's job, NOT this
 * decoder's — keep this a pure function so it's trivially testable and replayable from logs.
 * (See R08-Remote-Design.md §20.3 for the de-dup design.)
 */
object R08Frame {

    fun parse(data: ByteArray): RingEvent {
        if (data.isEmpty()) return RingEvent.Unknown(data)
        val b0 = data[0].toInt() and 0xFF
        val b1 = if (data.size >= 2) data[1].toInt() and 0xFF else -1

        return when (b0) {
            R08Protocol.PREFIX_RING -> when (b1) {
                R08Protocol.SUB_GESTURE      -> parseGesture(data)
                R08Protocol.SUB_TOUCH_STATUS -> parseTouchStatus(data)
                R08Protocol.SUB_ACTIVITY     -> parseActivity(data)
                else                          -> RingEvent.Unknown(data)
            }
            R08Protocol.PREFIX_ACCEL   -> RingEvent.AccelRaw(data.copyOf())
            R08Protocol.PREFIX_BATTERY -> if (data.size >= 2) RingEvent.Battery(data[1].toInt() and 0xFF) else RingEvent.Unknown(data)
            R08Protocol.PREFIX_HEALTH  -> parseHealth(data) ?: RingEvent.Unknown(data)
            R08Protocol.PREFIX_STEPS   -> if (data.size >= 3) {
                val s = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                RingEvent.Steps(s)
            } else RingEvent.Unknown(data)
            else -> RingEvent.Unknown(data)
        }
    }

    private fun parseGesture(data: ByteArray): RingEvent {
        if (data.size < 3) return RingEvent.Unknown(data)
        val raw = when (data[2].toInt() and 0xFF) {
            R08Protocol.G_SWIPE_UP   -> RawGesture.SWIPE_UP
            R08Protocol.G_SWIPE_DOWN -> RawGesture.SWIPE_DOWN
            R08Protocol.G_TOUCH      -> RawGesture.TOUCH
            R08Protocol.G_LONG_PRESS -> RawGesture.LONG_PRESS
            else                      -> return RingEvent.Unknown(data)
        }
        return RingEvent.GestureEvent(raw)
    }

    private fun parseTouchStatus(data: ByteArray): RingEvent {
        if (data.size < 3) return RingEvent.Unknown(data)
        return RingEvent.TouchStatus(enabled = (data[2].toInt() and 0xFF) == 0)
    }

    /** `73 12 ss ss ss cc cc cc dd dd dd` — steps (3-byte BE), calories÷1000, distance(m)÷1000. */
    private fun parseActivity(data: ByteArray): RingEvent {
        if (data.size < 11) return RingEvent.Unknown(data)
        fun be3(o: Int) =
            ((data[o].toInt() and 0xFF) shl 16) or
            ((data[o + 1].toInt() and 0xFF) shl 8) or
             (data[o + 2].toInt() and 0xFF)
        val steps = be3(2)
        val calories = be3(5) / 1000f
        val distanceMeters = be3(8) / 1000f
        return RingEvent.Activity(steps, calories, distanceMeters)
    }

    /** `69 <kind> ?? <value>`: kind 1=HR, 3=SpO2, 8=stress; value > 0 to be valid. */
    private fun parseHealth(data: ByteArray): RingEvent? {
        if (data.size < 4) return null
        val kind = when (data[1].toInt() and 0xFF) {
            1 -> HealthKind.HEART_RATE
            3 -> HealthKind.SPO2
            8 -> HealthKind.STRESS
            else -> return null
        }
        val value = data[3].toInt() and 0xFF
        if (value <= 0) return null
        return RingEvent.Health(kind, value)
    }

    /** Convenience for tests / debug HUD: format raw bytes as `"73 2d 03 ..."`. */
    fun hex(data: ByteArray): String = data.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}
