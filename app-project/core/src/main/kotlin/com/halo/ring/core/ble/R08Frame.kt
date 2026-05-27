package com.halo.ring.core.ble

import com.halo.ring.core.gesture.RawGesture

/**
 * Pure decoder for the ring's notify frames. Stateless — feed bytes in, get a [RingEvent] out.
 *
 * Implements `phase0/SPEC v3.md` v1.2 dispatch. The byte-level de-dup ("drop identical frame
 * within Nms") is the BLE client's job, NOT this decoder's — keep this a pure function so it's
 * trivially testable and replayable from logs.
 *
 * Dispatch order (in this exact priority):
 *   1. `0xEE` unsupported sentinel — `(op|0x80, 0xEE, ...)` → [RingEvent.UnsupportedOp]
 *   2. `0x73` device-notify → sub-id table (battery push, gesture, touch status, activity, …)
 *   3. `0x69` measurement progress / converged value → [RingEvent.Health] or [RingEvent.WearDetectFail]
 *   4. `0xA1` telemetry-stream channel → [RingEvent.AccelSample] (ch3) or [RingEvent.AccelOpaque] (ch1/2/4)
 *   5. `0x78` PhoneSportNotify → [RingEvent.SportTick]
 *   6. `0x03` Battery polled response → [RingEvent.Battery]
 *   7. anything else → [RingEvent.Unknown]
 */
object R08Frame {

    fun parse(data: ByteArray): RingEvent {
        if (data.isEmpty()) return RingEvent.Unknown(data)
        // 1. Universal unsupported sentinel — short-circuit before the opcode dispatch.
        if (R08Protocol.isUnsupported(data)) {
            val originalOp = data[0].toInt() and 0x7F   // strip the 0x80 error flag
            return RingEvent.UnsupportedOp(originalOp)
        }

        val op = data[0].toInt() and 0xFF
        val b1 = if (data.size >= 2) data[1].toInt() and 0xFF else -1

        return when (op) {
            R08Protocol.OP_DEVICE_NOTIFY  -> parseDeviceNotify(data)
            R08Protocol.OP_START_MEASURE  -> parseHealth(data) ?: RingEvent.Unknown(data)
            R08Protocol.OP_TELEMETRY_STREAM -> parseTelemetry(data)
            R08Protocol.OP_PHONE_SPORT_NOTIFY -> parseSportTick(data) ?: RingEvent.Unknown(data)
            R08Protocol.OP_BATTERY ->
                if (data.size >= 2) RingEvent.Battery(
                    percent = b1,
                    charging = if (data.size >= 3) (data[2].toInt() and 0xFF) != 0 else null,
                )
                else RingEvent.Unknown(data)
            else -> RingEvent.Unknown(data)
        }
    }

    /** Dispatch `0x73 [sub_id] [payload]`. */
    private fun parseDeviceNotify(data: ByteArray): RingEvent {
        if (data.size < 2) return RingEvent.Unknown(data)
        val sub = data[1].toInt() and 0xFF
        return when (sub) {
            R08Protocol.SUB_TOUCH_GESTURE -> parseTouchGesture(data)
            R08Protocol.SUB_TOUCH_STATUS  -> parseTouchStatus(data)
            R08Protocol.SUB_BATTERY_STATE -> parseBatteryPush(data)
            R08Protocol.SUB_ACTIVITY_TOTAL -> parseActivity(data)
            R08Protocol.SUB_TARGET_REACHED -> RingEvent.TargetReached
            R08Protocol.SUB_RING_GAME_KEY -> RingEvent.RingGameKey
            else -> RingEvent.Unknown(data)
        }
    }

    /** `73 2D <code>` — atomic gesture. */
    private fun parseTouchGesture(data: ByteArray): RingEvent {
        if (data.size < 3) return RingEvent.Unknown(data)
        val raw = when (data[2].toInt() and 0xFF) {
            R08Protocol.G_SWIPE_UP   -> RawGesture.SWIPE_UP
            R08Protocol.G_SWIPE_DOWN -> RawGesture.SWIPE_DOWN
            R08Protocol.G_TOUCH      -> RawGesture.TOUCH
            R08Protocol.G_LONG_PRESS -> RawGesture.LONG_PRESS
            else                     -> return RingEvent.Unknown(data)
        }
        return RingEvent.GestureEvent(raw)
    }

