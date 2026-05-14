package com.halo.ring.core.gesture

import com.halo.ring.core.action.ActionRouter
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.action.ModalSentinel
import com.halo.ring.core.action.ModeManager

/**
 * The top-level routing pipeline (R08-Remote-Design.md §23.4). Sits between the BLE client and the
 * [ActionRouter] / [ModeManager]; encodes the three-layer override hierarchy:
 *
 *   screen-state gateway  >  system-level gestures  >  modal layer  >  active profile
 *
 * ## Screen-state gateway (the "fast path")
 * When the screen is off, [onRawWhileScreenOff] is called directly with each [RawGesture] — it
 * **bypasses** the [GestureSynthesizer] entirely so the wake gesture (default LONG_PRESS) fires
 * with zero synthesis latency. Everything that isn't the wake gesture is silently dropped: no
 * profile mapping, no HUD, no LED — we don't want a hand brushing the ring while you sleep to
 * accidentally trigger anything. (See §23.2 for why we keep TOUCH_ENABLE on while worn + off.)
 *
 * ## Standard path
 * When the screen is on, the synthesizer's emitted [Gesture]s come in via [onGesture]. We check
 * the system-level bindings first, then the modal layer (if any modal is active), then fall back
 * to the active profile.
 *
 * Threading: same single looper as the synthesizer.
 */
