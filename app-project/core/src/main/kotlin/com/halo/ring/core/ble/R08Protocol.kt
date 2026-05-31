package com.halo.ring.core.ble

/**
 * R08 / QRing BLE protocol — constants, command frame builder, capability decoder, and helpers.
 *
 * **Source of truth**: `phase0/SPEC v3.md` v1.2 (2026-05-27). Verified on a real
 * `R08_E600` ring running `RT08_3.10.46_250621`. Where this file differs from the previous
 * (pre-hardware-arrival) revision, the SPEC v3 wins — every byte value below has been wire-
 * captured.
 *
 * Frame format (command channel):
 * ```
 *   byte 0      : opcode               (top bit 0x80 set = ERROR response from ring)
 *   byte 1..14  : payload              (zero-padded if shorter)
 *   byte 15     : checksum             = sum(bytes 0..14) & 0xFF   (NOT a CRC)
 * ```
 *
 * UUIDs are kept as Strings so :core stays free of `java.util.UUID` / Android.
 */
object R08Protocol {

    // ─── GATT layer ────────────────────────────────────────────────────────────────────────────
    /** Primary "command channel" service — the only one we use on prod RT08_3.10.46. */
    const val SERVICE_UUID      = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e"
    const val WRITE_CHAR_UUID   = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  // central → ring (write w/wo response)
    const val NOTIFY_CHAR_UUID  = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  // ring → central (notify)
    const val CCCD_UUID         = "00002902-0000-1000-8000-00805f9b34fb"

    /** Device-info service — read these BEFORE any opcode (the SDK's `ready` gate). */
    const val DEVINFO_SVC_UUID  = "0000180a-0000-1000-8000-00805f9b34fb"
    const val FW_REV_CHAR_UUID  = "00002a26-0000-1000-8000-00805f9b34fb"  // ASCII, e.g. "RT08_3.10.46_250621"
    const val HW_REV_CHAR_UUID  = "00002a27-0000-1000-8000-00805f9b34fb"  // ASCII, e.g. "RT08_V3.1"

    /** Negotiated MTU. The ring picks 247 immediately after connect. */
    const val MTU = 247

    /**
     * Local-name prefixes the QRing app (`com.app.cq.ring`) uses to discover R0x-family rings.
     * Lifted from `QCApplication.defaultScanDevice()`. The R08 advertises ONLY local name — no
     * service-UUID / manufacturer-data / tx-power on the wire (SPEC v3 §1.2).
     *
     * **Burn-in fix 2026-05-27**: tightened from the previous loose list. Earlier we had `"R0"`,
     * `"R1"`, `"Q_"`, `"O_"`, `"DS"`, `"Halo"` which matched random Bluetooth devices in the wild
     * (a USB receiver named "DSL_..." or a "Redmi 0..." phone would hit). The R08-family
     * advertises with an underscore-prefixed serial, so anchoring on that resolves it.
     */
    val DEVICE_NAME_KEYWORDS = listOf(
        // Burn-in 2026-05-27: user confirmed their R08 family always advertises as `R08_XXXX`,
        // so we anchor on that single prefix. Other OEM aliases (VK-, MERLIN, boAtring, etc.)
        // ship the same firmware but rebrand the advertised name — re-introduce them only when
        // we see a user actually pair one. Keeping the list short prevents the picker from
        // showing unrelated BLE devices.
        "R08_",
    )