    /** `73 2A <touch_disabled_flag>` — inverted polarity; 0 = ACTIVE, non-zero = disabled (also
     *  fires with byte 1 = 1 when the ring is inserted into the charging dock). */
    private fun parseTouchStatus(data: ByteArray): RingEvent {
        if (data.size < 3) return RingEvent.Unknown(data)
        return RingEvent.TouchStatus(enabled = (data[2].toInt() and 0xFF) == 0)
    }

    /** `73 0C <pct> <charging>` — autonomous battery push from the ring. */
    private fun parseBatteryPush(data: ByteArray): RingEvent {
        if (data.size < 3) return RingEvent.Unknown(data)
        val pct = data[2].toInt() and 0xFF
        if (pct > 100) return RingEvent.Unknown(data)
        val charging = if (data.size >= 4) (data[3].toInt() and 0xFF) != 0 else null
        return RingEvent.Battery(pct, charging)
    }

    /**
     * `73 12 [steps u24 BE][cal_raw u24 BE][dist_raw u24 BE]` — live activity counter.
     *
     * Units (SPEC v3 §5.3, wire-verified PASS 3):
     *  - steps  — daily count
     *  - cal_raw — millicalories; `kcal = cal_raw / 1000`
     *  - dist_raw — integer meters (NOT mm, NOT km)
     */
    private fun parseActivity(data: ByteArray): RingEvent {
        if (data.size < 11) return RingEvent.Unknown(data)
        val steps    = R08Protocol.readU24Be(data, 2)
        val calRaw   = R08Protocol.readU24Be(data, 5)
        val distRaw  = R08Protocol.readU24Be(data, 8)
        // Sanity: cap absurd values that would indicate a frame-boundary glitch.
        if (steps > 1_000_000 || calRaw > 100_000_000 || distRaw > 100_000_000) {
            return RingEvent.Unknown(data)
        }
        return RingEvent.Activity(
            steps = steps,
            calories = calRaw / 1000f,
            distanceMeters = distRaw,
        )
    }

    /**
     * `69 [type] [err] [val] [sbp_or_zero] [dbp_or_zero] [00] [raw_LE_u16] [zeros]`
     *
     * Convergence detection (SPEC v3 §4.5): `err = 0x00` throughout for HR / SpO2 / Healthcheck —
     * the firmware emits progress frames with `val = 0`, then begins carrying the locked-on
     * reading in the same `err = 0x00` frame once it converges. We surface those progress frames
     * (val == 0) as null so callers can wait for the first val ≠ 0.
     *
     * `err = 0x02` = wear-detect fail; we surface that as [RingEvent.WearDetectFail] so the UI
     * can prompt the user to adjust the ring.
     */
    private fun parseHealth(data: ByteArray): RingEvent? {
        if (data.size < 4) return null
        val typeByte = data[1].toInt() and 0xFF
        val err = data[2].toInt() and 0xFF
        val kind = when (typeByte) {
            R08Protocol.MEASURE_HR          -> HealthKind.HEART_RATE
            R08Protocol.MEASURE_SPO2        -> HealthKind.SPO2
            R08Protocol.MEASURE_STRESS      -> HealthKind.STRESS
            R08Protocol.MEASURE_HEALTHCHECK -> HealthKind.HEALTHCHECK
            R08Protocol.MEASURE_BP          -> HealthKind.BLOOD_PRESSURE
            R08Protocol.MEASURE_HRV         -> HealthKind.HRV
            R08Protocol.MEASURE_TEMP        -> HealthKind.TEMPERATURE
            R08Protocol.MEASURE_FATIGUE     -> HealthKind.FATIGUE
            R08Protocol.MEASURE_BLOOD_SUGAR -> HealthKind.BLOOD_SUGAR
            else                            -> return null
        }
        if (err == R08Protocol.MEASURE_ERR_WEAR_FAIL) return RingEvent.WearDetectFail(kind)
        if (err == R08Protocol.MEASURE_ERR_COMPLETE) return null   // sentinel — no value carried
        // err == 0x00 progress / converged. Detect convergence by val ≠ 0.
        val value = data[3].toInt() and 0xFF
        if (value == 0) return null   // pre-convergence progress frame — wait for next
        // Healthcheck composite also carries SBP@payload[2] / DBP@payload[3] when converged.
        val (sbp, dbp) = if (kind == HealthKind.HEALTHCHECK && data.size >= 6) {
            val s = data[4].toInt() and 0xFF
            val d = data[5].toInt() and 0xFF
            (if (s > 0) s else null) to (if (d > 0) d else null)
        } else null to null
        return RingEvent.Health(kind, value, sbp, dbp)
    }