class InteractionRouter(
    /** Reads at every dispatch — hot-swappable via the settings page. */
    var systemGestures: SystemGestures = SystemGestures(),
    private val modeManager: ModeManager,
    private val actionRouter: ActionRouter,
    /** Show the HUD overlay for ~2s. UI-layer concern; usually a callback to a flow/livedata. */
    private val onPeekHud: () -> Unit,
    /** Engage a modal — implementation TBD when the modal layer ships (see §25.1). */
    private val onEnterModal: (GlassAction) -> Unit = {},
    /** Dispatch a side-effect-free system action that the router itself handles (e.g. for telemetry). */
    private val onSystemAction: (GlassAction) -> Unit = {},
    /**
     * Fired after a gesture has been routed. [resolvedAction] is what the gesture ultimately
     * triggered: a GlassAction for profile-mapped or modal handling, the system pseudo-actions
     * (`ScreenWake` / `ScreenSleep` / `ProfileCycle` / `PeekHud` / `ForceReconnect`) for the
     * system layer, or `GlassAction.None` for unmapped / dropped gestures (including the
     * screen-off "wake gesture not matched" case).
     *
     * The [HudOverlay] in `:app` subscribes here for the gesture-hint feature
     * (Doc/08-ui-design.md §10). Telemetry can also tap in. The callback runs on the same
     * pipeline thread as the rest of the router — keep it non-blocking.
     *
     * Note on `ForceReconnect`: kept in the [GlassAction] vocabulary because user-bound profiles
     * may still reference it, and the in-app "Reconnect" button (Settings → Ring) dispatches it
     * via the same path. The system slot was retired in audit-pass 2026-05-14w in favour of
     * [GlassAction.OpenAIAssistant] (`SystemGestures.aiAssistant`). If a profile maps a gesture
     * to ForceReconnect, the router still dispatches through ActionRouter → executor backend,
     * where the rokid/rayneo mappers treat it as a no-injection in-app action and the foreground
     * service's listener calls `BleClient.stop()` + `start()`.
     */
    var onGestureRecognized: ((Gesture, GlassAction) -> Unit)? = null,
    /** Fired from the screen-off fast path. raw == the gesture configured as ScreenWake (or
     *  null if the user did some other raw event while screen was off). */
    var onScreenOffGesture: ((RawGesture, woke: Boolean) -> Unit)? = null,
    /**
     * Optional in-app fast path (Doc/08-ui-design.md §9.1, "foreground bypass"). When set and the
     * MainActivity is foreground, the router calls this BEFORE the [ActionRouter]. If it returns
     * true the action was handled in-app and we skip the executor-backend dispatch entirely —
     * saves ~30 ms per gesture and means navigating our own UI never depends on the agent.
     *
     * Wired in the foreground service: `{ MainActivity.isInForeground.get() && InAppFocusController.route(it) }`.
     */
    var inAppShortCircuit: ((GlassAction) -> Boolean)? = null,
) {
    /** Whether the glasses display is currently on. Set by the foreground service from
     *  ACTION_SCREEN_ON/OFF (or RayNeo ARSDK wear/screen state when available). */
    var screenOn: Boolean = true

    /** Active modal, if any. Set by the modal layer (TBD). null = no modal, dispatch to profile. */
    var activeModal: Modal? = null

    /**
     * Called for every [RawGesture] *before* it reaches the synthesizer. Returns true if the
     * router consumed the event (caller should skip the synthesizer); false if the synthesizer
     * should process it normally.
     */
    suspend fun onRawWhileScreenOff(raw: RawGesture): Boolean {
        if (screenOn) return false
        val wake = systemGestures.wake ?: run {
            onScreenOffGesture?.invoke(raw, false)
            return true  // wake disabled → drop everything, return consumed
        }
        val matched = matchesRaw(raw, wake)
        if (matched) dispatch(GlassAction.ScreenWake)
        onScreenOffGesture?.invoke(raw, matched)
        // screenOn will flip to true via the ACTION_SCREEN_ON broadcast; until then keep dropping
        return true
    }

    /**
     * The standard path. Called by the foreground service for every [Gesture] the synthesizer emits.
     */
    suspend fun onGesture(gesture: Gesture) {
        if (!screenOn) {
            // Belt-and-braces: if a stale gesture sneaks through after the screen went off, drop.
            return
        }
        // 1. system-level overrides. Note: `wake` is NOT intercepted while on — it's only meaningful
        //    on the screen-off fast path. So a profile that binds LONG_PRESS (= default wake) to
        //    Menu / VolumeUp / Home / Back still gets fired here normally.
        when (gesture) {
            systemGestures.sleep          -> {
                onGestureRecognized?.invoke(gesture, GlassAction.ScreenSleep)
                dispatch(GlassAction.ScreenSleep); return
            }
            systemGestures.profileCycle   -> {
                modeManager.cycleNext()
                onSystemAction(GlassAction.ProfileCycle)
                onGestureRecognized?.invoke(gesture, GlassAction.ProfileCycle); return
            }
            systemGestures.peekHud        -> {
                onPeekHud()
                onSystemAction(GlassAction.PeekHud)
                onGestureRecognized?.invoke(gesture, GlassAction.PeekHud); return
            }
            systemGestures.aiAssistant    -> {
                // Routes through the standard dispatch path so the per-flavor `FeatureIntents`
                // impl handles the actual launch (Rokid ChatPageActivity / RayNeo
                // VOICE_SEARCH_HANDS_FREE best-effort). Same path used when a profile binds
                // OpenAIAssistant via the picker. Audit-pass 2026-05-14w.
                onSystemAction(GlassAction.OpenAIAssistant)
                onGestureRecognized?.invoke(gesture, GlassAction.OpenAIAssistant)
                dispatch(GlassAction.OpenAIAssistant); return
            }
            else -> Unit
        }

        // 2. modal layer
        activeModal?.let { modal ->
            val a = modal.handle(gesture)
            if (a is GlassAction.None) {
                onGestureRecognized?.invoke(gesture, GlassAction.None); return
            }
            if (a is ModalSentinel.Exit)   { activeModal = null; onGestureRecognized?.invoke(gesture, a); return }
            if (a is ModalSentinel.Cancel) { activeModal = null; onGestureRecognized?.invoke(gesture, a); return }
            if (a is ModalSentinel.FireAndExit) {
                activeModal = null
                onGestureRecognized?.invoke(gesture, a.payload)
                dispatch(a.payload)
                return
            }
            onGestureRecognized?.invoke(gesture, a)
            dispatch(a)
            return
        }

        // 3. profile
        val action = modeManager.active().actionFor(gesture)
        onGestureRecognized?.invoke(gesture, action)
        if (action is GlassAction.None) return
        if (action.isModalEntry()) {
            onEnterModal(action)
            return
        }
        dispatch(action)
    }

    private suspend fun dispatch(action: GlassAction) {
        // System-level "in-app" actions don't go through the executor backends.
        when (action) {
            GlassAction.PeekHud,
            GlassAction.ProfileCycle,
            GlassAction.ForceReconnect,
            GlassAction.None -> return
            else -> {
                // Foreground bypass: handled directly by Compose focus traversal — no backend hop.
                if (inAppShortCircuit?.invoke(action) == true) return
                actionRouter.dispatch(action)
            }
        }
    }

    /** Match a [RawGesture] against a [Gesture] slot for the screen-off fast path. Only works for
     *  one-to-one mappings (LONG_PRESS, SWIPE_UP, SWIPE_DOWN). Multi-tap wake on the fast path
     *  isn't supported — use one of the raw events. */
    private fun matchesRaw(raw: RawGesture, slot: Gesture): Boolean = when (slot) {
        Gesture.LONG_PRESS  -> raw == RawGesture.LONG_PRESS
        Gesture.SWIPE_UP    -> raw == RawGesture.SWIPE_UP
        Gesture.SWIPE_DOWN  -> raw == RawGesture.SWIPE_DOWN
        // TAP would also match RawGesture.TOUCH, but a single touch is a terrible wake gesture
        // (every accidental brush wakes the screen). Don't support it.
        else -> false
    }
}

/** Marker so [InteractionRouter.onGesture] can recognise the four "Enter*Modal" actions. */
private fun GlassAction.isModalEntry(): Boolean = when (this) {
    GlassAction.EnterVolumeModal,
    GlassAction.EnterBrightnessModal,
    GlassAction.EnterRecentsModal,
    GlassAction.EnterAIDictateModal -> true
    else -> false
}

/**
 * A modal owns a window of the user's attention; subsequent gestures are routed to it instead of
 * the profile until it exits. See §25.1. Full implementation deferred — interfaces here so the
 * router compiles and the wiring is in place.
 */
interface Modal {
    val timeoutMs: Long
    /** Returns the action to dispatch for this gesture. Use [ModalSentinel] to signal exit. */
    fun handle(gesture: Gesture): GlassAction
    /** Optional: action to emit when entering / leaving (e.g. ring-LED feedback). */
    fun onEnter(): GlassAction = GlassAction.None
    fun onExit(reason: ModalExitReason): GlassAction = GlassAction.None
}

enum class ModalExitReason { CONFIRMED, CANCELLED, TIMEOUT }

// ModalSentinel lives in com.halo.ring.core.action (same package as the sealed parent GlassAction);
// imported above.
