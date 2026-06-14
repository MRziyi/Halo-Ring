package com.halo.ring

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.halo.ring.accessibility.HaloRingAccessibilityService
import com.halo.ring.adb.AdbBootstrap
import com.halo.ring.adb.RootBypass
import com.halo.ring.service.HaloRingService
import com.halo.ring.ui.AppState
import com.halo.ring.ui.BinocularMirror
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.HaloRingApp
import com.halo.ring.ui.StatusBarState
import com.halo.ring.ui.screens.AdvancedAction
import com.halo.ring.ui.screens.AdvancedPrefs
import com.halo.ring.ui.screens.FeedbackPrefs
import com.halo.ring.ui.screens.FirstRunPrefsStore
import com.halo.ring.ui.screens.FirstRunWizardScreen
import com.halo.ring.ui.screens.StatusState
import com.halo.ring.ui.screens.VitalsPrefs
import com.halo.ring.ui.screens.VitalsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's main Activity. Hosts the Compose [HaloRingApp] root. Owns the Activity-scoped
 * [com.halo.ring.ui.ActivitySystemKeyDispatcher] so the gesture pipeline's base-gesture
 * passthrough (Doc/05 §3.8) can dispatch system KeyEvents into this window when foreground.
 *
 * v0.4: in-app foreground-bypass (InAppFocusController) deleted — Compose's standard FocusManager
 * + system KeyEvents handle DPAD navigation natively. Mercury temple-touchpad bridge also deleted;
 * RayNeo input handling is deferred (re-add via Doc/04 §11 if needed).
 */
// Audit-2026-05-13s: switched ComponentActivity → AppCompatActivity. AppCompat-based per-app
// locale (`AppCompatDelegate.setApplicationLocales`) needs the Activity to be AppCompat-aware
// to trigger recreate() on locale change for pre-Android-13. On Android 13+, the system-level
// LocaleManager handles config change → Activity recreation when `android:localeConfig` is
// declared. Either path now works. AppCompatActivity extends ComponentActivity, so activity-
// compose's setContent() and the existing setup are unaffected.
class MainActivity : AppCompatActivity() {

    private lateinit var firstRunStore: FirstRunPrefsStore
    private lateinit var adb: AdbBootstrap

    private val accessibilityEnabledState = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val batteryExemptedState = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** v0.4 — live status of Developer Options + Wi-Fi + Wireless Debugging. Refreshed on every
     *  onResume AND by a 500 ms polling loop while the activity is foregrounded so the wizard
     *  auto-advances the moment a setting is toggled — no tap required. */
    private val devOptionsEnabledState = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val wifiConnectedState = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val wirelessDebugEnabledState = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var setupPollJob: kotlinx.coroutines.Job? = null

    /** Once-per-process guards so the launch-time self-check doesn't keep deep-linking to Settings on
     *  every onResume after the user dismisses it. Reset implicitly on app process death. */
    private var a11yJumpDone = false
    private var overlayJumpDone = false
    /** Once-per-process guard: (re)apply the background-unrestricted keep-alive appop via the agent
     *  the first time we see it alive, so the agent's reboot-respawn re-grants it without re-running
     *  the wizard. The persistent battery exemption stays the user's own system dialog. */
    private var keepAliveGrantDone = false
    /** v0.4 — true when the injection agent is already alive (fresh heartbeat). Lets the wizard's
     *  System-access step show "✓ already set up" instead of forcing the user to re-pair. */
    private val agentReadyState = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** v0.4 — set true by Advanced → "Re-run ADB bootstrap" so the ADB setup wizard re-appears
     *  even when a ring is already paired (the normal first-run guard skips the wizard once paired).
     *  Cleared when the wizard completes. */
    private val forceWizardState = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        result.forEach { (perm, granted) -> Log.i("Halo", "permission $perm = $granted") }
        // FGS of type `connectedDevice` requires at least one of BLUETOOTH_CONNECT / _SCAN to be
        // granted, otherwise the service's own startForeground() throws SecurityException and
        // crashes the service process. Only launch the service when we have what it needs.
        val haveBt = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        if (haveBt) tryStartForegroundService()
        else Log.w("Halo", "BT permissions denied — skipping service start; UI-only mode")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash theme drew the cold-start frame; swap to the real app theme BEFORE the first
        // Compose render so the system's splash icon hands off cleanly to our UI.
        setTheme(R.style.Theme_HaloRing)
        super.onCreate(savedInstanceState)
        val app = application as HaloRingApplication
        val graph = app.graph
        Log.i("Halo", "MainActivity onCreate; profile=${graph.deviceProfile}")
        firstRunStore = FirstRunPrefsStore(applicationContext)
        adb = graph.adbBootstrap   // process-scoped singleton — shares one loopback connection with the service
        // Seed state immediately so the wizard renders the correct gates on the very first frame
        // instead of showing everything as "not done" until the first onResume poll fires.
        refreshSetupState()

