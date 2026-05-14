package com.halo.ring.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Best-effort wear-state proxy used by both [com.halo.ring.device.rokid.RokidWearStateProvider]
 * and [com.halo.ring.device.rayneo.RayNeoWearStateProvider] when no first-class wear-detection
 * signal is available.
 *
 * The proxy approximates "worn" from the screen state alone — pessimistic about declaring the
 * user "not worn" so we don't accidentally drop the BLE link mid-session:
 *
 *  - **screen ON** → presumed worn.
 *  - **screen OFF, briefly** → still presumed worn. Lets [com.halo.ring.core.power.PowerPolicy]'s
 *    SLOW band kick in while keeping the touch IC enabled for wake-gesture.
 *  - **screen OFF for > [unwornAfterOffMs]** → presumed NOT worn. This is what lets PowerPolicy
 *    eventually disconnect via its 5-min not-worn rule (Doc/06 §3.5).
 *
 * A per-flavor "real signal source" (RokidDoor receiver, RayNeo Mercury 佩戴检测 module if/when
 * the AAR exposes it, a Sensor.TYPE_PROXIMITY-backed override, …) can call [overrideWorn] to
 * supersede the screen heuristic with higher-confidence data.
 *
 * Thread-safety: [observe] / [overrideWorn] / [close] may be called from any thread; observer
 * callbacks always fire on the main looper.
 *
 * Audit-2026-05-13k: introduced because both flavor providers shipped `isWorn() = true` constants
 * and their `observe(...)` never fired, making PowerPolicy's not-worn-disconnect branch
 * unreachable in practice.
 */
class ScreenWearProxy(
    private val context: Context,
    private val unwornAfterOffMs: Long = DEFAULT_UNWORN_AFTER_OFF_MS,
) {
    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<(Boolean) -> Unit>()
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile private var worn: Boolean = pm.isInteractive
    @Volatile private var screenOffSinceMs: Long =
        if (pm.isInteractive) 0L else SystemClock.elapsedRealtime()
    private var notWornRunnable: Runnable? = null

    /** Most-recent override from a real wear signal (null = no real signal received yet). */
    @Volatile private var realSignalOverride: Boolean? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            main.post {
                when (i?.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        notWornRunnable?.let { main.removeCallbacks(it); notWornRunnable = null }
                        // If a real signal already told us "not worn", honour it; otherwise the
                        // screen waking is strong enough evidence to flip back to worn.
                        if (realSignalOverride != false && !worn) setWornInternal(true)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOffSinceMs = SystemClock.elapsedRealtime()
                        if (realSignalOverride == null) scheduleNotWornCheck()
                    }
                }
            }
        }
    }

    init {
        try {
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                Context.RECEIVER_NOT_EXPORTED else 0
            context.registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, flags)
        } catch (e: SecurityException) {
            Log.w(TAG, "registerReceiver failed: ${e.message}")
        }
    }

    fun isWorn(): Boolean = worn

    /** Subscribe. Replays current state once, synchronously. Returns unsubscribe. */
    fun observe(onChange: (Boolean) -> Unit): () -> Unit {
        synchronized(observers) { observers += onChange }
        onChange(worn)
        return {
            synchronized(observers) { observers.remove(onChange) }
        }
    }

    /**
     * Called by a real wear-signal source (Rokid Door receiver, RayNeo Mercury, proximity sensor)
     * to supersede the screen heuristic. Subsequent screen-off events won't auto-trigger the
     * not-worn timer until the override is cleared.
     */
    fun overrideWorn(real: Boolean) {
        main.post {
            realSignalOverride = real
            notWornRunnable?.let { main.removeCallbacks(it); notWornRunnable = null }
            setWornInternal(real)
        }
    }

    /** Clear the real-signal override and revert to the screen heuristic. */
    fun clearOverride() {
        main.post {
            realSignalOverride = null
            if (!pm.isInteractive) scheduleNotWornCheck()
        }
    }

    fun close() {
        main.post {
            notWornRunnable?.let { main.removeCallbacks(it); notWornRunnable = null }
            try { context.unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) {}
            synchronized(observers) { observers.clear() }
        }
    }

    private fun setWornInternal(newWorn: Boolean) {
        if (worn == newWorn) return
        worn = newWorn
        Log.i(TAG, "wear-proxy: worn=$newWorn (override=$realSignalOverride, screenOn=${pm.isInteractive})")
        val snapshot = synchronized(observers) { observers.toList() }
        snapshot.forEach { it(newWorn) }
    }

    private fun scheduleNotWornCheck() {
        notWornRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable {
            if (!pm.isInteractive && realSignalOverride == null) {
                val offFor = SystemClock.elapsedRealtime() - screenOffSinceMs
                if (offFor >= unwornAfterOffMs) setWornInternal(false)
            }
        }
        notWornRunnable = r
        main.postDelayed(r, unwornAfterOffMs)
    }

    companion object {
        private const val TAG = "ScreenWearProxy"
        /**
         * Default 30 min. PowerPolicy's own not-worn-disconnect timer (Doc/06 §3.5) is 5 min; we
         * deliberately keep the screen-proxy threshold much higher so that someone reading or
         * watching without interaction doesn't get their ring link dropped. When a real wear
         * signal is wired (Rokid Door receiver / Mercury 佩戴检测), it takes precedence via
         * [overrideWorn] and the screen heuristic becomes irrelevant.
         */
        const val DEFAULT_UNWORN_AFTER_OFF_MS = 30L * 60L * 1000L
    }
}

/**
 * Optional supplementary wear signal: Android's standard proximity sensor. On Rokid Glasses the
 * "psensor" forehead-proximity sensor is exposed as `Sensor.TYPE_PROXIMITY` (Doc/03 §1.7).
 * X3 Pro doesn't necessarily expose one — `null` from [SensorManager.getDefaultSensor] is fine.
 *
 * The sensor is registered with [SensorManager.SENSOR_DELAY_NORMAL] (~200 ms) so it's effectively
 * free on the CPU side; the listener fires only when proximity state changes, not continuously.
 */
class ProximityWearSource(
    private val context: Context,
    private val proxy: ScreenWearProxy,
) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    @Volatile private var running = false

    /** Returns true if the sensor exists on this device and registration succeeded. */
    fun start(): Boolean {
        val s = sensor ?: return false
        if (running) return true
        val ok = sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL) ?: false
        running = ok
        if (ok) Log.i(TAG, "proximity sensor registered (max=${s.maximumRange} cm)")
        return ok
    }

    fun stop() {
        if (!running) return
        sm?.unregisterListener(this)
        proxy.clearOverride()
        running = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val v = event?.values?.firstOrNull() ?: return
        val max = sensor?.maximumRange ?: 5f
        // Most phones / glasses report a coarse two-state proximity (near = 0, far = max).
        // Treat "close to forehead" as "worn".
        val near = v < max / 2f
        proxy.overrideWorn(near)
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit

    companion object { private const val TAG = "ProximityWearSrc" }
}
