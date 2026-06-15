package com.halo.ring.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.halo.ring.runtime.AndroidScheduler
import com.halo.ring.core.ble.ConnectionState
import com.halo.ring.core.ble.ConnectionStateSource
import com.halo.ring.core.ble.DiscoveredDevice
import com.halo.ring.core.ble.DiscoveredDevicesSource
import com.halo.ring.core.ble.HealthKind
import com.halo.ring.core.ble.R08BleClient
import com.halo.ring.core.ble.R08Frame
import com.halo.ring.core.ble.R08Protocol
import com.halo.ring.core.ble.RingEvent
import com.halo.ring.core.ble.RingEventSource
import com.halo.ring.core.ble.Subscription
import com.halo.ring.core.power.PowerPolicy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android [R08BleClient] backed by [BluetoothGatt]. Implements the recipe from
 * Doc/02-hardware-and-protocol.md §5 and the power policy from Doc/06-performance-and-power.md §3.
 *
 * Connection lifecycle:
 *  1. [start] kicks off a scan filtered by [R08Protocol.SERVICE_UUID] (+ optional MAC whitelist).
 *  2. First matching advertisement → `connectGatt(autoConnect = true)`.
 *  3. `onServicesDiscovered` enables notify on [R08Protocol.NOTIFY_CHAR_UUID], then:
 *       +800 ms TOUCH_ENABLE → +500 ms TOUCH_MODE → +1500 ms first BATTERY_QUERY → poll every 30 min.
 *  4. While "active mode" is on (recent gesture in the last ~10 s) request `CONNECTION_PRIORITY_HIGH`;
 *     relax to `CONNECTION_PRIORITY_BALANCED` when idle.
 *  5. Frame dedup: bytes identical to the previous notify within [dedupWindowMs] (default 50 ms)
 *     are dropped.
 *  6. Disconnect → autoConnect handles reconnect; we re-run the init sequence each time.
 *
 * Threading:
 *  - BLE callbacks land on a binder thread. Every callback immediately re-posts onto
 *    [scheduler] so the rest of the pipeline (GestureSynthesizer, InteractionRouter) sees a
 *    single-threaded view.
 *  - Subscriber callbacks ([events] / [connectionState]) are invoked on the scheduler thread too.
 */
