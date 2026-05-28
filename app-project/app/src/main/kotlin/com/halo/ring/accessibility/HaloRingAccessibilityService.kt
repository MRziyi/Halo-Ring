package com.halo.ring.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.halo.ring.adb.AdbPairingOverlay

/**
 * AccessibilityService — referenced by [AndroidManifest.xml]; needs to exist for the user to be
 * able to grant accessibility access. Three responsibilities:
 *
 *  1. Keeps a static reference to the running instance so [com.halo.ring.inject.AccessibilityBackend]
 *     can call `performGlobalAction(BACK / HOME / RECENTS / NOTIFICATIONS / QUICK_SETTINGS / …)`.
 *  2. Forwards foreground-window-package changes via [foregroundPackageListener] so
 *     [com.halo.ring.core.action.ModeManager.onForegroundPackage] can auto-switch profiles
 *     (B11 — wired in the foreground service's onCreate).
 *  3. Owns the ADB pairing code-entry overlay using TYPE_ACCESSIBILITY_OVERLAY — the only
 *     window type that survives on top of Settings (which uses HIDE_NON_SYSTEM_OVERLAY_WINDOWS
 *     to suppress TYPE_APPLICATION_OVERLAY). The overlay is created here and routed back to
 *     MainActivity via a callback. See [showPairingOverlay].
 *
 * IMPORTANT: never block in [onAccessibilityEvent] — Android will rate-limit / disable us.
 */
class HaloRingAccessibilityService : AccessibilityService() {

    private var pairingOverlay: AdbPairingOverlay? = null

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
        pairingOverlay?.destroy()
        pairingOverlay = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Show the pairing-code NumPad as a TYPE_ACCESSIBILITY_OVERLAY window. This type is
     * explicitly exempt from Settings' HIDE_NON_SYSTEM_OVERLAY_WINDOWS suppression, so it
     * renders on top of the Wireless-Debugging "Pair with code" dialog. The user reads the
     * 6-digit code from Settings and enters it in the NumPad without pressing Back.
     *
     * Must be called from the main thread. No-ops if the overlay is already showing.
     */
    fun showPairingOverlay(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
        val overlay = pairingOverlay ?: AdbPairingOverlay(
            appContext = this,
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        ).also { pairingOverlay = it }
        overlay.show(onSubmit, onCancel)
    }

    fun hidePairingOverlay() {
        pairingOverlay?.hide()
    }

    fun updatePairingStatus(text: String) {
        pairingOverlay?.updateStatus(text)
    }

    companion object {
        private const val TAG = "HaloA11y"

        /** Most-recently-connected instance, or null if accessibility service is not enabled. */
        @Volatile var instance: HaloRingAccessibilityService? = null
            private set

        /** Subscribed by the service so [com.halo.ring.core.action.ModeManager.onForegroundPackage]
         *  can run for auto-profile-switch. */
        @Volatile var foregroundPackageListener: ((String?) -> Unit)? = null
    }
}