    // ─── Opcodes — host → ring (command channel) ───────────────────────────────────────────────
    // Every opcode below is annotated with its verified status on RT08_3.10.46:
    //   ✅ = works            ❌ = returns 0xEE      ⛔ = destructive, do not call
    //   🟡 = partial          ⏱ = silent timeout    🔧 = spec-only on this FW
    const val OP_SET_TIME            = 0x01  // ✅ — request body §4.1 BCD; reply = 14B capability extension
    const val OP_CAMERA_FROM_RING    = 0x02  // 🔧 — ring→phone push; spec only
    const val OP_BATTERY             = 0x03  // ✅ — empty req; reply [pct u8][charging u8]
    const val OP_BIND_ANCS           = 0x04  // ✅ — req [0x02][sdk u8][model utf8 ≤13]
    const val OP_PALM_SCREEN         = 0x05  // ⏱
    const val OP_DND                 = 0x06  // ❌ — was incorrectly used as "find ring" in our v0.2.3 code
    const val OP_TOTAL_STEP_SOMEDAY  = 0x07  // ❌
    const val OP_REBOOT_POWEROFF     = 0x08  // ⛔ destructive — req [0x01]; SHUTDOWN goes through here
    const val OP_INTELL              = 0x09  // ❌
    const val OP_TIME_FORMAT_PROFILE = 0x0A  // ✅ — user profile (gender/age/weight/HR threshold)
    const val OP_BIND_SUCCESS        = 0x10  // ✅ — empty; marks completion of binding (was incorrectly used as "blink twice")
    const val OP_PUSH_MSG            = 0x11  // ❌
    const val OP_DISPLAY_CLOCK       = 0x12  // ❌
    const val OP_BP_HISTORY          = 0x14  // ⏱
    const val OP_HR_HISTORY          = 0x15  // ✅ (FF sentinel)
    const val OP_HR_AUTO_MONITOR     = 0x16  // ✅ — read/write the autonomous-HR-every-30min config
    const val OP_PERSONALIZATION     = 0x17  // ❌
    const val OP_PING                = 0x18  // ✅ — empty body → empty-body ack (only empty payload works)
    const val OP_TEMP_UNIT           = 0x19  // ✅ — read/write °C / °F
    const val OP_WEATHER             = 0x1A  // ❌
    const val OP_BRIGHTNESS          = 0x1B  // ❌
    const val OP_MUSIC_SWITCH        = 0x1C  // ❌
    const val OP_MUSIC_COMMAND       = 0x1D  // 🔧 ring→phone
    const val OP_REALTIME_HR_ENABLE  = 0x1E  // ❌ on RT08; spec-only
    const val OP_DISPLAY_TIME        = 0x1F  // ❌
    const val OP_DIAL_PAINT          = 0x20  // ⛔
    const val OP_TARGET_SETTING      = 0x21  // ✅ — daily step / kcal / dist target (steps >= 100 to persist!)
    const val OP_FIND_PHONE          = 0x22  // 🔧 ring→phone
    const val OP_SET_ALARM           = 0x23  // ❌
    const val OP_READ_ALARM          = 0x24  // ❌
    const val OP_SET_SIT_LONG        = 0x25  // 🟡 — ack'd; verify via 0x26
    const val OP_READ_SIT_LONG       = 0x26  // ✅
    const val OP_SET_DRINK_ALARM     = 0x27  // 🟡 — ack'd; verify via 0x28
    const val OP_READ_DRINK_ALARM    = 0x28  // ✅
    const val OP_DISPLAY_ORIENTATION = 0x29  // ❌
    const val OP_DISPLAY_STYLE       = 0x2A  // ❌
    const val OP_MENSTRUATION        = 0x2B  // ✅ (read only)
    const val OP_SPO2_AUTO_MONITOR   = 0x2C  // ✅
    const val OP_BLACKLIST_LOCATION  = 0x2D  // ❌
    const val OP_OTA_MODE_SWITCH     = 0x2E  // ⛔
    const val OP_PACKAGE_LENGTH      = 0x2F  // ✅ — ring spontaneously sends `2F F4 00…` post-bootstrap (max SPP payload)
    const val OP_AGPS_SWITCH         = 0x30  // ❌
    const val OP_DEVICE_AVATAR       = 0x32  // ❌
    const val OP_PRESSURE_AUTO       = 0x36  // ✅
    const val OP_STRESS_HISTORY      = 0x37  // ✅ (FF sentinel)
    const val OP_HRV_AUTO            = 0x38  // ✅
    const val OP_HRV_HISTORY         = 0x39  // ✅ (FF sentinel)
    const val OP_SUGAR_LIPIDS_TEMP   = 0x3A  // 🟡
    const val OP_TOUCH_CONTROL       = 0x3B  // ✅ — gesture-IC enable + appType config (THE gesture gate)
    const val OP_DEVICE_SUPPORT      = 0x3C  // ✅ — empty req → 9-byte capability bitmap
    const val OP_STEP_SOMEDAY_DETAIL = 0x43  // ✅ (FF sentinel)
    const val OP_SLEEP_DETAIL        = 0x44  // ❌
    const val OP_QUERY_DATA_DIST     = 0x46  // ❌
    const val OP_TODAY_SPORT         = 0x48  // ✅ — body `00 00 …` when no data today
    const val OP_FIND_RING           = 0x50  // ✅ — magic [0x55, 0xAA]; ring LED flashes briefly (fire-and-forget, no reply)
    const val OP_LOVER_EVENT         = 0x51  // ❌
    const val OP_MUSLIM_REMIND       = 0x52  // ❌
    const val OP_HEARTBEAT_OP        = 0x5A  // ❌
    const val OP_SET_ANCS            = 0x60  // ✅ — body [0xFF, 0x9F] enables all ANCS notification categories
    const val OP_GET_MESSAGE_PUSH    = 0x61  // ✅
    const val OP_ECG_FAMILY_START    = 0x6C  // ❌ (no ECG hardware)
    const val OP_ECG_FAMILY_END      = 0x70
    const val OP_START_MEASURE       = 0x69  // ✅ — start a `0x69 [type, action]` measurement; results stream on opcode 0x69
    const val OP_STOP_MEASURE        = 0x6A  // ✅ — stop with [type, 4, 0]; ack carries final value in payload[1] (HR/SpO2/Healthcheck)
    const val OP_PUSH_MSG_UINT       = 0x72  // ⏱ — phone-notification push (no visible effect on R08)
    const val OP_DEVICE_NOTIFY       = 0x73  // ✅ — ring's primary EVENT channel (sub-id dispatch — see SUB_* below)
    const val OP_PHONE_GPS_PUSH      = 0x74  // ❌
    const val OP_DIAL_INDEX          = 0x75  // ❌
    const val OP_BATTERY_SAVING      = 0x76  // ❌
    const val OP_PHONE_SPORT         = 0x77  // ✅ — phone tells ring to start/stop/pause a workout session
    const val OP_PHONE_SPORT_NOTIFY  = 0x78  // ✅ ring→phone push during a workout session (sport tick)
    const val OP_MUSLIM_WORSHIP      = 0x7A  // ✅
    const val OP_MUSLIM_GOAL         = 0x7B  // ✅
    const val OP_STILL_TIME_PUSH     = 0x7E  // ❌
    const val OP_GET_HW_FW_VERSION   = 0x93  // ✅ — alternate path to read FW string; primary is GATT 0x2A26
    const val OP_TELEMETRY_STREAM    = 0xA1  // ✅ — multi-channel sensor telemetry: ch3 = 3-axis accel (DECODED)
    const val OP_FACTORY_RESTORE     = 0xFF  // ⛔ destructive — req [0xED, 0xED]