    /**
     * `A1 [channel] [payload]` — multi-channel telemetry stream. Channel 3 carries the 3-axis
     * accelerometer (BE int16 triplet, ±4 g, 8192 LSB/g — see SPEC v3 §6.3). Channels 1/2/4 are
     * opaque firmware-internal accumulators (SPEC §6.4); surfaced as [RingEvent.AccelOpaque]
     * for debug rendering.
     */
    private fun parseTelemetry(data: ByteArray): RingEvent {
        if (data.size < 2) return RingEvent.Unknown(data)
        val channel = data[1].toInt() and 0xFF
        // STOP ack `A1 FF 00 ...` — treat as Unknown; callers don't need to react.
        if (channel == 0xFF) return RingEvent.Unknown(data)
        return when (channel) {
            R08Protocol.TELEMETRY_CH_ACCEL -> parseAccelSample(data) ?: RingEvent.Unknown(data)
            R08Protocol.TELEMETRY_CH_PPG1,
            R08Protocol.TELEMETRY_CH_PPG2,
            R08Protocol.TELEMETRY_CH_STABILITY ->
                // payload is the 14 bytes after [opcode][channel]
                RingEvent.AccelOpaque(channel = channel, payload = data.copyOfRange(2, data.size.coerceAtMost(16)))
            else -> RingEvent.Unknown(data)
        }
    }

    /** Decode `A1 03 [X_hi X_lo Y_hi Y_lo Z_hi Z_lo]` → AccelSample in g. */
    private fun parseAccelSample(data: ByteArray): RingEvent.AccelSample? {
        if (data.size < 8) return null
        fun s16(hi: Int, lo: Int): Int {
            val v = (hi shl 8) or lo
            return if (v >= 0x8000) v - 0x10000 else v
        }
        val xRaw = s16(data[2].toInt() and 0xFF, data[3].toInt() and 0xFF)
        val yRaw = s16(data[4].toInt() and 0xFF, data[5].toInt() and 0xFF)
        val zRaw = s16(data[6].toInt() and 0xFF, data[7].toInt() and 0xFF)
        val scale = R08Protocol.ACCEL_LSB_PER_G.toFloat()
        return RingEvent.AccelSample(xRaw / scale, yRaw / scale, zRaw / scale)
    }

    /**
     * `78 [sport_type] [status] [duration_BE_u16] [hr_estimate] [zeros]`
     *
     * Live-verified layout (SPEC v3 §4.8 — diverges from SDK source). Bytes 5..11 are unused on
     * RT08; the SDK-claimed "metric A/B/C at u24 LE" never appears. Use the parallel
     * `0x73 sub=0x12` ACTIVITY_TOTAL stream for step / kcal / distance during a sport session.
     */
    private fun parseSportTick(data: ByteArray): RingEvent.SportTick? {
        if (data.size < 5) return null
        val sportType = data[1].toInt() and 0xFF
        val durationBe = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val hrEstimate = data[5].toInt() and 0xFF
        return RingEvent.SportTick(
            sportType = sportType,
            durationSeconds = durationBe,
            hrEstimate = hrEstimate,
        )
    }

