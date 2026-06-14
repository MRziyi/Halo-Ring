package com.halo.ring.service

import com.halo.ring.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.halo.ring.MainActivity
import com.halo.ring.HaloRingApplication
import com.halo.ring.accessibility.HaloRingAccessibilityService
import com.halo.ring.di.AppGraph
import com.halo.ring.inject.AppProcessAgentBackend
import com.halo.ring.ui.hud.HudEvent
import com.halo.ring.ui.hud.HudOverlay
import com.halo.ring.ui.hud.HudServiceHost
import com.halo.ring.ui.screens.FeedbackPrefs
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.ble.ConnectionState
import com.halo.ring.core.ble.HealthKind
import com.halo.ring.core.ble.RingEvent
import com.halo.ring.core.ble.Subscription
import com.halo.ring.core.gesture.GestureSynthesizer
import com.halo.ring.core.gesture.InteractionRouter
import com.halo.ring.core.modal.AIDictateModal
import com.halo.ring.core.modal.BrightnessModal
import com.halo.ring.core.modal.RecentsModal
import com.halo.ring.core.modal.VolumeModal
import com.halo.ring.core.power.PowerPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Resident, no-wakelock foreground service (Doc/06-performance-and-power.md §3, Doc/07 §"Module 5").
 *
 * Assembles the pipeline:
 *
 *   AndroidScheduler ──▶ R08BleClient
 *                          │
 *                          ▼
 *                    GestureSynthesizer ──▶ InteractionRouter ──▶ ActionRouter ──▶ ExecutorBackend
 *                                              │
 *                                              ├─▶ inAppShortCircuit (Doc/08 §9.1 foreground bypass)
 *                                              └─▶ HudOverlay events
 *
 * Threading: every code path that touches pipeline state runs on the scheduler thread. We achieve
 * that by binding [serviceScope] to [AndroidScheduler.coroutineDispatcher] — so even when callbacks
 * fire on the main/binder thread, the actual mutation runs on the single pipeline thread. The only
 * exception is [AppProcessAgentBackend.perform], which hops to [Dispatchers.IO] internally for the
 * blocking socket call.
 *
 * Power: power-state decisions live in pure [PowerPolicy]. We re-evaluate on every wear-state
 * change, screen-state change, and BLE event; the result is applied via the BLE client. There is
 * NO persistent wakelock — the BT controller's interrupt is sufficient (Doc/06 §3.3).
 */
class HaloRingService : Service() {

    private lateinit var graph: AppGraph
    private lateinit var serviceScope: CoroutineScope
    private var hud: HudOverlay? = null
    private var hudHost: HudServiceHost? = null
    private val subs = mutableListOf<Subscription>()
    private val cleanup = mutableListOf<() -> Unit>()

    @Volatile private var feedbackPrefs: FeedbackPrefs = FeedbackPrefs()

    /**
     * Convenience accessor for the ring telemetry — backed by [AppGraph.ringInfoFlow] so settings
     * screens (Ring, Status, About) and the HUD-Peek render the same numbers. Writers update the
     * flow atomically via `.value = it.copy(...)`.
     */
    private val ringInfo get() = graph.ringInfoFlow.value

    /** Power-policy inputs. Mutated only on the scheduler thread, so plain Boolean/Long is safe. */
    private var lastActivityMs: Long = Long.MIN_VALUE
    private var lastWornMs: Long = Long.MIN_VALUE
    private var worn: Boolean = false
    private var screenOnState: Boolean = true
    /** Touch-IC idle-sleep timeout (min), mirrored from AdvancedPrefs; passed to setTouchEnabled. */
    private var touchSleepMin: Int = com.halo.ring.core.ble.R08Protocol.DEFAULT_TOUCH_SLEEP_MIN
    /** Charging-milestone HUD state. [wasCharging] tracks edges (dock-in / unplug); the highest
     *  percentage threshold already announced this charging session lives in [lastChargeMilestone]
     *  so the ~60 s `0x73` sub-0C heartbeats while % rises don't re-fire. Reset on unplug. */
    private var wasCharging: Boolean = false
    private var lastChargeMilestone: Int = 0
    /** P-F: when an alert HUD wakes a sleeping screen, [wokeForHud] is set and [hudSleepTimer]
     *  is armed to auto-sleep after [HUD_AUTO_SLEEP_MS]. A user gesture in the window cancels it. */
    private var wokeForHud: Boolean = false
    private var hudSleepTimer: com.halo.ring.core.gesture.Cancellable? = null
    /** Highest 500-step boundary already announced via HUD today; resets when the ring's step
     *  counter rolls back (new day). Steps auto-stream via `0x73` sub-12 — no goal-setting. */
    private var lastStepMilestone: Int = 0
    /** Latest BLE connection state, mirrored from the [com.halo.ring.core.ble.R08BleClient]
     *  connectionState stream. Read by [reconcilePower] to decide whether a worn ring needs a
     *  (re)connect kicked off — the wear gate's `stop()` has no symmetric auto-`start()` otherwise. */
    private var connState: ConnectionState = ConnectionState.DISCONNECTED
    private var idleRelaxTimer: com.halo.ring.core.gesture.Cancellable? = null
    private var modalTimeoutTimer: com.halo.ring.core.gesture.Cancellable? = null
    /** Periodic re-display of the Disconnected HUD while the ring stays disconnected. The HUD
     *  itself auto-hides after 4 s; this timer re-fires with exponential backoff (P1-8: 60s →
     *  5min → 15min cap) so the wearer is reminded without a persistent overlay occluding their
     *  line of sight (the in-app StatusBar's red ● dot is the actual persistent indicator).
     *  Cancelled on READY. */
    private var disconnectReminderTimer: com.halo.ring.core.gesture.Cancellable? = null
    /** P1-8: current backoff for [scheduleDisconnectReminder]. Reset to [DISCONNECT_REMINDER_INITIAL_MS]
     *  on every fresh disconnect; doubles up to [DISCONNECT_REMINDER_MAX_MS] each fire. */
    private var disconnectReminderDelayMs: Long = 60_000L
    /** A-5: name of the action the router most recently resolved a gesture to. Used by the
     *  latency logger when a gesture is dispatched. Updated synchronously on the scheduler thread
     *  by [InteractionRouter.onGestureRecognized]. */
    @Volatile private var lastResolvedActionName: String = "None"

    /** P1-5: backends sorted by priority once at init (static set after AppGraph construction).
     *  Re-iterated on every gesture event for the active-backend probe; allocation-free that way. */
    private lateinit var backendsByPriority: List<com.halo.ring.core.inject.ExecutorBackend>
    /** Phase B3-5: pure :core processor for AccelSample → spatial events (posture / free-fall /
     *  wrist-shake). Stateful; runs on the scheduler thread alongside the rest of the pipeline. */
    private val accelProcessor = com.halo.ring.core.sensor.AccelProcessor { spatial ->
        when (spatial) {
            is com.halo.ring.core.sensor.AccelProcessor.SpatialEvent.FreeFall -> {
                Log.w(TAG, "ring in free-fall")
                // v0.4 C6: dedicated RingDropped HUD with throttling.
                val now = graph.scheduler.nowMs()
                if (now - lastDropHudMs >= 5_000L) {
                    lastDropHudMs = now
                    showAlert(HudEvent.RingDropped(graph.ringInfoFlow.value.advertisedName ?: "Halo"))
                }
            }
            is com.halo.ring.core.sensor.AccelProcessor.SpatialEvent.Impact ->
                Log.i(TAG, "ring impact ${"%.1f".format(spatial.peakG)} g")
            is com.halo.ring.core.sensor.AccelProcessor.SpatialEvent.PostureChanged ->
                Log.d(TAG, "ring posture: ${spatial.posture}")
            is com.halo.ring.core.sensor.AccelProcessor.SpatialEvent.WristShake ->
                // WRIST_SHAKE gesture is dropped for now (firmware shake needs appType=7, which is
                // mutually exclusive with the touch gestures — see R08Protocol.TOUCH_MODE). Keep the
                // detector running for free-fall/posture; just don't route shake to a Gesture yet.
                Log.d(TAG, "accel wrist-shake (unrouted)")
        }
    }
    /** P1-5: when ringInfo derived fields (estimatedConnIntervalMs / intervalMode / activeBackendId)
     *  were last recomputed. The estimator's "running median over the last 16 samples" doesn't
     *  shift fast enough to need a per-event update; throttling to 1 Hz on the scheduler thread
     *  cuts ~33×/s sort + estimate work down to 1×/s during HIGH-band activity bursts. */
    private var lastRingInfoRefreshMs: Long = 0L

    /** v0.4 C2 — Rokid temple system-broadcast receiver. Null on rayneo + generic-Android flavors
     *  (the Rokid system broadcasts don't exist there). Lifetime-managed by [onCreate]/[onDestroy]. */
    private var templeReceiver: RokidTempleBroadcastReceiver? = null

    /** v0.4 C5 — throttle SportTick HUD pips to ~8 s. The ring pushes ticks once per second during
     *  an active workout; rendering every tick would dominate the HUD. */
    private var lastSportHudMs: Long = 0L