class AndroidR08BleClient(
    private val context: Context,
    private val scheduler: AndroidScheduler,
    /** Optional MAC whitelist — empty means "accept any name-matching device" (legacy / first-pair).
     *  Use [setPairedMac] to restrict at runtime once the user has chosen a ring. */
    @Volatile private var macWhitelist: Set<String> = emptySet(),
    private val dedupWindowMs: Long = 50L,
) : R08BleClient {

    private val eventSubs = CopyOnWriteArrayList<(RingEvent, Long) -> Unit>()
    private val stateSubs = CopyOnWriteArrayList<(ConnectionState) -> Unit>()
    private val discoveredSubs = CopyOnWriteArrayList<(List<DiscoveredDevice>) -> Unit>()

    /** Discovered-device cache, keyed by MAC. Updated on every scan callback, fanned out to
     *  [discoveredSubs] on change. Cleared when scanning stops. */
    private val discoveredCache = LinkedHashMap<String, DiscoveredDevice>()

    /** True iff we're in *discovery* mode (scan-only, no auto-connect). Set by [startDiscovery],
     *  cleared by [stopDiscovery] / [start] / [stop]. */
    @Volatile private var discoveryMode = false

    private val serviceUuid = UUID.fromString(R08Protocol.SERVICE_UUID)
    private val writeCharUuid = UUID.fromString(R08Protocol.WRITE_CHAR_UUID)
    private val notifyCharUuid = UUID.fromString(R08Protocol.NOTIFY_CHAR_UUID)
    private val cccdUuid = UUID.fromString(R08Protocol.CCCD_UUID)
    private val devInfoSvcUuid  = UUID.fromString(R08Protocol.DEVINFO_SVC_UUID)
    private val hwRevCharUuid   = UUID.fromString(R08Protocol.HW_REV_CHAR_UUID)
    private val fwRevCharUuid   = UUID.fromString(R08Protocol.FW_REV_CHAR_UUID)

    /** ASCII strings read from the device-info service during bootstrap. Updated on
     *  [BluetoothGattCallback.onCharacteristicRead]; surfaced to UI via [hwRevisionString]. */
    @Volatile private var hwRevString: String? = null
    @Volatile private var fwRevString: String? = null

    /** Public read of the latest HW / FW revision strings — populated during bootstrap. */
    fun hwRevisionString(): String? = hwRevString
    fun fwRevisionString(): String? = fwRevString

    /** Most recently connected device's advertised name + MAC. Populated on every successful
     *  scan-then-connect; kept across disconnect/reconnect cycles so the UI's "ring name" field
     *  doesn't blank out during the 30s rescan window. */
    @Volatile private var connectedDeviceName: String? = null
    @Volatile private var connectedDeviceAddress: String? = null
    fun connectedDeviceName(): String? = connectedDeviceName
    fun connectedDeviceAddress(): String? = connectedDeviceAddress

    /** Accumulator for the union of SetTime (14B) + 0x3C (9B) capability bitmaps. Populated during
     *  bootstrap as responses arrive; emitted to the event stream as [RingEvent.Capability]. */
    @Volatile private var capabilityFlags: Set<String> = emptySet()
    @Volatile private var capabilityScreenWidth: Int = 0
    @Volatile private var capabilityScreenHeight: Int = 0
    /** True once the GATT-read of 0x2A27 has been issued for the current connection. Prevents
     *  the CCCD-write callback and the safety timer from both firing the read. Reset on disconnect. */
    @Volatile private var bootstrapStarted: Boolean = false

    fun capabilities(): Set<String> = capabilityFlags

    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    /** Latest live RSSI from [BluetoothGatt.readRemoteRssi] (callback below); null until first read
     *  / after disconnect. The scan-time RSSI only feeds the pairing picker, so the connected ring's
     *  signal must be polled separately. */
    @Volatile private var lastRssiDbm: Int? = null

    @Volatile private var state: ConnectionState = ConnectionState.DISCONNECTED
    @Volatile private var batteryPollHandle: com.halo.ring.core.gesture.Cancellable? = null
    @Volatile private var scanTimeoutHandle: com.halo.ring.core.gesture.Cancellable? = null
    /** Watchdog for the CONNECTING phase. `connectGatt(autoConnect=false)` normally fires
     *  onConnectionStateChange within 1-3 s, but on the Rokid BLE stack a failed direct connect can
     *  silently NEVER call back — leaving us pinned in CONNECTING forever (no auto-rescan, and
     *  [start] used to early-return on any non-DISCONNECTED state, so even manual reconnect was a
     *  no-op). This timer force-resolves a hung connect back to DISCONNECTED + re-scan. */
    @Volatile private var connectTimeoutHandle: com.halo.ring.core.gesture.Cancellable? = null
    @Volatile private var lastBytes: ByteArray? = null
    @Volatile private var lastBytesAt: Long = 0L
    /**
     * P1-6: cheap fingerprint of the most recently *accepted* notify (i.e. one that survived
     * dedup), readable from the binder thread without taking a lock. Used by
     * [onCharacteristicChanged] to skip the [ByteArray.copyOf] when the incoming buffer is
     * almost-certainly a same-payload duplicate within the dedup window. False negatives just
     * mean we do one redundant copy + the existing scheduler-thread dedup catches it; false
     * positives are impossible by construction (the fingerprint encodes size + selected bytes).
     *
     * Encoding: low 8 bits = size, then 4 bytes selected from the payload (positions 0, 1, len-1,
     * len/2) packed into the upper bits. Collisions on the 4 BLE-protocol frame types we see
     * (0x21 touch, 0x12 long-press release, 0x03 battery, 0x33 health) are negligible because
     * those positions encode the cmd byte + payload header which differ across frame types and
     * counter byte.
     */
    @Volatile private var lastNotifyFingerprint: Long = 0L

    /**
     * Idempotence trackers — Doc/13 §audit-2026-05-13j: [reconcilePower] runs on every BLE event
     * + wear/screen change. Without these guards, every gesture caused a redundant
     * `TOUCH_ENABLE` write to the ring (+ a `TOUCH_MODE` 500 ms later) and a redundant
     * `requestConnectionPriority` to the BT stack. Both reset to null on disconnect so a fresh
     * connection re-issues the writes.
     */
    @Volatile private var lastTouchEnabledRequested: Boolean? = null
    @Volatile private var lastTouchSleepMinRequested: Int? = null
    @Volatile private var lastIntervalModeRequested: PowerPolicy.IntervalMode? = null

    /** Pure JVM-testable BLE interval estimator (see :core `ConnIntervalEstimatorTest`). */
    private val intervalEstimator = com.halo.ring.core.perf.ConnIntervalEstimator()

    /**
     * Public read of the current BLE-interval mode last requested via [setIntervalMode]. Used by
     * [com.halo.ring.service.HaloRingService] to push the live value into `RingInfo` for the Status
     * screen. Returns BALANCED until any reconcile fires (which is on every connection-ready event
     * plus every gesture, so this is null for at most a few hundred ms after start).
     */
    fun currentIntervalMode(): PowerPolicy.IntervalMode =
        lastIntervalModeRequested ?: PowerPolicy.IntervalMode.BALANCED

    /** Median inter-notify delta in ms over the recent window, or null until enough samples. */
    fun estimatedConnIntervalMs(): Int? = intervalEstimator.estimate()

    /** Trigger an async live-RSSI read (result lands in [lastRssiDbm] via onReadRemoteRssi). No-op +
     *  clears the value when not connected. Called ~1/s by the service's ring-info refresh. */
    fun pollRssi() {
        val g = gatt
        if (g == null || state != ConnectionState.READY) { lastRssiDbm = null; return }
        try { g.readRemoteRssi() } catch (_: SecurityException) {}
    }

    /** Most-recent live RSSI in dBm (null if unread / disconnected). */
    fun currentRssiDbm(): Int? = lastRssiDbm

    // ── R08BleClient ───────────────────────────────────────────────────────────────────────────

    override fun events() = RingEventSource { onEvent ->
        eventSubs.add(onEvent)
        Subscription { eventSubs.remove(onEvent) }
    }

    override fun connectionState() = ConnectionStateSource { onState ->
        stateSubs.add(onState)
        scheduler.post { onState(state) }   // replay-on-subscribe
        Subscription { stateSubs.remove(onState) }
    }

    override fun discoveredDevices() = DiscoveredDevicesSource { onUpdate ->
        discoveredSubs.add(onUpdate)
        scheduler.post { onUpdate(discoveredCache.values.toList()) }
        Subscription { discoveredSubs.remove(onUpdate) }
    }

    override fun setPairedMac(mac: String?) {
        scheduler.post {
            macWhitelist = if (mac == null) emptySet() else setOf(mac.uppercase())
            Log.i(TAG, "paired MAC = ${mac ?: "<cleared>"}")
            // If we currently have a stale GATT for a different MAC, tear it down so [start] re-scans.
            if (mac != null && gatt?.device?.address?.uppercase() != mac.uppercase()) {
                try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
                gatt = null
                writeChar = null
                transitionTo(ConnectionState.DISCONNECTED)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery() {
        Log.i(TAG, "startDiscovery()")
        scheduler.post {
            val mgr = context.getSystemService(BluetoothManager::class.java) ?: run { Log.e(TAG, "startDiscovery: no BluetoothManager"); return@post }
            val a = mgr.adapter ?: run { Log.e(TAG, "startDiscovery: no adapter"); return@post }
            if (!a.isEnabled) { Log.e(TAG, "startDiscovery: adapter not enabled"); return@post }
            adapter = a
            // Tear down any existing connection / scan first.
            if (state != ConnectionState.DISCONNECTED) {
                try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
                try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
                gatt = null
                writeChar = null
                transitionTo(ConnectionState.DISCONNECTED)
            }
            discoveredCache.clear()
            fanOutDiscoveries()
            discoveryMode = true
            transitionTo(ConnectionState.SCANNING)
            beginScan()
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery() {
        scheduler.post {
            if (!discoveryMode) return@post
            discoveryMode = false
            try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
            scanTimeoutHandle?.cancel(); scanTimeoutHandle = null
            transitionTo(ConnectionState.DISCONNECTED)
        }
    }

    private fun fanOutDiscoveries() {
        val snapshot = discoveredCache.values.sortedByDescending { it.rssiDbm }
        discoveredSubs.forEach { it(snapshot) }
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        scheduler.post {
            val mgr = context.getSystemService(BluetoothManager::class.java) ?: run {
                Log.e(TAG, "BluetoothManager unavailable")
                return@post
            }
            val a = mgr.adapter
            if (a == null || !a.isEnabled) {
                Log.w(TAG, "Bluetooth not available/enabled — staying DISCONNECTED")
                return@post
            }
            adapter = a
            wantConnection = true
            // Don't let an auto-connect start() (e.g. the service's wear-state reconcile) tear down
            // and restart the scanner while the user is actively in the pairing picker — that churn
            // re-registers the LE scanner faster than some stacks allow and yields scan errors
            // (RayNeo X3 Pro returns SCAN_FAILED_FEATURE_UNSUPPORTED/ALREADY_STARTED; the unfiltered
            // discovery scan then never delivers results). Discovery owns the scanner until the user
            // picks a ring (which calls stopDiscovery → setPairedMac → start). No-op on Rokid (its
            // wear-reconcile doesn't collide with discovery in practice).
            if (discoveryMode) {
                Log.w(TAG, "start() ignored — discovery picker owns the scanner")
                return@post
            }
            when (state) {
                // Already connected — a stray start() must not drop a healthy link.
                ConnectionState.READY -> {
                    Log.d(TAG, "start() ignored — already READY")
                    return@post
                }
                // An in-progress attempt that may be stuck (the Rokid stack can hang a direct
                // connect with no callback). The old code early-returned here, so a user tapping
                // "connect"/"re-pair" during a hung attempt got NOTHING. Tear the attempt down and
                // restart cleanly so the action is always responsive.
                ConnectionState.SCANNING, ConnectionState.CONNECTING -> {
                    // RayNeo only: a scan is already running — DON'T stop+restart it. RayNeo's BLE
                    // stack returns SCAN_FAILED_FEATURE_UNSUPPORTED when a scan is stopped and
                    // re-issued within a few ms (the churn from overlapping start()/reconcile calls),
                    // and then never delivers results. Keeping the in-flight scan lets it actually
                    // find the ring. Rokid keeps the original tear-down+restart (stack tolerates it).
                    if (state == ConnectionState.SCANNING &&
                        com.halo.ring.BuildConfig.DEVICE_FLAVOR == "rayneo") {
                        Log.w(TAG, "start() while SCANNING (rayneo) — keeping the active scan, not restarting")
                        return@post
                    }
                    Log.w(TAG, "start() while $state — tearing down + restarting scan")
                    try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
                    connectTimeoutHandle?.cancel(); connectTimeoutHandle = null
                    scanTimeoutHandle?.cancel(); scanTimeoutHandle = null
                    try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
                    gatt = null
                    writeChar = null
                    transitionTo(ConnectionState.DISCONNECTED)
                }
                ConnectionState.DISCONNECTED -> { /* normal start — fall through to scan */ }
            }
            reconnectBackoffMs = RECONNECT_BACKOFF_BASE_MS   // explicit start → snappy first scan
            transitionTo(ConnectionState.SCANNING)
            beginScan()
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        scheduler.post {
            wantConnection = false
            try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
            batteryPollHandle?.cancel(); batteryPollHandle = null
            scanTimeoutHandle?.cancel(); scanTimeoutHandle = null
            connectTimeoutHandle?.cancel(); connectTimeoutHandle = null
            try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
            gatt = null
            writeChar = null
            lastTouchEnabledRequested = null
            lastTouchSleepMinRequested = null
            lastIntervalModeRequested = null
            lastBytes = null
            lastBytesAt = 0L
            lastNotifyFingerprint = 0L
            transitionTo(ConnectionState.DISCONNECTED)
        }
    }

    override fun setTouchEnabled(enabled: Boolean, sleepMin: Int) {
        scheduler.post {
            // Talking to the ring when we're not connected is pointless — and used to spam
            // `writeChar not yet available` once per reconcile cycle. Now silently ignored.
            if (state != ConnectionState.READY) return@post
            // Doc/13 §audit-2026-05-13j: reconcilePower fires on every BLE event, so without
            // this guard every gesture writes TOUCH_ENABLE + TOUCH_MODE redundantly. The flags
            // are cleared on disconnect so a fresh connection re-arms the writes.
            val wasEnabled = lastTouchEnabledRequested == true
            if (lastTouchEnabledRequested == enabled && lastTouchSleepMinRequested == sleepMin) return@post
            lastTouchEnabledRequested = enabled
            lastTouchSleepMinRequested = sleepMin
            if (!enabled) {
                writeBytes(R08Protocol.TOUCH_DISABLE)
                return@post
            }
            if (!wasEnabled) {
                // Fresh enable: TOUCH_ENABLE, then TOUCH_MODE (carrying the sleep timeout) ~500 ms later.
                writeBytes(R08Protocol.TOUCH_ENABLE)
                scheduler.postDelayed(500L) {
                    if (state == ConnectionState.READY) writeBytes(R08Protocol.touchModeCmd(sleepMin))
                }
            } else {
                // Already enabled, only the sleep timeout changed — re-send TOUCH_MODE alone.
                writeBytes(R08Protocol.touchModeCmd(sleepMin))
            }
        }
    }

    override fun queryBattery() { scheduler.post { if (state == ConnectionState.READY) writeBytes(R08Protocol.BATTERY_QUERY) } }
    override fun blinkLed()     { scheduler.post { if (state == ConnectionState.READY) writeBytes(R08Protocol.BLINK_TWICE) } }
    override fun shutdownRing() { scheduler.post { if (state == ConnectionState.READY) writeBytes(R08Protocol.SHUTDOWN) } }

    @SuppressLint("MissingPermission")
    override fun setIntervalMode(mode: PowerPolicy.IntervalMode) {
        scheduler.post {
            // Doc/13 §audit-2026-05-13j: same idempotence guard as setTouchEnabled. Every gesture
            // triggers reconcilePower, but the requested band rarely changes between gestures —
            // hitting requestConnectionPriority(...) every time burns a BLE LL control PDU per
            // gesture for no benefit.
            if (lastIntervalModeRequested == mode) return@post
            lastIntervalModeRequested = mode
            val priority = when (mode) {
                PowerPolicy.IntervalMode.HIGH     -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
                PowerPolicy.IntervalMode.BALANCED -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                PowerPolicy.IntervalMode.SLOW     -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            }
            try { gatt?.requestConnectionPriority(priority) } catch (_: SecurityException) {}
        }
    }

    @Volatile private var vitalsSnapshotInFlight = false
    /** Burn-in 2026-05-27: user confirmed both PPG sensors (green for HR, red for SpO2) can light
     *  simultaneously, so we kick off both measurements in parallel and wait for both to converge.
     *  Tracks (kind → typeCode) for every in-flight phase; cleared as each converges. */
    private val vitalsPending = mutableMapOf<HealthKind, Int>()
    private var vitalsStopHandle: com.halo.ring.core.gesture.Cancellable? = null

    /**
     * Spec-conformant vitals snapshot (SPEC v3 §4.5). **Parallel HR + SpO2** — user-verified on
     * RT08_3.10.46 that both green (HR) and red (SpO2) PPG LEDs light simultaneously, so we fire
     * both `0x69 [type, 01]` start commands back-to-back instead of sequentially.
     *
     * Stress (type=0x08) is omitted entirely — §4.5 marks it `🟡 progress-only` and live
     * observation confirms `val` stays 0; surfacing a stuck-at-0 metric to UI was misleading.
     *
     * Convergence is detected in [onNotify] (`err=0x00` + `val ≠ 0` per §4.5); each converged
     * Health frame triggers `0x6A stopMeasure(type)` for that specific kind. When both have
     * converged or [VITALS_SAFETY_TIMEOUT_MS] (30s) elapses, the snapshot completes.
     */
    override fun requestVitalsSnapshot() {
        scheduler.post {
            if (vitalsSnapshotInFlight) {
                Log.d(TAG, "vitals snapshot already in flight — ignoring")
                return@post
            }
            vitalsSnapshotInFlight = true
            vitalsPending.clear()
            vitalsPending[HealthKind.HEART_RATE] = R08Protocol.MEASURE_HR
            vitalsPending[HealthKind.SPO2]       = R08Protocol.MEASURE_SPO2
            Log.i(TAG, "vitals snapshot start — parallel HR + SpO2")
            writeBytes(R08Protocol.REAL_TIME_HR_START)
            // 150 ms spacing so the BLE stack queues both writes cleanly instead of dropping the
            // second when the first hasn't yet hit the wire.
            scheduler.postDelayed(150L) {
                if (vitalsSnapshotInFlight) writeBytes(R08Protocol.REAL_TIME_SPO2_START)
            }
            vitalsStopHandle?.cancel()
            vitalsStopHandle = scheduler.postDelayed(VITALS_SAFETY_TIMEOUT_MS) {
                if (vitalsSnapshotInFlight) {
                    val unresolved = vitalsPending.toMap()
                    Log.w(TAG, "vitals safety timeout — stopping unresolved kinds: ${unresolved.keys}")
                    unresolved.values.forEach { writeBytes(R08Protocol.stopMeasure(it)) }
                    completeVitalsSnapshot("safety-timeout")
                }
            }
        }
    }

    /** Called from [onNotify] when a Health frame for one of the in-flight kinds has converged.
     *  Stops just that kind; the others continue. When [vitalsPending] is empty, the snapshot
     *  is complete. */
    private fun advanceVitalsSnapshot(kind: HealthKind) {
        val typeCode = vitalsPending.remove(kind) ?: return
        writeBytes(R08Protocol.stopMeasure(typeCode))
        if (vitalsPending.isEmpty()) completeVitalsSnapshot("all-converged")
    }

    /** Terminate the in-flight vitals snapshot (success / timeout / disconnect) and emit
     *  [RingEvent.VitalsSnapshotComplete] so the UI can flip its `measuring` flag off. Without
     *  this the snapshot flow's `measuring` stayed true forever — HR set it to true on each
     *  progress frame but nothing cleared it (Zack 2026-05-29 "心率测试会一直卡住"). */
    private fun completeVitalsSnapshot(reason: String) {
        vitalsStopHandle?.cancel(); vitalsStopHandle = null
        if (!vitalsSnapshotInFlight) return
        vitalsSnapshotInFlight = false
        vitalsPending.clear()
        Log.i(TAG, "vitals snapshot complete ($reason)")
        val nowMs = scheduler.nowMs()
        eventSubs.forEach { it(RingEvent.VitalsSnapshotComplete, nowMs) }
    }

    override fun findRing() {
        scheduler.post { if (state == ConnectionState.READY) writeBytes(R08Protocol.FIND_DEVICE) }
    }

    override fun setHrAutoMonitor(enabled: Boolean, intervalMinutes: Int) {
        scheduler.post {
            if (state != ConnectionState.READY) return@post
            writeBytes(R08Protocol.hrAutoMonitorWrite(enabled = enabled, intervalMin = intervalMinutes))
        }
    }

    override fun setDailyTarget(steps: Int, kcal: Int, distanceMeters: Int) {
        scheduler.post {
            if (state != ConnectionState.READY) return@post
            writeBytes(R08Protocol.targetSettingWrite(steps, kcal, distanceMeters))
            // SPEC §4.9: firmware silently rejects steps < 100 with a clean ACK. Read back so the
            // service can detect silent-persist failure.
            scheduler.postDelayed(300L) {
                if (state == ConnectionState.READY) writeBytes(R08Protocol.TARGET_SETTING_READ)
            }
        }
    }

    override fun startSportSession(sportType: Int) {
        scheduler.post {
            if (state != ConnectionState.READY) return@post
            writeBytes(R08Protocol.phoneSportStart(sportType))
        }
    }

    override fun stopSportSession(sportType: Int) {
        scheduler.post {
            if (state != ConnectionState.READY) return@post
            writeBytes(R08Protocol.phoneSportStop(sportType))
        }
    }

    @Volatile private var accelStreaming = false

    override fun startAccelStream(continuous: Boolean) {
        scheduler.post {
            if (state != ConnectionState.READY) return@post
            if (continuous) {
                if (accelStreaming) return@post
                accelStreaming = true
                writeBytes(R08Protocol.TELEMETRY_BASIC_START_CMD)
            } else {
                writeBytes(R08Protocol.TELEMETRY_ONE_SHOT_CMD)
            }
        }
    }

    override fun stopAccelStream() {
        scheduler.post {
            if (!accelStreaming) return@post
            accelStreaming = false
            if (state == ConnectionState.READY) writeBytes(R08Protocol.TELEMETRY_STOP_CMD)
        }
    }

    // ── scanning ──────────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun beginScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "no BluetoothLeScanner; staying disconnected"); return
        }
        // SPEC v3 §1.2 — burn-in fix 2026-05-27: the ring advertises ONLY its local name. No
        // service UUID, no manufacturer-data, no service-data on the wire. So filtering by
        // setServiceUuid yields ZERO matches and our 30 s scan times out without ever calling
        // back. Scan unfiltered and rely on the in-code name-keyword match in [onScanResult].
        //
        // Power note: an unfiltered LOW_LATENCY scan is more expensive than a filtered one, but
        // SCAN_TIMEOUT_MS caps it at 30 s and we connectGatt(autoConnect=true) the moment we
        // see a match, so steady-state cost is unchanged.
        // RayNeo X3 Pro: its BLE stack rejects a fully UNFILTERED scan with
        // SCAN_FAILED_FEATURE_UNSUPPORTED (verified on ARGF20 — the stack scans but never delivers
        // to our client). Provide a ScanFilter (the standard workaround). Rokid keeps the unfiltered
        // scan it's always used (the ring advertises no service UUID there, so a filter would miss).
        // RayNeo needs a NON-EMPTY filter list, but the ring advertises no service UUID (only its
        // local name), so we can't filter by UUID. An EMPTY ScanFilter (no criteria) is a "match
        // all" filter — it satisfies RayNeo's "must have a filter" requirement while still delivering
        // every advertisement, so the name-keyword match in onScanResult still finds "R08_XXXX".
        val isRayneo = com.halo.ring.BuildConfig.DEVICE_FLAVOR == "rayneo"
        val filters: List<ScanFilter> = if (isRayneo) {
            listOf(ScanFilter.Builder().build())
        } else {
            emptyList()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)   // brief, then we connectGatt(autoConnect=true)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            Log.i(TAG, "beginScan: startScan issued")
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan denied: ${e.message}")
            return
        }
        // Don't sit at LOW_LATENCY indefinitely — that's a steady-state power drain. Give up after
        // SCAN_TIMEOUT_MS and fall back to DISCONNECTED; the user re-initiates via "Reconnect" or
        // wear-state change kicks us back to start().
        scanTimeoutHandle?.cancel()
        scanTimeoutHandle = scheduler.postDelayed(SCAN_TIMEOUT_MS) {
            if (state == ConnectionState.SCANNING) {
                Log.w(TAG, "no ring found within ${SCAN_TIMEOUT_MS} ms; backing off ${reconnectBackoffMs} ms")
                try { scanner.stopScan(scanCallback) } catch (_: SecurityException) {}
                transitionTo(ConnectionState.DISCONNECTED)
                // Escalate backoff and keep trying (slower) while we still want a connection — the
                // ring may just be out of range. Capped so the worst case is one short scan every
                // RECONNECT_BACKOFF_MAX_MS, not a tight loop.
                if (wantConnection) {
                    reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
                    scanTimeoutHandle = scheduler.postDelayed(reconnectBackoffMs) {
                        if (wantConnection && state == ConnectionState.DISCONNECTED) {
                            transitionTo(ConnectionState.SCANNING)
                            beginScan()
                        }
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            // Prefer the ADVERTISED local name from the scan record. `device.name` is the GATT/bond
            // name and on some BLE stacks (RayNeo X3 Pro / AIOS) returns null during a scan — the ring
            // then never matched the name-keyword filter and "什么都没扫描到". The advertised name in
            // the scan record is present pre-connect, so use it first; fall back to device.name.
            val advName = try { result.scanRecord?.deviceName } catch (_: Exception) { null }
            val gattName = try { device.name } catch (_: SecurityException) { null }
            val name = advName ?: gattName
            val rssi = result.rssi
            // Burn-in fix 2026-05-27: name-keyword filter — only surface devices whose advertised
            // local name matches an R0x-family prefix. (SPEC v3 §1.2 — no service UUID is in the
            // advertisement, so this is the only safe pre-connect filter.) A nameless adv that
            // happens to be from the ring after the user has paired before will be picked up via
            // macWhitelist below.
            val nameMatch = name?.let { n ->
                R08Protocol.DEVICE_NAME_KEYWORDS.any { n.startsWith(it) || n.contains(it) }
            } ?: false
            val macMatch = macWhitelist.isNotEmpty() && device.address.uppercase() in macWhitelist
            if (!nameMatch && !macMatch) return

            // Push into the discovery cache regardless of which mode we're in; the picker UI
            // observes this.
            scheduler.post {
                discoveredCache[device.address.uppercase()] = DiscoveredDevice(
                    macAddress = device.address.uppercase(),
                    advertisedName = name,
                    rssiDbm = rssi,
                    lastSeenMs = scheduler.nowMs(),
                )
                fanOutDiscoveries()
            }

            if (discoveryMode) return   // discovery: never auto-connect, the user picks

            // Connect path: prefer the paired MAC if set; otherwise the FIRST name-match wins.
            // The race-to-first-match was the cause of the 2026-05-27 "phantom auto-connect" bug.
            // We mitigate by (a) requiring a name-prefix match (above) AND (b) once the user
            // pairs explicitly, macWhitelist locks future connections to that MAC.
            if (macWhitelist.isNotEmpty() && device.address.uppercase() !in macWhitelist) return
            Log.w(TAG, "ring discovered: name=$name address=${device.address} rssi=$rssi")
            try { adapter?.bluetoothLeScanner?.stopScan(this) } catch (_: SecurityException) {}
            scheduler.post {
                scanTimeoutHandle?.cancel()
                scanTimeoutHandle = null
                // Burn-in 2026-05-27 (user feedback): the status bar was rendering "R08_…"
                // placeholder + red icon because we never captured the advertisedName/MAC.
                // Snapshot them now so HaloRingService can write into ringInfoFlow on READY.
                connectedDeviceName = name
                connectedDeviceAddress = device.address.uppercase()
                connectTo(device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scheduler.post { transitionTo(ConnectionState.DISCONNECTED) }
        }
    }

    // ── connect & GATT callbacks ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        transitionTo(ConnectionState.CONNECTING)
        try {
            // On-glasses fix 2026-05-27: was `autoConnect=true`, which on this stack took ~57 s to
            // establish the FIRST connection (the OS waits for a slow background-scan window). The
            // pairing UI sat at "Connecting 2/3" the whole time. `autoConnect=false` is a DIRECT
            // connect — completes in ~1-3 s when the ring is in range (we just saw it on the
            // active scan). We trade the OS's free auto-reconnect for our own re-scan in the
            // disconnect handler below (gated on [wantConnection]); given the ring's SPEC §6.5
            // ~10-20 s reconnect quirk we were re-scanning constantly anyway.
            gatt = device.connectGatt(
                context,
                /* autoConnect */ false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "connectGatt denied: ${e.message}")
            transitionTo(ConnectionState.DISCONNECTED)
            return
        }
        // Watchdog: a direct connect must reach READY (or fire a DISCONNECTED callback) within
        // CONNECT_TIMEOUT_MS. If it doesn't (Rokid stack hang, or STATE_CONNECTED with no service
        // discovery), force it back to DISCONNECTED + re-scan so we never pin at CONNECTING. Cleared
        // on READY ([onServicesDiscovered]) and on any onConnectionStateChange.
        connectTimeoutHandle?.cancel()
        connectTimeoutHandle = scheduler.postDelayed(CONNECT_TIMEOUT_MS) {
            if (state == ConnectionState.CONNECTING) {
                Log.w(TAG, "connect timed out after ${CONNECT_TIMEOUT_MS} ms — tearing down + re-scanning")
                try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
                gatt = null
                writeChar = null
                transitionTo(ConnectionState.DISCONNECTED)
                if (wantConnection) {
                    reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
                    scanTimeoutHandle?.cancel()
                    scanTimeoutHandle = scheduler.postDelayed(reconnectBackoffMs) {
                        if (wantConnection && state == ConnectionState.DISCONNECTED) {
                            transitionTo(ConnectionState.SCANNING)
                            beginScan()
                        }
                    }
                }
            }
        }
    }

    /** True between [start] and [stop] — drives whether the disconnect handler re-scans (direct
     *  connect has no OS auto-reconnect, so we re-scan ourselves) or stays down (intentional stop). */
    @Volatile private var wantConnection = false
    /** Energy: backoff before an auto-reconnect re-scan. Starts at [RECONNECT_BACKOFF_BASE_MS],
     *  doubles on each consecutive scan that finds no ring (capped at [RECONNECT_BACKOFF_MAX_MS]),
     *  resets to base on a successful READY. Keeps the SPEC §6.5 quirk-drop reconnect snappy while
     *  preventing a permanent LOW_LATENCY scan loop when the ring is genuinely out of range. */
    @Volatile private var reconnectBackoffMs = RECONNECT_BACKOFF_BASE_MS

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) lastRssiDbm = rssi
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scheduler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // Rokid Glasses' BLE chip stalls service discovery if discoverServices()
                        // is called immediately after STATE_CONNECTED — observed as 10-20 s hang
                        // at "Connecting 2/3". 300 ms settle gives the link layer time to finish
                        // negotiating before the GATT ATT exchange begins. harmless on OnePlus.
                        Log.w(TAG, "GATT connected (status=$status), discovering services in ${GATT_SETTLE_MS} ms")
                        scheduler.postDelayed(GATT_SETTLE_MS) {
                            if (gatt != null) try { g.discoverServices() } catch (_: SecurityException) {}
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "GATT disconnected (status=$status)")
                        connectTimeoutHandle?.cancel(); connectTimeoutHandle = null
                        batteryPollHandle?.cancel(); batteryPollHandle = null
                        // Drop stale notify timestamps so a fresh connection's estimator isn't
                        // biased by the gap between the old and new connection's samples.
                        intervalEstimator.reset()
                        // D4: abort any in-flight vitals snapshot — otherwise the flag stays set
                        // and the UI can never re-trigger MEASURE NOW until the app restarts.
                        if (vitalsSnapshotInFlight) {
                            Log.w(TAG, "aborting in-flight vitals snapshot due to disconnect")
                            completeVitalsSnapshot("disconnect")
                        }
                        accelStreaming = false
                        hwRevString = null
                        fwRevString = null
                        capabilityFlags = emptySet()
                        capabilityScreenWidth = 0
                        capabilityScreenHeight = 0
                        bootstrapStarted = false
                        writeChar = null
                        // Audit-2026-05-13j: clear idempotence trackers so the next connection's
                        // init sequence re-issues TOUCH_ENABLE/TOUCH_MODE and the first
                        // reconcilePower writes a fresh connection priority. Stale flags would
                        // suppress these and leave the ring with no touch IC after reconnect.
                        lastTouchEnabledRequested = null
                        lastTouchSleepMinRequested = null
                        lastIntervalModeRequested = null
                        // autoConnect=false has no OS auto-reconnect. Close the dead GATT and,
                        // if we still want a connection (not an intentional stop()), re-scan for
                        // the ring — fast direct re-connect when it re-advertises (SPEC §6.5 quirk).
                        try { g.close() } catch (_: SecurityException) {}
                        gatt = null
                        if (wantConnection) {
                            // Energy fix (audit 2026-05-27): don't re-scan back-to-back. With
                            // autoConnect=false we own reconnect, and the ring's SPEC §6.5 quirk
                            // drops the link every ~10-20 s — an immediate LOW_LATENCY re-scan each
                            // time = a permanent scan loop. Wait `reconnectBackoffMs` first; it
                            // starts small (so the quirk-drop reconnect is still snappy) and only
                            // escalates if scans keep failing (ring truly gone). Reset to 0 on READY.
                            transitionTo(ConnectionState.DISCONNECTED)
                            scanTimeoutHandle?.cancel()
                            scanTimeoutHandle = scheduler.postDelayed(reconnectBackoffMs) {
                                if (wantConnection && state == ConnectionState.DISCONNECTED) {
                                    transitionTo(ConnectionState.SCANNING)
                                    beginScan()
                                }
                            }
                        } else {
                            transitionTo(ConnectionState.DISCONNECTED)
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scheduler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "service discovery failed: $status"); return@post
                }
                val service = g.getService(serviceUuid) ?: run {
                    Log.w(TAG, "service ${serviceUuid} not found on device"); return@post
                }
                writeChar = service.getCharacteristic(writeCharUuid)
                val notify = service.getCharacteristic(notifyCharUuid) ?: run {
                    Log.w(TAG, "notify char missing"); return@post
                }
                // Enable notifications + start the CCCD descriptor write. The actual GATT-level
                // bootstrap (read 0x2A27 / 0x2A26 / SetTime / …) waits for [onDescriptorWrite]
                // because Android serialises GATT ops: issuing readCharacteristic *while* the
                // CCCD write is still in flight makes the read silently fail on some firmware
                // (observed on RT08_3.10.46 via OnePlus 9 Pro — burn-in 2026-05-27).
                try {
                    g.setCharacteristicNotification(notify, true)
                    val cccd = notify.getDescriptor(cccdUuid)
                    if (cccd != null) {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val accepted = g.writeDescriptor(cccd)
                        if (!accepted) Log.w(TAG, "writeDescriptor returned false — bootstrap may stall")
                    } else {
                        Log.w(TAG, "CCCD descriptor missing on notify char — kicking off bootstrap without notify")
                        kickOffReadyGateRead(g)   // best-effort fallback
                    }
                } catch (_: SecurityException) {}
                connectTimeoutHandle?.cancel(); connectTimeoutHandle = null   // connect succeeded
                reconnectBackoffMs = RECONNECT_BACKOFF_BASE_MS   // healthy connection → reset backoff
                transitionTo(ConnectionState.READY)

                // Safety: if onDescriptorWrite never fires (some firmware acks lazily or the
                // platform drops the callback under load), kick off the read-gate anyway after
                // a generous timeout. Idempotent because [bootstrapStarted] gates it.
                scheduler.postDelayed(CCCD_ACK_SAFETY_MS) {
                    if (!bootstrapStarted && state == ConnectionState.READY) {
                        Log.w(TAG, "CCCD write ack never fired within ${CCCD_ACK_SAFETY_MS} ms — kicking off bootstrap anyway")
                        kickOffReadyGateRead(g)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            scheduler.post {
                if (descriptor.uuid != cccdUuid) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "CCCD write failed: status=$status — kicking off bootstrap anyway")
                }
                kickOffReadyGateRead(g)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            scheduler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "characteristic read failed: status=$status uuid=${ch.uuid}")
                    return@post
                }
                when (ch.uuid) {
                    hwRevCharUuid -> {
                        hwRevString = ch.value?.toString(Charsets.UTF_8)?.trim()
                        Log.i(TAG, "HW revision: ${hwRevString}")
                        // Chain into FW read.
                        val fwRev = g.getService(devInfoSvcUuid)?.getCharacteristic(fwRevCharUuid)
                        if (fwRev != null) {
                            try { g.readCharacteristic(fwRev) }
                            catch (_: SecurityException) { startCommandChannelBootstrap() }
                        } else {
                            startCommandChannelBootstrap()
                        }
                    }
                    fwRevCharUuid -> {
                        fwRevString = ch.value?.toString(Charsets.UTF_8)?.trim()
                        Log.i(TAG, "FW revision: ${fwRevString}")
                        // 500 ms wait then command-channel bootstrap (SPEC §2.1).
                        scheduler.postDelayed(BOOTSTRAP_READY_GATE_MS) {
                            startCommandChannelBootstrap()
                        }
                    }
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            // Hot path on the BT binder thread — keep allocation-free in the steady-state dup case.
            val raw = ch.value ?: return
            val now = scheduler.nowMs()
            // P1-6: skip copyOf when the incoming buffer matches the most recent accepted notify
            // by fingerprint AND we're inside the dedup window. This shortcuts the typical
            // "same TOUCH frame redelivered every conn-interval until the user releases" case
            // — at HIGH band that's ~33 redundant copies/s on the binder thread (GC hot region).
            val fp = fingerprintOf(raw)
            if (fp == lastNotifyFingerprint && now - lastBytesAt < dedupWindowMs) return
            val bytes = raw.copyOf()
            scheduler.post { onNotify(bytes, now, fp) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeBytes(bytes: ByteArray, retriesLeft: Int = WRITE_MAX_RETRIES) {
        val ch = writeChar ?: run {
            Log.w(TAG, "writeBytes: writeChar not yet available, dropping ${bytes.size}-byte cmd")
            return
        }
        try {
            ch.value = bytes
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            // Android BLE allows only ONE outstanding GATT op — if another op (a read, the CCCD
            // write, or a prior command whose onCharacteristicWrite hasn't returned) is still in
            // flight, writeCharacteristic returns false and the write is **silently dropped**. The
            // old code ignored that return, so an unlucky drop lost a command outright — observed
            // 2026-05-31 as the bootstrap SetTime (0x01) getting dropped, so the ring never sent its
            // 14-byte capability extension and bloodOxygen/HR/HRV/temperature caps went missing
            // (SpO₂ readout disappeared). Retry shortly, bounded, so the next free slot picks it up.
            val ok = gatt?.writeCharacteristic(ch)
            if (ok != true && retriesLeft > 0) {
                scheduler.postDelayed(WRITE_RETRY_MS) {
                    if (state == ConnectionState.READY) writeBytes(bytes, retriesLeft - 1)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "writeCharacteristic denied: ${e.message}")
        }
    }

    // ── notify handling: dedup → parse → fan out ──────────────────────────────────────────────

    private fun onNotify(bytes: ByteArray, nowMs: Long, fp: Long) {
        val prev = lastBytes
        if (prev != null
            && prev.size == bytes.size
            && prev.contentEquals(bytes)
            && nowMs - lastBytesAt < dedupWindowMs
        ) {
            return  // duplicate within window — drop silently
        }
        lastBytes = bytes
        lastBytesAt = nowMs
        // P1-6: publish the fingerprint for the binder thread's pre-copy filter. Visible to the
        // BT stack's binder thread on the next notify.
        lastNotifyFingerprint = fp

        // Record this notify's timestamp for the interval estimator. The estimator's internal
        // lock is held only for the O(1) record path; the UI-thread `estimate()` call holds it
        // for the median-window scan.
        intervalEstimator.record(nowMs)

        // Capability accumulator — SetTime + 0x3C responses carry the 14B / 9B bitmaps we use to
        // gate UI features. These don't parse through R08Frame (they're not part of the sub-id
        // event bus); pick them off here before the standard parse.
        val op = bytes[0].toInt() and 0xFF
        if (op == R08Protocol.OP_SET_TIME && bytes.size >= 16) {
            // payload occupies bytes[1..14]
            val caps = R08Frame.parseSetTimeCapabilities(bytes.copyOfRange(1, 15))
            capabilityFlags = capabilityFlags + caps.flags
            capabilityScreenWidth = caps.screenWidth
            capabilityScreenHeight = caps.screenHeight
            eventSubs.forEach { it(RingEvent.Capability(capabilityFlags, capabilityScreenWidth, capabilityScreenHeight), nowMs) }
        } else if (op == R08Protocol.OP_DEVICE_SUPPORT && bytes.size >= 11) {
            // 9-byte bitmap at payload[0..8] = wire bytes [1..9]
            val flags = R08Frame.parseDeviceSupportCapabilities(bytes.copyOfRange(1, 10))
            capabilityFlags = capabilityFlags + flags
            eventSubs.forEach { it(RingEvent.Capability(capabilityFlags, capabilityScreenWidth, capabilityScreenHeight), nowMs) }
        }

        // R08Frame.parse returns RingEvent.Unknown for anything it can't decode, never null —
        // we still forward Unknowns so the debug HUD / logging can render the raw hex.
        val event = R08Frame.parse(bytes)
        // Burn-in 2026-05-27: while a vitals snapshot is in flight, log every notify so we can
        // see what the ring is actually emitting. Healthcheck composite (`0x69 type=05`) might
        // return progress frames with val=0 forever (PPG contact issue), or might respond on a
        // different opcode the parser doesn't yet recognise. This is the diagnostic hook to
        // distinguish those cases.
        if (vitalsSnapshotInFlight) {
            Log.d(TAG, "vitals-notify ${R08Frame.hex(bytes)} → ${event::class.simpleName}")
        }
        // P0 vitals snapshot: stop the live measurement on converged Health or wear-fail.
        when (event) {
            is RingEvent.Health -> {
                if (vitalsSnapshotInFlight && vitalsPending.containsKey(event.kind)) {
                    Log.i(TAG, "vitals converged ${event.kind} val=${event.value} sbp=${event.systolic} dbp=${event.diastolic}")
                    advanceVitalsSnapshot(event.kind)
                }
            }
            is RingEvent.WearDetectFail -> {
                // Wear-detect failure affects PPG broadly (sensor can't see finger). Abort all
                // pending kinds — the user needs to adjust the ring before retrying.
                if (vitalsSnapshotInFlight) {
                    Log.w(TAG, "vitals wear-detect fail (${event.kind}) — stopping all pending")
                    vitalsPending.values.forEach { writeBytes(R08Protocol.stopMeasure(it)) }
                    completeVitalsSnapshot("wear-detect-fail")
                }
            }
            else -> Unit
        }
        eventSubs.forEach { it(event, nowMs) }
    }

    /**
     * Issue the GATT read of 0x2A27 (HW revision). The result lands in [onCharacteristicRead]
     * which chains into 0x2A26 (FW) and then [startCommandChannelBootstrap]. Idempotent —
     * subsequent calls are no-ops within a single connection (gated by [bootstrapStarted]).
     */
    @SuppressLint("MissingPermission")
    private fun kickOffReadyGateRead(g: BluetoothGatt) {
        if (bootstrapStarted) return
        bootstrapStarted = true
        val hwRev = g.getService(devInfoSvcUuid)?.getCharacteristic(hwRevCharUuid)
        if (hwRev != null) {
            try {
                val ok = g.readCharacteristic(hwRev)
                if (!ok) {
                    Log.w(TAG, "readCharacteristic(0x2A27) returned false — falling back to direct bootstrap")
                    startCommandChannelBootstrap()
                }
            } catch (_: SecurityException) {
                Log.w(TAG, "readCharacteristic(0x2A27) denied — falling back")
                startCommandChannelBootstrap()
            }
        } else {
            Log.w(TAG, "DeviceInfo / HW-rev char missing — skipping ready-gate read")
            startCommandChannelBootstrap()
        }
    }

    /**
     * SPEC v3 §2.1 command-channel bootstrap. Fires after the GATT-read of 0x2A27 + 0x2A26 has
     * cleared the "ready" gate. Each write is staggered to give the SDK time to settle.
     * - 0: SetTime (Beijing-locked) — reply carries 14-byte capability extension
     * - +200ms: BindAncs(sdk=2, model="halo-ring")
     * - +400ms: DeviceSupport (0x3C) — reply carries 9-byte capability bitmap
     * - +600ms: GetMessagePush (0x61)
     * - +800ms: Battery (0x03)
     * - +1000ms: TOUCH_ENABLE
     * - +1500ms: TOUCH_MODE (with appType=9 — required for gesture reporting)
     */
    private fun startCommandChannelBootstrap() {
        Log.i(TAG, "starting command-channel bootstrap")
        val nowMs = System.currentTimeMillis()
        // Set the ring's clock to the GLASSES' local time (its current tz offset, DST-aware) — not a
        // hard-coded Beijing UTC+8 — so the ring rolls its day (and zeroes the daily step counter) at
        // the wearer's local midnight. SetTime had to retry-survive too: it's the first bootstrap
        // write and was the one that got dropped (→ missing caps); writeBytes now retries.
        val tzOffsetSec = java.util.TimeZone.getDefault().getOffset(nowMs) / 1000
        writeBytes(R08Protocol.setTimeForOffset(nowMs / 1000, tzOffsetSec))
        scheduler.postDelayed(200L) { if (state == ConnectionState.READY) writeBytes(R08Protocol.bindAncs()) }
        scheduler.postDelayed(400L) { if (state == ConnectionState.READY) writeBytes(R08Protocol.DEVICE_SUPPORT_QUERY) }
        scheduler.postDelayed(600L) { if (state == ConnectionState.READY) writeBytes(R08Protocol.GET_MESSAGE_PUSH) }
        scheduler.postDelayed(800L) { if (state == ConnectionState.READY) writeBytes(R08Protocol.BATTERY_QUERY) }
        scheduler.postDelayed(1000L) {
            if (state == ConnectionState.READY) {
                writeBytes(R08Protocol.TOUCH_ENABLE)
                lastTouchEnabledRequested = true
            }
        }
        scheduler.postDelayed(1500L) {
            // Only assert the DEFAULT sleep timeout if the service hasn't already configured touch:
            // reconcilePower() runs on the READY transition (before this +1500 ms fires) and calls
            // setTouchEnabled(worn, wearerSleepMin), which sets lastTouchSleepMinRequested. If we
            // unconditionally wrote touchModeCmd(DEFAULT) here it would clobber a non-default sleepMin
            // back to 5 until the next reconcile. So skip when the sleep timeout is already set.
            if (state == ConnectionState.READY && lastTouchSleepMinRequested == null) {
                writeBytes(R08Protocol.touchModeCmd(R08Protocol.DEFAULT_TOUCH_SLEEP_MIN))
                lastTouchSleepMinRequested = R08Protocol.DEFAULT_TOUCH_SLEEP_MIN
            }
        }
    }

    /** P1-6: cheap binder-thread fingerprint — see [lastNotifyFingerprint] kdoc. */
    private fun fingerprintOf(b: ByteArray): Long {
        val n = b.size
        if (n == 0) return 0L
        val b0 = b[0].toLong() and 0xFFL
        val b1 = if (n > 1) b[1].toLong() and 0xFFL else 0L
        val bL = b[n - 1].toLong() and 0xFFL
        val bM = b[n / 2].toLong() and 0xFFL
        return (n.toLong() and 0xFF) or (b0 shl 8) or (b1 shl 16) or (bM shl 24) or (bL shl 32)
    }

    // ── state helpers ─────────────────────────────────────────────────────────────────────────

    private fun transitionTo(next: ConnectionState) {
        if (state == next) return
        Log.w(TAG, "state: $state → $next")
        state = next
        stateSubs.forEach { it(next) }
    }

    /**
     * SPEC v3 §5.2 sub=0x0C: the ring autonomously pushes battery state (with charging flag) on
     * plug-in / unplug / while-rising. So our 30-min `0x03 BATTERY_QUERY` poll is obsolete —
     * we'd be spending an extra notify per half-hour to learn what the firmware would tell us
     * for free.
     *
     * Kept as a stub for forward-compat in case a future firmware doesn't push; currently always
     * a no-op (cleared in lifecycle teardown). On-demand battery query is still available via
     * [queryBattery] for the UI "refresh now" CTA.
     */
    private fun schedulePeriodicBatteryPoll() {
        // No-op: spec-confirmed push from 0x73 sub=0x0C replaces this.
        batteryPollHandle?.cancel()
        batteryPollHandle = null
    }

    companion object {
        private const val TAG = "AndroidR08BleClient"
        // SPEC v3 §2.1: 500 ms wait between the GATT-read "ready" gate and the first command-channel write.
        private const val BOOTSTRAP_READY_GATE_MS = 500L
        /** Safety: if the CCCD-write ack never fires (some firmware acks lazily / silently), kick
         *  off the read-gate anyway after this gap. 1.5s gives the BT stack time to complete
         *  the descriptor write in the common case + leaves slack. */
        private const val CCCD_ACK_SAFETY_MS = 1500L
        // Give up the LOW_LATENCY scan after this long. The user can retry via "Reconnect" or by
        // taking the glasses off + putting them back on (wear-state change re-invokes start()).
        private const val SCAN_TIMEOUT_MS = 30_000L
        /** A GATT write returns false when another op is in flight (Android allows only one at a
         *  time). Retry the dropped write this many times, [WRITE_RETRY_MS] apart, so a transient
         *  busy-slot doesn't lose a command (e.g. the bootstrap SetTime capability query). */
        private const val WRITE_MAX_RETRIES = 5
        private const val WRITE_RETRY_MS = 70L
        /** Energy: auto-reconnect re-scan backoff bounds. Base is short so the SPEC §6.5 quirk-drop
         *  reconnect stays snappy (resets on every READY); max caps the worst-case to one short
         *  scan per 30 s when the ring is genuinely gone, instead of a back-to-back scan loop. */
        /** Settle delay before discoverServices() on Rokid Glasses' BLE chip. Without it, the
         *  Rokid GATT stack stalls for 10-20 s at STATE_CONNECTED before answering ATT requests.
         *  macOS/bleak (phase0) doesn't need this — it uses the host BLE stack directly. */
        private const val GATT_SETTLE_MS = 300L
        private const val RECONNECT_BACKOFF_BASE_MS = 800L
        private const val RECONNECT_BACKOFF_MAX_MS = 30_000L
        /** Watchdog for a hung CONNECTING phase (see [connectTimeoutHandle]). A direct connect to an
         *  in-range ring reaches READY in ~1-3 s; the GATT_SETTLE + service discovery + the SPEC §2.1
         *  read-gate add a few more. 15 s is generous headroom — if we're still CONNECTING past it,
         *  the connect has hung and we force a re-scan rather than pin forever. */
        private const val CONNECT_TIMEOUT_MS = 15_000L
        /** Safety timeout for [requestVitalsSnapshot]. Burn-in 2026-05-27: bumped from 12s to 30s
         *  after live observation — the R08 PPG sensor emits `err=0/val=0` progress frames every
         *  ~500 ms and can take 15-25 s to converge on the first reading, especially after a
         *  cold start or with a non-ideal finger contact. 30 s gives the warm-up cycle room
         *  without leaving the LED on forever if the ring is genuinely off-finger. */
        private const val VITALS_SAFETY_TIMEOUT_MS = 30_000L
    }
}
