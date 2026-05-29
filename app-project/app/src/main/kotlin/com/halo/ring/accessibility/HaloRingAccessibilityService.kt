package com.halo.ring.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.halo.ring.R
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

    // ── Bluetooth "Internet access" re-enable flow ──────────────────────────────────────────────
    // Android resets the per-device "Internet access" (PAN) toggle on every reboot, and there's no
    // non-privileged API to set it (PanService.connect needs BLUETOOTH_PRIVILEGED). So we replay the
    // user's manual tap: open Bluetooth settings, walk to the paired phone's detail, click the
    // "Internet access" switch. State machine driven by window-change events.
    @Volatile private var btActive = false
    private var btStage = 0            // 0 = open device detail (gear), 1 = find the toggle
    private var btTarget = ""          // device name (for the confirmation toast)
    private var btReturnToApp = true   // after toggling, bring Halo Ring back (manual) vs go home (boot)
    private var btDeadline = 0L
    private var btEntryDeadline = 0L   // give the current device-list entry until here to render
    private var btReentries = 0        // exit+reenter refresh attempts (first entry often unrendered)
    private val btHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Polls the flow forward — the device card under "Media devices" can appear seconds after the
    // Settings screen opens (slow on cold boot), and that arrival isn't always a window-state event.
    private val btPoll = object : Runnable {
        override fun run() {
            if (!btActive) return
            stepBtInternetFlow()
            if (btActive) btHandler.postDelayed(this, 600)
        }
    }

    /**
     * Start the Bluetooth-internet re-enable flow. [deviceName] is the paired phone (for the
     * confirmation toast); null = auto-detect the currently-connected classic-BT device. When
     * [returnToApp] is true we relaunch Halo Ring after toggling (manual trigger); otherwise we go
     * Home (on-boot trigger). Opens Settings and drives the rest from [onAccessibilityEvent].
     */
    fun startBtInternetFlow(deviceName: String? = null, returnToApp: Boolean = true) {
        btTarget = deviceName.orEmpty()  // filled from the detail screen's title once we get there
        btStage = 0
        btReentries = 0
        btActive = true
        btReturnToApp = returnToApp
        val now = SystemClock.uptimeMillis()
        btDeadline = now + BT_FLOW_TIMEOUT_MS
        btEntryDeadline = now + BT_ENTRY_RENDER_MS
        Log.i(TAG, "btInternet: starting flow, target='$btTarget' returnToApp=$returnToApp")
        if (!openBtList()) {
            failBtInternetFlow(getString(R.string.bt_internet_fail_open_settings))
            return
        }
        btHandler.removeCallbacks(btPoll)
        btHandler.postDelayed(btPoll, 1200)  // let Settings open, then poll for the device card
    }

    /** Open the Bluetooth device-list screen. Returns false if it can't be launched. */
    private fun openBtList(): Boolean = try {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            // IGNORE our own windows (the MainActivity + the HUD overlay). Critical: when we show the
            // "ProfileSwitched" HUD, that overlay fires its own TYPE_WINDOW_STATE_CHANGED with our
            // package — which would otherwise be read as "foreground changed to Halo Ring" and revert
            // the profile to the Navigation fallback ~150 ms after every switch (a feedback loop that
            // left the ring stuck in Navigation: long-press slept, music-tap did nothing). Our app
            // isn't a profile context anyway. (root-caused on-device 2026-05-28)
            // The activity class — needed to tell apart Rokid Sprite "apps" (camera / music /
            // translate / chat …) which are all ONE package (com.rokid.os.sprite.launcher) but
            // different activities. Without this, package-only matching never fires for them.
            val activity = event.className?.toString()
            // Only react to real ACTIVITY transitions. TYPE_WINDOW_STATE_CHANGED also fires for
            // popups / dialogs / dropdowns / IME whose className is a widget (PopupWindow, a View,
            // a Dialog) — those would match no profile trigger and revert us to the Navigation
            // fallback ~250 ms after every switch (observed: launcher sub-windows kept knocking
            // Media/Camera back to Navigation, so long-press slept and music-tap did nothing).
            // Heuristic: a top-level Activity class ends in "Activity". (root-caused 2026-05-28)
            if (pkg != null && pkg != packageName && activity != null && activity.endsWith("Activity")) {
                Log.v(TAG, "TYPE_WINDOW_STATE_CHANGED pkg=$pkg activity=$activity")
                foregroundPackageListener?.invoke(pkg, activity)
            }
        }
        if (btActive) stepBtInternetFlow()
    }

    /** One step of the BT-internet flow, run on each window change. Non-blocking (cheap node scan). */
    private fun stepBtInternetFlow() {
        if (SystemClock.uptimeMillis() > btDeadline) {
            failBtInternetFlow(getString(R.string.bt_internet_fail_not_found))
            return
        }
        val root = rootInActiveWindow ?: return

        // Read the phone's name off the detail header whenever present (used in the HUD message).
        findViewId(root, ENTITY_HEADER_TITLE_ID)?.text?.toString()?.takeIf { it.isNotBlank() }
            ?.let { btTarget = it }

        // If the "Internet access" row is on screen, toggle it. (It lives on the device-detail
        // screen, below the FORGET/DISCONNECT buttons — we scroll down to it in stage 1.)
        val toggleLabel = findNodeByTexts(root, INTERNET_ACCESS_TEXTS)
        if (toggleLabel != null) {
            val row = clickableAncestor(toggleLabel)
            val on = row?.let { switchCheckedIn(it) } ?: false
            when {
                on -> Log.i(TAG, "btInternet: Internet access already ON")
                row != null && row.performAction(AccessibilityNodeInfo.ACTION_CLICK) ->
                    Log.i(TAG, "btInternet: clicked Internet access toggle (was off)")
                else -> { failBtInternetFlow(getString(R.string.bt_internet_fail_click)); return }
            }
            finishBtInternetFlow()
            return
        }

        if (btStage == 1) {
            // On the device detail but the toggle isn't visible yet — scroll down to reveal it.
            scrollForward(root)
            return
        }

        // Stage 0: device list — open the connected phone's detail via the gear in the row of the
        // node whose icon marks a connected phone (content-desc "Phone"; a disconnected device shows
        // a generic "Bluetooth" icon, and right after a reboot the phone briefly sits under "Other
        // devices" with that generic icon — so waiting for the "Phone" icon also waits for it to
        // finish connecting). Returns null until then; we keep polling.
        val gear = topmostDeviceGear(root)
        if (gear != null && gear.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "btInternet: opened the connected-phone detail")
            btStage = 1
            return
        }
        // The device list is often not fully rendered on first entry (the phone hasn't been promoted
        // into "Media devices" / grown its gear yet). Give each entry a render window, then exit and
        // reopen to force a refresh — up to a few times.
        if (SystemClock.uptimeMillis() > btEntryDeadline && btReentries < BT_MAX_REENTRIES) {
            btReentries++
            Log.i(TAG, "btInternet: no gear — exit + reopen BT settings (refresh #$btReentries)")
            performGlobalAction(GLOBAL_ACTION_BACK)
            btHandler.postDelayed({ if (btActive) openBtList() }, 900)
            btEntryDeadline = SystemClock.uptimeMillis() + 900 + BT_ENTRY_RENDER_MS
        }
    }

    /**
     * The settings gear of the connected phone — purely structural, NO text/locale matching. The
     * connected media device (the phone) sits in the first BT category, so its gear (resource-id
     * settings_button) is the TOP-MOST one ON SCREEN. We require it to be on-screen so that right
     * after a reboot — when the phone is still under "Other devices" with no gear yet and the only
     * gear belongs to a device far down the list (off-screen) — we return null and keep waiting
     * instead of opening the wrong device. Null until the phone's gear appears near the top.
     */
    private fun topmostDeviceGear(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels
        val excluded = excludedRowRanges(root)  // hard-excluded devices (e.g. Mercury_iPhone — no PAN)
        val gears = ArrayList<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
        collectGears(root, gears)
        return gears
            .filter { it.second.top in 0 until screenH }
            .filterNot { g -> excluded.any { g.second.centerY() in it.first..it.second } }
            .minByOrNull { it.second.top }?.first
    }

    /** Y-bands of device rows we must never open (hard rule). [EXCLUDED_DEVICE_NAMES] is the ONLY
     *  place a device name is matched, and only to EXCLUDE — positive selection stays structural. */
    private fun excludedRowRanges(root: AccessibilityNodeInfo): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (name in EXCLUDED_DEVICE_NAMES) {
            root.findAccessibilityNodeInfosByText(name)?.forEach { n ->
                val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                out.add((r.top - 30) to (r.bottom + 30))
            }
        }
        return out
    }

    /** Per-device "open detail" gears — matched purely by resource-id (.../settings_button), no text. */
    private fun collectGears(
        node: AccessibilityNodeInfo,
        out: MutableList<Pair<AccessibilityNodeInfo, android.graphics.Rect>>,
    ) {
        if (node.isClickable && node.viewIdResourceName.orEmpty().endsWith("settings_button")) {
            out.add(node to android.graphics.Rect().also { node.getBoundsInScreen(it) })
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectGears(it, out) }
    }

    /** Scroll the first scrollable container forward to reveal rows below the fold. */
    private fun scrollForward(root: AccessibilityNodeInfo): Boolean =
        findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findScrollable(c)?.let { return it }
        }
        return null
    }

    private fun findViewId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()

    /** Success: confirmation on the HUD + leave Settings. */
    private fun finishBtInternetFlow() {
        val name = btTarget.ifBlank { getString(R.string.bt_internet_device_fallback) }
        endBtInternetFlow(getString(R.string.bt_internet_enabled_toast, name), bad = false)
    }

    /** Failure: HUD says WHY + leave Settings, so the user isn't stranded in the Settings app. */
    private fun failBtInternetFlow(reason: String) {
        endBtInternetFlow(getString(R.string.bt_internet_failed_toast, reason), bad = true)
    }

    /** End the flow: stop the state machine, show [message] on the HUD (same pip as gesture hints),
     *  and leave Settings (back to the app for a manual trigger, or Home for the on-boot trigger). */
    private fun endBtInternetFlow(message: String, bad: Boolean) {
        btActive = false
        btHandler.removeCallbacks(btPoll)
        try {
            (application as com.halo.ring.HaloRingApplication).graph.hudNoticeFlow.tryEmit(
                com.halo.ring.ui.hud.HudEvent.Notice(message, bad = bad),
            )
        } catch (e: Exception) {
            Log.w(TAG, "btInternet: couldn't post HUD notice: ${e.message}")
        }
        if (btReturnToApp) {
            try {
                packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    ?.let { startActivity(it) } ?: performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (_: Exception) { performGlobalAction(GLOBAL_ACTION_HOME) }
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun findNodeByTexts(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (t in texts) {
            if (t.isBlank()) continue
            val hits = root.findAccessibilityNodeInfosByText(t)
            if (!hits.isNullOrEmpty()) return hits[0]
        }
        return null
    }

    /** Nearest clickable ancestor of [node] (the tappable Settings row), or [node] if it's clickable. */
    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 8) {
            if (n.isClickable) return n
            n = n.parent
            depth++
        }
        return null
    }

    /** True if [row]'s subtree contains a checked Switch/checkable node (the toggle is already on). */
    private fun switchCheckedIn(row: AccessibilityNodeInfo): Boolean {
        if (row.isCheckable && row.isChecked) return true
        val cn = row.className?.toString().orEmpty()
        if (cn.contains("Switch", ignoreCase = true) && row.isChecked) return true
        for (i in 0 until row.childCount) {
            val c = row.getChild(i) ?: continue
            if (switchCheckedIn(c)) return true
        }
        return false
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        btActive = false
        btHandler.removeCallbacks(btPoll)
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

        private const val BT_FLOW_TIMEOUT_MS = 25_000L
        private const val BT_ENTRY_RENDER_MS = 2_500L  // render window per device-list entry
        private const val BT_MAX_REENTRIES = 5         // exit+reopen refreshes before giving up

        /** Settings detail-screen device-name node — read for the HUD confirmation. */
        private const val ENTITY_HEADER_TITLE_ID = "com.android.settings:id/entity_header_title"

        /** Label text of the per-device "Internet access" toggle across locales. Extend as needed —
         *  tune against the actual Rokid Settings string if matching fails. */
        private val INTERNET_ACCESS_TEXTS = listOf(
            "Internet access", "互联网访问", "互联网连接共享", "互联网共享", "网络共享",
        )

        /** Hard-rule: device rows we must NEVER open. "Mercury_iPhone" is a BLE client a phone app
         *  registers — it has no Internet-access toggle. The only place a device name is matched,
         *  and only to exclude. */
        private val EXCLUDED_DEVICE_NAMES = listOf("Mercury_iPhone")

        /** Most-recently-connected instance, or null if accessibility service is not enabled. */
        @Volatile var instance: HaloRingAccessibilityService? = null
            private set

        /** Subscribed by the service so [com.halo.ring.core.action.ModeManager.onForegroundPackage]
         *  can run for auto-profile-switch. Args: (package, activityClass). */
        @Volatile var foregroundPackageListener: ((String?, String?) -> Unit)? = null
    }
}
