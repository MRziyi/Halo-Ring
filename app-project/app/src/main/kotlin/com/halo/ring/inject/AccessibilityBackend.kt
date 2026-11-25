package com.halo.ring.inject

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import com.halo.ring.accessibility.HaloRingAccessibilityService
import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.device.A11yGlobalAction
import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.device.InjectionPrimitive
import com.halo.ring.core.inject.ExecutorBackend

/**
 * Second-tier backend (priority 80) that needs no ADB / no Shizuku — just the user enabling the
 * accessibility service in Settings (deep-linked from the first-run wizard). Covers Back / Home /
 * Recents / Notifications via [AccessibilityService.performGlobalAction]. Cannot inject DPAD keys
 * on Android 12 (the `GLOBAL_ACTION_DPAD_*` family is API 33+) so it never claims NAVIGATE.
 *
 * Bonus: the same [HaloRingAccessibilityService] delivers `TYPE_WINDOW_STATE_CHANGED` events, which we
 * feed into [com.halo.ring.core.action.ModeManager.onForegroundPackage] for auto-switch — that
 * wiring happens in [com.halo.ring.service.HaloRingService.onCreate].
 *
 * Threading: [perform] is suspend but does no I/O — `performGlobalAction` is a same-process binder
 * call that returns immediately. Safe to call from the scheduler thread.
 */
class AccessibilityBackend(private val mapper: GlassActionMapper) : ExecutorBackend {

    override val id = "accessibility"
    override val priority = 80

    /** Capabilities the running a11y service can satisfy. Reported only when the service is bound;
     *  otherwise the [ExecutorBackend] router treats us as unavailable and falls through. */
    override fun capabilities(): Set<Capability> = if (isReady()) BASE_CAPS else emptySet()

    override fun isReady(): Boolean = HaloRingAccessibilityService.instance != null

    override suspend fun perform(action: GlassAction): Boolean {
        val service = HaloRingAccessibilityService.instance ?: return false
        // Walk the mapper's primitive list and execute the first A11yGlobal we can. Other primitives
        // (Key / Tap / Swipe / Shell / StartActivity / Broadcast) require shell uid → not us.
        for (p in mapper.primitives(action)) {
            if (p !is InjectionPrimitive.A11yGlobal) continue
            val androidAction = toGlobalAction(p.action) ?: continue
            val ok = try {
                service.performGlobalAction(androidAction)
            } catch (t: Throwable) {
                Log.w(TAG, "performGlobalAction(${p.action}) threw: ${t.message}")
                false
            }
            if (ok) return true
        }
        return false
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
