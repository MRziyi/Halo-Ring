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
import com.halo.ring.ui.InAppFocusController
import com.halo.ring.ui.hud.HudEvent
import com.halo.ring.ui.hud.HudOverlay
import com.halo.ring.ui.hud.HudServiceHost
import com.halo.ring.ui.screens.FeedbackPrefs
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.ble.ConnectionState
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
    private var idleRelaxTimer: com.halo.ring.core.gesture.Cancellable? = null
    private var modalTimeoutTimer: com.halo.ring.core.gesture.Cancellable? = null
    /** A-5: name of the action the router most recently resolved a gesture to. Used by the
     *  latency logger when a gesture is dispatched. Updated synchronously on the scheduler thread
     *  by [InteractionRouter.onGestureRecognized]. */
    @Volatile private var lastResolvedActionName: String = "None"

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HaloRingService.onCreate")
        graph = (application as HaloRingApplication).graph
        // CRITICAL: bind serviceScope to the scheduler thread so suspending pipeline work doesn't
        // race with the synthesiser. See Doc/06 §2.2.
        serviceScope = CoroutineScope(SupervisorJob() + graph.scheduler.coroutineDispatcher)

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
                serviceScope.launch {
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
                hud?.show(HudEvent.Peek(
                    ringId = s.advertisedName ?: "Halo",
                    batteryPct = s.batteryPct,
                    mode = graph.modeManager.active().name,
                    connected = s.connected,
                ))
            },
            onForceReconnect = {
                Log.i(TAG, "force reconnect requested")
                graph.bleClient.stop()
                graph.bleClient.start()
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
            // A5: HUD wiring for recognised gestures + system pseudo-actions.
            // A-5 (latency): also stash the action name for [LatencyLogger] to pull when the
            // gesture finishes dispatching.
            r.onGestureRecognized = { gesture, action ->
                lastResolvedActionName = action::class.simpleName ?: "None"
                if (feedbackPrefs.gestureHintHud && action !is GlassAction.None) {
                    hud?.show(HudEvent.GestureRecognised(gesture, action))
                }
            }
            // No HUD on screen-off wake — the display turning on is feedback enough, and the HUD
            // wouldn't be visible anyway until composition wakes back up.
            r.onScreenOffGesture = { _, _ -> /* no-op */ }
            // A6: foreground bypass. When MainActivity is in foreground AND the in-app focus
            // controller can route the action (Nav*/Back/Home), skip the executor backend.
            r.inAppShortCircuit = { action ->
                MainActivity.isInForeground.get() && InAppFocusController.route(action)
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
            )
        } catch (t: Throwable) {
            // Without SYSTEM_ALERT_WINDOW the overlay can't be created — degrade gracefully.
            Log.w(TAG, "HudOverlay unavailable: ${t.message}")
            null
        }

        // ── 3. subscribe BLE events ────────────────────────────────────────────────────────────
        subs += graph.bleClient.events().subscribe { event, nowMs ->
            // Already on the scheduler thread (AndroidR08BleClient.onNotify reposts before fanning out).
            when (event) {
                is RingEvent.GestureEvent -> {
                    // Track activity for the power policy (Doc/06 §3.5).
                    lastActivityMs = nowMs
                    reconcilePower()
                    if (!interactionRouter.screenOn) {
                        // Screen-off fast path: bypass synthesizer (LONG_PRESS = wake in ~50-80 ms).
                        serviceScope.launch { interactionRouter.onRawWhileScreenOff(event.raw) }
                    } else {
                        synthesizer.onRaw(event.raw)
                    }
                }
                is RingEvent.Battery -> {
                    graph.ringInfoFlow.value = graph.ringInfoFlow.value.copy(batteryPct = event.percent)
                    if (event.percent <= LOW_BATTERY_THRESHOLD) {
                        hud?.show(HudEvent.LowBattery(graph.ringInfoFlow.value.advertisedName ?: "Halo", event.percent))
                    }
                }
                is RingEvent.TouchStatus -> Log.d(TAG, "ring touch IC enabled=${event.enabled}")
                is RingEvent.Health -> {
                    // B6: collect HR / SpO2 / stress into the vitals snapshot flow. The BLE client's
                    // requestVitalsSnapshot() sequence pushes the start commands; the ring streams
                    // values back as 0x69 notify frames, which R08Frame parses into Health events.
                    val prev = graph.vitalsSnapshotFlow.value
                    val updated = when (event.kind) {
                        com.halo.ring.core.ble.HealthKind.HEART_RATE -> prev.copy(heartRateBpm = event.value, capturedAtMs = nowMs, measuring = true)
                        com.halo.ring.core.ble.HealthKind.SPO2       -> prev.copy(spo2Pct = event.value, capturedAtMs = nowMs, measuring = true)
                        com.halo.ring.core.ble.HealthKind.STRESS     -> prev.copy(stressIndex = event.value, capturedAtMs = nowMs, measuring = false)
                    }
                    graph.vitalsSnapshotFlow.value = updated
                }
                is RingEvent.Activity,
                is RingEvent.Steps,
                is RingEvent.AccelRaw,
                is RingEvent.Unknown -> Unit
            }
        }

        // ── 4. subscribe BLE connection state ──────────────────────────────────────────────────
        subs += graph.bleClient.connectionState().subscribe { state ->
            Log.i(TAG, "BLE connection state: $state")
            when (state) {
                ConnectionState.READY -> {
                    graph.ringInfoFlow.value = graph.ringInfoFlow.value.copy(connected = true)
                    hud?.show(HudEvent.Reconnected)
                    synthesizer.armWakeSwallow()
                    // First-time pairing teaching aid: enable HUD hints for 5 minutes.
                    if (feedbackPrefs.autoHintAfterPairing) {
                        serviceScope.launch { graph.feedbackPrefs.armAutoHintAfterPairing() }
                    }
                }
                ConnectionState.DISCONNECTED -> {
                    graph.ringInfoFlow.value = graph.ringInfoFlow.value.copy(connected = false)
                    hud?.show(HudEvent.Disconnected())
                }
                else -> Unit
            }
        }

        // ── 5. wear state → power reconcile ────────────────────────────────────────────────────
        val wearUnsub = graph.wearProvider.observe { w ->
            graph.scheduler.post {
                worn = w
                if (w) lastWornMs = graph.scheduler.nowMs()
                Log.i(TAG, "wear state: worn=$w")
                reconcilePower()
            }
        }
        cleanup += wearUnsub

        // ── 6. profile changes → HUD + ring LED + synthesizer config swap ─────────────────────
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

        // ── 6b. user edits in the settings UI: profilesFlow → ModeManager.upsert ──────────────
        // Drop the initial value (already in ModeManager); only react to user-initiated changes.
        val profilesEditJob = serviceScope.launch {
            var first = true
            graph.profilesFlow.collect { list ->
                if (first) { first = false; return@collect }
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
        // Seed initial state.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        graph.scheduler.post {
            screenOnState = pm.isInteractive
            interactionRouter.screenOn = pm.isInteractive
        }

        // ── 8. FeedbackPrefs DataStore → cached snapshot ──────────────────────────────────────
        val prefsJob = serviceScope.launch {
            graph.feedbackPrefs.flow.collectLatest { p -> feedbackPrefs = p }
        }
        cleanup += { prefsJob.cancel() }

        // ── 8b. AdvancedPrefs → mirror Advanced toggles into runtime hooks ─────────────────────
        // Only the latency-measurement toggle has a runtime effect today (gates the
        // LatencyLogger ring buffer). debugHud / spatialMode are UI-only stubs.
        val advancedJob = serviceScope.launch {
            graph.advancedPrefsFlow.collectLatest { p ->
                graph.latencyLogger.enabled = p.latencyMeasurementEnabled
            }
        }
        cleanup += { advancedJob.cancel() }

        // ── 9. AccessibilityService foreground-pkg → ModeManager auto-switch (B11) ────────────
        // The a11y service may already be running (from a previous app session) or come online
        // later. The listener is process-wide, so we set it unconditionally — when the service
        // binds (or re-binds) it'll start delivering events to us.
        HaloRingAccessibilityService.foregroundPackageListener = { pkg ->
            graph.scheduler.post { graph.modeManager.onForegroundPackage(pkg) }
        }
        cleanup += { HaloRingAccessibilityService.foregroundPackageListener = null }

        // ── 10. foreground notification + start BLE ───────────────────────────────────────────
        startInForeground()
        graph.bleClient.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "HaloRingService.onDestroy")
        try {
            cleanup.forEach { try { it() } catch (_: Throwable) {} }
            subs.forEach { it.unsubscribe() }
            idleRelaxTimer?.cancel()
            modalTimeoutTimer?.cancel()
            graph.bleClient.stop()
            // Best-effort close on the highest-priority backend (the agent socket).
            graph.backends.filterIsInstance<AppProcessAgentBackend>().forEach { it.close() }
            hud?.hide()
            hudHost?.destroy()
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
        graph.bleClient.setTouchEnabled(decision.touchEnabled)
        graph.bleClient.setIntervalMode(decision.intervalMode)
        if (decision.disconnect) {
            Log.i(TAG, "PowerPolicy: not worn for ${PowerPolicy.NOT_WORN_DISCONNECT_MS} ms → disconnect")
            graph.bleClient.stop()
        }

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
    }
}