    /** When a notify frame's first byte has bit 0x80 set AND payload[0] is 0xEE, the firmware is
     *  telling us "this opcode is not supported on me". Detect with [isUnsupported]. */
    const val ERROR_FLAG = 0x80
    const val UNSUPPORTED_SENTINEL = 0xEE

    // ─── 0x73 device-notify sub-ids (the ring's event bus) ─────────────────────────────────────
    const val SUB_NEW_HR_RECORD     = 0x01
    const val SUB_NEW_BP_RECORD     = 0x02
    const val SUB_NEW_SPO2_RECORD   = 0x03
    const val SUB_NEW_STEP_DETAIL   = 0x04
    const val SUB_NEW_TEMP_RECORD   = 0x05
    const val SUB_SPORT_ENDED       = 0x07
    /** ✅ Battery push — `[pct u8][charging u8]`. Pushed within seconds of plug-in, ~60s heartbeat
     *  while battery is rising, silent at 100%, **two pushes ~50ms apart on unplug**. */
    const val SUB_BATTERY_STATE     = 0x0C
    const val SUB_NEW_BLOOD_SUGAR   = 0x0D
    const val SUB_TARGET_REACHED    = 0x10
    const val SUB_STEP_INCREMENT    = 0x11   // superseded by SUB_ACTIVITY_TOTAL on RT08
    /** ✅ Live activity counters. `[steps u24 BE][cal_raw u24 BE][dist_raw u24 BE]`. cal_raw/1000=kcal, dist_raw=METERS. */
    const val SUB_ACTIVITY_TOTAL    = 0x12
    const val SUB_MUSLIM_PRAISE     = 0x25
    const val SUB_NEW_TEMP_2        = 0x27
    const val SUB_DEVICE_SETTINGS   = 0x28
    /** ✅ Wrist-shake event — fires when [TOUCH_APP_GAME] (=7) is configured. */
    const val SUB_RING_GAME_KEY     = 0x29
    /** ✅ Touch-IC status echo. `byte 1 = touch_disabled_flag` (**0 = ACTIVE**, non-zero = disabled).
     *  Fires after the 2-step TouchControl init AND on charging-dock insertion (with byte 1 = 1). */
    const val SUB_TOUCH_STATUS      = 0x2A
    const val SUB_NEW_HRV           = 0x2B
    const val SUB_NEW_STRESS        = 0x2C
    /** ✅ Atomic touch gesture — `byte 1 = G_*` code. Only fires after the TouchControl init. */
    const val SUB_TOUCH_GESTURE     = 0x2D
    const val SUB_LOVER_DOUBLE_TAP  = 0x30
    const val SUB_EXERCISE_HR_HIGH  = 0x31
    const val SUB_DRINK_WATER       = 0x32
    const val SUB_SEDENTARY_REMIND  = 0x33
    const val SUB_ALARM_RING        = 0x34
    const val SUB_MANUAL_HR_TEST    = 0x37
    const val SUB_CUSTOMER_PRAISE   = 0x38
    const val SUB_MENSTRUATION_TICK = 0x39
    const val SUB_REST_HR_ALERT     = 0x3A
    const val SUB_TEMP_ALARM        = 0x3D
    const val SUB_G_SENSOR_STILL    = 0x3E
    const val SUB_ECG_CONNECT       = 0x3F

