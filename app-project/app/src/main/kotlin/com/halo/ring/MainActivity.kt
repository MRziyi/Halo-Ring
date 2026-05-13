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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.halo.ring.adb.AdbBootstrap
import com.halo.ring.adb.AdbPairingOverlay
import com.halo.ring.adb.RootBypass
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
    private var pairingOverlay: AdbPairingOverlay? = null

    private val accessibilityEnabledState = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val batteryExemptedState = kotlinx.coroutines.flow.MutableStateFlow(false)

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
                    var adbStatus by remember { mutableStateOf("") }
                    // Refreshed on every onResume — when the user comes back from a settings
                    // deep-link, the wizard re-checks state so the "CONTINUE" CTA appears
                    // automatically once the system permission is granted.
                    val a11yEnabled by accessibilityEnabledState.collectAsState()
                    val batteryExempted by batteryExemptedState.collectAsState()
                    FirstRunWizardScreen(
                        onOpenDeveloperSettings = { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
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
                    // A-4: onMeasureNow / onFindRing / onShutdownRing / onForgetRing removed —
                    // VitalsScreen + RingScreen consume LocalAppGraph directly. No callback
                    // threading, same semantics.
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
            AdvancedAction.EXPORT_LATENCY_LOG           -> exportLatencyCsv()
        }
    }

    /**
     * A-5: write the LatencyLogger ring buffer to Downloads/ as a CSV via MediaStore (scoped
     * storage, no WRITE_EXTERNAL_STORAGE required on Android 10+). Returns silently if the
     * buffer is empty or the file write fails — the user can tell from logcat / file system
     * whether it succeeded; a toast pops to surface success / empty / failure.
     */
    private fun exportLatencyCsv() {
        val graph = (application as HaloRingApplication).graph
        val samples = graph.latencyLogger.size()
        if (samples == 0) {
            android.widget.Toast.makeText(this,
                "Latency log is empty — enable measurement first, then exercise the ring.",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val csv = graph.latencyLogger.toCsv()
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val displayName = "halo-latency-$ts.csv"

        val resolver = contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS)
            }
        }

        val uri: android.net.Uri? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } else {
                @Suppress("DEPRATION")
                val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                val out = java.io.File(downloads, displayName)
                out.writeText(csv)
                android.net.Uri.fromFile(out)
            }
        } catch (e: Exception) {
            Log.e("Halo", "latency CSV export failed: ${e.message}")
            null
        }

        if (uri == null) {
            android.widget.Toast.makeText(this,
                "Latency export failed — see logcat", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                Log.e("Halo", "latency CSV write to $uri failed: ${e.message}")
            }
        }

        Log.i("Halo", "wrote $samples latency samples to $uri")
        android.widget.Toast.makeText(this,
            "Wrote $samples samples → Downloads/$displayName",
            android.widget.Toast.LENGTH_LONG).show()
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { Log.w("Halo", "openSettings($action) failed: ${e.message}") }
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
                        runRootedBootstrap(report)
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
            // No root or root bypass failed → fall through to the manual code flow.
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
        report("Installing agent…")
        when (val r = adb.pushAgentDex()) {
            is AdbBootstrap.Result.Failure -> return report("✗ ${r.message}")
            else -> Unit
        }
        when (val r = adb.grantWriteSecureSettings()) {
            is AdbBootstrap.Result.Failure ->
                Log.i("Halo", "pm grant skipped (vendor restriction): ${r.message}")
            else -> Unit
        }
        report("Starting agent…")
        when (val r = adb.startAgent()) {
            is AdbBootstrap.Result.Failure -> return report("✗ ${r.message}")
            else -> Unit
        }
        adb.disconnect()
        report("✓ Agent running (via root bypass).")
    }

    private fun startOverlayPairingFlow(report: (String) -> Unit) {
        if (!AdbPairingOverlay.hasPermission(this)) {
            report("✗ Allow \"Display over other apps\" for Halo Ring, then try again.")
            try { startActivity(AdbPairingOverlay.permissionIntent(this)) }
            catch (e: Exception) { Log.w("Halo", "open overlay perm settings failed: ${e.message}") }
            return
        }

        val overlay = pairingOverlay ?: AdbPairingOverlay(applicationContext).also { pairingOverlay = it }

        report("Open Wireless debugging → Pair with code, then type the code below.")
        overlay.show(
            onSubmit = { code ->
                lifecycleScope.launch {
                    runAdbBootstrap(code, overlay::updateStatus) { final ->
                        // Final state from runAdbBootstrap also flows back to the wizard surface
                        // so the user sees the result after the overlay dismisses.
                        report(final)
                        overlay.hide()
                    }
                }
            },
            onCancel = { report("") },
        )
        // Drop the user into Settings so the system pairing dialog opens on top, with our
        // overlay underneath. (Actually: our overlay window is TYPE_APPLICATION_OVERLAY which
        // floats above Activities — including the Settings dialog. Good.)
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("Halo", "open dev settings failed: ${e.message}")
        }
    }

    /**
     * Run pair → connect → push agent dex → grant → start agent. Reports incremental progress
     * via [progress] (drives the overlay's status text) and the terminal state via [done] (drives
     * the wizard's status text after the overlay closes). The `pm grant` step is allowed to
     * fail — some vendors (OnePlus, Xiaomi) strip `GRANT_RUNTIME_PERMISSIONS` from shell.
     */
    private suspend fun runAdbBootstrap(
        code: String,
        progress: (String) -> Unit,
        done: (String) -> Unit,
    ) {
        progress("Discovering…")
        val ep = adb.discoverPairingEndpoint()
            ?: return done("✗ Pairing service not found. Open \"Pair with code\" and try again.")

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
        progress("Installing agent…")
        when (val r = adb.pushAgentDex()) {
            is AdbBootstrap.Result.Failure -> return done("✗ ${r.message}")
            else -> Unit
        }
        when (val r = adb.grantWriteSecureSettings()) {
            is AdbBootstrap.Result.Failure ->
                Log.i("Halo", "pm grant skipped (vendor restriction): ${r.message}")
            else -> Unit
        }
        progress("Starting agent…")
        when (val r = adb.startAgent()) {
            is AdbBootstrap.Result.Failure -> return done("✗ ${r.message}")
            else -> Unit
        }
        adb.disconnect()
        done("✓ Agent running.")
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
    }

    override fun onPause() {
        super.onPause()
        isInForeground.set(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        pairingOverlay?.destroy()
        pairingOverlay = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.v("Halo", "onKeyDown $keyCode")
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        val isInForeground = AtomicBoolean(false)
    }
}