    /** v0.4 C6 — throttle RingDropped HUD to ~5 s between FreeFall fires (the accel processor
     *  may classify rapid hand-shakes as multiple free-fall events otherwise). */
    private var lastDropHudMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HaloRingService.onCreate")
        // Pre-render + load the gesture blips into SoundPool now (idempotent) so the first gesture
        // plays with no allocation latency. Off the UI/scheduler path — just file writes + load.
        com.halo.ring.ui.GestureSounds.init(applicationContext)
        graph = (application as HaloRingApplication).graph
        // CRITICAL: bind serviceScope to the scheduler thread so suspending pipeline work doesn't
        // race with the synthesiser. See Doc/06 §2.2.
        serviceScope = CoroutineScope(SupervisorJob() + graph.scheduler.coroutineDispatcher)
        // P1-5: priorities are fixed after AppGraph construction. Sort once.
        backendsByPriority = graph.backends.sortedByDescending { it.priority }

        // ── 1. assemble the gesture pipeline ───────────────────────────────────────────────────
        val synthesizer = GestureSynthesizer(
            config = graph.modeManager.active().gestureConfig,
            scheduler = graph.scheduler,
            sink = { gesture ->
                // A-5: capture emit/dispatch timestamps when latency measurement is on.
                //
                // Hot path: when the user has the Advanced "Latency measurement" toggle OFF
                // (the default in both debug and release builds), the only overhead is a single
                // @Volatile read of `enabled` — about 1 ns per gesture. We pay nothing else: no
                // allocations, no clock reads, no lock acquisition.
                //
                // When the toggle is ON: we read the clock twice, allocate one Sample (~80 B),
                // take one synchronized lock to push into the 200-entry ring buffer. ~50 µs total
                // per gesture. Even at one gesture/sec sustained, that's 0.005 % CPU and a few
                // KB of heap — effectively zero battery impact for diagnostic value.
                val measure = graph.latencyLogger.enabled
                val tBle     = if (measure) lastActivityMs           else 0L
                val tEmitted = if (measure) graph.scheduler.nowMs()  else 0L
                // P3-14: launch on Unconfined so onGesture's suspend body runs INLINE on the
                // scheduler thread (no Handler.post hop). The agent backend's `withContext(IO)`
                // is the only real suspension point — that legitimately switches threads. Without
                // Unconfined, the bound HandlerDispatcher re-posts every launch to the same
                // Looper, which is one wasted tick per gesture (~0.5–2 ms p50, more under load).
                serviceScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                    interactionRouter.onGesture(gesture)
                    if (graph.latencyLogger.enabled) {
                        graph.latencyLogger.record(
                            com.halo.ring.core.perf.LatencyLogger.Sample(
                                gestureName  = gesture.name,
                                // The router fires onGestureRecognized synchronously; the .also { }
                                // block on the router below caches the resolved action here.
                                actionName   = lastResolvedActionName,
                                tBleMs       = tBle,
                                tEmittedMs   = tEmitted,
                                tDispatchedMs = graph.scheduler.nowMs(),
                            )
                        )
                    }
                }
            },
        )
        interactionRouter = InteractionRouter(
            modeManager = graph.modeManager,
            actionRouter = graph.router,
            onPeekHud = {
                val s = ringInfo
                // P0-4: include HR / activity steps when the user has the matching VitalsPrefs
                // toggle on AND the ring has reported them recently. Steps decode is gated on
                // hardware (Doc/07 §3 — pedometer BLE notifs not yet decoded), so the
                // VitalsSnapshot.activitySteps field is currently always null; the wiring is in
                // place so flipping the toggle does the right thing the moment the decode lands.
                val vp = graph.vitalsPrefsFlow.value
                val vs = graph.vitalsSnapshotFlow.value
                hud?.show(HudEvent.Peek(
                    ringId = s.advertisedName ?: "Halo",
                    batteryPct = s.batteryPct,
                    mode = graph.modeManager.active().name,
                    connected = s.connected,
                    heartRateBpm = if (vp.showHrOnHud) vs.heartRateBpm else null,
                    activitySteps = if (vp.activityOverlay) vs.activitySteps else null,
                ))
            },
            onEnterModal = { entryAction ->
                val modal = when (entryAction) {
                    GlassAction.EnterVolumeModal     -> VolumeModal()
                    GlassAction.EnterBrightnessModal -> BrightnessModal()
                    GlassAction.EnterRecentsModal    -> RecentsModal()
                    GlassAction.EnterAIDictateModal  -> AIDictateModal()
                    else -> null
                } ?: return@InteractionRouter
                Log.i(TAG, "entering modal: ${entryAction::class.simpleName}")
                interactionRouter.activeModal = modal
                // Schedule the modal's timeout. If a new modal supersedes, the previous timer is
                // cancelled below (Cancellable.cancel via the lambda capture).
                modalTimeoutTimer?.cancel()
                val onEnter = modal.onEnter()
                if (onEnter !is GlassAction.None) {
                    serviceScope.launch { graph.router.dispatch(onEnter) }
                }
                modalTimeoutTimer = graph.scheduler.postDelayed(modal.timeoutMs) {
                    if (interactionRouter.activeModal === modal) {
                        interactionRouter.activeModal = null
                        Log.i(TAG, "modal timed out")
                    }
                }
            },
        ).also { r ->
            // v0.4 (Doc/20): base-gesture KeyEvent dispatch goes through the composite dispatcher —
            // foreground → our Activity window (ActivitySystemKeyDispatcher), backgrounded → the
            // agent's system-wide InputManager.injectInputEvent. This is what lets the ring drive
            // OTHER apps on the glasses (not just Halo Ring's own UI), once the ADB agent is up.
            r.systemKeyDispatcher = com.halo.ring.ui.CompositeSystemKeyDispatcher(
                agentBackend = graph.backends.filterIsInstance<AppProcessAgentBackend>().firstOrNull(),
                scope = serviceScope,
            )
            // A5: HUD wiring for recognised gestures + system pseudo-actions.
            // A-5 (latency): also stash the action name for [LatencyLogger] to pull when the
            // gesture finishes dispatching.
            r.onGestureRecognized = { gesture, action ->
                lastResolvedActionName = action::class.simpleName ?: "None"
                // P-F: a user interaction cancels a pending HUD auto-sleep (don't yank the screen
                // they're now using). No-op unless an alert HUD recently woke a sleeping display.
                cancelHudAutoSleep()
                // v0.4: distinct per-gesture audio feedback (eyes-off confirmation). Fired for
                // every recognised gesture, even base/system ones, so the wearer hears *what* the
                // ring understood. Gated by the "UI click sound" feedback pref.
                //
                // P-latency (2026-05-29): this callback runs SYNCHRONOUSLY inside the router, BEFORE
                // it dispatches the action (InteractionRouter.onGesture: onGestureRecognized(...) then
                // dispatch(...)). The hint-HUD's WindowManager.addView is ~30 ms — measured on-device
                // as the bulk of a gesture's "dispatch" time — so doing it inline delayed the actual
                // input injection by that much. Defer the cosmetic sound + HUD to a separate launch
                // so the action fires first; a frame-late hint is imperceptible.
                serviceScope.launch {
                    com.halo.ring.ui.GestureSounds.play(gesture)
                    if (feedbackPrefs.gestureHintHud && action !is GlassAction.None) {
                        hud?.show(HudEvent.GestureRecognised(gesture, action))
                    }
                }
                // Doc/18 §5 — fire targeted broadcast for external plugin actions. Defensive: skip
                // if the owning plugin is no longer installed (binding survived an uninstall via
                // GlassActionCodec snapshot). PluginRegistry.plugin() reads the cached list — no
                // PackageManager IPC on the hot path unless the cache is cold.
                if (action is GlassAction.PluginAction) {
                    if (graph.pluginRegistry.plugin(action.pluginPackage) != null) {
                        com.halo.ring.plugin.PluginTrigger.fire(applicationContext, action, gesture)
                    } else {
                        Log.w(TAG, "PluginAction ${action.pluginPackage}/${action.actionId} skipped — owning plugin not installed")
                    }
                }
                // Audit-2026-05-13q: publish every recognised gesture into a SharedFlow so the
                // interactive `GuidedTour` and the Test Arena (Settings) can listen for the
                // user's input and react ("✓ you did SWIPE_UP"). tryEmit is non-blocking; if
                // the 8-slot buffer is somehow saturated, we drop the gesture for coaching but
                // it's still dispatched through the normal action path — no functional impact.
                graph.recognisedGestureFlow.tryEmit(gesture)
                // Audit-pass-x-test-arena: also publish to lastRecognisedFlow with action +
                // synth-latency so the Test Arena can render the mockup's "LAST GESTURE → Action
                // · NN ms" hero card. lastActivityMs is updated on every GestureEvent so the
                // delta below is exactly the synth-window cost (combo timer, multi-tap window,
                // etc.). Negative values are impossible by construction; coerce defensively.
                val latency = (graph.scheduler.nowMs() - lastActivityMs).toInt().coerceIn(0, 9999)
                graph.lastRecognisedFlow.value = com.halo.ring.di.RecognisedGesture(
                    gesture = gesture, action = action, latencyMs = latency,
                )
            }
            // No HUD on screen-off wake — the display turning on is feedback enough, and the HUD
            // wouldn't be visible anyway until composition wakes back up.
            r.onScreenOffGesture = { _, _ -> /* no-op */ }
            // v0.4: InAppFocusController deleted. Base gestures (TAP / DOUBLE_TAP / SWIPE_UP /
            // SWIPE_DOWN) flow through `r.systemKeyDispatcher` above as raw KeyEvents — Compose's
            // standard FocusManager handles DPAD navigation when MainActivity is foreground;
            // out-of-app it lands on whichever Activity is foreground via the temple-broadcast
            // path (C2 / SystemBroadcastReceiver) or the agent. Custom gestures (TRIPLE_TAP etc.)
            // continue to flow through profile → ActionRouter → executor backend below.
            // Doc/18 §7 — exclusive plugin overlay. When a plugin's HUD is up it owns the ring:
            // the router forwards every gesture to the plugin and lets nothing leak to the
            // underlying app. expireIfStale releases a crashed/hung plugin on the next gesture.
            r.overlayCaptures = {
                if (graph.overlayController.expireIfStale(graph.scheduler.nowMs())) onOverlayChanged(false)
                graph.overlayController.isActive()
            }
            r.forwardOverlayGesture = { gesture -> forwardOverlayGesture(gesture) }
            // Context gesture (2026-05-29): while our Config Activity is foreground, LONG_PRESS
            // cycles the home tab (RING → VITALS → MORE); out-of-app it sleeps the screen (handled
            // in InteractionRouter §0). Both the UI tap and this gesture drive `homeTabIndexFlow`.
            r.appForeground = { com.halo.ring.MainActivity.isInForeground.get() }
            r.onAppTabCycle = {
                val n = com.halo.ring.ui.screens.HomeTab.values().size
                graph.homeTabIndexFlow.value = (graph.homeTabIndexFlow.value + 1) % n
            }
        }

        // ── 2. install the HUD overlay (after router so it can dispatch into it) ────────────────
        hud = try {
            hudHost = HudServiceHost()
            HudOverlay(
                context = this,
                lifecycleOwner = hudHost!!,
                viewModelStoreOwner = hudHost!!,
                savedStateRegistryOwner = hudHost!!,
            ).also {
                // Tell the overlay whether this device is side-by-side binocular (RayNeo X3 Pro)
                // so it can re-anchor `TopCenter` into the right-eye region instead of landing
                // between the eyes. The flag is sourced from the flavor-specific DisplayAdapter
                // (rokid: false, rayneo: true). Audit-2026-05-13s.
                it.setBinocular(graph.displayAdapter.isBinocular)
            }
        } catch (t: Throwable) {
            // Without SYSTEM_ALERT_WINDOW the overlay can't be created — degrade gracefully.
            Log.w(TAG, "HudOverlay unavailable: ${t.message}")
            null
        }

        // Forward HUD notices posted from elsewhere in the process (the accessibility service's
        // Bluetooth-internet flow) so the foreground service stays the single HUD owner.
        serviceScope.launch { graph.hudNoticeFlow.collect { showAlert(it) } }

        // ── 3. subscribe BLE events ────────────────────────────────────────────────────────────
        subs += graph.bleClient.events().subscribe { event, nowMs ->
            // Already on the scheduler thread (AndroidR08BleClient.onNotify reposts before fanning out).
            when (event) {
                is RingEvent.GestureEvent -> {
                    // Track activity for the power policy (Doc/06 §3.5).
                    lastActivityMs = nowMs
                    reconcilePower()
                    // P1-5: throttle the Status-screen-only derived fields to 1 Hz. At HIGH band
                    // (~30 ms intervals) we get ~33 GestureEvents/s during a swipe burst — the
                    // 16-sample median doesn't move per event, the priority-sorted backend list
                    // is static, and isReady() does file-stat syscalls. Worth limiting.
                    if (nowMs - lastRingInfoRefreshMs >= RING_INFO_REFRESH_MS) {
                        lastRingInfoRefreshMs = nowMs
                        val client = graph.bleClient as? com.halo.ring.ble.AndroidR08BleClient
                        client?.pollRssi()   // async; result read on the next cycle (~1 s lag)
                        val ms = client?.estimatedConnIntervalMs()
                        val mode = client?.currentIntervalMode() ?: PowerPolicy.IntervalMode.BALANCED
                        val backendId = backendsByPriority.firstOrNull { it.isReady() }?.id ?: "(none)"
                        val rssi = client?.currentRssiDbm()
                        val prev = graph.ringInfoFlow.value
                        if (prev.estimatedConnIntervalMs != ms ||
                            prev.intervalMode != mode ||
                            prev.activeBackendId != backendId ||
                            prev.rssiDbm != rssi
                        ) {
                            graph.ringInfoFlow.value = prev.copy(
                                estimatedConnIntervalMs = ms,
                                intervalMode = mode,
                                activeBackendId = backendId,
                                rssiDbm = rssi,
                            )
                        }
                    }
                    // Emit the raw atomic gesture immediately for diagnostic UIs (Test Arena
                    // shows it instantly, before the synthesizer's multi-tap window has fired).
                    // No backpressure: tryEmit is fire-and-forget against an extraBufferCapacity=16
                    // SharedFlow.
                    graph.rawAtomicGestureFlow.tryEmit(event.raw)
                    if (!interactionRouter.screenOn) {
                        // Screen-off fast path: a raw-matchable wake gesture (LONG_PRESS / SWIPE)
                        // fires in ~50-80 ms without the synthesizer. If the wake gesture is a
                        // synthesized one (DOUBLE_TAP, the current default), onRawWhileScreenOff
                        // returns false (not consumed) and we feed the synthesizer so it can emit
                        // DOUBLE_TAP → wake (handled in InteractionRouter.onGesture while off).
                        serviceScope.launch {
                            val consumed = interactionRouter.onRawWhileScreenOff(event.raw)
                            if (!consumed) synthesizer.onRaw(event.raw)
                        }
                    } else {
                        synthesizer.onRaw(event.raw)
                    }
                }
                is RingEvent.Battery -> {
                    val charging = event.charging
                    graph.ringInfoFlow.value = graph.ringInfoFlow.value.copy(
                        batteryPct = event.percent,
                        charging = charging,
                    )
                    // Low-battery warning only while NOT charging (no point nagging on the dock).
                    if (event.percent <= LOW_BATTERY_THRESHOLD && charging != true) {
                        showAlert(HudEvent.LowBattery(graph.ringInfoFlow.value.advertisedName ?: "Halo", event.percent))
                    }
                    // Charging milestones: dock-in, then 90 / 95 / full — each once per session.
                    if (charging == true) {
                        if (!wasCharging) {
                            lastChargeMilestone = 0
                            showAlert(HudEvent.Notice(getString(R.string.hud_charging_started)))
                        }
                        for (threshold in intArrayOf(90, 95, 100)) {
                            if (event.percent >= threshold && lastChargeMilestone < threshold) {
                                lastChargeMilestone = threshold
                                showAlert(HudEvent.Notice(
                                    if (threshold >= 100) getString(R.string.hud_charging_full)
                                    else getString(R.string.hud_charging_pct, threshold)
                                ))
                            }
                        }
                        wasCharging = true
                    } else if (charging == false) {
                        wasCharging = false
                        lastChargeMilestone = 0
                    }
                }
                is RingEvent.TouchStatus -> {
                    // SPEC v3 §10.6 / §5.2: `0x2A` is the touch-IC POWER-state echo (fires after
                    // TOUCH_MODE writes, on charging-dock insertion, and — as observed on-device
                    // 2026-05-29 — when the firmware autonomously dozes the touch IC after
                    // `sleepMin`). It is NOT a wear signal: the touch IC can be disabled while the
                    // ring is firmly on a finger (we send TOUCH_DISABLE for power, or the ~10 min
                    // doze fires). Conflating `enabled=false` with "ring off the user" caused the
                    // PowerPolicy to tear down the link mid-session — bug 2026-05-29.
                    // Per SPEC v3 there is NO continuous ring-side worn/off-finger event; the only
                    // ring-wear evidence the firmware emits is `0x69 err=0x02` during a PPG snapshot
                    // (handled in [AndroidR08BleClient] + the Vitals "wear-detect fail" HUD). So
                    // glasses-side wear (`wearProvider`) is the only source feeding [worn].
                    Log.d(TAG, "ring touch IC power state echo: enabled=${event.enabled} (informational, does not flip wear)")
                }
                is RingEvent.Health -> {
                    val prev = graph.vitalsSnapshotFlow.value
                    val updated = when (event.kind) {
                        HealthKind.HEART_RATE  -> prev.copy(heartRateBpm = event.value, capturedAtMs = nowMs, measuring = true)
                        HealthKind.SPO2        -> prev.copy(spo2Pct = event.value, capturedAtMs = nowMs, measuring = true)
                        HealthKind.STRESS      -> prev.copy(stressIndex = event.value, capturedAtMs = nowMs, measuring = false)
                        HealthKind.HEALTHCHECK -> prev.copy(
                            heartRateBpm = event.value,
                            systolic = event.systolic,
                            diastolic = event.diastolic,
                            capturedAtMs = nowMs,
                            measuring = false,
                        )
                        HealthKind.BLOOD_PRESSURE -> prev.copy(systolic = event.systolic, diastolic = event.diastolic, capturedAtMs = nowMs)
                        HealthKind.TEMPERATURE -> prev.copy(temperature = event.value.toFloat() / 10f, capturedAtMs = nowMs)
                        HealthKind.HRV, HealthKind.FATIGUE, HealthKind.BLOOD_SUGAR -> prev  // progress-only on RT08
                    }
                    graph.vitalsSnapshotFlow.value = updated
                    val loggerKind = when (event.kind) {
                        HealthKind.HEART_RATE, HealthKind.HEALTHCHECK -> com.halo.ring.core.perf.VitalsLogger.Kind.HEART_RATE
                        HealthKind.SPO2 -> com.halo.ring.core.perf.VitalsLogger.Kind.SPO2
                        HealthKind.STRESS -> com.halo.ring.core.perf.VitalsLogger.Kind.STRESS
                        else -> null
                    }
                    if (loggerKind != null) {
                        graph.vitalsLogger.record(
                            com.halo.ring.core.perf.VitalsLogger.Sample(loggerKind, event.value, nowMs)
                        )
                    }
                }
                is RingEvent.WearDetectFail -> {
                    Log.w(TAG, "vitals wear-detect fail (${event.kind})")
                    // Surface to UI via the vitals snapshot's "measuring" flag flipping off + null.
                    val prev = graph.vitalsSnapshotFlow.value
                    graph.vitalsSnapshotFlow.value = prev.copy(measuring = false, wearDetectFail = true)
                }
                is RingEvent.VitalsSnapshotComplete -> {
                    // Clear the UI's "MEASURING…" sticky flag — HR/SpO₂ set it true on every progress
                    // frame but nothing else cleared it on success, so the readout was stuck (Zack
                    // 2026-05-29: "心率测试会一直卡住").
                    val prev = graph.vitalsSnapshotFlow.value
                    graph.vitalsSnapshotFlow.value = prev.copy(measuring = false)
                }
                is RingEvent.Activity -> {
                    // SPEC §5.3 wire-verified: cal=mcal/1000, dist=integer meters.
                    val prev = graph.vitalsSnapshotFlow.value
                    graph.vitalsSnapshotFlow.value = prev.copy(
                        activitySteps = event.steps,
                        activityKcal = event.calories,
                        activityMeters = event.distanceMeters,
                    )
                    // Every-500-steps HUD milestone (500 / 1000 / 1500…). Fires once per boundary;
                    // a counter rollback (new day) re-arms it. On a big jump we announce only the
                    // highest boundary crossed rather than spamming each.
                    val steps = event.steps
                    if (steps < lastStepMilestone) lastStepMilestone = 0
                    if (steps >= lastStepMilestone + 500) {
                        lastStepMilestone = (steps / 500) * 500
                        showAlert(HudEvent.Notice(getString(R.string.hud_step_milestone, lastStepMilestone)))
                    }
                }
                is RingEvent.TargetReached -> {
                    showAlert(HudEvent.TargetReached)
                }
                is RingEvent.RingGameKey ->
                    // Firmware wrist-shake (0x73 sub=0x29). Only ever fires in appType=7 (Game) mode,
                    // which we don't use (it would disable the touch gestures). Won't fire in practice.
                    Log.d(TAG, "RING_GAME_KEY (unrouted — appType=7 not enabled)")
                is RingEvent.SportTick -> {
                    // Live HR + duration during a workout. Pushed into the sport-session flow.
                    val prev = graph.vitalsSnapshotFlow.value
                    val newHr = event.hrEstimate.takeIf { it > 0 } ?: prev.heartRateBpm
                    graph.vitalsSnapshotFlow.value = prev.copy(
                        heartRateBpm = newHr,
                        sportDurationSec = event.durationSeconds,
                        // First tick implicitly confirms the session is up — if the user started
                        // via the UI we already set sportActive=true; this catches sessions started
                        // out-of-band (e.g. via plugin or future remote launch).
                        sportActive = true,
                    )
                    // v0.4 C5: throttled HUD pip. 8 s between renders is comfortable.
                    val now = graph.scheduler.nowMs()
                    if (now - lastSportHudMs >= 8_000L) {
                        lastSportHudMs = now
                        hud?.show(HudEvent.SportTick(event.durationSeconds, newHr))
                    }
                }
                is RingEvent.AccelSample -> {
                    // Phase B3-5: feed the pure :core AccelProcessor for posture / free-fall /
                    // wrist-shake detection. SpatialEvents come back through the emit lambda set
                    // up below.
                    accelProcessor.onSample(event)
                    val prev = graph.vitalsSnapshotFlow.value
                    graph.vitalsSnapshotFlow.value = prev.copy(
                        lastAccelMagnitudeG = event.magnitude,
                    )
                }
                is RingEvent.AccelOpaque -> Unit
                is RingEvent.Capability -> {
                    Log.i(TAG, "ring capabilities: ${event.flags.size} flags — ${event.flags.sorted().joinToString(",")}")
                    graph.ringCapabilitiesFlow.value = event.flags
                }
                is RingEvent.UnsupportedOp -> Log.w(TAG, "ring refused opcode 0x${event.opcode.toString(16)}")
                is RingEvent.Unknown -> Unit
            }
        }

        // ── 4. subscribe BLE connection state ──────────────────────────────────────────────────
        subs += graph.bleClient.connectionState().subscribe { state ->
            Log.i(TAG, "BLE connection state: $state")
            connState = state
            when (state) {
                ConnectionState.READY -> {
                    val client = graph.bleClient as? com.halo.ring.ble.AndroidR08BleClient
                    graph.ringInfoFlow.value = graph.ringInfoFlow.value.copy(
                        connected = true,
                        // SPEC v3 §1.1: HW + FW revisions are read during bootstrap before this
                        // READY transition fires. If they're null here, the device-info service
                        // was missing on this firmware (rare).
                        firmwareVersion = client?.fwRevisionString() ?: graph.ringInfoFlow.value.firmwareVersion,
                        // Burn-in 2026-05-27 (user feedback): status bar showed "R08_…" placeholder
                        // because we never propagated the scan's advertisedName/MAC. Capture from
                        // the BLE client (which snapshots them in onScanResult) on every READY.
                        advertisedName = client?.connectedDeviceName() ?: graph.ringInfoFlow.value.advertisedName,
                        macAddress = client?.connectedDeviceAddress() ?: graph.ringInfoFlow.value.macAddress,
                    )
                    disconnectReminderTimer?.cancel(); disconnectReminderTimer = null
                    showAlert(HudEvent.Reconnected)
                    synthesizer.armWakeSwallow()
                    // Audit-2026-05-13j (P3): "just-connected" counts as activity for the policy —
                    // otherwise the very first gesture rides whatever interval Android negotiated
                    // by default (~30-100 ms) instead of HIGH (15-30 ms). Seed first, then
                    // reconcile so [setIntervalMode(HIGH)] goes out before the user's first tap.
                    lastActivityMs = graph.scheduler.nowMs()
                    reconcilePower()
                    // Re-sync the active profile to whatever app is in front RIGHT NOW. By the time
                    // the ring is connected (and the wearer can actually gesture), the a11y service
                    // has long since seen the foreground window — so this closes the cold-start race
                    // where the app was opened before everything was wired and its profile never
                    // activated until a leave + re-enter. Idempotent (no-op if already correct).
                    // (Zack 2026-05-31)
                    graph.scheduler.post {
                        HaloRingAccessibilityService.lastForeground?.let { (pkg, activity) ->
                            if (!graph.overlayController.isActive()) graph.modeManager.onForegroundPackage(pkg, activity)
                        }
                    }
                    // First-time pairing teaching aid: enable HUD hints for 5 minutes.
                    if (feedbackPrefs.autoHintAfterPairing) {
                        serviceScope.launch { graph.feedbackPrefs.armAutoHintAfterPairing() }
                    }
                    // SPEC v3 §4.4 / §4.9: apply user's VitalsPrefs to the ring — autonomous-HR
                    // monitoring + daily-step target. Done after a brief delay so the bootstrap
                    // command-channel writes complete first; otherwise the writes can collide.
                    graph.scheduler.postDelayed(2_500L) {
                        val vp = graph.vitalsPrefsFlow.value
                        // Always disable the ring's own autonomous HR-only `0x16` monitor: vitals
                        // auto-detection (app-driven HR+SpO₂, VitalsPrefs.vitalsAutoDetectEnabled) now
                        // owns the cadence, so leaving `0x16` on would double-measure (extra LED drain).
                        graph.bleClient.setHrAutoMonitor(false, intervalMinutes = 30)
                        // SPEC §4.9: firmware silently drops steps < 100; the client coerces.
                        graph.bleClient.setDailyTarget(
                            steps = vp.dailyStepTarget,
                            kcal = (vp.dailyStepTarget * 36 / 1000).coerceAtLeast(50),  // 36 mcal/step on RT08
                            distanceMeters = (vp.dailyStepTarget * 8 / 10),               // ~0.8 m/step stride
                        )
                    }
                }
                ConnectionState.DISCONNECTED -> {
                    graph.ringInfoFlow.value = graph.ringInfoFlow.value.copy(connected = false)
                    showAlert(HudEvent.Disconnected())
                    // P1-8: fresh disconnect → reset the backoff so the user gets the first
                    // reminder on the short cadence; if the ring is genuinely gone, subsequent
                    // fires stretch out.
                    disconnectReminderDelayMs = DISCONNECT_REMINDER_INITIAL_MS
                    scheduleDisconnectReminder()
                    // Clear AccelProcessor state so a fresh connection doesn't fire stale spatial
                    // events from buffered samples.
                    accelProcessor.reset()
                }
                else -> Unit
            }
        }

        // ── 5. wear state → power reconcile + wear HUD ─────────────────────────────────────────
        // The wearProvider is our best proxy for ring-on-hand (the firmware doesn't push a
        // finger-on/off event — 0x2A only fires on dock-in). Drives the wear HUD + auto-snapshot
        // via onRingWorn(showHud=true), plus the power reconcile inside it.
        val wearUnsub = graph.wearProvider.observe { w ->
            graph.scheduler.post { onRingWorn(w, showHud = true) }
        }
        cleanup += wearUnsub

        // ── 6. profile changes → HUD + synthesizer config swap ────────────────────────────────
        // Ring-LED blink on switch is OFF by default now (user 2026-05-28: "模式切换别闪灯了，默认把亮灯
        // 关掉" — needless battery, and switches are frequent). The HUD prompt is the feedback channel.
        // The "Ring LED feedback" toggle (default off) can re-enable it.
        val modeUnsub = graph.modeManager.observe { profile ->
            graph.scheduler.post {
                Log.i(TAG, "active profile: ${profile.name}")
                hud?.show(HudEvent.ProfileSwitched(profile.name))
                if (feedbackPrefs.ringLedFeedback) graph.bleClient.blinkLed()
                synthesizer.config = profile.gestureConfig
                graph.activeProfileIdFlow.value = profile.id
            }
        }
        cleanup += modeUnsub

        // ── 6b. profilesFlow → ModeManager (persisted load + later user edits) ────────────────
        // Sync EVERY emission into ModeManager — never drop the first. The persisted profiles load
        // ASYNC (HaloRingApplication, Dispatchers.IO); if that load wins the race against this
        // collector subscribing, the FIRST emission IS the wearer's persisted config — the old
        // `if (first) drop` then silently discarded it, so ModeManager (which drives gesture routing)
        // kept the DEFAULT bindings while the config file + UI showed the wearer's edits. That's the
        // "config file says my plugin, but the gesture fires the default" bug (Zack 2026-05-31).
        // upsert is now change-aware, so re-applying the unchanged seed here is a silent no-op.
        val profilesEditJob = serviceScope.launch {
            graph.profilesFlow.collect { list ->
                list.forEach { graph.modeManager.upsert(it) }
            }
        }
        cleanup += { profilesEditJob.cancel() }

        // ── 6c. user edits to System Gestures → push to InteractionRouter ─────────────────────
        val sysGesturesJob = serviceScope.launch {
            graph.systemGesturesFlow.collect { sg ->
                interactionRouter.systemGestures = sg
            }
        }
        cleanup += { sysGesturesJob.cancel() }

        // ── 7. ACTION_SCREEN_ON/OFF → router.screenOn (drives the screen-off fast path) ───────
        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_ON  -> graph.scheduler.post {
                        screenOnState = true
                        interactionRouter.screenOn = true
                        reconcilePower()
                    }
                    Intent.ACTION_SCREEN_OFF -> graph.scheduler.post {
                        screenOnState = false
                        interactionRouter.screenOn = false
                        reconcilePower()
                    }
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        cleanup += { try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) {} }

        // ── DEBUG + RayNeo-only: dispatch a GlassAction by name through the router, no ring needed.
        // Lets the host validate the RayNeo accessibility dispatchGesture path without driving real
        // BLE gestures:
        //   adb shell am broadcast -a com.halo.ring.TEST_ACTION -p com.mudra.mudraband --es action NavNext
        // Watch: adb logcat -s HaloService:* A11yBackend:* ActionRouter:*
        // Gated to the rayneo flavor so Rokid debug builds are byte-for-byte unaffected.
        if (com.halo.ring.BuildConfig.DEBUG && com.halo.ring.BuildConfig.DEVICE_FLAVOR == "rayneo") {
            val testActionReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val name = i?.getStringExtra("action") ?: return
                    val action = when (name) {
                        "NavNext"  -> GlassAction.NavNext
                        "NavPrev"  -> GlassAction.NavPrev
                        "NavLeft"  -> GlassAction.NavLeft
                        "NavRight" -> GlassAction.NavRight
                        "Confirm"  -> GlassAction.Confirm
                        "Back"     -> GlassAction.Back
                        "Home"     -> GlassAction.Home
                        "Recents"  -> GlassAction.Recents
                        else       -> { Log.w(TAG, "TEST_ACTION: unknown action '$name'"); return }
                    }
                    Log.i(TAG, "TEST_ACTION → dispatch $name")
                    serviceScope.launch {
                        val backend = graph.router.dispatch(action)
                        Log.i(TAG, "TEST_ACTION $name → backend=${backend?.id ?: "NONE"}")
                    }
                }
            }
            val testFilter = IntentFilter("com.halo.ring.TEST_ACTION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(testActionReceiver, testFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(testActionReceiver, testFilter)
            }
            cleanup += { try { unregisterReceiver(testActionReceiver) } catch (_: IllegalArgumentException) {} }
        }

        // v0.4 C2: Rokid temple touchpad → InteractionRouter (system ordered broadcasts).
        // Rokid-only — RayNeo doesn't publish the `com.android.action.ACTION_SPRITE_*` constants
        // (its temple input flows through Mercury SDK's TouchDispatcher, which we may re-wire in a
        // future C-step). On generic Android, no glasses, also skipped.
        if (graph.deviceProfile == com.halo.ring.core.DeviceProfile.ROKID_GLASSES) {
            val rcv = RokidTempleBroadcastReceiver(
                router = interactionRouter,
                scope = serviceScope,
                onOpenSettings = {
                    // Two-finger long-press = "open Halo Ring config". Launch MainActivity into the
                    // Settings root.
                    try {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "open MainActivity from temple SETTINGS_KEY failed: ${e.message}")
                    }
                },
            )
            try {
                registerReceiver(rcv, rcv.filter())
                templeReceiver = rcv
                cleanup += {
                    try { unregisterReceiver(rcv) } catch (_: IllegalArgumentException) {}
                    templeReceiver = null
                }
                Log.i(TAG, "Rokid temple broadcast receiver registered")
            } catch (e: Exception) {
                Log.w(TAG, "Rokid temple broadcast receiver registration failed: ${e.message}")
            }
        }
        // Seed initial state. Audit-2026-05-13j (P3): also pre-fetch wear state via the synchronous
        // [WearStateProvider.isWorn] (the observer fires on *changes* — may never fire if the user
        // is already wearing the glasses when our service starts), then trigger one reconcile so
        // the BLE client's [setTouchEnabled] / [setIntervalMode] guards have known good initial
        // values before the first ring event arrives.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        graph.scheduler.post {
            screenOnState = pm.isInteractive
            interactionRouter.screenOn = pm.isInteractive
            val initiallyWorn = try { graph.wearProvider.isWorn() } catch (_: Throwable) { false }
            worn = initiallyWorn
            if (initiallyWorn) lastWornMs = graph.scheduler.nowMs()
            reconcilePower()
        }

        // ── 8. FeedbackPrefs DataStore → cached snapshot + push to HUD ─────────────────────────
        // Audit-2026-05-13l: previously the user's `hudPosition` and `hudDurationMs` preferences
        // were persisted but never reached HudOverlay — the overlay kept its defaults forever.
        // Now we push every prefs change through.
        val prefsJob = serviceScope.launch {
            graph.feedbackPrefs.flow.collectLatest { p ->
                feedbackPrefs = p
                hud?.setPosition(when (p.hudPosition) {
                    com.halo.ring.ui.screens.HudPosition.TOP_RIGHT    -> HudOverlay.HudPosition.TopRight
                    com.halo.ring.ui.screens.HudPosition.TOP_LEFT     -> HudOverlay.HudPosition.TopLeft
                    com.halo.ring.ui.screens.HudPosition.TOP_CENTER   -> HudOverlay.HudPosition.TopCenter
                    com.halo.ring.ui.screens.HudPosition.BOTTOM_RIGHT -> HudOverlay.HudPosition.BottomRight
                    com.halo.ring.ui.screens.HudPosition.BOTTOM_LEFT  -> HudOverlay.HudPosition.BottomLeft
                })
                hud?.setVerticalOffsetSteps(p.hudOffsetSteps)
                hud?.setDefaultDuration(p.hudDurationMs.toLong())
                // v0.4: per-gesture audio feedback gated by the same "click sound" pref.
                com.halo.ring.ui.GestureSounds.enabled = p.clickSoundOnModeSwitch
            }
        }
        cleanup += { prefsJob.cancel() }

        // ── 8b. AdvancedPrefs → mirror Advanced toggles into runtime hooks ─────────────────────
        // Only the latency-measurement toggle has a runtime effect today (gates the
        // LatencyLogger ring buffer). debugHud / spatialMode are UI-only stubs.
        val advancedJob = serviceScope.launch {
            graph.advancedPrefsFlow.collectLatest { p ->
                graph.latencyLogger.enabled = p.latencyMeasurementEnabled
                // Touch-IC sleep timeout (SPEC v3 §4.10): cache it and re-apply via reconcilePower so
                // a change takes effect immediately (re-sends TOUCH_MODE if the ring is worn+enabled).
                if (touchSleepMin != p.touchSleepMin) {
                    touchSleepMin = p.touchSleepMin
                    reconcilePower()
                }
            }
        }
        cleanup += { advancedJob.cancel() }

        // ── 8c. VitalsPrefs → toggle CSV logger + drive auto-snapshot scheduler (P0-2/3) ───────
        // The auto-snapshot job re-launches whenever the user changes the interval. wear-detection
        // gate consults the freshly-cached `worn` field maintained by the wearProvider observer
        // above. Job is cancelled in cleanup{} via collectLatest's structured cancellation.
        val vitalsJob = serviceScope.launch {
            var snapshotJob: kotlinx.coroutines.Job? = null
            graph.vitalsPrefsFlow.collectLatest { p ->
                graph.vitalsLogger.enabled = p.csvExportEnabled
                snapshotJob?.cancel()
                snapshotJob = if (p.vitalsAutoDetectEnabled && p.autoSnapshotIntervalMin > 0) {
                    serviceScope.launch { runVitalsAutoSnapshot(p) }
                } else null
                // v0.4 C6 + energy fix 2: the spatial toggle is the master switch; the actual
                // stream is additionally gated on worn+screenOn by reconcileAccelStream (the
                // accel telemetry is ~64 B/s of CPU-waking BLE notifies — no point streaming it
                // when the screen is off or the ring is off-finger and nobody's gesturing).
                spatialPrefEnabled = p.spatialFeaturesEnabled
                reconcileAccelStream()
            }
        }
        cleanup += { vitalsJob.cancel() }

        // ── 9. AccessibilityService foreground-pkg → ModeManager auto-switch (B11) ────────────
        // The a11y service may already be running (from a previous app session) or come online
        // later. The listener is process-wide, so we set it unconditionally — when the service
        // binds (or re-binds) it'll start delivering events to us.
        HaloRingAccessibilityService.foregroundPackageListener = { pkg, activity ->
            graph.scheduler.post {
                // Freeze profile inference while a plugin overlay owns the ring — the underlying
                // app's profile must not churn under the HUD; it re-asserts when the overlay releases.
                if (!graph.overlayController.isActive()) graph.modeManager.onForegroundPackage(pkg, activity)
            }
        }
        // Cold-start / service-restart sync: the a11y service may have already delivered the
        // foreground window event BEFORE this listener was attached (the wearer's "first entry into
        // Music didn't switch, but exit + re-enter did" race). Replay the last-seen foreground now so
        // the correct profile activates immediately, without needing a fresh window transition.
        // (Zack 2026-05-31)
        graph.scheduler.post {
            HaloRingAccessibilityService.lastForeground?.let { (pkg, activity) ->
                if (!graph.overlayController.isActive()) graph.modeManager.onForegroundPackage(pkg, activity)
            }
        }
        cleanup += { HaloRingAccessibilityService.foregroundPackageListener = null }

        // ── 9b. Doc/18 plugin discovery + push/pop receiver ────────────────────────────────────
        // Three pieces:
        //   (a) PluginRegistry's package-event receiver — invalidates the discovery cache on
        //       plugin install/uninstall/replace so the next Action Picker open sees fresh state.
        //   (b) A PACKAGE_REMOVED watch that releases the overlay if its owner is uninstalled
        //       (in-process crash is caught by the keepalive timeout in OverlayController.expireIfStale).
        //   (c) PluginBroadcastReceiver itself for the OVERLAY_ACTIVATE / OVERLAY_DEACTIVATE intents.
        graph.pluginRegistry.registerPackageEventReceiver()
        cleanup += { graph.pluginRegistry.unregisterPackageEventReceiver() }
        // Eager discovery on service start so the Settings root row badge ("External plugins  N
        // active") + ActionPicker show plugin actions immediately, not after the user navigates
        // into one of those screens. Runs on Dispatchers.IO — PackageManager.queryBroadcastReceivers
        // + a few CP queries take 20-80 ms total on a stock device.
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { graph.pluginRegistry.refresh() } catch (t: Throwable) {
                Log.w(TAG, "initial plugin refresh failed: ${t.message}")
            }
        }

        val pluginUninstallReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_PACKAGE_REMOVED) return
                val pkg = intent.data?.schemeSpecificPart ?: return
                graph.scheduler.post {
                    if (graph.overlayController.deactivateOwner(pkg)) {
                        Log.i(TAG, "released overlay — $pkg uninstalled")
                        onOverlayChanged(false)
                    }
                }
            }
        }
        registerReceiver(pluginUninstallReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
        })
        cleanup += { try { unregisterReceiver(pluginUninstallReceiver) } catch (_: IllegalArgumentException) {} }

        val pluginBroadcast = com.halo.ring.plugin.PluginBroadcastReceiver(
            overlay = graph.overlayController,
            pm = packageManager,
            onOverlayChanged = { activated -> graph.scheduler.post { onOverlayChanged(activated) } },
        )
        val pluginFilter = IntentFilter().apply {
            addAction(com.halo.ring.plugin.PluginBroadcastReceiver.ACTION_OVERLAY_ACTIVATE)
            addAction(com.halo.ring.plugin.PluginBroadcastReceiver.ACTION_OVERLAY_DEACTIVATE)
            addAction(com.halo.ring.plugin.PluginBroadcastReceiver.ACTION_PROFILE_PUSH)   // legacy
            addAction(com.halo.ring.plugin.PluginBroadcastReceiver.ACTION_PROFILE_POP)    // legacy
        }
        try {
            // RECEIVER_EXPORTED on Android 14+ — receiver MUST opt in to receiving from external
            // packages. PUSH_PROFILE permission gate is enforced by registerReceiver overload.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(
                    pluginBroadcast, pluginFilter,
                    com.halo.ring.plugin.PluginBroadcastReceiver.PERMISSION_PUSH_PROFILE, null,
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                registerReceiver(
                    pluginBroadcast, pluginFilter,
                    com.halo.ring.plugin.PluginBroadcastReceiver.PERMISSION_PUSH_PROFILE, null,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "plugin push/pop receiver registration failed: ${e.message}")
        }
        cleanup += { try { unregisterReceiver(pluginBroadcast) } catch (_: IllegalArgumentException) {} }

        // ── 10. foreground notification + start BLE (gated on paired-MAC presence) ────────────
        // Burn-in fix 2026-05-27: ONLY scan on service start if the user has previously paired a
        // ring (persisted MAC in [RingPairingPrefsStore]). Otherwise wait — the user opens the
        // pairing picker from the wizard / RingScreen, which calls startDiscovery() + setPairedMac().
        startInForeground()
        serviceScope.launch {
            val pairedMac = graph.ringPairingPrefs.pairedMacFlow.first()
            if (pairedMac != null) {
                Log.i(TAG, "auto-start BLE for paired ring $pairedMac")
                graph.bleClient.setPairedMac(pairedMac)
                graph.bleClient.start()
            } else {
                Log.i(TAG, "no paired ring — service idle until user opens pairing picker")
            }
        }

        // ── 11. headless boot-recovery: re-spawn the agent if it died on reboot ────────────────
        // The `app_process` agent doesn't survive reboot — nothing keeps it alive after the
        // OS shuts down. BootReceiver brings us back, and the persisted keypair + adbd's
        // remembered adb_keys entry let us reconnect over wireless ADB without UI. Silent on
        // failure: the wizard CTA is the user-facing fallback.
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                graph.adbBootstrap.bootRecoverAgent()
            } catch (t: Throwable) {
                Log.w(TAG, "bootRecoverAgent threw: ${t.message}")
            }
        }

        // ── 12. Wi-Fi connectivity listener → auto-recover agent when Wi-Fi comes up ──────────
        // Covers the common reboot scenario: Wi-Fi is off at boot → bootRecoverAgent fails →
        // user (or auto-enable) brings Wi-Fi up → we catch the onAvailable callback and retry.
        // This means turning Wi-Fi on is sufficient to start the agent — no wizard required.
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        if (connectivityManager != null) {
            val recoveryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
            val wifiCallback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (!recoveryInFlight.compareAndSet(false, true)) return
                    Log.i(TAG, "Wi-Fi available — scheduling agent recovery")
                    serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            kotlinx.coroutines.delay(2_000)  // let adbd bind its port
                            graph.adbBootstrap.bootRecoverAgent()
                        } catch (t: Throwable) {
                            Log.w(TAG, "bootRecover on Wi-Fi up: ${t.message}")
                        } finally {
                            recoveryInFlight.set(false)
                        }
                    }
                }
            }
            try {
                connectivityManager.registerNetworkCallback(
                    android.net.NetworkRequest.Builder()
                        .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        .build(),
                    wifiCallback,
                )
                cleanup += {
                    try { connectivityManager.unregisterNetworkCallback(wifiCallback) }
                    catch (_: Exception) {}
                }
                Log.i(TAG, "Wi-Fi recovery callback registered")
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi callback registration failed: ${e.message}")
            }
        }

        // ── 13. Bluetooth-internet auto-reconnect on boot ─────────────────────────────────────
        // Android resets the phone's "Internet access" (PAN) toggle on every reboot. If the user
        // opted in (Settings → Bluetooth internet → auto on boot), replay the toggle via the
        // accessibility UI flow once things have settled (BT reconnect + a11y service bound).
        serviceScope.launch {
            if (!graph.advancedPrefs.flow.first().btInternetAutoBoot) return@launch
            // Wait until the phone is fully connected as a MEDIA device (A2DP/HFP), not just
            // ACL-linked. Only then does it move into the top "Media devices" category and grow the
            // settings gear the UI flow clicks — right after a reboot it briefly sits under "Other
            // devices" with no gear. Waiting here (no UI) means Settings opens already-ready, so the
            // flow finishes fast instead of staring at the list. Poll up to ~90 s.
            var waited = 0
            while (waited < 90_000 &&
                !(isMediaDeviceConnected() && com.halo.ring.accessibility.HaloRingAccessibilityService.instance != null)) {
                kotlinx.coroutines.delay(3_000); waited += 3_000
            }
            val a11y = com.halo.ring.accessibility.HaloRingAccessibilityService.instance
            when {
                a11y == null -> Log.w(TAG, "btInternet auto-boot: accessibility service off — skipping")
                !isMediaDeviceConnected() -> Log.w(TAG, "btInternet auto-boot: phone not media-connected within 90s — skipping")
                else -> {
                    // Small settle so the UI promotes the phone into "Media devices" before we look.
                    kotlinx.coroutines.delay(2_000)
                    Log.i(TAG, "btInternet auto-boot: phone media-connected, replaying Internet-access toggle")
                    a11y.startBtInternetFlow(returnToApp = false)
                }
            }
        }
    }

    /** True if the phone is fully connected as a media device — which is when it moves into the top
     *  "Media devices" category and grows a settings gear. The glasses are the RECEIVER, so the
     *  relevant profiles are the sink/client roles A2DP_SINK(11) and HEADSET_CLIENT(16), NOT the
     *  source roles A2DP(2)/HEADSET(1). */
    private fun isMediaDeviceConnected(): Boolean = try {
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return false
        val connected = android.bluetooth.BluetoothProfile.STATE_CONNECTED
        adapter.getProfileConnectionState(11) == connected ||  // A2DP_SINK
        adapter.getProfileConnectionState(16) == connected ||  // HEADSET_CLIENT
        adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP) == connected ||
        adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == connected
    } catch (e: Exception) { Log.w(TAG, "isMediaDeviceConnected: ${e.message}"); false }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "HaloRingService.onDestroy")
        try {
            cleanup.forEach { try { it() } catch (_: Throwable) {} }
            subs.forEach { it.unsubscribe() }
            idleRelaxTimer?.cancel()
            modalTimeoutTimer?.cancel()
            disconnectReminderTimer?.cancel()
            graph.bleClient.stop()
            // Best-effort close on the highest-priority backend (the agent socket).
            graph.backends.filterIsInstance<AppProcessAgentBackend>().forEach { it.close() }
            hud?.destroy()
            hudHost?.destroy()
            com.halo.ring.ui.GestureSounds.release()
        } finally {
            serviceScope.cancel()
            super.onDestroy()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /**
     * Re-evaluate [PowerPolicy] against the current inputs and push the resulting decision to the
     * BLE client. Idempotent, cheap, scheduler-thread-only. Also re-arms the idle-relax timer:
     * after [PowerPolicy.ACTIVE_WINDOW_MS] of no further activity, we call [reconcilePower] again
     * so [PowerPolicy.Decision.intervalMode] drops from HIGH to BALANCED (or to SLOW if the screen
     * also turned off).
     */
    /**
     * SPEC v3 §4.10: `0x73 sub=0x2A` byte 1 = 1 fires when the ring is removed from finger OR
     * inserted into the charging dock. Both are "not worn" semantically; use as a fast-acting
     * wear-state signal that doesn't depend on the eye-side ScreenWearProxy.
     */
    /** P-F: show an "alert-class" HUD (charging / step / wear / drop / low-battery / (dis)connect).
     *  If the screen is off, briefly wake it and auto-sleep after [HUD_AUTO_SLEEP_MS] so the notice
     *  is seen without leaving the display on — unless the user interacts ([cancelHudAutoSleep]).
     *  Frequent HUDs (gesture-hint / profile-switch / sport tick) call hud?.show directly so they
     *  don't keep waking the screen. Runs on the scheduler thread (all callers repost there). */
    private fun showAlert(event: HudEvent) {
        if (!screenOnState) {
            wokeForHud = true
            serviceScope.launch { graph.router.dispatch(GlassAction.ScreenWake) }
        }
        hud?.show(event)
        if (wokeForHud) {
            hudSleepTimer?.cancel()
            hudSleepTimer = graph.scheduler.postDelayed(HUD_AUTO_SLEEP_MS) {
                if (wokeForHud) {
                    wokeForHud = false
                    serviceScope.launch { graph.router.dispatch(GlassAction.ScreenSleep) }
                }
            }
        }
    }

    /** A user interaction cancels a pending HUD auto-sleep — don't yank a screen they're using. */
    private fun cancelHudAutoSleep() {
        if (wokeForHud) {
            wokeForHud = false
            hudSleepTimer?.cancel(); hudSleepTimer = null
        }
    }

    /**
     * Update wear state. Driven **solely by the glasses-side wear provider** (Rokid `is_take_on`
     * sysprop / RayNeo Mercury + screen heuristic) — the only continuous, reliable signal we have.
     *
     * Per SPEC v3, the ring firmware does NOT push a continuous worn/off-finger event:
     *  - `0x2A` is a touch-IC power-state echo (handled above as informational only — it fires for
     *    TOUCH_MODE writes, dock-insertion, and the firmware's ~10 min touch-IC doze; conflating it
     *    with "off-finger" tore down the link mid-session on 2026-05-29).
     *  - `0x69 err=0x02` is the only authentic ring wear-detect, but it's a one-shot during a PPG
     *    measurement, not a persistent state — surfaced in the Vitals flow, not here.
     *
     * The glasses-wear signal is an imperfect proxy for "ring on hand" (someone could wear the
     * glasses while the ring is off, or vice versa) but it's strongly correlated with active use
     * and is what we use to gate the power-policy disconnect. The `showHud` flag is kept so the
     * ring-on/off HUD notice only fires on a real user transition.
     */
    private fun onRingWorn(worn: Boolean, showHud: Boolean = true) {
        if (this.worn == worn) return
        this.worn = worn
        if (worn) lastWornMs = graph.scheduler.nowMs()
        graph.ringInfoFlow.value = graph.ringInfoFlow.value.copy(worn = worn)
        Log.i(TAG, "wear state changed: worn=$worn (showHud=$showHud)")
        if (showHud) {
            showAlert(HudEvent.Notice(getString(if (worn) R.string.hud_ring_on else R.string.hud_ring_off)))
        }
        // "戴上即测": the moment the ring is put on (and we're connected) take one snapshot so the
        // Vitals readout is fresh without a manual MEASURE. Only on the false→true transition (this
        // method early-returns on no-change), so reconnect flaps don't trigger repeated PPG.
        if (worn && graph.ringInfoFlow.value.connected) {
            graph.bleClient.requestVitalsSnapshot()
        }
        reconcilePower()
    }

    /** Doc/18 §7 — overlay activated / released. Runs on the scheduler thread. Shows the activation
     *  HUD (overlay app name) on activate; on release, re-shows the underlying profile so the wearer
     *  sees the ring is theirs again. (The underlying [ModeManager] profile was never changed — the
     *  overlay is a router-layer takeover — so there's nothing to restore beyond the HUD cue.) */
    private fun onOverlayChanged(activated: Boolean) {
        val ov = graph.overlayController.active()
        if (activated && ov != null) {
            Log.i(TAG, "overlay ACTIVE — ${ov.ownerPackage}/${ov.profileId} owns the ring")
            hud?.show(HudEvent.ProfileSwitched(ov.displayName))
        } else if (ov == null) {
            Log.i(TAG, "overlay released → back to ${graph.modeManager.active().name}")
            hud?.show(HudEvent.ProfileSwitched(graph.modeManager.active().name))
        }
    }

    /** Forward a raw ring gesture to the active overlay plugin (Doc/18 §7). Explicit broadcast to the
     *  owner package only; the plugin assigns meaning + updates its own HUD. No-op if released. */
    private fun forwardOverlayGesture(gesture: com.halo.ring.core.gesture.Gesture) {
        val owner = graph.overlayController.ownerPackage() ?: return
        val i = Intent(com.halo.ring.plugin.PluginBroadcastReceiver.ACTION_OVERLAY_GESTURE).apply {
            setPackage(owner)
            putExtra("gesture", gesture.name)
            putExtra("from_package", packageName)
        }
        try { sendBroadcast(i) } catch (e: Exception) { Log.w(TAG, "overlay forward failed: ${e.message}") }
        Log.i(TAG, "overlay gesture → $owner: ${gesture.name}")
    }

    private fun reconcilePower() {
        val now = graph.scheduler.nowMs()
        val decision = PowerPolicy.decide(
            PowerPolicy.Inputs(
                worn = worn,
                screenOn = screenOnState,
                lastActivityMs = lastActivityMs,
                lastWornMs = lastWornMs,
                nowMs = now,
            )
        )
        graph.bleClient.setTouchEnabled(decision.touchEnabled, touchSleepMin)
        graph.bleClient.setIntervalMode(decision.intervalMode)
        if (decision.disconnect) {
            Log.i(TAG, "PowerPolicy: not worn for ${PowerPolicy.NOT_WORN_DISCONNECT_MS} ms → disconnect")
            graph.bleClient.stop()
        } else if (worn && connState == ConnectionState.DISCONNECTED) {
            // Symmetric counterpart to the wear-gate stop() above: once the ring is worn again we
            // must actively re-establish the link. Nothing else calls start() after a wear-gated
            // (or any) disconnect, so without this the ring stays down until the user manually
            // reconnects — the "断了不会自动连上" bug. start() is a cheap no-op when already
            // scanning/connecting, and the BLE client owns the retry/backoff from here.
            Log.i(TAG, "worn + ring DISCONNECTED → kicking off (re)connect")
            graph.bleClient.start()
        }
        // Energy fix 2: the accel stream (for spatial/wrist-shake) follows worn+screen state too.
        reconcileAccelStream()

        // Schedule a re-eval at the active-window boundary so HIGH→BALANCED happens automatically
        // without needing another input event. SLOW and BALANCED are stable resting states — no
        // timer needed.
        idleRelaxTimer?.cancel()
        if (decision.intervalMode == PowerPolicy.IntervalMode.HIGH) {
            val relaxIn = PowerPolicy.ACTIVE_WINDOW_MS - (now - lastActivityMs).coerceAtLeast(0)
            idleRelaxTimer = graph.scheduler.postDelayed(relaxIn.coerceAtLeast(100)) { reconcilePower() }
        } else {
            idleRelaxTimer = null
        }
    }

    /** v0.4 — the user's spatial-features master toggle (mirrored from VitalsPrefs). */
    @Volatile private var spatialPrefEnabled = false
    /** Tracks whether the accel telemetry stream is currently subscribed (avoids redundant writes). */
    private var accelStreamOn = false

    /**
     * Energy fix 2 (audit 2026-05-27): the accelerometer telemetry stream powers spatial / wrist-
     * shake gestures but is ~64 B/s of CPU-waking BLE notifies. Stream it ONLY when the user has
     * spatial features on AND the ring is worn AND the screen is on — i.e. when the wearer could
     * actually be air-gesturing. Pauses automatically on screen-off / off-finger. Scheduler-thread
     * only (called from [reconcilePower] + the VitalsPrefs collector).
     */
    private fun reconcileAccelStream() {
        val desired = spatialPrefEnabled && worn && screenOnState
        if (desired == accelStreamOn) return
        accelStreamOn = desired
        if (desired) graph.bleClient.startAccelStream(continuous = true)
        else         graph.bleClient.stopAccelStream()
        Log.i(TAG, "accel stream: ${if (desired) "ON" else "OFF"} (spatial=$spatialPrefEnabled worn=$worn screen=$screenOnState)")
    }

    /**
     * P0-3: periodic on-demand vitals snapshot. Loops while the prefs row is non-zero, sleeping
     * between fires. Each fire calls into the BLE client's [requestVitalsSnapshot] which is
     * idempotent (in-flight guard) and self-recovers on disconnect (audit-pass-f §D4) — so we
     * don't need to track our own success/failure state here.
     *
     * The wear-detection gate consults [worn] (cached on the scheduler thread by the wear-state
     * observer); when enabled and the wearer has the glasses off, we skip the BLE write. The
     * sleep still runs so we re-check on the next interval.
     */
    private suspend fun runVitalsAutoSnapshot(prefs: com.halo.ring.ui.screens.VitalsPrefs) {
        val intervalMs = prefs.autoSnapshotIntervalMin * 60_000L
        if (intervalMs <= 0L) return
        while (true) {
            kotlinx.coroutines.delay(intervalMs)
            // Skip-this-cycle gates. Both safe to re-evaluate on the scheduler dispatcher: `worn`
            // is volatile-by-virtue-of-single-thread, ringInfo is a flow value read.
            val gateOk = (!prefs.wearDetectionEnabled || worn) && graph.ringInfoFlow.value.connected
            if (!gateOk) continue
            try {
                graph.bleClient.requestVitalsSnapshot()
            } catch (t: Throwable) {
                Log.w(TAG, "auto vitals snapshot threw: ${t.message}")
            }
        }
    }

    /**
     * Periodically re-display the Disconnected HUD until the ring reconnects. P1-8: exponential
     * backoff — first nudge at [DISCONNECT_REMINDER_INITIAL_MS] (60 s), then doubles each fire
     * up to [DISCONNECT_REMINDER_MAX_MS] (15 min). If the ring is genuinely gone (out of range,
     * dead battery), this stops nagging the wearer instead of pinging every 60 s forever.
     * Cancelled + reset on [ConnectionState.READY] and on service destroy.
     */
    private fun scheduleDisconnectReminder() {
        disconnectReminderTimer?.cancel()
        disconnectReminderTimer = graph.scheduler.postDelayed(disconnectReminderDelayMs) {
            // Re-check connection state at fire time — a fast reconnect may have already happened.
            if (!graph.ringInfoFlow.value.connected) {
                showAlert(HudEvent.Disconnected())
                disconnectReminderDelayMs = (disconnectReminderDelayMs * 2)
                    .coerceAtMost(DISCONNECT_REMINDER_MAX_MS)
                scheduleDisconnectReminder()
            } else {
                disconnectReminderTimer = null
            }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,   // no buzz, no sound, no peek
            ).apply { setShowBadge(false) }
        )
        val n: Notification = Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)   // brand-aligned monochrome ring
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n)
    }

    /** Exposed for testing — wired to the InteractionRouter created in onCreate. */
    private lateinit var interactionRouter: InteractionRouter

    companion object {
        private const val TAG = "HaloService"
        private const val NOTIF_CHANNEL_ID = "halo.ring"
        private const val NOTIF_ID = 1
        private const val LOW_BATTERY_THRESHOLD = 20
        /** How long an alert HUD keeps the screen lit when it woke a sleeping display, before
         *  auto-sleeping it again (so off-screen notices are seen without draining battery). */
        private const val HUD_AUTO_SLEEP_MS = 7_000L
        /** P1-8: first reminder fires after this gap. Doubles each subsequent fire up to
         *  [DISCONNECT_REMINDER_MAX_MS]. AR-HUD philosophy (Doc/06 §3.3): the StatusBar's red ●
         *  dot is the persistent indicator; the HUD nudges briefly. */
        private const val DISCONNECT_REMINDER_INITIAL_MS = 60_000L
        /** P1-8: cap on the exponential backoff so a permanently-out-of-range ring stops
         *  waking the scheduler thread every minute. 15 min matches Android's typical idle
         *  maintenance-window cadence so it won't add wakes the OS isn't already issuing. */
        private const val DISCONNECT_REMINDER_MAX_MS = 15 * 60_000L
        /** P1-5: minimum gap between recomputes of the Status-screen derived fields. The
         *  16-sample median estimator + backend-priority sort are wasted work at per-gesture
         *  cadence; 1 Hz is the slowest the Status screen actually shows differences. */
        private const val RING_INFO_REFRESH_MS = 1_000L
    }
}
