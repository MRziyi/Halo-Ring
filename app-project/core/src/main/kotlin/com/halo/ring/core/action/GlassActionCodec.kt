package com.halo.ring.core.action

/**
 * String-based codec for [GlassAction], used by [ProfilesPrefsStore] (`:app`) to persist user
 * profile bindings to DataStore. Pure JVM so the round-trip is unit-testable.
 *
 * Format:
 *  - Data-object actions encode to their `simpleName`, e.g. `"NavPrev"`, `"Confirm"`, `"Back"`.
 *  - [GlassAction.LaunchApp] encodes as `"LaunchApp:<pkg>"`.
 *  - [GlassAction.Shell] encodes as `"Shell:<cmd>"`.
 *  - [ModalSentinel] subtypes encode as `"Exit"` / `"Cancel"`. (Persistable for completeness; never
 *    legitimately stored from the UI.)
 *  - Unknown / malformed input decodes to [GlassAction.None] (forgiving — better than crashing on
 *    a corrupt DataStore).
 */
object GlassActionCodec {

    /** Encode an action to a single ASCII line. */
    fun encode(action: GlassAction): String = when (action) {
        is GlassAction.LaunchApp -> "LaunchApp:${action.pkg}"
        is GlassAction.Shell     -> "Shell:${action.cmd}"
        is ModalSentinel.Exit    -> "Exit"
        is ModalSentinel.Cancel  -> "Cancel"
        else -> action::class.simpleName ?: "None"
    }

    /** Decode. Unknown strings (corrupted prefs, downgrade) become [GlassAction.None]. */
    fun decode(s: String): GlassAction {
        val colon = s.indexOf(':')
        if (colon > 0) {
            val tag = s.substring(0, colon)
            val arg = s.substring(colon + 1)
            return when (tag) {
                "LaunchApp" -> GlassAction.LaunchApp(arg)
                "Shell"     -> GlassAction.Shell(arg)
                else        -> GlassAction.None
            }
        }
        return BY_NAME[s] ?: GlassAction.None
    }

    /** All [GlassAction] singletons that the codec recognises, keyed by their `simpleName`. */
    private val BY_NAME: Map<String, GlassAction> = listOf(
        // navigation / system
        GlassAction.NavPrev, GlassAction.NavNext, GlassAction.NavLeft, GlassAction.NavRight,
        GlassAction.Confirm, GlassAction.Back, GlassAction.Home, GlassAction.Recents,
        GlassAction.Menu, GlassAction.Notifications, GlassAction.QuickSettings, GlassAction.Screenshot,
        // volume / brightness / media
        GlassAction.VolumeUp, GlassAction.VolumeDown, GlassAction.ToggleMute,
        GlassAction.BrightnessUp, GlassAction.BrightnessDown,
        GlassAction.MediaPlayPause, GlassAction.MediaPrev, GlassAction.MediaNext,
        // features
        GlassAction.OpenCamera, GlassAction.TakePhoto, GlassAction.AskVisualAI,
        GlassAction.OpenTranslate, GlassAction.OpenChat, GlassAction.OpenMusic,
        GlassAction.OpenSettings, GlassAction.OpenGallery,
        // system pseudo-actions
        GlassAction.ScreenSleep, GlassAction.ScreenWake,
        GlassAction.PeekHud, GlassAction.ProfileCycle, GlassAction.ForceReconnect,
        // modal entries
        GlassAction.EnterVolumeModal, GlassAction.EnterBrightnessModal,
        GlassAction.EnterRecentsModal, GlassAction.EnterAIDictateModal,
        // escape hatch
        GlassAction.None,
        ModalSentinel.Exit, ModalSentinel.Cancel,
    ).associateBy { it::class.simpleName!! }
}
