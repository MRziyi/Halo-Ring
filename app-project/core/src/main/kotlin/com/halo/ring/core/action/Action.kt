package com.halo.ring.core.action

/**
 * Abstract "what the user wants the glasses to do". Device-agnostic — the per-device
 * [com.halo.ring.core.device.GlassActionMapper] turns each of these into a concrete injection
 * (DPAD key on Rokid, swipe MotionEvent on RayNeo, etc.) plus optional `am start`/`broadcast`.
 *
 * Each action carries its minimum [Capability] requirement; [com.halo.ring.core.action.ActionRouter]
 * uses that to pick the cheapest backend that can actually do it.
 *
 * Vocabulary (per R08-Remote-Design.md §25.3, ~30 actions across 5 buckets).
 */
sealed interface GlassAction {
    val needs: Capability

    // ── navigation / system (most-used) ──────────────────────────────────────────────────────────
    data object NavPrev   : GlassAction { override val needs = Capability.NAVIGATE }    // Rokid: DPAD_UP key / RayNeo: forward-swipe
    data object NavNext   : GlassAction { override val needs = Capability.NAVIGATE }
    data object NavLeft   : GlassAction { override val needs = Capability.NAVIGATE }
    data object NavRight  : GlassAction { override val needs = Capability.NAVIGATE }
    data object Confirm   : GlassAction { override val needs = Capability.NAVIGATE }
    data object Back      : GlassAction { override val needs = Capability.BACK }        // accessibility GLOBAL_ACTION_BACK works → cheaper
    data object Home      : GlassAction { override val needs = Capability.HOME }
    data object Recents   : GlassAction { override val needs = Capability.RECENTS }
    data object Menu      : GlassAction { override val needs = Capability.KEY_EVENT }
    data object Notifications : GlassAction { override val needs = Capability.NOTIFICATIONS }
    data object QuickSettings : GlassAction { override val needs = Capability.NOTIFICATIONS }   // a11y exposes both as global actions
    data object Screenshot    : GlassAction { override val needs = Capability.NOTIFICATIONS }   // API 30+ a11y global action

    // ── volume / brightness / media ─────────────────────────────────────────────────────────────
    data object VolumeUp   : GlassAction { override val needs = Capability.KEY_EVENT }
    data object VolumeDown : GlassAction { override val needs = Capability.KEY_EVENT }
    data object ToggleMute : GlassAction { override val needs = Capability.KEY_EVENT }          // KEYCODE_VOLUME_MUTE (164)
    data object BrightnessUp   : GlassAction { override val needs = Capability.KEY_EVENT }      // KEYCODE_BRIGHTNESS_UP (221); fall back to `settings put system screen_brightness` shell
    data object BrightnessDown : GlassAction { override val needs = Capability.KEY_EVENT }
    data object MediaPlayPause : GlassAction { override val needs = Capability.KEY_EVENT }
    data object MediaPrev      : GlassAction { override val needs = Capability.KEY_EVENT }
    data object MediaNext      : GlassAction { override val needs = Capability.KEY_EVENT }

    // ── glasses-specific features (per-device FeatureIntents) ────────────────────────────────────
    data object OpenCamera   : GlassAction { override val needs = Capability.LAUNCH_INTENT }
    data object TakePhoto    : GlassAction { override val needs = Capability.LAUNCH_INTENT }
    /** "Wake the everyday voice / chat assistant" — Gemini / Rokid Chat / Hey-RayNeo etc. Distinct
     *  from [AskVisualAI] (which is camera-grounded VQA). Most-used AI entry point for both flavors;
     *  default system gesture is DOUBLE_LONG_PRESS. Audit-pass 2026-05-14w. */
    data object OpenAIAssistant : GlassAction { override val needs = Capability.LAUNCH_INTENT }
    data object AskVisualAI  : GlassAction { override val needs = Capability.LAUNCH_INTENT }
    data object OpenTranslate: GlassAction { override val needs = Capability.LAUNCH_INTENT }
    data object OpenChat     : GlassAction { override val needs = Capability.LAUNCH_INTENT }
    data object OpenMusic    : GlassAction { override val needs = Capability.LAUNCH_INTENT }
    data object OpenSettings : GlassAction { override val needs = Capability.LAUNCH_INTENT }
    data object OpenGallery  : GlassAction { override val needs = Capability.LAUNCH_INTENT }
    data class  LaunchApp(val pkg: String) : GlassAction { override val needs = Capability.LAUNCH_INTENT }

