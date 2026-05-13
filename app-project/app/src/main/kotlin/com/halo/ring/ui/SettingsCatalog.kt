package com.halo.ring.ui

import com.halo.ring.core.action.GlassAction

/**
 * Curated catalog of GlassActions the user can bind from the settings UI (Doc/13 §B2). Drives the
 * [com.halo.ring.ui.screens.ActionPickerScreen]. Grouped by mockup §3 E categories.
 *
 * Excluded from the picker (not user-bindable from a UI):
 *   - [GlassAction.LaunchApp] — needs a package-name arg (separate "Custom" sub-flow)
 *   - [GlassAction.Shell]    — power-user escape hatch; lives behind the Advanced screen
 *   - [com.halo.ring.core.action.ModalSentinel] — internal sentinel for the modal layer
 *
 * The system pseudo-actions (`ScreenWake` / `ScreenSleep` / `PeekHud` / `ProfileCycle` /
 * `ForceReconnect`) ARE in the list but they're separated by [ActionGroup.SYSTEM] so the picker
 * can render them in a different colour — the user can technically bind a system gesture's effect
 * into a profile slot, but it usually means they've understood the consequences.
 */
object SettingsCatalog {

    enum class ActionGroup(val title: String) {
        NAV("Navigation"),
        MEDIA_VOL("Media / Volume / Brightness"),
        FEATURE("Glasses features"),
        MODAL("Modal-entry"),
        SYSTEM("System (advanced)"),
        NONE("Unbind"),
    }

    data class Entry(val action: GlassAction, val group: ActionGroup, val friendly: String)

    /** Pre-built list, ordered for display. Stable + safe to expose by reference. */
    val ENTRIES: List<Entry> = listOf(
        // Navigation
        Entry(GlassAction.NavPrev,        ActionGroup.NAV, "Nav prev"),
        Entry(GlassAction.NavNext,        ActionGroup.NAV, "Nav next"),
        Entry(GlassAction.NavLeft,        ActionGroup.NAV, "Nav left"),
        Entry(GlassAction.NavRight,       ActionGroup.NAV, "Nav right"),
        Entry(GlassAction.Confirm,        ActionGroup.NAV, "Confirm"),
        Entry(GlassAction.Back,           ActionGroup.NAV, "Back"),
        Entry(GlassAction.Home,           ActionGroup.NAV, "Home"),
        Entry(GlassAction.Recents,        ActionGroup.NAV, "Recents"),
        Entry(GlassAction.Menu,           ActionGroup.NAV, "Menu"),
        Entry(GlassAction.Notifications,  ActionGroup.NAV, "Notifications"),
        Entry(GlassAction.QuickSettings,  ActionGroup.NAV, "Quick settings"),
        Entry(GlassAction.Screenshot,     ActionGroup.NAV, "Screenshot"),

        // Volume / brightness / media
        Entry(GlassAction.VolumeUp,       ActionGroup.MEDIA_VOL, "Volume +"),
        Entry(GlassAction.VolumeDown,     ActionGroup.MEDIA_VOL, "Volume −"),
        Entry(GlassAction.ToggleMute,     ActionGroup.MEDIA_VOL, "Mute / unmute"),
        Entry(GlassAction.BrightnessUp,   ActionGroup.MEDIA_VOL, "Brightness +"),
        Entry(GlassAction.BrightnessDown, ActionGroup.MEDIA_VOL, "Brightness −"),
        Entry(GlassAction.MediaPlayPause, ActionGroup.MEDIA_VOL, "Play / pause"),
        Entry(GlassAction.MediaPrev,      ActionGroup.MEDIA_VOL, "Previous track"),
        Entry(GlassAction.MediaNext,      ActionGroup.MEDIA_VOL, "Next track"),

        // Glasses features
        Entry(GlassAction.OpenCamera,     ActionGroup.FEATURE, "Camera"),
        Entry(GlassAction.TakePhoto,      ActionGroup.FEATURE, "Take photo"),
        Entry(GlassAction.AskVisualAI,    ActionGroup.FEATURE, "Ask Visual AI"),
        Entry(GlassAction.OpenTranslate,  ActionGroup.FEATURE, "Translate"),
        Entry(GlassAction.OpenChat,       ActionGroup.FEATURE, "AI chat"),
        Entry(GlassAction.OpenMusic,      ActionGroup.FEATURE, "Music"),
        Entry(GlassAction.OpenSettings,   ActionGroup.FEATURE, "Settings"),
        Entry(GlassAction.OpenGallery,    ActionGroup.FEATURE, "Gallery"),

        // Modal-entry — full state machines live in B10
        Entry(GlassAction.EnterVolumeModal,     ActionGroup.MODAL, "Volume modal"),
        Entry(GlassAction.EnterBrightnessModal, ActionGroup.MODAL, "Brightness modal"),
        Entry(GlassAction.EnterRecentsModal,    ActionGroup.MODAL, "Recents modal"),
        Entry(GlassAction.EnterAIDictateModal,  ActionGroup.MODAL, "AI dictate modal"),

        // System pseudo-actions (advanced)
        Entry(GlassAction.ScreenSleep,    ActionGroup.SYSTEM, "Sleep screen"),
        Entry(GlassAction.ScreenWake,     ActionGroup.SYSTEM, "Wake screen"),
        Entry(GlassAction.PeekHud,        ActionGroup.SYSTEM, "Peek HUD"),
        Entry(GlassAction.ProfileCycle,   ActionGroup.SYSTEM, "Cycle profile"),
        Entry(GlassAction.ForceReconnect, ActionGroup.SYSTEM, "Force reconnect"),

        // Unbind
        Entry(GlassAction.None,           ActionGroup.NONE, "(no action)"),
    )

    /** Lookup by action — used to show the "(currently)" badge in the editor. */
    fun entryFor(action: GlassAction): Entry? = ENTRIES.firstOrNull { it.action == action }
}
