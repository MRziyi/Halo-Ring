package com.halo.ring.di

import android.content.Context
import android.os.Build
import com.halo.ring.ble.AndroidR08BleClient
import com.halo.ring.runtime.AndroidScheduler
import com.halo.ring.ui.screens.AdvancedPrefsStore
import com.halo.ring.ui.screens.FeedbackPrefsStore
import com.halo.ring.ui.screens.ProfilesPrefsStore
import com.halo.ring.ui.screens.VitalsPrefsStore
import com.halo.ring.core.DeviceProfile
import com.halo.ring.core.action.ActionRouter
import com.halo.ring.core.action.DefaultProfiles
import com.halo.ring.core.action.KeyMapProfile
import com.halo.ring.core.action.ModeManager
import com.halo.ring.core.ble.R08BleClient
import com.halo.ring.core.device.DisplayAdapter
import com.halo.ring.core.device.FeatureIntents
import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.device.WearStateProvider
import com.halo.ring.core.gesture.SystemGestures
import com.halo.ring.core.inject.ExecutorBackend
import com.halo.ring.core.perf.LatencyLogger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hand-rolled DI graph (avoid Hilt/Dagger overhead for a small app). Built once in
 * [com.halo.ring.HaloRingApplication.onCreate].
 *
 * The four device strategies are picked at build-time by Gradle product flavors: each flavor
 * (`src/rokid/` vs `src/rayneo/`) defines its own [DeviceFlavorBindings] under this same FQN, and
 * the flavor's version wins. Runtime [DeviceProfile] detection is still done as a sanity check
 * (catches "wrong flavor installed") and to drive GENERIC_ANDROID fallback during dev.
 */
