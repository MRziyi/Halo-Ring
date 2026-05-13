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
    /** Optional MAC whitelist — empty means "accept any device matching the service UUID + name". */
    private val macWhitelist: Set<String> = emptySet(),
    private val dedupWindowMs: Long = 50L,
) : R08BleClient {

    private val eventSubs = CopyOnWriteArrayList<(RingEvent, Long) -> Unit>()
    private val stateSubs = CopyOnWriteArrayList<(ConnectionState) -> Unit>()

    private val serviceUuid = UUID.fromString(R08Protocol.SERVICE_UUID)
    private val writeCharUuid = UUID.fromString(R08Protocol.WRITE_CHAR_UUID)
    private val notifyCharUuid = UUID.fromString(R08Protocol.NOTIFY_CHAR_UUID)
    private val cccdUuid = UUID.fromString(R08Protocol.CCCD_UUID)

    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    @Volatile private var state: ConnectionState = ConnectionState.DISCONNECTED
    @Volatile private var batteryPollHandle: com.halo.ring.core.gesture.Cancellable? = null
    @Volatile private var scanTimeoutHandle: com.halo.ring.core.gesture.Cancellable? = null
    @Volatile private var lastBytes: ByteArray? = null
    @Volatile private var lastBytesAt: Long = 0L

    /**
     * Idempotence trackers — Doc/13 §audit-2026-05-13j: [reconcilePower] runs on every BLE event
     * + wear/screen change. Without these guards, every gesture caused a redundant
     * `TOUCH_ENABLE` write to the ring (+ a `TOUCH_MODE` 500 ms later) and a redundant
     * `requestConnectionPriority` to the BT stack. Both reset to null on disconnect so a fresh
     * connection re-issues the writes.
     */
    @Volatile private var lastTouchEnabledRequested: Boolean? = null
    @Volatile private var lastIntervalModeRequested: PowerPolicy.IntervalMode? = null

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
            if (state != ConnectionState.DISCONNECTED) {
                Log.d(TAG, "start() ignored — current state=$state")
                return@post
            }
            transitionTo(ConnectionState.SCANNING)
            beginScan()
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        scheduler.post {
            try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
            batteryPollHandle?.cancel(); batteryPollHandle = null
            scanTimeoutHandle?.cancel(); scanTimeoutHandle = null
            try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
            gatt = null
            writeChar = null
            lastTouchEnabledRequested = null
            lastIntervalModeRequested = null
            transitionTo(ConnectionState.DISCONNECTED)
        }
    }

    override fun setTouchEnabled(enabled: Boolean) {
        scheduler.post {
            // Talking to the ring when we're not connected is pointless — and used to spam
            // `writeChar not yet available` once per reconcile cycle. Now silently ignored.
            if (state != ConnectionState.READY) return@post
            // Doc/13 §audit-2026-05-13j: reconcilePower fires on every BLE event, so without
            // this guard every gesture writes TOUCH_ENABLE + TOUCH_MODE redundantly. The flag
            // is cleared on disconnect so a fresh connection re-arms the writes.
            if (lastTouchEnabledRequested == enabled) return@post
            lastTouchEnabledRequested = enabled
            writeBytes(if (enabled) R08Protocol.TOUCH_ENABLE else R08Protocol.TOUCH_DISABLE)
            if (enabled) {
                scheduler.postDelayed(TOUCH_MODE_DELAY_MS) {
                    if (state == ConnectionState.READY) writeBytes(R08Protocol.TOUCH_MODE)
                }
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
    private var vitalsStopHandle: com.halo.ring.core.gesture.Cancellable? = null

    /**
     * Doc/07 §1.1: HR → SpO2 → stress sequentially. Each measurement gets ~3 s with the PPG LED on.
     * After the third reading we send `0x6A` (STOP) so the LED turns off.
     *
     * The snapshot is a fire-and-forget operation: results arrive on the normal [events] stream
     * as [RingEvent.Health]; the foreground service collects them and pushes into the Vitals UI.
     */
    override fun requestVitalsSnapshot() {
        scheduler.post {
            if (vitalsSnapshotInFlight) {
                Log.d(TAG, "vitals snapshot already in flight — ignoring")
                return@post
            }
            vitalsSnapshotInFlight = true
            Log.i(TAG, "starting vitals snapshot (HR → SpO2 → stress)")

            // HR first.
            writeBytes(R08Protocol.REAL_TIME_HR_START)
            scheduler.postDelayed(VITALS_HR_DURATION_MS) {
                writeBytes(R08Protocol.REAL_TIME_SPO2_START)
            }
            scheduler.postDelayed(VITALS_HR_DURATION_MS + VITALS_SPO2_DURATION_MS) {
                writeBytes(R08Protocol.REAL_TIME_STRESS_START)
            }
            vitalsStopHandle?.cancel()
            vitalsStopHandle = scheduler.postDelayed(
                VITALS_HR_DURATION_MS + VITALS_SPO2_DURATION_MS + VITALS_STRESS_DURATION_MS,
            ) {
                writeBytes(R08Protocol.REAL_TIME_STOP)
                vitalsSnapshotInFlight = false
                Log.i(TAG, "vitals snapshot finished")
            }
        }
    }

    // ── scanning ──────────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun beginScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "no BluetoothLeScanner; staying disconnected"); return
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)   // brief, then we connectGatt(autoConnect=true)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
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
                Log.w(TAG, "no ring found within ${SCAN_TIMEOUT_MS} ms; stopping scan")
                try { scanner.stopScan(scanCallback) } catch (_: SecurityException) {}
                transitionTo(ConnectionState.DISCONNECTED)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            // Optional MAC filter
            if (macWhitelist.isNotEmpty() && device.address !in macWhitelist) return
            // Name keyword filter (R08_XXXX, R06_*, Colmi…)
            val name = try { device.name } catch (_: SecurityException) { null } ?: ""
            if (name.isNotEmpty() && R08Protocol.DEVICE_NAME_KEYWORDS.none { it in name }) return

            Log.w(TAG, "ring discovered: name=$name address=${device.address}")
            try { adapter?.bluetoothLeScanner?.stopScan(this) } catch (_: SecurityException) {}
            scheduler.post {
                scanTimeoutHandle?.cancel()
                scanTimeoutHandle = null
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
            gatt = device.connectGatt(
                context,
                /* autoConnect */ true,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "connectGatt denied: ${e.message}")
            transitionTo(ConnectionState.DISCONNECTED)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scheduler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.w(TAG, "GATT connected (status=$status), discovering services")
                        try { g.discoverServices() } catch (_: SecurityException) {}
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "GATT disconnected (status=$status), autoConnect will retry")
                        batteryPollHandle?.cancel(); batteryPollHandle = null
                        // D4: abort any in-flight vitals snapshot — otherwise the flag stays set
                        // and the UI can never re-trigger MEASURE NOW until the app restarts.
                        if (vitalsSnapshotInFlight) {
                            Log.w(TAG, "aborting in-flight vitals snapshot due to disconnect")
                            vitalsStopHandle?.cancel(); vitalsStopHandle = null
                            vitalsSnapshotInFlight = false
                        }
                        writeChar = null
                        // Audit-2026-05-13j: clear idempotence trackers so the next connection's
                        // init sequence re-issues TOUCH_ENABLE/TOUCH_MODE and the first
                        // reconcilePower writes a fresh connection priority. Stale flags would
                        // suppress these and leave the ring with no touch IC after reconnect.
                        lastTouchEnabledRequested = null
                        lastIntervalModeRequested = null
                        transitionTo(ConnectionState.CONNECTING)   // autoConnect is alive
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
                try {
                    g.setCharacteristicNotification(notify, true)
                    val cccd = notify.getDescriptor(cccdUuid)
                    if (cccd != null) {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    } else {
                        Log.w(TAG, "CCCD descriptor missing on notify char")
                    }
                } catch (_: SecurityException) {}

                // §5 init sequence — staggered writes are necessary; the BLE stack will queue them.
                // Audit-2026-05-13j: also tick [lastTouchEnabledRequested] so the first
                // [reconcilePower]-driven [setTouchEnabled] doesn't re-issue a duplicate write.
                scheduler.postDelayed(TOUCH_ENABLE_DELAY_MS) {
                    if (state == ConnectionState.READY) {
                        writeBytes(R08Protocol.TOUCH_ENABLE)
                        lastTouchEnabledRequested = true
                    }
                }
                scheduler.postDelayed(TOUCH_ENABLE_DELAY_MS + TOUCH_MODE_DELAY_MS) {
                    if (state == ConnectionState.READY) writeBytes(R08Protocol.TOUCH_MODE)
                }
                scheduler.postDelayed(BATTERY_FIRST_DELAY_MS) {
                    writeBytes(R08Protocol.BATTERY_QUERY)
                    schedulePeriodicBatteryPoll()
                }
                transitionTo(ConnectionState.READY)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            // Hot path — must be cheap. Bytes are copied because the buffer can be reused.
            val bytes = ch.value?.copyOf() ?: return
            val now = scheduler.nowMs()
            scheduler.post { onNotify(bytes, now) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeBytes(bytes: ByteArray) {
        val ch = writeChar ?: run {
            Log.w(TAG, "writeBytes: writeChar not yet available, dropping ${bytes.size}-byte cmd")
            return
        }
        try {
            ch.value = bytes
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt?.writeCharacteristic(ch)
        } catch (e: SecurityException) {
            Log.e(TAG, "writeCharacteristic denied: ${e.message}")
        }
    }

    // ── notify handling: dedup → parse → fan out ──────────────────────────────────────────────

    private fun onNotify(bytes: ByteArray, nowMs: Long) {
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

        // R08Frame.parse returns RingEvent.Unknown for anything it can't decode, never null —
        // we still forward Unknowns so the debug HUD / logging can render the raw hex.
        val event = R08Frame.parse(bytes)
        eventSubs.forEach { it(event, nowMs) }
    }

    // ── state helpers ─────────────────────────────────────────────────────────────────────────

    private fun transitionTo(next: ConnectionState) {
        if (state == next) return
        Log.w(TAG, "state: $state → $next")
        state = next
        stateSubs.forEach { it(next) }
    }

    private fun schedulePeriodicBatteryPoll() {
        batteryPollHandle?.cancel()
        // D5: only poll while we're actually connected — once GATT goes away, the poll is wasted
        // work that just keeps the scheduler thread semi-active.
        if (state != ConnectionState.READY) {
            batteryPollHandle = null
            return
        }
        batteryPollHandle = scheduler.postDelayed(BATTERY_POLL_INTERVAL_MS) {
            if (state == ConnectionState.READY) {
                writeBytes(R08Protocol.BATTERY_QUERY)
                schedulePeriodicBatteryPoll()
            } else {
                batteryPollHandle = null
            }
        }
    }

    companion object {
        private const val TAG = "AndroidR08BleClient"
        // Delays from Doc/02-hardware-and-protocol.md §5; copied from the reference APK's flow.
        private const val TOUCH_ENABLE_DELAY_MS  = 800L
        private const val TOUCH_MODE_DELAY_MS    = 500L
        private const val BATTERY_FIRST_DELAY_MS = 800L + 500L + 1500L
        // Battery is best-effort context info, not on the hot path; 30 min between polls is plenty.
        private const val BATTERY_POLL_INTERVAL_MS = 30L * 60L * 1000L
        // Give up the LOW_LATENCY scan after this long. The user can retry via "Reconnect" or by
        // taking the glasses off + putting them back on (wear-state change re-invokes start()).
        private const val SCAN_TIMEOUT_MS = 30_000L
        // How long each phase of the on-demand vitals snapshot runs. Picked conservatively so the
        // ring has time to settle on each PPG measurement before we switch to the next reading.
        private const val VITALS_HR_DURATION_MS     = 3_000L
        private const val VITALS_SPO2_DURATION_MS   = 3_000L
        private const val VITALS_STRESS_DURATION_MS = 3_000L
    }
}
