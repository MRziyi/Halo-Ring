package com.halo.ring.core.ble

/**
 * R08 ring BLE protocol constants & command builders.
 * Source of truth: R08-Remote-Design.md §3 (reverse-engineered from the `com.ring.r08remote` APK).
 *
 * UUIDs are kept as Strings here so :core stays free of `java.util.UUID` / Android — the :app layer
 * does `UUID.fromString(...)`.
 */
object R08Protocol {
    const val SERVICE_UUID      = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e"
    const val WRITE_CHAR_UUID   = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  // central → ring (write)
    const val NOTIFY_CHAR_UUID  = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  // ring → central (notify)
    const val CCCD_UUID         = "00002902-0000-1000-8000-00805f9b34fb"

    /** Advertised name is `R08_XXXX`; the APK also matches names containing these. */
    val DEVICE_NAME_KEYWORDS = listOf("Halo", "R06", "Colmi", "COLMI")

    // ── notify-frame leading bytes (see R08Frame) ────────────────────────────────────────────────
    const val PREFIX_RING      = 0x73   // 's' — namespace for ring-specific reports
    const val SUB_GESTURE      = 0x2D   // 73 2D <code> — control gesture
    const val SUB_TOUCH_STATUS = 0x2A   // 73 2A <0=enabled>
    const val SUB_ACTIVITY     = 0x12   // 73 12 ...steps/cal/dist
    const val PREFIX_ACCEL     = 0xA1   // accelerometer raw (encoding TBD — undecoded in the APK)
    const val PREFIX_BATTERY   = 0x03   // 03 <level%>
    const val PREFIX_HEALTH    = 0x69   // 'i' — 69 <1=HR|3=SpO2|8=stress> ... <value@[3]>
    const val PREFIX_STEPS     = 0x51   // 51 <lo> <hi> ...

    // gesture codes inside a `73 2D <code>` frame
    const val G_SWIPE_UP   = 0x01
    const val G_SWIPE_DOWN = 0x02
    const val G_TOUCH      = 0x03
    const val G_LONG_PRESS = 0x04

    // ── write commands (16-byte fixed frames, [15] = sum of bytes [0..14] & 0xFF) ────────────────
    /** Must be sent after enabling notifications, else the ring won't report touch/gestures. */
    val TOUCH_ENABLE: ByteArray  = command(0x3B, byteArrayOf(0x01, 0x00, 0x01, 0x01))
    /** Sent ~500ms after TOUCH_ENABLE. */
    val TOUCH_MODE: ByteArray    = command(0x3B, byteArrayOf(0x02, 0x00, 0x09, 0x01))
    val TOUCH_DISABLE: ByteArray = command(0x3B, byteArrayOf(0x01, 0x00, 0x01, 0x00))
    /** Battery query — response arrives as a `03 <level%> ...` notify frame. */
    val BATTERY_QUERY: ByteArray = command(0x03)
    /** Blink the LED (find-device). 0x06 = blink ~10s; 0x10 = blink twice. */
    val FIND_DEVICE: ByteArray   = command(0x06)
    val BLINK_TWICE: ByteArray   = command(0x10)
    /** Power off the ring. */
    val SHUTDOWN: ByteArray      = command(0x0F)

    // ── real-time health measurement (`0x69` start / `0x6A` stop) ────────────────────────────────
    // Frame: `69 <kind> 01 ...` to START streaming the chosen reading, `6A 00 ...` to STOP.
    // Kind values per Doc/02 §4.1 health-frame table:
    //   1 = heart-rate, 3 = SpO2, 8 = stress.
    // Replies arrive on the notify channel as `69 <kind> ?? <value>` frames (see R08Frame.parseHealth).
    val REAL_TIME_HR_START:     ByteArray = command(0x69, byteArrayOf(0x01, 0x01))
    val REAL_TIME_SPO2_START:   ByteArray = command(0x69, byteArrayOf(0x03, 0x01))
    val REAL_TIME_STRESS_START: ByteArray = command(0x69, byteArrayOf(0x08, 0x01))
    val REAL_TIME_STOP:         ByteArray = command(0x6A)

    /** Build a 16-byte command: `[0]=code, [1..]=payload (zero-padded), [15]=checksum`. */
    fun command(code: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 14) { "payload too long" }
        val out = ByteArray(16)
        out[0] = code.toByte()
        payload.copyInto(out, destinationOffset = 1)
        var sum = 0
        for (i in 0 until 15) sum += out[i].toInt() and 0xFF
        out[15] = (sum and 0xFF).toByte()
        return out
    }
}
