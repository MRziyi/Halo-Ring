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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.halo.ring.adb.AdbBootstrap
import com.halo.ring.service.HaloRingService
import com.halo.ring.ui.AppState
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
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's main Activity. Hosts the Compose [HaloRingApp] root. Also exposes a public foreground flag
 * so the foreground service knows when to short-circuit GlassActions through [InAppFocusController].
 */
class MainActivity : ComponentActivity() {

    private lateinit var firstRunStore: FirstRunPrefsStore
    private lateinit var adb: AdbBootstrap

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        result.forEach { (perm, granted) -> Log.i("Halo", "permission $perm = $granted") }
        // Whatever the user said, retry the service start — Android only blocks the FGS-with-type
        // when *no* matching runtime permission is held; if the user denied them all we'll still
        // try and the system will throw which we'll surface in logcat.
        tryStartForegroundService()
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
        adb = AdbBootstrap(applicationContext)

        // Android 14 / targetSdk 34 — starting a foreground service of type=connectedDevice
        // requires the matching runtime BLUETOOTH permissions to already be granted, otherwise
        // Service.startForeground() throws SecurityException. Request first; on grant, start.
        ensurePermissionsThenStartService()

        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                val prefs by graph.feedbackPrefs.flow.collectAsState(initial = FeedbackPrefs())
                val profiles by graph.profilesFlow.collectAsState()
                val activeProfileId by graph.activeProfileIdFlow.collectAsState()
                val sysGestures by graph.systemGesturesFlow.collectAsState()
                val ringInfo by graph.ringInfoFlow.collectAsState()
                val firstRunCompleted by firstRunStore.completedFlow.collectAsState(initial = true)
                val advancedPrefs by graph.advancedPrefsFlow.collectAsState()
                val vitalsPrefs by graph.vitalsPrefsFlow.collectAsState()
                val vitalsSnapshot by graph.vitalsSnapshotFlow.collectAsState()

                if (!firstRunCompleted) {
                    FirstRunWizardScreen(
                        onOpenDeveloperSettings = { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
                        onStartAdbPairing = {
                            lifecycleScope.launch {
                                val ep = adb.discoverPairingEndpoint()
                                if (ep == null) {
                                    Log.w("Halo", "No ADB pairing service found via mDNS; user must open Wireless debugging on the glasses first")
                                } else {
                                    // The 6-digit code is read off the glasses' OS pairing dialog.
                                    // A future revision will prompt the user for it via a Compose dialog.
                                    val placeholder = "000000"
                                    val res = adb.pairWithCode(placeholder, ep)
                                    Log.i("Halo", "pair result: $res")
                                }
                            }
                        },
                        onOpenAccessibilitySettings = { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                        onRequestBatteryExemption = ::requestBatteryExemption,
                        onStartRingPairing = { graph.bleClient.start() },
                        onCompleted = {
                            lifecycleScope.launch { firstRunStore.markCompleted() }
                        },
                    )
                    return@CompositionLocalProvider
                }

                HaloRingApp(
                    initial = AppState(
                        statusBar = StatusBarState(
                            connected = ringInfo.connected,
                            ringId = ringInfo.advertisedName ?: "R08_…",
                            batteryPct = ringInfo.batteryPct,
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
                        ),
                        status = StatusState(activeBackend = "(none)"),
                        feedbackPrefs = prefs,
                    ),
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    systemGestures = sysGestures,
                    ringInfo = ringInfo,
                    advancedPrefs = advancedPrefs,
                    vitalsPrefs = vitalsPrefs,
                    deviceProfile = graph.deviceProfile,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    onMeasureNow = {
                        // B6: trigger an on-demand vitals snapshot via the BLE client.
                        Log.i("Halo", "Vitals MEASURE NOW")
                        graph.bleClient.requestVitalsSnapshot()
                    },
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
                    onFindRing = { graph.bleClient.blinkLed() },
                    onShutdownRing = { graph.bleClient.shutdownRing() },
                    onForgetRing = {
                        graph.bleClient.stop()
                        graph.bleClient.start()
                    },
                )
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
            AdvancedAction.REOPEN_ADB_WIZARD            -> lifecycleScope.launch { firstRunStore.reset() }
            AdvancedAction.EXPORT_LATENCY_LOG           -> Log.i("Halo", "TODO: export latency log CSV")
        }
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { Log.w("Halo", "openSettings($action) failed: ${e.message}") }
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
    }

    override fun onPause() {
        super.onPause()
        isInForeground.set(false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.v("Halo", "onKeyDown $keyCode")
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        val isInForeground = AtomicBoolean(false)
    }
}

