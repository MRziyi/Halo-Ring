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
import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.SystemGestures
import com.halo.ring.core.inject.ExecutorBackend
import com.halo.ring.core.perf.LatencyLogger
import com.halo.ring.core.perf.VitalsLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

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
    /** Per-reading vitals ring buffer (HR / SpO2 / stress). The foreground service records into
     *  this when [vitalsPrefsFlow.csvExportEnabled] is on; the Advanced screen's
     *  "Export vitals log" action pulls [VitalsLogger.toCsv]. */
    val vitalsLogger: VitalsLogger,
    /** Doc/18 plugin discovery + cache. The foreground service registers package-event receivers
     *  in onCreate so the cache invalidates on plugin install/uninstall; UI screens read via
     *  [com.halo.ring.plugin.PluginRegistry.plugins] (StateFlow) for snappy recomposition. */
    val pluginRegistry: com.halo.ring.plugin.PluginRegistry,
    /** Doc/18 §6 — overlay-app profile stack. Pure-JVM state in :core; the service plus the
     *  [com.halo.ring.plugin.PluginBroadcastReceiver] are the only writers. The router consults
     *  this BEFORE the active KeyMapProfile on every gesture (still after system gestures). */
    val profileStack: com.halo.ring.core.plugin.ProfileStack,
    /**
     * Live stream of recognised gestures, published by the foreground service's
     * `InteractionRouter.onGestureRecognized` callback. Consumers (the interactive
     * `GuidedTour` + the `TestArenaScreen`) collect from it to detect what the user just did
     * — replaces the static "tap NEXT to advance" pattern with real-input coaching.
     *
     * Implemented as a `SharedFlow` with a small buffer so a slow consumer can't backpressure
     * the gesture pipeline. Replay is 0 — we don't want a stale gesture to fire a tour step
     * the instant the user opens it.
     */
    val recognisedGestureFlow: MutableSharedFlow<Gesture>,
    /**
     * Most-recent [RecognisedGesture] — gesture + resolved action + synth-latency. Distinct from
     * [recognisedGestureFlow] because (a) the Test Arena needs the action + latency to render the
     * mockup's big "LAST GESTURE → Action · NN ms latency" card, and (b) StateFlow gives a
     * persistent "what just happened" snapshot whereas the SharedFlow is replay=0 (intentional —
     * GuidedTour shouldn't react to stale gestures on open).
     */
    val lastRecognisedFlow: MutableStateFlow<RecognisedGesture?>,
) {
    /**
     * Best-effort detection of which input source the wearer is using right now. Drives the
     * interactive tour + Test Arena's coaching content (ring-specific gesture names vs temple
     * touchpad terminology).
     *
     * - RING: ring is connected via BLE. Primary input.
     * - TEMPLE: no ring but we're on a flavor with a temple touchpad (RayNeo X3 Pro).
     * - NONE: neither — running on a generic Android device (dev rig / phone), no live input.
     *   Tour falls back to informational slides.
     */
    fun currentInputSource(): InputSource = when {
        ringInfoFlow.value.connected            -> InputSource.RING
        deviceProfile == DeviceProfile.RAYNEO_X3PRO -> InputSource.TEMPLE
        else                                    -> InputSource.NONE
    }

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
                vitalsLogger = VitalsLogger(capacity = 500),
                pluginRegistry = com.halo.ring.plugin.PluginRegistry(context.applicationContext),
                profileStack = com.halo.ring.core.plugin.ProfileStack(),
                recognisedGestureFlow = MutableSharedFlow(extraBufferCapacity = 8),
                lastRecognisedFlow = MutableStateFlow(null),
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

/**
 * Which input source the wearer is actively using. Drives interactive-tour / Test-Arena
 * coaching content (ring-vocabulary vs temple-vocabulary).
 */
enum class InputSource { RING, TEMPLE, NONE }

/**
 * What the gesture pipeline just recognised, with enough context to render the Test Arena's
 * "LAST GESTURE" hero card. Published by [com.halo.ring.service.HaloRingService] on every
 * `InteractionRouter.onGestureRecognized` callback.
 */
data class RecognisedGesture(
    val gesture: com.halo.ring.core.gesture.Gesture,
    val action: com.halo.ring.core.action.GlassAction,
    /** Synthesizer-only latency in ms: time from the last raw BLE event contributing to this
     *  gesture to the gesture's emission. Does NOT include executor / dispatch cost (Test Arena
     *  doesn't dispatch; the displayed action is just informational). Coerced to 0..9999. */
    val latencyMs: Int,
)

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
    /**
     * Most-recent [com.halo.ring.core.power.PowerPolicy.IntervalMode] the BLE client was asked to
     * request. The actual negotiated interval is what the radio settled on — Android's
     * `BluetoothGatt` doesn't expose that directly; we estimate it via [estimatedConnIntervalMs].
     */
    val intervalMode: com.halo.ring.core.power.PowerPolicy.IntervalMode =
        com.halo.ring.core.power.PowerPolicy.IntervalMode.BALANCED,
    /**
     * Median inter-notify delta over the last ~16 ring events, with sub-5 ms bursts filtered out
     * (those are multiple notifies queued in a single BLE connection event, not the interval).
     * Null when the ring hasn't sent enough events to estimate (idle / freshly connected).
     * Used by the Status screen for Doc/06 §4's "actual negotiated BLE connection interval" field.
     */
    val estimatedConnIntervalMs: Int? = null,
    /** Which [com.halo.ring.core.inject.ExecutorBackend] last successfully dispatched an action. */
    val activeBackendId: String = "(none)",
)
