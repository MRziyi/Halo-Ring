package com.halo.ring.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Skeleton AccessibilityService. Referenced by [AndroidManifest.xml]; needs to exist for the APK to
 * install and for the user to be able to grant accessibility access. Full implementation is B11
 * (Doc/13 §B); for now this stub:
 *
 *  - keeps a static reference to the running instance so [com.halo.ring.inject.AccessibilityBackend]
 *    can call `performGlobalAction(BACK / HOME / RECENTS / NOTIFICATIONS)` once that backend grows;
 *  - logs foreground-window changes so we can already see what auto-switch *would* trigger on.
 *
 * IMPORTANT: never block in [onAccessibilityEvent] — Android will rate-limit/disable us.
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