    // ─── 0x73 sub=0x2D gesture codes ───────────────────────────────────────────────────────────
    const val G_SWIPE_UP   = 0x01
    const val G_SWIPE_DOWN = 0x02
    /** Single tap. Hardware does NOT debounce — inter-tap intervals as short as 0.14 s have been
     *  observed; the multi-tap synthesis is entirely app-layer. */
    const val G_TOUCH      = 0x03
    /** Long-press, ~3 s hold. */
    const val G_LONG_PRESS = 0x04

    // ─── 0x69 measurement type codes ───────────────────────────────────────────────────────────
    const val MEASURE_HR          = 0x01  // ✅ converges to HR
    const val MEASURE_BP          = 0x02  // 🟡 progress only on RT08; sbp/dbp not populated via type=2
    const val MEASURE_SPO2        = 0x03  // ✅
    const val MEASURE_FATIGUE     = 0x04  // 🟡
    const val MEASURE_HEALTHCHECK = 0x05  // ✅ HR + BP composite — single shot returns BPM+SBP+DBP in one frame
    const val MEASURE_REALTIME_HR = 0x06  // 🔧 spec only — `0x1E` realtime stream not induced on RT08
    const val MEASURE_ECG         = 0x07  // ❌ (no ECG hardware)
    const val MEASURE_STRESS      = 0x08  // 🟡 progress only on RT08
    const val MEASURE_BLOOD_SUGAR = 0x09  // 🟡
    const val MEASURE_HRV         = 0x0A  // 🟡
    const val MEASURE_TEMP        = 0x0B  // 🟡

    const val MEASURE_ACTION_START    = 0x01
    const val MEASURE_ACTION_PAUSE    = 0x02
    const val MEASURE_ACTION_CONTINUE = 0x03
    const val MEASURE_ACTION_STOP     = 0x04
    /** Special sub-action for ECG (0x10 = 16). */
    const val MEASURE_ACTION_ECG      = 0x10

    /** Stop-measurement reply `err` semantics. */
    const val MEASURE_ERR_PROGRESS    = 0x00  // also "converged" — convergence detected by val ≠ 0
    const val MEASURE_ERR_COMPLETE    = 0x01  // sentinel; only Pressure/Temp ever emit this
    const val MEASURE_ERR_WEAR_FAIL   = 0x02  // finger not making good optical contact

    // ─── 0xA1 telemetry-stream sub-actions ─────────────────────────────────────────────────────
    const val TELEMETRY_STOP        = 0x00  // ring sends `A1 FF 00 …` ack
    const val TELEMETRY_START_BASIC = 0x01  // 4-sub-frame cycle, ~4 cycles/s
    const val TELEMETRY_ONE_SHOT    = 0x03  // single 4-frame burst then stops
    const val TELEMETRY_START_EXT   = 0x04  // ch1/ch2 carry more data, ch4 silent

    /** Channel identifiers inside the 4-frame cycle. */
    const val TELEMETRY_CH_PPG1    = 0x01
    const val TELEMETRY_CH_PPG2    = 0x02
    /** ✅ 3-axis accelerometer — 6 bytes BE int16 (X_hi, X_lo, Y_hi, Y_lo, Z_hi, Z_lo); ±4g, 8192 LSB/g. */
    const val TELEMETRY_CH_ACCEL   = 0x03
    const val TELEMETRY_CH_STABILITY = 0x04
    /** LSB-per-g scaling for [TELEMETRY_CH_ACCEL] decode (verified ±4g range). */
    const val ACCEL_LSB_PER_G = 8192

