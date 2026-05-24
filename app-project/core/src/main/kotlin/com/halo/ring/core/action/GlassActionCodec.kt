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

    /** Encode an action to a single line.
     *
     *  PluginAction format: `Plugin:<pkg>|<actionId>|<label>`. Pipe is the separator because none
     *  of (pkg / actionId) can contain it (Android package + identifier syntax) and the label is
     *  taken last so we restore it verbatim. Any literal `|` in the label is escaped to `\\|`
     *  with a leading backslash so the split is unambiguous; backslash itself escapes as `\\\\`.
     */
    fun encode(action: GlassAction): String = when (action) {
        is GlassAction.LaunchApp     -> "LaunchApp:${action.pkg}"
        is GlassAction.Shell         -> "Shell:${action.cmd}"
        is GlassAction.PluginAction  ->
            "Plugin:${action.pluginPackage}|${action.actionId}|${escapePipe(action.label)}"
        is ModalSentinel.Exit        -> "Exit"
        is ModalSentinel.Cancel      -> "Cancel"
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
                "Plugin"    -> decodePlugin(arg)
                else        -> GlassAction.None
            }
        }
        return BY_NAME[s] ?: GlassAction.None
    }

    private fun decodePlugin(arg: String): GlassAction {
        val parts = splitPipe(arg)
        if (parts.size != 3) return GlassAction.None
        val (pkg, actionId, label) = parts
        if (pkg.isEmpty() || actionId.isEmpty()) return GlassAction.None
        return GlassAction.PluginAction(pluginPackage = pkg, actionId = actionId, label = label)
    }

    private fun escapePipe(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\|")

    /** Inverse of [escapePipe], applied per field after splitting on unescaped pipes. */
    private fun splitPipe(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                cur.append(s[i + 1])
                i += 2
                continue
            }
            if (c == '|') {
                out.add(cur.toString())
                cur.setLength(0)
                i += 1
                continue
            }
            cur.append(c)
            i += 1
        }
        out.add(cur.toString())
        return out
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
        GlassAction.OpenCamera, GlassAction.TakePhoto,
        GlassAction.OpenAIAssistant, GlassAction.AskVisualAI,
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
