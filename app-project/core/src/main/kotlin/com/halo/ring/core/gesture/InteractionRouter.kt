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
    /**
     * Doc/18 §7 — exclusive plugin OVERLAY. Returns true while a plugin's HUD overlay is active and
     * owns the ring. When true, [onGesture] forwards EVERY gesture to the plugin via
     * [forwardOverlayGesture] and lets nothing leak to the underlying app (no base-key passthrough,
     * no profile routing). Wired in the service: `{ graph.overlayController.isActive() }`.
     */
    var overlayCaptures: () -> Boolean = { false },
    /** Forward a raw gesture to the active overlay plugin (the service broadcasts it). The plugin
     *  interprets it + updates its own HUD; Halo Ring never assigns semantics. */
    var forwardOverlayGesture: (Gesture) -> Unit = {},
    /**
     * v0.3 P1 (Doc/19 §3.P1): dispatches a system-level KeyEvent when the active profile's
     * [GestureConfig.useSystemKeyEvents] is true and the gesture is one of the 4 BASE gestures
     * (TAP / DOUBLE_TAP / SWIPE_UP / SWIPE_DOWN). The default [NoopSystemKeyDispatcher] is safe
     * for tests; the foreground service wires a real Activity-window dispatcher.
     */
    var systemKeyDispatcher: SystemKeyDispatcher = NoopSystemKeyDispatcher,
    /**
     * True when our own Config Activity is foreground. When so, an in-app LONG_PRESS cycles the
     * home tab (RING → VITALS → MORE) instead of sleeping the screen — sleep stays the out-of-app
     * meaning. The service wires this to `MainActivity.isInForeground`. (Context gesture,
     * 2026-05-29: the dual-axis focus juggling for a horizontal tab strip was too confusing.)
     */
    var appForeground: () -> Boolean = { false },
    /** Cycle the foreground Config Activity's home tab. Wired by the service to bump
     *  `AppGraph.homeTabIndexFlow`; no-op when our UI isn't up. */
    var onAppTabCycle: () -> Unit = {},
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
        // If the wake gesture is a single raw event (LONG_PRESS / SWIPE), match it here on the
        // zero-latency fast path. If it's a *synthesized* gesture (e.g. DOUBLE_TAP), we can't match
        // a single raw — return false so the caller feeds the synthesizer, and [onGesture] fires
        // wake when the synth emits it (with screen still off).
        if (!isRawMatchable(wake)) return false
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
            // Screen off: the only thing that does anything is the (synthesized) wake gesture
            // (e.g. DOUBLE_TAP, which the raw fast path can't match). Everything else is dropped so
            // a hand brushing the ring while you sleep can't trigger actions.
            if (gesture == systemGestures.wake) {
                onGestureRecognized?.invoke(gesture, GlassAction.ScreenWake)
                dispatch(GlassAction.ScreenWake)
            }
            return
        }
        // 0a. EXCLUSIVE plugin overlay (Doc/18 §7). When a plugin's HUD is up it owns the ring
        //     entirely: forward the raw gesture to the plugin and swallow it here — nothing reaches
        //     the system layer, base-key passthrough, or the wearer's profile, so the underlying app
        //     (music / launcher) never sees a page-flip or click. Highest priority after wake.
        if (overlayCaptures()) {
            forwardOverlayGesture(gesture)
            onGestureRecognized?.invoke(gesture, GlassAction.None)   // HUD-hint / telemetry only
            return
        }
        // 0b. In-app tab switch (context gesture). When our own Config Activity is foreground a
        //     LONG_PRESS cycles the home tab instead of sleeping the screen. Placed before the
        //     screen-sleep check so in-app LONG_PRESS never falls through to sleep; out-of-app it
        //     does (below). Guarded by appForeground() so out-of-app behaviour is unchanged.
        if (gesture == Gesture.LONG_PRESS && appForeground()) {
            onAppTabCycle()
            onGestureRecognized?.invoke(gesture, GlassAction.None)
            return
        }
        // 0. system screen-sleep (LONG_PRESS) — but ONLY in the fallback profile (Navigation).
        //    In app profiles the same gesture belongs to the app (e.g. Media → next track,
        //    Camera → start recording), so we let it fall through to the profile there.
        //    (user 2026-05-28: "长按锁屏应该是 navigation 的 profile 的，其他 profile 可以利用这个".)
        if (gesture == systemGestures.sleep && modeManager.isFallbackActive()) {
            onGestureRecognized?.invoke(gesture, GlassAction.ScreenSleep)
            dispatch(GlassAction.ScreenSleep); return
        }
        // 1. system-level overrides. Note: `wake` is NOT intercepted while on — it's only meaningful
        //    on the screen-off fast path.
        when (gesture) {
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
                // Launch the glasses' native AI agent (Rokid: Sprite AI via ACTION_AI_START
                // broadcast). Routes through the standard dispatch path so the per-flavor strategy
                // mapper handles the actual trigger. Default gesture TRIPLE_TAP (user 2026-05-28).
                onSystemAction(GlassAction.WakeSystemAI)
                onGestureRecognized?.invoke(gesture, GlassAction.WakeSystemAI)
                dispatch(GlassAction.WakeSystemAI); return
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

        // 2.5 v0.3 P1 (Doc/19 §3.P1) — base-gesture system-KeyEvent passthrough.
        //   The 4 BASE gestures (TAP / DOUBLE_TAP / SWIPE_UP / SWIPE_DOWN) fire system KeyEvents
        //   to mirror the Rokid temple touchpad. Inserted AFTER the modal layer (modals own their
        //   gesture vocabulary while active) but BEFORE the pushed-profile and active-profile
        //   layers — plugins/profiles only customise the *non-base* gestures (TRIPLE_TAP,
        //   QUADRUPLE_TAP, LONG_PRESS, combos). The user's invariant: "ring = temple extension,
        //   you can't remap the temple's core 4."
        val activeConfig = modeManager.active().gestureConfig
        if (activeConfig.useSystemKeyEvents) {
            val keyCodes = SystemKeyMapping.forBaseGesture(gesture, activeConfig)
            if (keyCodes.isNotEmpty()) {
                // A swipe dispatches BOTH a vertical and a horizontal DPAD key (e.g. UP + LEFT) so
                // it navigates regardless of whether the foreground UI is a vertical list (home,
                // notifications, in-app) or a horizontal grid (the Sprite Launcher app list). Only
                // the axis with a focusable neighbour actually moves; the other no-ops. This mirrors
                // the Rokid temple swipe, which emits the same dual-axis sequence — fixing
                // "在 APP 列表里不能滑动". TAP / DOUBLE_TAP are single-key.
                keyCodes.forEach { systemKeyDispatcher.dispatch(it) }
                // Synthetic GlassAction for HUD / Test Arena display — never actually dispatched
                // through ActionRouter. Maps in temple semantics:
                //   TAP → Confirm, DOUBLE_TAP → Back, SWIPE_UP/forward → NavNext, SWIPE_DOWN/back → NavPrev
                val synthetic = when (gesture) {
                    Gesture.TAP        -> GlassAction.Confirm
                    Gesture.DOUBLE_TAP -> GlassAction.Back
                    Gesture.SWIPE_UP   -> if (activeConfig.reverseSwipeSemantics) GlassAction.NavPrev else GlassAction.NavNext
                    Gesture.SWIPE_DOWN -> if (activeConfig.reverseSwipeSemantics) GlassAction.NavNext else GlassAction.NavPrev
                    else               -> GlassAction.None
                }
                onGestureRecognized?.invoke(gesture, synthetic)
                return
            }
        }

        // (Plugin overlays are handled exclusively at §0a above — there is no per-gesture
        //  fall-through overlay layer anymore; an overlay either owns the whole ring or isn't active.)

        // 4. profile
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
            // External-plugin actions (Doc/18): the foreground service's [onGestureRecognized]
            // listener fires the targeted broadcast via PluginTrigger. Intercept here so we don't
            // waste a `mapper.primitives()` call only to find an empty list + silently drop.
            is GlassAction.PluginAction -> return
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

    /** Whether a wake-gesture can be matched directly from a single [RawGesture] on the screen-off
     *  fast path. Synthesized gestures (taps/combos) can't — they need the synthesizer to run. */
    private fun isRawMatchable(slot: Gesture): Boolean = when (slot) {
        Gesture.LONG_PRESS, Gesture.SWIPE_UP, Gesture.SWIPE_DOWN -> true
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
 * the profile until it exits. See §25.1. Concrete modals: [com.halo.ring.core.modal.VolumeModal] /
 * [com.halo.ring.core.modal.BrightnessModal] / [com.halo.ring.core.modal.RecentsModal] /
 * [com.halo.ring.core.modal.AIDictateModal] (skeleton — see kdoc on AIDictateModal).
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