    // ─── 0x3B TouchControl appType values ──────────────────────────────────────────────────────
    const val TOUCH_APP_NONE      = 0x00
    const val TOUCH_APP_MUSIC     = 0x01
    const val TOUCH_APP_VIDEO     = 0x02
    const val TOUCH_APP_CAMERA    = 0x03
    const val TOUCH_APP_EBOOK     = 0x04
    const val TOUCH_APP_PHONE     = 0x05
    /** Game mode — enables `SUB_RING_GAME_KEY` (wrist-shake) events alongside normal gestures. */
    const val TOUCH_APP_GAME      = 0x07
    const val TOUCH_APP_HEART     = 0x08
    /** Hidden magic value — NOT exposed by the QRing UI. Required for `SUB_TOUCH_GESTURE` reporting
     *  (recovered by on-device reverse-engineering of the touch-IC init handshake). */
    const val TOUCH_APP_REPORT_ALL_GESTURES = 0x09

    // ─── 0x77 PhoneSport sub-actions ───────────────────────────────────────────────────────────
    const val PHONE_SPORT_START      = 0x01
    const val PHONE_SPORT_PAUSE      = 0x02
    const val PHONE_SPORT_RESUME     = 0x03
    const val PHONE_SPORT_STOP       = 0x04
    const val PHONE_SPORT_FORCE_STOP = 0x06

    // ─── 0x60 SetANCS magic payload ────────────────────────────────────────────────────────────
    val SET_ANCS_PAYLOAD = byteArrayOf(0xFF.toByte(), 0x9F.toByte())

    // ─── 0x50 FindRing magic payload ───────────────────────────────────────────────────────────
    val FIND_RING_PAYLOAD = byteArrayOf(0x55, 0xAA.toByte())

    // ─── Pre-built command frames (the common ones; the rest are built via [command]) ─────────
    /** TouchControl: enable touch IC. `[01 00 01 01]`. */
    val TOUCH_ENABLE: ByteArray  = command(OP_TOUCH_CONTROL, byteArrayOf(0x01, 0x00, 0x01, 0x01))
    /** TouchControl: disable touch IC. */
    val TOUCH_DISABLE: ByteArray = command(OP_TOUCH_CONTROL, byteArrayOf(0x01, 0x00, 0x01, 0x00))
    /** TouchControl: write the touch-sleep variant with **hidden appType=9** so the firmware reports
     *  all four atomic gestures (SWIPE_UP/DOWN, TAP, LONG_PRESS) as `SUB_TOUCH_GESTURE` pushes.
     *  Sent ~500 ms after TOUCH_ENABLE; without this, the touch IC is on but never reports.
     *
     *  4th byte = `sleepMin` (SPEC §4.10 `[2,0,appType,sleepMin]`): how long the touch IC stays
     *  awake after the LAST touch before sleeping. Longer = touch instantly ready for longer = a bit
     *  more ring battery; shorter = sleeps sooner (saves battery) but the first touch after sleep may
     *  lag. History: 1 → 10 min (user 2026-05-28: "现在有点经常睡眠"); now wearer-tunable in
     *  Settings → Power & Connection → Ring sleep, default [DEFAULT_TOUCH_SLEEP_MIN]. */
    fun touchModeCmd(sleepMin: Int): ByteArray =
        command(OP_TOUCH_CONTROL, byteArrayOf(0x02, 0x00, TOUCH_APP_REPORT_ALL_GESTURES.toByte(), sleepMin.coerceIn(1, 255).toByte()))
    /** Default touch-IC idle-sleep timeout in minutes (wearer-tunable). */
    const val DEFAULT_TOUCH_SLEEP_MIN = 5
    // NOTE: the firmware wrist-shake event (SUB_RING_GAME_KEY 0x29) requires appType=7 (Game), and
    // the four touch gestures (SUB_TOUCH_GESTURE 0x2D) require appType=9 (REPORT_ALL_GESTURES).
    // appType is a single-slot mode selector — per SPEC v3 §4.10/§5.2 + on-device test (a dual-touch
    // [2,2,9,7] write did NOT produce shake), these are mutually exclusive on RT08. We keep the four
    // gestures (appType=9); the firmware shake is therefore unavailable. Any wrist-shake would have
    // to come from the 0xA1 accel stream (SPEC §6.3) instead. See feedback-spec-v3-ground-truth.