    /** Decode the SetTime 14-byte capability extension (SPEC v3 §3.1). Returns a string-set of
     *  flag names that are set, suitable for UI capability-gating. */
    fun parseSetTimeCapabilities(payload: ByteArray): RingEvent.Capability {
        if (payload.size < 14) return RingEvent.Capability(emptySet())
        val flags = mutableSetOf<String>()
        if (payload[0].toInt() == 1) flags += "temperature"
        if (payload[1].toInt() == 1) flags += "plate"
        if (payload[2].toInt() == 1) flags += "menstruation"
        val b3 = payload[3].toInt() and 0xFF
        if (b3 and 0x01 != 0) flags += "customWallpaper"
        if (b3 and 0x02 != 0) flags += "bloodOxygen"
        if (b3 and 0x04 != 0) flags += "bloodPressure"
        if (b3 and 0x08 != 0) flags += "feature"
        if (b3 and 0x10 != 0) flags += "oneKeyCheck"
        if (b3 and 0x20 != 0) flags += "weather"
        if (b3 and 0x40 == 0) flags += "wechat"     // bit 0x40 set = WeChat NOT supported (inverted)
        if (b3 and 0x80 != 0) flags += "avatar"
        val sw = (payload[4].toInt() and 0xFF) or ((payload[5].toInt() and 0xFF) shl 8)
        val sh = (payload[6].toInt() and 0xFF) or ((payload[7].toInt() and 0xFF) shl 8)
        if (payload[8].toInt() == 1) flags += "newSleepProtocol"
        val b10 = payload[10].toInt() and 0xFF
        if (b10 and 0x01 != 0) flags += "contact"
        if (b10 and 0x02 != 0) flags += "lyrics"
        if (b10 and 0x04 != 0) flags += "album"
        if (b10 and 0x08 != 0) flags += "gps"
        if (b10 and 0x10 != 0) flags += "jieLiMusic"
        if (b10 and 0x20 != 0) flags += "appMeasure"
        if (b10 and 0x40 != 0) flags += "manualBloodOxygen"
        if (b10 and 0x80 != 0) flags += "yaWei"
        val b11 = payload[11].toInt() and 0xFF
        if (b11 and 0x01 != 0) flags += "manualHeart"
        if (b11 and 0x02 != 0) flags += "eCard"
        if (b11 and 0x04 != 0) flags += "location"
        if (b11 and 0x10 != 0) flags += "musicLockscreen"
        if (b11 and 0x20 != 0) flags += "rtkMcu"
        if (b11 and 0x40 != 0) flags += "ebook"
        if (b11 and 0x80 != 0) flags += "bloodSugar"
        val b13 = payload[13].toInt() and 0xFF
        if (b13 and 0x01 != 0) flags += "record"
        if (b13 and 0x02 != 0) flags += "bpSetting"
        if (b13 and 0x04 != 0) flags += "4g"
        if (b13 and 0x08 != 0) flags += "navPicture"
        if (b13 and 0x10 != 0) flags += "pressure"
        if (b13 and 0x20 != 0) flags += "hrv"
        return RingEvent.Capability(flags, screenWidth = sw, screenHeight = sh)
    }

    /** Decode the `0x3C` 9-byte capability bitmap (SPEC v3 §3.2). Merge with [parseSetTimeCapabilities]. */
    fun parseDeviceSupportCapabilities(payload: ByteArray): Set<String> {
        if (payload.size < 9) return emptySet()
        val flags = mutableSetOf<String>()
        val b1 = payload[0].toInt() and 0xFF
        if (b1 and 0x01 != 0) flags += "touch"
        if (b1 and 0x02 != 0) flags += "moslin"
        if (b1 and 0x04 != 0) flags += "appRevision"
        if (b1 and 0x08 != 0) flags += "blePair"
        if (b1 and 0x40 != 0) flags += "deviceNoScreen"
        if (b1 and 0x80 != 0) flags += "gesture"
        val b2 = payload[1].toInt() and 0xFF
        if (b2 and 0x01 != 0) flags += "ringMusic"
        if (b2 and 0x02 != 0) flags += "ringVideo"
        if (b2 and 0x04 != 0) flags += "ringEbook"
        if (b2 and 0x08 != 0) flags += "ringCamera"
        if (b2 and 0x10 != 0) flags += "ringPhoneCall"
        if (b2 and 0x20 != 0) flags += "ringGame"
        if (b2 and 0x40 != 0) flags += "heart"
        val b3 = payload[2].toInt() and 0xFF
        if (b3 and 0x01 != 0) flags += "skinTemperature"
        if (b3 and 0x04 != 0) flags += "longSit"
        if (b3 and 0x08 != 0) flags += "drink"
        if (b3 and 0x10 != 0) flags += "noSingleTemperature"
        if (b3 and 0x20 != 0) flags += "notification"
        if (b3 and 0x80 != 0) flags += "aiAnalyze"
        val b7 = payload[6].toInt() and 0xFF
        if (b7 and 0x04 != 0) flags += "realTimeOxygen"
        if (b7 and 0x08 != 0) flags += "realTimeHr"
        val b9 = payload[8].toInt() and 0xFF
        if (b9 and 0x02 != 0) flags += "ecg"
        if (b9 and 0x04 != 0) flags += "tempBoth"
        if (b9 and 0x08 != 0) flags += "breathTraining"
        if (b9 and 0x10 != 0) flags += "audio"
        if (b9 and 0x20 != 0) flags += "meetingRecord"
        return flags
    }

    /** Convenience for tests / debug HUD: format raw bytes as `"73 2d 03 ..."`. */
    fun hex(data: ByteArray): String = data.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}
