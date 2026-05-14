package com.halo.ring.ui.hud

import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.gesture.Gesture

/**
 * A discrete event the HUD can be asked to show. Each maps to one of the variants in
 * Doc/08-ui-design.md §3 / §10. The HUD chooses the visual treatment from the event type.
 */
sealed interface HudEvent {

    /** "Peek" — connection + battery + mode. Triggered on QUADRUPLE_TAP and on connect/disconnect
     *  state changes; 2 s. */
    data class Peek(val ringId: String, val batteryPct: Int?, val mode: String, val connected: Boolean) : HudEvent

    /** Profile switched (via TRIPLE_TAP or auto-switch). 2 s, accent-bordered. */
    data class ProfileSwitched(val newMode: String) : HudEvent

    /** Ring battery dipped below 20%. Warn-coloured. 2 s; repeats every 5 min while low. */
    data class LowBattery(val ringId: String, val pct: Int) : HudEvent

    /** BLE connection lost. **Centre-positioned** and persistent until reconnect. */
    data class Disconnected(val sinceMs: Long = System.currentTimeMillis()) : HudEvent

    /** Reconnected; transient "back online" hint. 1 s. */
    data object Reconnected : HudEvent

    /**
     * **Gesture-hint** (Doc/08-ui-design.md §10). 800 ms. Replaces any in-flight gesture-hint.
     * Doesn't queue. Only fires while the user preference is on.
     *
     * [resolvedAction] = what the gesture was routed to — can be a profile action, a system
     * pseudo-action ([com.halo.ring.core.action.GlassAction.ScreenSleep] etc.), or `None` if the
     * gesture was unmapped.
     */
    data class GestureRecognised(val gesture: Gesture, val resolvedAction: GlassAction) : HudEvent
}

/** Pick a string-resource id for a gesture's friendly localized name. Doc/08-ui-design.md §10.3.
 *  Renamed from `Gesture.friendly()` to `gestureFriendlyRes(g)` (audit-2026-05-13s i18n) so the
 *  consumer can call `stringResource(...)` at render time and get a locale-correct string. */
fun gestureFriendlyRes(g: Gesture): Int = when (g) {
    Gesture.TAP                   -> com.halo.ring.R.string.gesture_tap
    Gesture.DOUBLE_TAP            -> com.halo.ring.R.string.gesture_double_tap
    Gesture.TRIPLE_TAP            -> com.halo.ring.R.string.gesture_triple_tap
    Gesture.QUADRUPLE_TAP         -> com.halo.ring.R.string.gesture_quadruple_tap
    Gesture.SWIPE_UP              -> com.halo.ring.R.string.gesture_swipe_up
    Gesture.SWIPE_DOWN            -> com.halo.ring.R.string.gesture_swipe_down
    Gesture.LONG_PRESS            -> com.halo.ring.R.string.gesture_long_press
    Gesture.DOUBLE_TAP_SWIPE_UP   -> com.halo.ring.R.string.gesture_double_tap_swipe_up
    Gesture.DOUBLE_TAP_SWIPE_DOWN -> com.halo.ring.R.string.gesture_double_tap_swipe_down
    Gesture.LONG_PRESS_SWIPE_UP   -> com.halo.ring.R.string.gesture_long_press_swipe_up
    Gesture.LONG_PRESS_SWIPE_DOWN -> com.halo.ring.R.string.gesture_long_press_swipe_down
    Gesture.DOUBLE_LONG_PRESS     -> com.halo.ring.R.string.gesture_double_long_press
}

/** String-resource id for an action's friendly localized name. `LaunchApp` gets a special
 *  resolver that injects the package basename (see [actionFriendlyText]). */