    /** Battery query — response arrives as a `03 <pct> <charging>` notify. Optional now that we
     *  also subscribe to `SUB_BATTERY_STATE` pushes; use only on first connect + on user demand. */
    val BATTERY_QUERY: ByteArray = command(OP_BATTERY)

    /** "Find my ring" — `0x50 [0x55, 0xAA]`, fire-and-forget. Ring LED flashes briefly, no reply.
     *  (Was previously incorrectly built from `0x06`, which is DND and returns `0xEE`.) */
    val FIND_DEVICE: ByteArray   = command(OP_FIND_RING, FIND_RING_PAYLOAD)
    /** Backwards-compatible alias — old callers (and tests) still reference this name. */
    val BLINK_TWICE: ByteArray   = FIND_DEVICE

    /** Power-off the ring. `0x08 [0x01]` per SPEC v3 §4.2 — ⛔ destructive, do not call without a
     *  user-confirmation prompt. (Was previously `0x0F`, which is OTA mode entry — far worse.) */
    val SHUTDOWN: ByteArray      = command(OP_REBOOT_POWEROFF, byteArrayOf(0x01))

    /** Empty `0x18` ping — returns an empty-body ack. Useful as a "is the ring still listening" probe. */
    val PING: ByteArray          = command(OP_PING)

    /** Marks the binding flow complete — sent from `DeviceBindActivity.onDestroy` per spec. */
    val BIND_SUCCESS: ByteArray  = command(OP_BIND_SUCCESS)

    /** `0x3C` — request the 9-byte capability bitmap (no payload). */
    val DEVICE_SUPPORT_QUERY: ByteArray = command(OP_DEVICE_SUPPORT)

    /** `0x61` — request notification-routing config. */
    val GET_MESSAGE_PUSH: ByteArray = command(OP_GET_MESSAGE_PUSH)

    /** `0x60 [0xFF, 0x9F]` — enable all ANCS notification categories. */
    val SET_ANCS: ByteArray      = command(OP_SET_ANCS, SET_ANCS_PAYLOAD)

    // ─── 0x69 measurement start/stop ──────────────────────────────────────────────────────────
    /** Start measurement of [type] using the [MEASURE_ACTION_START] sub-action. */
    fun startMeasure(type: Int): ByteArray =
        command(OP_START_MEASURE, byteArrayOf(type.toByte(), MEASURE_ACTION_START.toByte()))

    /** Stop measurement of [type]. The spec calls for `[type, 4, 0]` — non-empty payload required. */
    fun stopMeasure(type: Int): ByteArray =
        command(OP_STOP_MEASURE, byteArrayOf(type.toByte(), MEASURE_ACTION_STOP.toByte(), 0x00))

    // Pre-built convenience constants for the three common single-shot kinds.
    val REAL_TIME_HR_START:     ByteArray = startMeasure(MEASURE_HR)
    val REAL_TIME_SPO2_START:   ByteArray = startMeasure(MEASURE_SPO2)
    val REAL_TIME_STRESS_START: ByteArray = startMeasure(MEASURE_STRESS)
    val REAL_TIME_HEALTHCHECK_START: ByteArray = startMeasure(MEASURE_HEALTHCHECK)
    /** Backwards-compatible stop — historically callers issued one stop at the end of a 3-phase
     *  HR→SpO2→stress run. We default to MEASURE_HR; per-type stops use [stopMeasure]. */
    val REAL_TIME_STOP:         ByteArray = stopMeasure(MEASURE_HR)

    // ─── 0xA1 telemetry stream control ────────────────────────────────────────────────────────
    val TELEMETRY_ONE_SHOT_CMD:    ByteArray = command(OP_TELEMETRY_STREAM, byteArrayOf(TELEMETRY_ONE_SHOT.toByte()))
    val TELEMETRY_BASIC_START_CMD: ByteArray = command(OP_TELEMETRY_STREAM, byteArrayOf(TELEMETRY_START_BASIC.toByte()))
    val TELEMETRY_STOP_CMD:        ByteArray = command(OP_TELEMETRY_STREAM, byteArrayOf(TELEMETRY_STOP.toByte()))

    // ─── 0x16 HR auto-monitor read / write ────────────────────────────────────────────────────
    /** Read the autonomous-HR-every-30min config. Body returns `[01, en, interval_min, start_h, low, high, main]`. */
    val HR_AUTO_MONITOR_READ: ByteArray = command(OP_HR_AUTO_MONITOR, byteArrayOf(0x01))