        // Android 14 / targetSdk 34 — starting a foreground service of type=connectedDevice
        // requires the matching runtime BLUETOOTH permissions to already be granted, otherwise
        // Service.startForeground() throws SecurityException. Request first; on grant, start.
        ensurePermissionsThenStartService()

        // v0.4 — auto-request battery-optimisation exemption ONCE. It's the keep-alive linchpin:
        // without it Android Doze kills our foreground service after a few hours, breaking the
        // resident BLE link + agent. The system dialog only appears if not already exempted; we
        // gate on a one-shot flag so a user who declines isn't nagged every launch. (User asked
        // for this 2026-05-27 — "保活" concern.)
        lifecycleScope.launch {
            val asked = firstRunStore.batteryExemptionAskedFlow.first()
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!asked && !pm.isIgnoringBatteryOptimizations(packageName)) {
                firstRunStore.markBatteryExemptionAsked()
                requestBatteryExemption()
            }
        }

        // v0.4 — auto-recovery: if setup was completed before but the injection agent is no longer
        // alive on startup, drop the user back into the wizard. Fast-fail path: if Wi-Fi is off
        // adbd can't bind its port and bootRecoverAgent cannot possibly succeed, so we show the
        // wizard immediately. If Wi-Fi is on we give the service's bootRecoverAgent a 3-s window
        // to revive the agent before triggering the wizard.
        lifecycleScope.launch {
            if (!firstRunStore.completedFlow.first()) return@launch
            if (agentReadyState.value) return@launch  // already alive — all good
            if (!wifiConnectedState.value) {
                Log.w("Halo", "Wi-Fi off on start — re-entering wizard immediately")
                forceWizardState.value = true
                return@launch
            }
            kotlinx.coroutines.delay(3000)
            refreshSetupState()
            if (!agentReadyState.value) {
                Log.w("Halo", "agent not ready 3s after start — re-entering wizard")
                forceWizardState.value = true
            }
        }

        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                val prefs by graph.feedbackPrefs.flow.collectAsState(initial = FeedbackPrefs())
                val profiles by graph.profilesFlow.collectAsState()
                val activeProfileId by graph.activeProfileIdFlow.collectAsState()
                val sysGestures by graph.systemGesturesFlow.collectAsState()
                val ringInfo by graph.ringInfoFlow.collectAsState()
                val firstRunCompleted by firstRunStore.completedFlow.collectAsState(initial = true)
                // On-glasses fix 2026-05-27: a ring that's already paired means setup is effectively
                // done — show the main app, not the wizard. Without this, a connection that didn't
                // mark first-run complete (e.g. a slow connect the user backed out of) re-showed the
                // pairing wizard on every launch ("每次重装就需要重新配对").
                val pairedMac by graph.ringPairingPrefs.pairedMacFlow.collectAsState(initial = null)
                val forceWizard by forceWizardState.collectAsState()
                val advancedPrefs by graph.advancedPrefsFlow.collectAsState()
                val vitalsPrefs by graph.vitalsPrefsFlow.collectAsState()
                val vitalsSnapshot by graph.vitalsSnapshotFlow.collectAsState()
                val ringCapabilities by graph.ringCapabilitiesFlow.collectAsState()
                // Live system-access state (refreshed by the onResume 500 ms poll) for the Advanced
                // screen's critical rows — so each shows ✓/needs-setup + guards an accidental re-run.
                val a11yEnabledNow by accessibilityEnabledState.collectAsState()
                val batteryExemptedNow by batteryExemptedState.collectAsState()
                val agentReadyNow by agentReadyState.collectAsState()
                // Active home tab — driven by both UI taps and the in-app LONG_PRESS gesture (which
                // the service routes into this same flow). See AppGraph.homeTabIndexFlow.
                val homeTabIndex by graph.homeTabIndexFlow.collectAsState()
                // Audit-2026-05-13o: app-wide prefs — language override only in v0.4 (GuidedTour deleted).
                val appPrefs = (application as HaloRingApplication).appPrefs
                val currentLanguage by appPrefs.languageFlow
                    .collectAsState(initial = com.halo.ring.ui.screens.AppLanguage.SYSTEM)

                // RayNeo: mirror the whole UI into both eye-halves (fixes the x=640 seam split).
                // Rokid / mono → enabled=false → pass-through, zero overhead. See BinocularMirror.
                BinocularMirror(enabled = com.halo.ring.BuildConfig.DEVICE_FLAVOR == "rayneo") {
                if ((!firstRunCompleted && pairedMac == null) || forceWizard) {
                    var adbStatus by remember { mutableStateOf("") }
                    // Refreshed on every onResume — when the user comes back from a settings
                    // deep-link, the wizard re-checks state so the "CONTINUE" CTA appears
                    // automatically once the system permission is granted.
                    val a11yEnabled by accessibilityEnabledState.collectAsState()
                    val batteryExempted by batteryExemptedState.collectAsState()
                    val devOptionsEnabled by devOptionsEnabledState.collectAsState()
                    val wifiConnected by wifiConnectedState.collectAsState()
                    val wirelessDebugEnabled by wirelessDebugEnabledState.collectAsState()
                    val agentReady by agentReadyState.collectAsState()
                    FirstRunWizardScreen(
                        onOpenDeveloperSettings = { openWirelessDebuggingSettings() },
                        onOpenBuildNumber = { openAboutForBuildNumber() },
                        devOptionsEnabled = devOptionsEnabled,
                        wifiConnected = wifiConnected,
                        onOpenWifiSettings = { openWifiSettings() },
                        wirelessDebugEnabled = wirelessDebugEnabled,
                        agentReady = agentReady,
                        onStartAdbPairing = {
                            startPairingFlow { adbStatus = it }
                        },
                        adbStatus = adbStatus,
                        onOpenAccessibilitySettings = { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                        accessibilityEnabled = a11yEnabled,
                        onRequestBatteryExemption = ::requestBatteryExemption,
                        batteryExempted = batteryExempted,
                        onStartRingPairing = { graph.bleClient.start() },
                        onCompleted = {
                            lifecycleScope.launch { firstRunStore.markCompleted() }
                            forceWizardState.value = false   // clear the Advanced-triggered re-show
                        },
                        isReRecovery = forceWizard,
                        pairedMac = pairedMac,
                        ringConnected = ringInfo.connected,
                        onStartRingReconnect = {
                            graph.bleClient.stop()
                            graph.bleClient.start()
                        },
                        onStartAdbReconnect = {
                            lifecycleScope.launch {
                                adb.withRecoveryLock { runAdbReconnect({ adbStatus = it }, { adbStatus = it }) }
                            }
                        },
                    )
                    return@BinocularMirror
                }

                HaloRingApp(
                    initial = AppState(
                        statusBar = StatusBarState(
                            connected = ringInfo.connected,
                            ringId = ringInfo.advertisedName ?: "R08_…",
                            batteryPct = ringInfo.batteryPct,
                            charging = ringInfo.charging == true,
                            currentMode = graph.modeManager.active().name,
                        ),
                        vitals = VitalsState(
                            heartRateBpm = vitalsSnapshot.heartRateBpm,
                            spo2Pct = vitalsSnapshot.spo2Pct,
                            stressIndex = vitalsSnapshot.stressIndex,
                            measuring = vitalsSnapshot.measuring,
                            measuredMinutesAgo = vitalsSnapshot.capturedAtMs.takeIf { it > 0 }?.let {
                                ((android.os.SystemClock.uptimeMillis() - it) / 60_000L).toInt()
                            },
                            // SPEC v3 §5.3: live activity counters from `0x73 sub=0x12` push.
                            // Empty (null) means no data this session; the screen treats 0 as "—".
                            stepsToday = vitalsSnapshot.activitySteps ?: 0,
                            caloriesToday = vitalsSnapshot.activityKcal ?: 0f,
                            distanceKmToday = (vitalsSnapshot.activityMeters ?: 0) / 1000f,
                            sportActive = vitalsSnapshot.sportActive,
                            sportType = vitalsSnapshot.sportType,
                            sportDurationSec = vitalsSnapshot.sportDurationSec,
                        ),
                        status = StatusState(activeBackend = "(none)"),
                        feedbackPrefs = prefs,
                    ),
                    homeTab = com.halo.ring.ui.screens.HomeTab.values()
                        .getOrElse(homeTabIndex) { com.halo.ring.ui.screens.HomeTab.RING },
                    onSelectTab = { graph.homeTabIndexFlow.value = it.ordinal },
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    systemGestures = sysGestures,
                    ringInfo = ringInfo,
                    ringCapabilities = ringCapabilities,
                    advancedPrefs = advancedPrefs,
                    setupStatus = com.halo.ring.ui.screens.SetupStatus(
                        batteryExempted = batteryExemptedNow,
                        accessibilityEnabled = a11yEnabledNow,
                        agentReady = agentReadyNow,
                    ),
                    vitalsPrefs = vitalsPrefs,
                    deviceProfile = graph.deviceProfile,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    onPrefsChanged = { newPrefs ->
                        lifecycleScope.launch { graph.feedbackPrefs.updatePrefs { newPrefs } }
                    },
                    onProfileUpdated = { updated ->
                        val list = graph.profilesFlow.value
                        val i = list.indexOfFirst { it.id == updated.id }
                        graph.profilesFlow.value = if (i < 0) list + updated
                                                   else list.toMutableList().also { it[i] = updated }
                    },
                    onSystemGesturesChanged = { graph.systemGesturesFlow.value = it },
                    onAdvancedPrefsChanged = { graph.advancedPrefsFlow.value = it },
                    onVitalsPrefsChanged = { graph.vitalsPrefsFlow.value = it },
                    onAdvancedAction = { handleAdvancedAction(it) },
                    currentLanguage = currentLanguage,
                    onLanguageSelected = { lang ->
                        lifecycleScope.launch { appPrefs.setLanguage(lang) }
                    },
                    // Audit-2026-05-13s: when DOUBLE_TAP / Back fires at the app's root (no
                    // sub-screen to pop), drop the Activity to the back of the stack — which on
                    // the glasses lands the wearer back on the system launcher / whatever they
                    // came from. We don't `finish()` because that would kill the foreground
                    // service's tied Activity context unnecessarily; `moveTaskToBack(true)`
                    // is the equivalent of pressing Home — process keeps running, gestures stay
                    // live for screen-wake etc.
                    onExitToSystem = { moveTaskToBack(true) },
                    // A-4: onMeasureNow / onFindRing / onShutdownRing / onForgetRing removed —
                    // VitalsScreen + RingScreen consume LocalAppGraph directly. No callback
                    // threading, same semantics.
                )
                }  // BinocularMirror
            }
        }
    }

    /** Build the list of permissions we still need and either request them or proceed straight
     *  to starting the foreground service. */
    private fun ensurePermissionsThenStartService() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {  // 31+
            addIfMissing(needed, Manifest.permission.BLUETOOTH_CONNECT)
            addIfMissing(needed, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Pre-12 the BLE scan permission gate is location.
            addIfMissing(needed, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {  // 33+
            addIfMissing(needed, Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isEmpty()) {
            tryStartForegroundService()
        } else {
            Log.i("Halo", "requesting runtime permissions: $needed")
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    private fun addIfMissing(out: MutableList<String>, perm: String) {
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            out += perm
        }
    }

    private fun tryStartForegroundService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, HaloRingService::class.java))
        } catch (e: SecurityException) {
            // Surface this rather than crash the activity — the BLE central is genuinely useless
            // without the BLUETOOTH_* permissions but the UI still works for read-only screens.
            Log.e("Halo", "startForegroundService denied: ${e.message}")
        }
    }

    private fun handleAdvancedAction(action: AdvancedAction) {
        when (action) {
            AdvancedAction.DEEP_LINK_ACCESSIBILITY      -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            AdvancedAction.DEEP_LINK_BATTERY_EXEMPTION  -> requestBatteryExemption()
            AdvancedAction.REOPEN_ADB_WIZARD            -> forceWizardState.value = true
            AdvancedAction.OPEN_ANDROID_SETTINGS        -> openSettings(Settings.ACTION_SETTINGS)
            AdvancedAction.BT_INTERNET_CONNECT_NOW      -> {
                val a11y = com.halo.ring.accessibility.HaloRingAccessibilityService.instance
                if (a11y == null) {
                    openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    android.widget.Toast.makeText(
                        this, getString(R.string.bt_internet_need_a11y), android.widget.Toast.LENGTH_LONG,
                    ).show()
                } else {
                    a11y.startBtInternetFlow()
                }
            }
        }
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { Log.w("Halo", "openSettings($action) failed: ${e.message}") }
    }

    /** v0.4 — get the user to Wireless Debugging with as little friction as possible.
     *
     * If the app already holds `WRITE_SECURE_SETTINGS` (granted once the agent is bootstrapped),
     * we **enable Developer Options + Wireless Debugging ourselves** via `Settings.Global` — no
     * manual toggling needed — then deep-link to the screen so the user can confirm + start the
     * pairing dialog.
     *
     * On a fresh device (no perm yet) we can't flip those toggles (Android security: Developer
     * Options is gated behind tapping Build Number ×7, which no API can bypass), so we deep-link
     * to the most specific screen reachable: Wireless Debugging → Developer Options → About
     * (where Build Number lives). The wizard text explains the one-time manual enable.
     */
    private fun openWirelessDebuggingSettings() {
        // Best-effort programmatic enable (silently no-ops without WRITE_SECURE_SETTINGS).
        try {
            android.provider.Settings.Global.putInt(
                contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            android.provider.Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1)
            Log.i("Halo", "enabled developer options + wireless debugging via WRITE_SECURE_SETTINGS")
        } catch (e: SecurityException) {
            Log.i("Halo", "no WRITE_SECURE_SETTINGS yet — user must enable dev options + wireless debug manually")
        } catch (e: Exception) {
            Log.w("Halo", "auto-enable wireless debug failed: ${e.message}")
        }

        val candidates = listOf(
            "android.settings.ADB_WIFI_SETTINGS",
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            Settings.ACTION_DEVICE_INFO_SETTINGS,   // About → Build number (×7 to unlock dev options)
        )
        for (action in candidates) {
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* try next */ }
        }
        Log.w("Halo", "no wireless-debugging settings screen available")
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    /** True when the Wi-Fi radio is on. Wireless ADB binds to the Wi-Fi interface; the
     *  radio must be enabled but an actual internet connection is NOT required. */
    private fun isWifiConnected(): Boolean {
        val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return false
        return wm.isWifiEnabled
    }

    /** v0.4 — jump to the About / Device-info screen where "Build number" lives (tap ×7 to unlock
     *  Developer Options). We can't focus the row itself — Android forbids cross-app focus control
     *  of the system Settings UI — but this lands the user one screen away. If we already hold
     *  WRITE_SECURE_SETTINGS we enable Developer Options outright (no ×7 needed) before jumping. */
    private fun openAboutForBuildNumber() {
        try {
            android.provider.Settings.Global.putInt(
                contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            Log.i("Halo", "enabled developer options via WRITE_SECURE_SETTINGS")
        } catch (_: Exception) { /* no perm yet — user does the ×7 manually */ }
        val candidates = listOf(
            Settings.ACTION_DEVICE_INFO_SETTINGS,   // About → Build number
            "android.settings.DEVICE_INFO_SETTINGS",
            Settings.ACTION_SETTINGS,
        )
        for (action in candidates) {
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* try next */ }
        }
    }

    /**
     * Kick off the pairing flow. Two paths:
     *
     * 1. **Root bypass** (dev rig on rooted phones) — `su` writes our pubkey directly into
     *    `/data/misc/adb/adb_keys` and we connect on the persistent TLS-connect port. No code
     *    entry, no overlay, no system dialog. Tried first.
     * 2. **Overlay + mDNS** (default on glasses) — system overlay sits on top of the Settings
     *    pairing dialog; user types the 6-digit code; we discover the pair port via mDNS.
     */
    private fun startPairingFlow(report: (String) -> Unit) {
        lifecycleScope.launch {
            report("Trying root auto-setup…")
            if (RootBypass.isRootAvailable()) {
                when (val r = adb.installKeyViaRoot()) {
                    is AdbBootstrap.Result.Success -> {
                        adb.withRecoveryLock { runRootedBootstrap(report) }
                        return@launch
                    }
                    is AdbBootstrap.Result.Failure -> {
                        Log.w("Halo", "root bypass failed, falling back to manual: ${r.message}")
                        report("Root failed — falling back to pairing code.")
                    }
                }
            } else {
                report("No root — opening pairing code entry.")
            }
            // No root or root bypass failed → fall through to overlay code entry.
            startOverlayPairingFlow(report)
        }
    }

    /** After [AdbBootstrap.installKeyViaRoot], adbd trusts our key. Just connect + provision. */
    private suspend fun runRootedBootstrap(report: (String) -> Unit) {
        report("Connecting…")
        when (val r = adb.connect()) {
            is AdbBootstrap.Result.Failure -> return report("✗ ${r.message}")
            else -> Unit
        }
        when (val r = adb.grantWriteSecureSettings()) {
            is AdbBootstrap.Result.Failure ->
                Log.i("Halo", "pm grant skipped (vendor restriction): ${r.message}")
            else -> Unit
        }
        adb.grantOverlayPermission()         // best-effort: lets the HUD overlay render (else hud is null)
        adb.grantAccessibilityService()      // best-effort: re-enables HaloRingAccessibilityService (foreground detection)
        adb.grantKeepAlive()                 // best-effort: background-unrestricted appop (replaces the "open App info" tail)
        if (!adb.isOnPersistentTcp()) {
            report("Enabling offline control…")
            when (val r = adb.migrateToPersistentTcp()) {
                is AdbBootstrap.Result.Failure -> return report("✗ offline setup: ${r.message}")
                else -> Unit
            }
        }
        report("Installing agent…")
        when (val r = adb.pushAgentDex()) {
            is AdbBootstrap.Result.Failure -> return report("✗ ${r.message}")
            else -> Unit
        }
        report("Starting agent…")
        when (val r = adb.startAgent()) {
            is AdbBootstrap.Result.Failure -> return report("✗ ${r.message}")
            else -> Unit
        }
        report("Verifying agent…")
        for (i in 0..14) {
            kotlinx.coroutines.delay(200)
            if (adb.checkAgentAlive()) return report("✓ Agent running (via root bypass).")
        }
        adb.disconnect()
        report("✗ Agent didn't start. Check halo-agent.log on device.")
    }

    /**
     * Overlay-based code entry.
     *
     * TYPE_APPLICATION_OVERLAY is suppressed by Settings' HIDE_NON_SYSTEM_OVERLAY_WINDOWS.
     * TYPE_ACCESSIBILITY_OVERLAY is explicitly exempt — only visible when the accessibility
     * service is running. If the service is connected, the overlay is created there with
     * TYPE_ACCESSIBILITY_OVERLAY; otherwise we surface an actionable error.
     */
    private fun startOverlayPairingFlow(report: (String) -> Unit) {
        if (!isWifiConnected()) {
            report("✗ Enable Wi-Fi first — wireless ADB needs the Wi-Fi interface (no internet required).")
            try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { }
            return
        }

        val a11y = HaloRingAccessibilityService.instance
        if (a11y == null) {
            report("✗ Enable \"Halo Ring\" in Settings → Accessibility first, then tap TRY AGAIN.")
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { }
            return
        }

        report("Open Wireless Debugging → \"Pair device with pairing code\" and enter the 6-digit code below.")
        a11y.showPairingOverlay(
            onSubmit = { code ->
                lifecycleScope.launch {
                    adb.withRecoveryLock {
                        runAdbBootstrap(code, a11y::updatePairingStatus) { final ->
                            report(final)
                            a11y.hidePairingOverlay()
                        }
                    }
                }
            },
            onCancel = { report("") },
        )
        for (action in listOf("android.settings.ADB_WIFI_SETTINGS",
                              Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) {
            try { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break }
            catch (_: Exception) { }
        }
    }

    /**
     * Re-recovery path: connect (keypair already exists) → push agent dex → grant → start agent.
     * No SPAKE2 pairing code required. Used when the agent died after a previous successful setup.
     */
    private suspend fun runAdbReconnect(
        progress: (String) -> Unit,
        done: (String) -> Unit,
    ) {
        progress("Connecting…")
        when (val r = adb.connect()) {
            is AdbBootstrap.Result.Failure -> return done("✗ ${r.message}")
            else -> Unit
        }
        progress("Pushing agent…")
        when (val r = adb.pushAgentDex()) {
            is AdbBootstrap.Result.Failure -> return done("✗ ${r.message}")
            else -> Unit
        }
        when (val r = adb.grantWriteSecureSettings()) {
            is AdbBootstrap.Result.Failure ->
                Log.i("Halo", "pm grant skipped: ${r.message}")
            else -> Unit
        }
        adb.grantOverlayPermission()         // best-effort: lets the HUD overlay render (else hud is null)
        adb.grantAccessibilityService()      // best-effort: re-enables HaloRingAccessibilityService (foreground detection)
        adb.grantKeepAlive()                 // best-effort: background-unrestricted appop (replaces the "open App info" tail)
        progress("Starting agent…")
        when (val r = adb.startAgent()) {
            is AdbBootstrap.Result.Failure -> return done("✗ ${r.message}")
            else -> Unit
        }
        progress("Verifying agent…")
        for (i in 0..14) {
            kotlinx.coroutines.delay(200)
            if (adb.checkAgentAlive()) return done("✓ Agent running.")
        }
        adb.disconnect()
        done("✗ Agent didn't start. Check halo-agent.log on device.")
    }

    /**
     * Run pair → connect → push agent dex → grant → start agent. Reports incremental progress
     * via [progress] (drives the overlay status text) and the terminal state via [done].
     * The `pm grant` step is allowed to fail — some vendors strip GRANT_RUNTIME_PERMISSIONS.
     */
    private suspend fun runAdbBootstrap(
        code: String,
        progress: (String) -> Unit,
        done: (String) -> Unit,
    ) {
        progress("Discovering…")
        val ep = adb.discoverPairingEndpoint()
            ?: return done("✗ Pairing service not found. Open \"Pair with code\" and tap TRY AGAIN.")

        progress("Pairing…")
        when (val r = adb.pairWithCode(code, ep)) {
            is AdbBootstrap.Result.Failure -> return done("✗ ${r.message}")
            else -> Unit
        }
        progress("Connecting…")
        when (val r = adb.connect()) {
            is AdbBootstrap.Result.Failure -> return done("✗ ${r.message}")
            else -> Unit
        }
        when (val r = adb.grantWriteSecureSettings()) {
            is AdbBootstrap.Result.Failure ->
                Log.i("Halo", "pm grant skipped (vendor restriction): ${r.message}")
            else -> Unit
        }
        adb.grantOverlayPermission()         // best-effort: lets the HUD overlay render (else hud is null)
        adb.grantAccessibilityService()      // best-effort: re-enables HaloRingAccessibilityService (foreground detection)
        adb.grantKeepAlive()                 // best-effort: background-unrestricted appop (replaces the "open App info" tail)
        // Move off the Wi-Fi-bound wireless transport onto the persistent loopback port so the
        // agent survives Wi-Fi off / leaving the AP / reboot. No-op if we already came up on it.
        if (!adb.isOnPersistentTcp()) {
            progress("Enabling offline control…")
            when (val r = adb.migrateToPersistentTcp()) {
                is AdbBootstrap.Result.Failure -> return done("✗ offline setup: ${r.message}")
                else -> Unit
            }
        }
        progress("Installing agent…")
        when (val r = adb.pushAgentDex()) {
            is AdbBootstrap.Result.Failure -> return done("✗ ${r.message}")
            else -> Unit
        }
        progress("Starting agent…")
        when (val r = adb.startAgent()) {
            is AdbBootstrap.Result.Failure -> return done("✗ ${r.message}")
            else -> Unit
        }
        // Poll for the agent's abstract socket — up to 3 s. On success, leave the ADB connection
        // open: the agent runs in the shell foreground and dies the moment we disconnect.
        progress("Verifying agent…")
        for (i in 0..14) {
            kotlinx.coroutines.delay(200)
            if (adb.checkAgentAlive()) return done("✓ Agent running.")
        }
        adb.disconnect()
        done("✗ Agent didn't start. Check halo-agent.log on device.")
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i("Halo", "battery exemption already granted")
            return
        }
        try {
            @SuppressWarnings("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.w("Halo", "requestBatteryExemption failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground.set(true)
        refreshSetupState()
        // Poll every 500 ms so the wizard reacts instantly when a setting is changed (e.g. the
        // user enables Wi-Fi from the quick-settings shade without leaving Halo Ring).
        setupPollJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                refreshSetupState()
            }
        }
        // v0.3 P1 (Doc/19 §3.P1) / v0.4 (Doc/20 §3): publish our activity ref so the gesture
        // pipeline can dispatch base-gesture KeyEvents into this window. Compose handles
        // DPAD_CENTER / BACK / DPAD_LEFT/RIGHT natively on the focused element.
        com.halo.ring.ui.ActivitySystemKeyDispatcher.attach(this)
    }

    /**
     * Re-check accessibility and battery-exemption state. Called on every onResume so the
     * wizard auto-advances when the user comes back from a system Settings deep-link.
     */
    private fun refreshSetupState() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        batteryExemptedState.value = pm.isIgnoringBatteryOptimizations(packageName)

        val expectedSvc = "$packageName/com.halo.ring.accessibility.HaloRingAccessibilityService"
        val enabledList = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: ""
        accessibilityEnabledState.value = enabledList.split(':').any { it == expectedSvc }

        // Auto-re-enable our a11y service when it's missing. Done via the **agent** (shell uid) —
        // a direct Settings.Secure write from the app silently fails to bind even with
        // WRITE_SECURE_SETTINGS (Android refuses to honour app-side enables for a11y security).
        // Verified on-device 2026-05-29: shell-route works, app-route writes but never binds.
        // Covers `install -r` (Android disables a11y services whose app process was killed by the
        // install) — the next launch silently puts it back without the user re-toggling Settings.
        val agentLikelyUp = try {
            val hb = java.io.File("/data/local/tmp/halo.agent.heartbeat")
            hb.exists() && (System.currentTimeMillis() - hb.lastModified()) < 30_000L
        } catch (_: Exception) { false }
        if (!accessibilityEnabledState.value) {
            if (agentLikelyUp) lifecycleScope.launch {
                when (val r = adb.grantAccessibilityService()) {
                    is AdbBootstrap.Result.Success ->
                        Log.i("Halo", "auto-re-enabled HaloRingAccessibilityService via agent")
                    is AdbBootstrap.Result.Failure ->
                        Log.w("Halo", "a11y re-enable via agent failed: ${r.message}")
                }
            } else if (!a11yJumpDone) {
                // Agent down → can't auto-fix; prompt + jump to Settings so the user enables it.
                a11yJumpDone = true
                android.widget.Toast.makeText(this, getString(R.string.toast_enable_accessibility), android.widget.Toast.LENGTH_LONG).show()
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { Log.w("Halo", "couldn't deep-link to accessibility settings") }
            }
        }

        // Self-check the HUD overlay permission too (same pattern as a11y). Granted by the agent on
        // bootstrap; if it's gone and the agent is down, prompt + jump to MANAGE_OVERLAY_PERMISSION.
        val overlayGranted = Settings.canDrawOverlays(this)
        if (!overlayGranted) {
            if (agentLikelyUp) lifecycleScope.launch {
                when (val r = adb.grantOverlayPermission()) {
                    is AdbBootstrap.Result.Success -> Log.i("Halo", "auto-re-granted SYSTEM_ALERT_WINDOW via agent")
                    is AdbBootstrap.Result.Failure -> Log.w("Halo", "overlay re-grant via agent failed: ${r.message}")
                }
            } else if (!overlayJumpDone) {
                overlayJumpDone = true
                android.widget.Toast.makeText(this, getString(R.string.toast_enable_overlay), android.widget.Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { Log.w("Halo", "couldn't deep-link to overlay settings") }
            }
        }

        // Keep-alive self-heal: once per process, when the agent is up, (re)apply the background-
        // unrestricted appop silently. Covers boot-recovery (the agent re-spawns on boot but the
        // wizard doesn't re-run) and any user who reached the home without it set. This is the
        // silent replacement for the old "open App info → set Unrestricted" tail; the persistent
        // battery-optimisation exemption stays the user's own REQUEST_IGNORE_BATTERY dialog.
        if (agentLikelyUp && !keepAliveGrantDone) {
            keepAliveGrantDone = true
            lifecycleScope.launch {
                when (val r = adb.grantKeepAlive()) {
                    is AdbBootstrap.Result.Success -> Log.i("Halo", "keep-alive appop ensured via agent")
                    is AdbBootstrap.Result.Failure -> Log.w("Halo", "keep-alive appop via agent failed: ${r.message}")
                }
            }
        }

        // v0.4 — Developer Options + Wi-Fi + Wireless Debugging live status.
        devOptionsEnabledState.value = try {
            android.provider.Settings.Global.getInt(
                contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Exception) { false }
        wifiConnectedState.value = isWifiConnected()
        wirelessDebugEnabledState.value = try {
            android.provider.Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: Exception) { false }

        // Auto-re-enable wireless debugging when Wi-Fi is on and we hold WRITE_SECURE_SETTINGS.
        // Covers the common case after reboot: the OEM clears adb_wifi_enabled when Wi-Fi was off
        // at boot. Once the user turns Wi-Fi on, we flip it back silently and the wizard advances.
        if (wifiConnectedState.value && !wirelessDebugEnabledState.value) {
            try {
                android.provider.Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1)
                wirelessDebugEnabledState.value = true
                Log.i("Halo", "auto-re-enabled adb_wifi_enabled (Wi-Fi on + WRITE_SECURE_SETTINGS)")
            } catch (_: Exception) { /* no WRITE_SECURE_SETTINGS yet — user must enable manually */ }
        }

        // Agent liveness: the heartbeat file is world-readable (-rw-rw-rw-) and the agent rewrites
        // it every 20 s (P1-7). Fresh (< 30 s) ⇒ agent up ⇒ system access already done.
        agentReadyState.value = try {
            val hb = java.io.File("/data/local/tmp/halo.agent.heartbeat")
            hb.exists() && (System.currentTimeMillis() - hb.lastModified()) < 30_000L
        } catch (_: Exception) { false }
    }

    override fun onPause() {
        super.onPause()
        isInForeground.set(false)
        setupPollJob?.cancel()
        setupPollJob = null
        // v0.3 P1: clear the dispatcher's activity ref so the gesture pipeline falls back to
        // the no-op dispatcher while we're backgrounded. Out-of-app gesture handling will go
        // through the agent (Phase 1b — out of scope for v0.3 P1).
        com.halo.ring.ui.ActivitySystemKeyDispatcher.detach()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.v("Halo", "onKeyDown $keyCode")
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        val isInForeground = AtomicBoolean(false)
    }
}