    // ── system-level (set by SystemGestures; not assignable via profile) ─────────────────────────
    data object ScreenSleep    : GlassAction { override val needs = Capability.KEY_EVENT }       // KEYCODE_SLEEP (223) or a11y GLOBAL_ACTION_LOCK_SCREEN
    data object ScreenWake     : GlassAction { override val needs = Capability.KEY_EVENT }       // KEYCODE_WAKEUP (224)
    data object PeekHud        : GlassAction { override val needs = Capability.NONE }            // handled in-app, no injection
    data object ProfileCycle   : GlassAction { override val needs = Capability.NONE }            // handled by ModeManager
    data object ForceReconnect : GlassAction { override val needs = Capability.NONE }            // handled by R08BleClient

    // ── modal entries (profile-bindable; modal layer takes over until exit) ──────────────────────
    data object EnterVolumeModal     : GlassAction { override val needs = Capability.NONE }
    data object EnterBrightnessModal : GlassAction { override val needs = Capability.NONE }
    data object EnterRecentsModal    : GlassAction { override val needs = Capability.NONE }
    data object EnterAIDictateModal  : GlassAction { override val needs = Capability.NONE }

    // ── escape hatches ───────────────────────────────────────────────────────────────────────────
    data class  Shell(val cmd: String) : GlassAction { override val needs = Capability.SHELL }
    /** "Do nothing" — useful to explicitly unbind a gesture. */
    data object None : GlassAction { override val needs = Capability.NONE }

    // ── external plugins (Doc/18 — Halo Ring Plugin Protocol v1) ─────────────────────────────────
    /**
     * An action provided by an external app (a "plugin"). Carries enough to identify the target
     * (package + action id) and to render its UI row without re-querying the plugin's
     * `ContentProvider` (label cached at bind time).
     *
     * Dispatch path lives in `:app/plugin/PluginTrigger` because it requires Android `Intent`s —
     * the router treats this as an out-of-band action handled by the foreground service before
     * the normal `ExecutorBackend` chain (similar to how `PeekHud` is intercepted).
     */
    data class PluginAction(
        /** Plugin owner package, e.g. `com.constellation.glass`. */
        val pluginPackage: String,
        /** Stable action id within the plugin, e.g. `voice_invoke`. Used as `action_id` extra. */
        val actionId: String,
        /** Human-readable label snapshotted at bind time. Survives plugin uninstall so the
         *  Profile editor can show "(missing) Voice invoke" instead of an opaque hex code. */
        val label: String,
    ) : GlassAction { override val needs = Capability.NONE }
}

/**
 * Sentinel actions a Modal can return to tell the router "I'm done". Declared here (not in the
 * `gesture` package alongside `Modal`) because Kotlin requires sealed subtypes to live in the same
 * package as their parent.
 */
sealed class ModalSentinel : GlassAction {
    override val needs = Capability.NONE
    data object Exit   : ModalSentinel()
    data object Cancel : ModalSentinel()
    /**
     * Dispatch [payload] *and* exit the modal. Used for "tap to select the recent task → activate
     * it → close the recents picker" — see [com.halo.ring.core.modal.RecentsModal].
     */
    data class  FireAndExit(val payload: GlassAction) : ModalSentinel()
}

/** Coarse capability tags used to match actions against [com.halo.ring.core.inject.ExecutorBackend]s. */
enum class Capability {
    NONE,                                  // handled in-app — no backend needed
    BACK, HOME, RECENTS, NOTIFICATIONS,    // AccessibilityService can do these
    NAVIGATE,                              // device-specific transport (DPAD key OR swipe MotionEvent) — agent/Shizuku/ADB
    KEY_EVENT,                             // arbitrary Android keycode injection — needs shell privilege
    TAP_SWIPE,                             // touch MotionEvent injection — same privilege
    LAUNCH_INTENT,                         // `am start` / `am broadcast` — any backend with a shell
    SHELL,                                 // arbitrary shell command — agent/Shizuku/ADB
}