    /**
     * Write the HR-auto-monitor config. Default factory value is `[02, 01, 0x1E (30), 0x05, 0, 0, 0]`
     * = enabled, 30-min interval, start_h=5 (presumably 5am UTC+8), no thresholds, main switch off.
     *
     * **Side effect when enabled**: the ring autonomously fires the PPG LED (green then red briefly)
     * every [intervalMin] minutes on a worn ring. Power-saving apps may want to disable.
     */
    fun hrAutoMonitorWrite(
        enabled: Boolean,
        intervalMin: Int = 30,
        startHour: Int = 5,
        lowThreshold: Int = 0,
        highThreshold: Int = 0,
        mainSwitch: Boolean = false,
    ): ByteArray = command(OP_HR_AUTO_MONITOR, byteArrayOf(
        0x02,                       // write
        if (enabled) 0x01 else 0x00,
        intervalMin.toByte(),
        startHour.toByte(),
        lowThreshold.toByte(),
        highThreshold.toByte(),
        if (mainSwitch) 0x01 else 0x00,
    ))

    // ─── 0x21 TargetSetting ────────────────────────────────────────────────────────────────────
    /** Read current daily targets — `[01]`. */
    val TARGET_SETTING_READ: ByteArray = command(OP_TARGET_SETTING, byteArrayOf(0x01))

    /**
     * Write daily targets. **The firmware silently rejects `steps < 100`** (clean ACK, but read-back
     * shows the unchanged prior value). Caller must enforce the floor; we coerce here as defence.
     *
     * Wire layout: `[02, steps_u24_LE, kcal_u24_LE, dist_u24_LE]`.
     */
    fun targetSettingWrite(steps: Int, kcal: Int, distanceMeters: Int): ByteArray {
        val s = steps.coerceAtLeast(100)
        return command(OP_TARGET_SETTING, byteArrayOf(0x02) + u24Le(s) + u24Le(kcal) + u24Le(distanceMeters))
    }

    // ─── 0x77 PhoneSport ──────────────────────────────────────────────────────────────────────
    /** Start a workout session on the ring. [sportType] is echoed back in `0x78` pushes; the
     *  firmware doesn't differentiate behavior between values 1..15. */
    fun phoneSportStart(sportType: Int = 1): ByteArray =
        command(OP_PHONE_SPORT, byteArrayOf(PHONE_SPORT_START.toByte(), sportType.toByte()))

    fun phoneSportStop(sportType: Int = 1): ByteArray =
        command(OP_PHONE_SPORT, byteArrayOf(PHONE_SPORT_STOP.toByte(), sportType.toByte()))

    // ─── 0x01 SetTime ─────────────────────────────────────────────────────────────────────────
    /**
     * Build the SetTime payload. **WARNING — Beijing-locked timestamps**: the firmware interprets
     * the BCD bytes as local time in **UTC+8 (Beijing) unconditionally** when it later emits Unix
     * timestamps (e.g. the `0x77` START ack). For a non-China host, send `(now_utc + 8 h)` so
     * device-emitted timestamps come out as real Unix-UTC.
     *
     * Param [language] codes: 0=zh_CN, 1=en, 2=zh_HK/TW, 3=el, 4=fr, 5=de, …, 16=ar, 17=th.
     *
     * @param yearMinus2000 year - 2000 (so 2026 → 26), BCD-packed.
     */
    fun setTime(
        yearMinus2000: Int,
        month: Int, day: Int, hour: Int, minute: Int, second: Int,
        language: Int = 1,  // default English
    ): ByteArray = command(OP_SET_TIME, byteArrayOf(
        decimalToBcd(yearMinus2000).toByte(),
        decimalToBcd(month).toByte(),
        decimalToBcd(day).toByte(),
        decimalToBcd(hour).toByte(),
        decimalToBcd(minute).toByte(),
        decimalToBcd(second).toByte(),
        language.toByte(),
    ))

