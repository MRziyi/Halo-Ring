package com.halo.ring.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityService — referenced by [AndroidManifest.xml]; needs to exist for the user to be
 * able to grant accessibility access. Two responsibilities:
 *
 *  1. Keeps a static reference to the running instance so [com.halo.ring.inject.AccessibilityBackend]
 *     can call `performGlobalAction(BACK / HOME / RECENTS / NOTIFICATIONS / QUICK_SETTINGS / …)`.
 *  2. Forwards foreground-window-package changes via [foregroundPackageListener] so
 *     [com.halo.ring.core.action.ModeManager.onForegroundPackage] can auto-switch profiles
 *     (B11 — wired in the foreground service's onCreate).
 *
 * Both fully wired since audit-pass-c. IMPORTANT: never block in [onAccessibilityEvent] — Android
 * will rate-limit / disable us.
 */
class HaloRingAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            Log.v(TAG, "TYPE_WINDOW_STATE_CHANGED pkg=$pkg")
            foregroundPackageListener?.invoke(pkg)
        }
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HaloA11y"

        /** Most-recently-connected instance, or null. */
        @Volatile var instance: HaloRingAccessibilityService? = null
            private set

        /** Subscribed by the service so [com.halo.ring.core.action.ModeManager.onForegroundPackage]
         *  can run for auto-profile-switch. */
        @Volatile var foregroundPackageListener: ((String?) -> Unit)? = null
    }
}
