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

/** Pick a friendly English name for a gesture. Doc/08-ui-design.md §10.3. */
fun Gesture.friendly(): String = when (this) {
    Gesture.TAP                   -> "Tap"
    Gesture.DOUBLE_TAP            -> "Double tap"
    Gesture.TRIPLE_TAP            -> "Triple tap"
    Gesture.QUADRUPLE_TAP         -> "4× tap"
    Gesture.SWIPE_UP              -> "Swipe up"
    Gesture.SWIPE_DOWN            -> "Swipe down"
    Gesture.LONG_PRESS            -> "Long press"
    Gesture.DOUBLE_TAP_SWIPE_UP   -> "Double-tap + swipe up"
    Gesture.DOUBLE_TAP_SWIPE_DOWN -> "Double-tap + swipe down"
    Gesture.LONG_PRESS_SWIPE_UP   -> "Long-press + swipe up"
    Gesture.LONG_PRESS_SWIPE_DOWN -> "Long-press + swipe down"
    Gesture.DOUBLE_LONG_PRESS     -> "Long-press × 2"
}

/** Pick a friendly English name for an action. */
fun GlassAction.friendly(): String = when (this) {
    GlassAction.NavPrev   -> "Nav prev"
    GlassAction.NavNext   -> "Nav next"
    GlassAction.NavLeft   -> "Nav left"
    GlassAction.NavRight  -> "Nav right"
    GlassAction.Confirm   -> "Confirm"
    GlassAction.Back      -> "Back"
    GlassAction.Home      -> "Home"
    GlassAction.Recents   -> "Recents"
    GlassAction.Menu      -> "Menu"
    GlassAction.Notifications  -> "Notifications"
    GlassAction.QuickSettings  -> "Quick settings"
    GlassAction.Screenshot     -> "Screenshot"
    GlassAction.VolumeUp   -> "Vol +"
    GlassAction.VolumeDown -> "Vol −"
    GlassAction.ToggleMute -> "Mute"
    GlassAction.BrightnessUp   -> "Brightness +"
    GlassAction.BrightnessDown -> "Brightness −"
    GlassAction.MediaPlayPause -> "Play / pause"
    GlassAction.MediaPrev      -> "Previous"
    GlassAction.MediaNext      -> "Next"
    GlassAction.OpenCamera     -> "Camera"
    GlassAction.TakePhoto      -> "Photo"
    GlassAction.AskVisualAI    -> "Visual AI"
    GlassAction.OpenTranslate  -> "Translate"
    GlassAction.OpenChat       -> "Chat"
    GlassAction.OpenMusic      -> "Music"
    GlassAction.OpenSettings   -> "Settings"
    GlassAction.OpenGallery    -> "Gallery"
    is GlassAction.LaunchApp   -> "Open ${pkg.substringAfterLast('.')}"
    GlassAction.ScreenSleep    -> "Sleep screen"
    GlassAction.ScreenWake     -> "Wake screen"
    GlassAction.PeekHud        -> "Peek"
    GlassAction.ProfileCycle   -> "Cycle profile"
    GlassAction.ForceReconnect -> "Reconnect"
    GlassAction.EnterVolumeModal     -> "Volume modal"
    GlassAction.EnterBrightnessModal -> "Brightness modal"
    GlassAction.EnterRecentsModal    -> "Recents modal"
    GlassAction.EnterAIDictateModal  -> "AI dictate"
    is GlassAction.Shell -> "Shell"
    GlassAction.None     -> "(no action)"
    is com.halo.ring.core.action.ModalSentinel -> "(modal exit)"
}

/** True if this action is system-level (gets accent colour treatment in the hint HUD). */
fun GlassAction.isSystemLevel(): Boolean = when (this) {
    GlassAction.ScreenSleep, GlassAction.ScreenWake,
    GlassAction.PeekHud, GlassAction.ProfileCycle, GlassAction.ForceReconnect -> true
    else -> false
}