fun actionFriendlyRes(a: GlassAction): Int = when (a) {
    GlassAction.NavPrev   -> com.halo.ring.R.string.action_nav_prev
    GlassAction.NavNext   -> com.halo.ring.R.string.action_nav_next
    GlassAction.NavLeft   -> com.halo.ring.R.string.action_nav_left
    GlassAction.NavRight  -> com.halo.ring.R.string.action_nav_right
    GlassAction.Confirm   -> com.halo.ring.R.string.action_confirm
    GlassAction.Back      -> com.halo.ring.R.string.action_back
    GlassAction.Home      -> com.halo.ring.R.string.action_home
    GlassAction.Recents   -> com.halo.ring.R.string.action_recents
    GlassAction.Menu      -> com.halo.ring.R.string.action_menu
    GlassAction.Notifications  -> com.halo.ring.R.string.action_notifications
    GlassAction.QuickSettings  -> com.halo.ring.R.string.action_quick_settings
    GlassAction.Screenshot     -> com.halo.ring.R.string.action_screenshot
    GlassAction.VolumeUp   -> com.halo.ring.R.string.action_volume_up
    GlassAction.VolumeDown -> com.halo.ring.R.string.action_volume_down
    GlassAction.ToggleMute -> com.halo.ring.R.string.action_mute
    GlassAction.BrightnessUp   -> com.halo.ring.R.string.action_brightness_up
    GlassAction.BrightnessDown -> com.halo.ring.R.string.action_brightness_down
    GlassAction.MediaPlayPause -> com.halo.ring.R.string.action_media_play_pause
    GlassAction.MediaPrev      -> com.halo.ring.R.string.action_media_prev
    GlassAction.MediaNext      -> com.halo.ring.R.string.action_media_next
    GlassAction.OpenCamera     -> com.halo.ring.R.string.action_open_camera
    GlassAction.TakePhoto      -> com.halo.ring.R.string.action_take_photo
    GlassAction.AskVisualAI    -> com.halo.ring.R.string.action_visual_ai
    GlassAction.OpenTranslate  -> com.halo.ring.R.string.action_open_translate
    GlassAction.OpenChat       -> com.halo.ring.R.string.action_open_chat
    GlassAction.OpenMusic      -> com.halo.ring.R.string.action_open_music
    GlassAction.OpenSettings   -> com.halo.ring.R.string.action_open_settings
    GlassAction.OpenGallery    -> com.halo.ring.R.string.action_open_gallery
    is GlassAction.LaunchApp   -> com.halo.ring.R.string.action_launch_app
    GlassAction.ScreenSleep    -> com.halo.ring.R.string.action_screen_sleep
    GlassAction.ScreenWake     -> com.halo.ring.R.string.action_screen_wake
    GlassAction.PeekHud        -> com.halo.ring.R.string.action_peek_hud
    GlassAction.ProfileCycle   -> com.halo.ring.R.string.action_profile_cycle
    GlassAction.ForceReconnect -> com.halo.ring.R.string.action_force_reconnect
    GlassAction.EnterVolumeModal     -> com.halo.ring.R.string.action_volume_modal
    GlassAction.EnterBrightnessModal -> com.halo.ring.R.string.action_brightness_modal
    GlassAction.EnterRecentsModal    -> com.halo.ring.R.string.action_recents_modal
    GlassAction.EnterAIDictateModal  -> com.halo.ring.R.string.action_ai_dictate_modal
    is GlassAction.Shell -> com.halo.ring.R.string.action_shell
    GlassAction.None     -> com.halo.ring.R.string.action_none
    is com.halo.ring.core.action.ModalSentinel -> com.halo.ring.R.string.action_modal_exit
}

/** Convenience composable that resolves an action's name including the LaunchApp variant. */
@androidx.compose.runtime.Composable
fun actionFriendlyText(a: GlassAction): String =
    if (a is GlassAction.LaunchApp) {
        androidx.compose.ui.res.stringResource(
            com.halo.ring.R.string.action_launch_app, a.pkg.substringAfterLast('.'),
        )
    } else {
        androidx.compose.ui.res.stringResource(actionFriendlyRes(a))
    }

/** Convenience composable that resolves a gesture's friendly name in the current locale. */
@androidx.compose.runtime.Composable
fun gestureFriendlyText(g: Gesture): String =
    androidx.compose.ui.res.stringResource(gestureFriendlyRes(g))

/** True if this action is system-level (gets accent colour treatment in the hint HUD). */
fun GlassAction.isSystemLevel(): Boolean = when (this) {
    GlassAction.ScreenSleep, GlassAction.ScreenWake,
    GlassAction.PeekHud, GlassAction.ProfileCycle, GlassAction.ForceReconnect -> true
    else -> false
}

/** Friendly localized profile name. Default profiles ([com.halo.ring.core.action.DefaultProfiles])
 *  have stable ids — those map to translated strings. User-created profiles fall back to their
 *  raw `name`. */
@androidx.compose.runtime.Composable
fun profileFriendlyText(profile: com.halo.ring.core.action.KeyMapProfile): String = when (profile.id) {
    "navigation" -> androidx.compose.ui.res.stringResource(com.halo.ring.R.string.profile_default_navigation)
    "media"      -> androidx.compose.ui.res.stringResource(com.halo.ring.R.string.profile_default_media)
    "reader"     -> androidx.compose.ui.res.stringResource(com.halo.ring.R.string.profile_default_reader)
    "fast"       -> androidx.compose.ui.res.stringResource(com.halo.ring.R.string.profile_default_fast)
    else         -> profile.name
}

/** Profile name resolver that works from just an id + raw English name (used when a UI consumer
 *  has a `String` not a full [com.halo.ring.core.action.KeyMapProfile]). */
@androidx.compose.runtime.Composable
fun profileFriendlyTextByName(rawName: String): String = when (rawName.lowercase()) {
    "navigation" -> androidx.compose.ui.res.stringResource(com.halo.ring.R.string.profile_default_navigation)
    "media"      -> androidx.compose.ui.res.stringResource(com.halo.ring.R.string.profile_default_media)
    "reader"     -> androidx.compose.ui.res.stringResource(com.halo.ring.R.string.profile_default_reader)
    "fast"       -> androidx.compose.ui.res.stringResource(com.halo.ring.R.string.profile_default_fast)
    else         -> rawName
}