    /**
     * Build a SetTime command so the ring's clock reads wall-time at [utcOffsetSeconds]. **Pass the
     * wearer's device-timezone offset** (e.g. Chicago CDT = -5h): the ring has no timezone field, it
     * just stores the BCD we send and rolls its DAY (zeroing the daily step counter) at that clock's
     * midnight. Sending Beijing time (the old hard-coded UTC+8) made the daily step count reset at
     * Beijing midnight — ~11:00 in Chicago — so "today's steps" were wrong for any non-Beijing wearer
     * (Zack 2026-05-31). Using the device offset puts the roll-over at the wearer's local midnight.
     * (SPEC §4.8: the firmware *emits* timestamps assuming UTC+8, but we don't consume those — vitals
     * are stamped with the glasses' own clock.)
     */
    fun setTimeForOffset(unixEpochSecond: Long, utcOffsetSeconds: Int): ByteArray {
        val local = unixEpochSecond + utcOffsetSeconds
        val sec  = local % 60
        val min  = (local / 60) % 60
        val hour = (local / 3600) % 24
        // Use a stripped-down GMT-Julian-to-YMD to avoid pulling java.util.Date/Calendar into :core.
        // Algorithm from Howard Hinnant's chrono paper (public domain).
        val days = local / 86400
        val z = days + 719468
        val era = if (z >= 0) z / 146097 else (z - 146096) / 146097
        val doe = (z - era * 146097)
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val yearFull = if (m <= 2) y + 1 else y
        return setTime(
            yearMinus2000 = (yearFull - 2000).toInt(),
            month = m.toInt(),
            day = d.toInt(),
            hour = hour.toInt(),
            minute = min.toInt(),
            second = sec.toInt(),
        )
    }

    /** Legacy UTC+8 variant — kept for tests/reference. Production passes the device tz offset via
     *  [setTimeForOffset] so the daily step roll-over matches the wearer's local midnight. */
    fun setTimeBeijingLocked(unixEpochSecond: Long): ByteArray = setTimeForOffset(unixEpochSecond, 8 * 3600)

    // ─── 0x04 BindAncs ────────────────────────────────────────────────────────────────────────
    fun bindAncs(sdkLevel: Int = 2, model: String = "halo-ring"): ByteArray {
        val modelBytes = model.toByteArray(Charsets.UTF_8).take(13).toByteArray()
        val payload = ByteArray(2 + modelBytes.size)
        payload[0] = 0x02
        payload[1] = sdkLevel.toByte()
        modelBytes.copyInto(payload, 2)
        return command(OP_BIND_ANCS, payload)
    }

    // ─── Frame helpers ────────────────────────────────────────────────────────────────────────
    /** Build a 16-byte command frame: `[op][payload, zero-padded][checksum = sum(0..14) & 0xFF]`. */
    fun command(code: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 14) { "payload too long: ${payload.size}" }
        val out = ByteArray(16)
        out[0] = code.toByte()
        payload.copyInto(out, destinationOffset = 1)
        var sum = 0
        for (i in 0 until 15) sum += out[i].toInt() and 0xFF
        out[15] = (sum and 0xFF).toByte()
        return out
    }

    /** Verify the 16-byte frame's sum-mod-256 checksum. */
    fun verifyChecksum(frame: ByteArray): Boolean {
        if (frame.size != 16) return false
        var sum = 0
        for (i in 0 until 15) sum += frame[i].toInt() and 0xFF
        return (sum and 0xFF).toByte() == frame[15]
    }

    /** True if [frame] is the firmware's universal "unsupported opcode" sentinel. */
    fun isUnsupported(frame: ByteArray): Boolean =
        frame.size >= 2 &&
        (frame[0].toInt() and ERROR_FLAG) != 0 &&
        (frame[1].toInt() and 0xFF) == UNSUPPORTED_SENTINEL

    /** Pack a 3-byte little-endian unsigned int (used by 0x21 TargetSetting). */
    fun u24Le(value: Int): ByteArray {
        require(value in 0..0xFFFFFF) { "value out of u24 range: $value" }
        return byteArrayOf(
            (value         and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
        )
    }

    /** Pack a 3-byte big-endian unsigned int (used by 0x73 sub=0x12 activity). */
    fun u24Be(value: Int): ByteArray {
        require(value in 0..0xFFFFFF) { "value out of u24 range: $value" }
        return byteArrayOf(
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8)  and 0xFF).toByte(),
            (value          and 0xFF).toByte(),
        )
    }

    /** Decode a 3-byte big-endian unsigned int at [offset] in [data]. Caller checks bounds. */
    fun readU24Be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 16) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        (data[offset + 2].toInt() and 0xFF)

    /** BCD-pack a decimal value 0..99 → e.g. 26 → 0x26. */
    fun decimalToBcd(value: Int): Int {
        require(value in 0..99) { "BCD out of range: $value" }
        return ((value / 10) shl 4) or (value % 10)
    }

    /** Unpack a BCD byte → decimal. `0x26` → 26. */
    fun bcdToDecimal(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return ((v shr 4) * 10) + (v and 0x0F)
    }
}
