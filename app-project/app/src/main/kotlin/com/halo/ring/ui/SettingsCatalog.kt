package com.halo.ring.ui

import androidx.annotation.StringRes
import com.halo.ring.R
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
 * can render them in a different colour.
 *
 * **i18n** (audit-pass 2026-05-13t): `ActionGroup.title` is now a `@StringRes Int` (resolved at
 * render time via `stringResource`); `Entry.friendly` was removed entirely — consumers call
 * [com.halo.ring.ui.hud.actionFriendlyText] on the action, which routes through the same
 * `R.string.action_*` keys used by the HUD and Test Arena. One source of truth for action names.
 */
object SettingsCatalog {

    enum class ActionGroup(@StringRes val titleRes: Int) {
        NAV(R.string.action_group_nav),
        MEDIA_VOL(R.string.action_group_media_vol),
        FEATURE(R.string.action_group_feature),
        MODAL(R.string.action_group_modal),
        SYSTEM(R.string.action_group_system),
        NONE(R.string.action_group_none),
    }

    data class Entry(val action: GlassAction, val group: ActionGroup)

    /** Pre-built list, ordered for display. Stable + safe to expose by reference. */
    val ENTRIES: List<Entry> = listOf(
        // Navigation
        Entry(GlassAction.NavPrev,        ActionGroup.NAV),
        Entry(GlassAction.NavNext,        ActionGroup.NAV),
        Entry(GlassAction.NavLeft,        ActionGroup.NAV),
        Entry(GlassAction.NavRight,       ActionGroup.NAV),
        Entry(GlassAction.Confirm,        ActionGroup.NAV),
        Entry(GlassAction.Back,           ActionGroup.NAV),
        Entry(GlassAction.Home,           ActionGroup.NAV),
        Entry(GlassAction.Recents,        ActionGroup.NAV),
        Entry(GlassAction.Menu,           ActionGroup.NAV),
        Entry(GlassAction.Notifications,  ActionGroup.NAV),
        Entry(GlassAction.QuickSettings,  ActionGroup.NAV),
        Entry(GlassAction.Screenshot,     ActionGroup.NAV),

        // Volume / brightness / media
        Entry(GlassAction.VolumeUp,       ActionGroup.MEDIA_VOL),
        Entry(GlassAction.VolumeDown,     ActionGroup.MEDIA_VOL),
        Entry(GlassAction.ToggleMute,     ActionGroup.MEDIA_VOL),
        Entry(GlassAction.BrightnessUp,   ActionGroup.MEDIA_VOL),
        Entry(GlassAction.BrightnessDown, ActionGroup.MEDIA_VOL),
        Entry(GlassAction.MediaPlayPause, ActionGroup.MEDIA_VOL),
        Entry(GlassAction.MediaPrev,      ActionGroup.MEDIA_VOL),
        Entry(GlassAction.MediaNext,      ActionGroup.MEDIA_VOL),

        // Glasses features
        Entry(GlassAction.OpenCamera,     ActionGroup.FEATURE),
        Entry(GlassAction.TakePhoto,      ActionGroup.FEATURE),
        Entry(GlassAction.OpenAIAssistant, ActionGroup.FEATURE),
        Entry(GlassAction.AskVisualAI,    ActionGroup.FEATURE),
        Entry(GlassAction.OpenTranslate,  ActionGroup.FEATURE),
        Entry(GlassAction.OpenChat,       ActionGroup.FEATURE),
        Entry(GlassAction.OpenMusic,      ActionGroup.FEATURE),
        Entry(GlassAction.OpenSettings,   ActionGroup.FEATURE),
        Entry(GlassAction.OpenGallery,    ActionGroup.FEATURE),

        // Modal-entry — full state machines live in B10
        Entry(GlassAction.EnterVolumeModal,     ActionGroup.MODAL),
        Entry(GlassAction.EnterBrightnessModal, ActionGroup.MODAL),
        Entry(GlassAction.EnterRecentsModal,    ActionGroup.MODAL),
        Entry(GlassAction.EnterAIDictateModal,  ActionGroup.MODAL),

        // System pseudo-actions (advanced)
        Entry(GlassAction.ScreenSleep,    ActionGroup.SYSTEM),
        Entry(GlassAction.ScreenWake,     ActionGroup.SYSTEM),
        Entry(GlassAction.PeekHud,        ActionGroup.SYSTEM),
        Entry(GlassAction.ProfileCycle,   ActionGroup.SYSTEM),
        Entry(GlassAction.ForceReconnect, ActionGroup.SYSTEM),

        // Unbind
        Entry(GlassAction.None,           ActionGroup.NONE),
    )

    /** Lookup by action — used to show the "(currently)" badge in the editor. */
    fun entryFor(action: GlassAction): Entry? = ENTRIES.firstOrNull { it.action == action }
}