class AppGraph private constructor(
    val deviceProfile: DeviceProfile,
    val displayAdapter: DisplayAdapter,
    val mapper: GlassActionMapper,
    val wearProvider: WearStateProvider,
    val featureIntents: FeatureIntents,
    val backends: List<ExecutorBackend>,
    val modeManager: ModeManager,
    val router: ActionRouter,
    val scheduler: AndroidScheduler,
    val bleClient: R08BleClient,
    val feedbackPrefs: FeedbackPrefsStore,
    val profilesPrefs: ProfilesPrefsStore,
    val advancedPrefs: AdvancedPrefsStore,
    val vitalsPrefs: VitalsPrefsStore,
    /**
     * Live snapshot of the editable profile list. The settings UI mutates this; the foreground
     * service forwards updates into [modeManager] via [com.halo.ring.core.action.ModeManager.upsert]
     * so the running pipeline picks them up immediately. Persisted to DataStore by
     * [com.halo.ring.HaloRingApplication]'s background coroutine.
     */
    val profilesFlow: MutableStateFlow<List<KeyMapProfile>>,
    /** Pushed by [modeManager.observe] so the UI's "(active)" badge stays in sync with ring activity. */
    val activeProfileIdFlow: MutableStateFlow<String>,
    /** Editable [SystemGestures] binding; the foreground service re-applies on change. */
    val systemGesturesFlow: MutableStateFlow<SystemGestures>,
    /** Latest ring telemetry — the foreground service publishes; the Ring / Status / About screens read. */
    val ringInfoFlow: MutableStateFlow<RingInfo>,
    /** Advanced-screen toggles; seeded from DataStore in Application.onCreate. */
    val advancedPrefsFlow: MutableStateFlow<com.halo.ring.ui.screens.AdvancedPrefs>,
    /** Vitals-prefs screen; seeded from DataStore in Application.onCreate. */
    val vitalsPrefsFlow: MutableStateFlow<com.halo.ring.ui.screens.VitalsPrefs>,
    /** Latest vitals snapshot (HR / SpO2 / stress) — the foreground service updates this when
     *  [RingEvent.Health] events arrive in response to [R08BleClient.requestVitalsSnapshot]. */
    val vitalsSnapshotFlow: MutableStateFlow<com.halo.ring.ui.screens.VitalsSnapshot>,
    /** Per-gesture latency ring buffer (Doc/06 §4 — debug HUD's "Latency measurement mode").
     *  The foreground service records into this when [advancedPrefsFlow.latencyMeasurement] is on;
     *  the Advanced screen's "Export latency log" action pulls [LatencyLogger.toCsv]. */
    val latencyLogger: LatencyLogger,
) {
    companion object {
        fun create(context: Context): AppGraph {
            val detected = detectDeviceProfile()
            // DeviceFlavorBindings is provided by the active product flavor's source set.
            val bindings = DeviceFlavorBindings.create(context, detected)

            val mm = ModeManager()
            val router = ActionRouter(bindings.mapper) { bindings.backends }
            val scheduler = AndroidScheduler.start()
            val ble = AndroidR08BleClient(context.applicationContext, scheduler)

            return AppGraph(
                deviceProfile = detected,
                displayAdapter = bindings.displayAdapter,
                mapper = bindings.mapper,
                wearProvider = bindings.wearProvider,
                featureIntents = bindings.featureIntents,
                backends = bindings.backends,
                modeManager = mm,
                router = router,
                scheduler = scheduler,
                bleClient = ble,
                feedbackPrefs = FeedbackPrefsStore(context.applicationContext),
                profilesPrefs = ProfilesPrefsStore(context.applicationContext),
                advancedPrefs = AdvancedPrefsStore(context.applicationContext),
                vitalsPrefs   = VitalsPrefsStore(context.applicationContext),
                profilesFlow = MutableStateFlow(DefaultProfiles.ALL),
                activeProfileIdFlow = MutableStateFlow(mm.active().id),
                systemGesturesFlow = MutableStateFlow(SystemGestures()),
                ringInfoFlow = MutableStateFlow(RingInfo()),
                advancedPrefsFlow = MutableStateFlow(com.halo.ring.ui.screens.AdvancedPrefs()),
                vitalsPrefsFlow = MutableStateFlow(com.halo.ring.ui.screens.VitalsPrefs()),
                vitalsSnapshotFlow = MutableStateFlow(com.halo.ring.ui.screens.VitalsSnapshot()),
                latencyLogger = LatencyLogger(capacity = 200),
            )
        }

        /** Best-effort runtime detection. Refine predicates with real getprop output (§18.7 step 1). */
        private fun detectDeviceProfile(): DeviceProfile {
            val brand = Build.BRAND.orEmpty().lowercase()
            val manuf = Build.MANUFACTURER.orEmpty().lowercase()
            val model = Build.MODEL.orEmpty().lowercase()
            val device = Build.DEVICE.orEmpty().lowercase()
            return when {
                brand == "rokid" || manuf == "rokid" || model == "rg-glasses" || device == "glasses" ->
                    DeviceProfile.ROKID_GLASSES
                "argf20" in model || (brand in setOf("rayneo", "ffalcon", "tcl") && "glass" in model) ->
                    DeviceProfile.RAYNEO_X3PRO
                else -> DeviceProfile.GENERIC_ANDROID
            }
        }
    }
}

/** Each flavor's `DeviceFlavorBindings.kt` exposes this exact shape. */
data class Bindings(
    val displayAdapter: DisplayAdapter,
    val mapper: GlassActionMapper,
    val wearProvider: WearStateProvider,
    val featureIntents: FeatureIntents,
    val backends: List<ExecutorBackend>,
)

/**
 * Ring telemetry that the foreground service maintains and the settings UI reads. None of these
 * fields are persisted — they reflect the live BLE connection state.
 */
data class RingInfo(
    /** "R08_xxxx" advertised name (last 4 hex of MAC); null until first connection. */
    val advertisedName: String? = null,
    /** Hardware MAC address; null until first discovery. */
    val macAddress: String? = null,
    /** Ring firmware version reported in init handshake; null until known. */
    val firmwareVersion: String? = null,
    /** Latest RSSI in dBm (negative). Null until we've polled. */
    val rssiDbm: Int? = null,
    /** Battery percentage 0-100, null = unknown. */
    val batteryPct: Int? = null,
    /** True when [com.halo.ring.core.ble.ConnectionState.READY] is observed. */
    val connected: Boolean = false,
)
