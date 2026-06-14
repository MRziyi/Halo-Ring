package com.halo.ring.inject

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import com.halo.ring.accessibility.HaloRingAccessibilityService
import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.device.A11yGlobalAction
import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.device.InjectionPrimitive
import com.halo.ring.core.inject.ExecutorBackend
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Second-tier backend (priority 80) that needs no ADB / no Shizuku — just the user enabling the
 * accessibility service in Settings (deep-linked from the first-run wizard). Covers Back / Home /
 * Recents / Notifications via [AccessibilityService.performGlobalAction], **plus Tap / Swipe via
 * [AccessibilityService.dispatchGesture]** (API 24+, no shell uid required).
 *
 * The Tap/Swipe path is what makes this backend the **primary injector on RayNeo X3 Pro**: AIOS
 * SELinux blocks the agent's self-bootstrap (`setprop service.adb.tcp.port` / `ctl.restart adbd`
 * are denied for `shell`, `adb_wifi_enabled` won't stick), so the app_process agent can't come up
 * untethered. But RayNeo navigation = touch swipe MotionEvents into the Mercury `TouchDispatcher`
 * (verified on-device 2026-06-14: an injected touchscreen swipe drives SlideForward/Backward, a tap
 * = Click), and `dispatchGesture` produces exactly those events without ADB. The agent stays the
 * preferred backend (priority 100) on Rokid where it bootstraps fine; on RayNeo it reports no
 * capabilities (never ready) and the router falls through to us.
 *
 * Still cannot inject arbitrary DPAD / volume / media KeyEvents (the `GLOBAL_ACTION_DPAD_*` family is
 * API 33+, and a11y has no KeyEvent injection) so it never claims NAVIGATE/KEY_EVENT — those route
 * to app-level APIs or the agent where available.
 *
 * Bonus: the same [HaloRingAccessibilityService] delivers `TYPE_WINDOW_STATE_CHANGED` events, which we
 * feed into [com.halo.ring.core.action.ModeManager.onForegroundPackage] for auto-switch — that
 * wiring happens in [com.halo.ring.service.HaloRingService.onCreate].
 *
 * Threading: [perform] is suspend but does no I/O — `performGlobalAction` is a same-process binder
 * call that returns immediately. Safe to call from the scheduler thread.
 */
class AccessibilityBackend(
    private val mapper: GlassActionMapper,
    /**
     * Whether this backend may inject tap/swipe via `dispatchGesture` (advertises [Capability.TAP_SWIPE]
     * and realises Tap/Swipe primitives). **RayNeo-only** — the rayneo flavor passes `true` because the
     * app_process agent can't bootstrap there, so gesture injection is the primary nav path. Rokid
     * passes `false` (the default): its a11y service config has `canPerformGestures="false"`, the agent
     * owns TAP_SWIPE, and Rokid behaviour is unchanged (global actions only, exactly as before).
     */
    private val gesturesEnabled: Boolean = false,
) : ExecutorBackend {

    override val id = "accessibility"
    override val priority = 80

    /** Capabilities the running a11y service can satisfy. Reported only when the service is bound;
     *  otherwise the [ExecutorBackend] router treats us as unavailable and falls through.
     *  TAP_SWIPE is added only when [gesturesEnabled] (RayNeo) — Rokid stays global-actions-only. */
    override fun capabilities(): Set<Capability> = when {
        !isReady()       -> emptySet()
        gesturesEnabled  -> BASE_CAPS + Capability.TAP_SWIPE
        else             -> BASE_CAPS
    }

    override fun isReady(): Boolean = HaloRingAccessibilityService.instance != null

    override suspend fun perform(action: GlassAction): Boolean {
        val service = HaloRingAccessibilityService.instance ?: return false
        // Walk the mapper's primitive list and execute the first one we can satisfy. We handle
        // A11yGlobal (performGlobalAction) and Tap/Swipe (dispatchGesture). Other primitives
        // (Key / Shell / StartActivity / Broadcast) require shell uid → skipped (fall through).
        for (p in mapper.primitives(action)) {
            val ok = when (p) {
                is InjectionPrimitive.A11yGlobal -> {
                    val androidAction = toGlobalAction(p.action) ?: continue
                    try {
                        service.performGlobalAction(androidAction)
                    } catch (t: Throwable) {
                        Log.w(TAG, "performGlobalAction(${p.action}) threw: ${t.message}")
                        false
                    }
                }
                is InjectionPrimitive.Tap   -> if (gesturesEnabled) dispatchTap(service, p.x, p.y) else continue
                is InjectionPrimitive.Swipe -> if (gesturesEnabled) dispatchSwipe(service, p.x1, p.y1, p.x2, p.y2, p.durationMs) else continue
                else -> continue
            }
            if (ok) return true
        }
        return false
    }

    private suspend fun dispatchTap(service: AccessibilityService, x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        // A tap is a short stroke at one point; ~40ms reads as a Click to Mercury's TouchDispatcher.
        return dispatchStroke(service, path, durationMs = 40)
    }

    private suspend fun dispatchSwipe(
        service: AccessibilityService, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        // Clamp the duration so the gesture detector still reads it as a fling, not a slow drag.
        return dispatchStroke(service, path, durationMs = durationMs.coerceIn(20, 300))
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Build a one-stroke [GestureDescription] and await its completion callback.
     *
     * `dispatchGesture` is dispatched on the **main thread** — calling it from a background
     * scheduler thread leaves the framework's `GestureController` posting its result callback to a
     * looper-less thread, so `onCompleted`/`onCancelled` never fired and we hung (observed on RayNeo
     * 2026-06-14). We also bound it with a [withTimeoutOrNull] so a dropped callback degrades to
     * `false` instead of suspending the router forever.
     */
    private suspend fun dispatchStroke(
        service: AccessibilityService, path: Path, durationMs: Int,
    ): Boolean {
        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "GestureDescription build failed: ${t.message}")
            return false
        }
        val result = kotlinx.coroutines.withTimeoutOrNull(durationMs + 800L) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(d: GestureDescription?) {
                        Log.w(TAG, "dispatchGesture cancelled")
                        if (cont.isActive) cont.resume(false)
                    }
                }
                // dispatchGesture must run on the main thread for its callback to be delivered.
                mainHandler.post {
                    val started = try {
                        service.dispatchGesture(gesture, callback, mainHandler)
                    } catch (t: Throwable) {
                        Log.w(TAG, "dispatchGesture threw: ${t.message}")
                        false
                    }
                    if (!started && cont.isActive) {
                        Log.w(TAG, "dispatchGesture returned false (not started)")
                        cont.resume(false)
                    }
                }
            }
        }
        return result == true
    }

    private fun toGlobalAction(a: A11yGlobalAction): Int? = when (a) {
        A11yGlobalAction.BACK            -> AccessibilityService.GLOBAL_ACTION_BACK
        A11yGlobalAction.HOME            -> AccessibilityService.GLOBAL_ACTION_HOME
        A11yGlobalAction.RECENTS         -> AccessibilityService.GLOBAL_ACTION_RECENTS
        A11yGlobalAction.NOTIFICATIONS   -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        A11yGlobalAction.QUICK_SETTINGS  -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        A11yGlobalAction.POWER_DIALOG    -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        A11yGlobalAction.LOCK_SCREEN     ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else null
        A11yGlobalAction.TAKE_SCREENSHOT ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else null
    }

    companion object {
        private const val TAG = "A11yBackend"
        private val BASE_CAPS = setOf(
            Capability.BACK, Capability.HOME, Capability.RECENTS, Capability.NOTIFICATIONS,
        )
    }
}
