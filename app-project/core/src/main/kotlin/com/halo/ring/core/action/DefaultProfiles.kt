package com.halo.ring.core.action

import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.GestureConfig

/**
 * Built-in profiles, fully specified across all 12 gestures (R08-Remote-Design.md §25.2).
 *
 * System-level gestures (TRIPLE_TAP, QUADRUPLE_TAP, LONG_PRESS_SWIPE_DOWN, DOUBLE_LONG_PRESS) are
 * intentionally left as [GlassAction.None] here — [com.halo.ring.core.gesture.InteractionRouter]
 * intercepts them before they reach the profile.
 */
object DefaultProfiles {

    private val systemSlots: Map<Gesture, GlassAction> = mapOf(
        // System-level gestures: intercepted by InteractionRouter. Bound to None here so users see
        // them in the mapping UI as "(system)" and can't accidentally double-bind.
        Gesture.TRIPLE_TAP             to GlassAction.None,   // ↳ ProfileCycle
        Gesture.QUADRUPLE_TAP          to GlassAction.None,   // ↳ PeekHud
        Gesture.LONG_PRESS_SWIPE_DOWN  to GlassAction.None,   // ↳ ScreenSleep
        Gesture.DOUBLE_LONG_PRESS      to GlassAction.None,   // ↳ ForceReconnect
    )

    /** Default for system-UI navigation: precise (no optimistic tap), full combo support. */
    val NAVIGATION = KeyMapProfile(
        id = "navigation",
        name = "Navigation",
        gestureConfig = GestureConfig(
            multiTapWindowMs = 280,
            comboWindowMs = 300,
            optimisticSingleTap = false,
            awaitCombos = true,
            awaitLongPressCombos = true,
            longPressFollowupWindowMs = 400,
            enableTripleTap = true,
            enableQuadrupleTap = true,
            enableDoubleLongPress = true,
        ),
        map = systemSlots + mapOf(
            Gesture.TAP                   to GlassAction.Confirm,
            Gesture.DOUBLE_TAP            to GlassAction.Back,
            Gesture.SWIPE_UP              to GlassAction.NavPrev,
            Gesture.SWIPE_DOWN            to GlassAction.NavNext,
            Gesture.LONG_PRESS            to GlassAction.Menu,
            Gesture.DOUBLE_TAP_SWIPE_UP   to GlassAction.TakePhoto,
            Gesture.DOUBLE_TAP_SWIPE_DOWN to GlassAction.AskVisualAI,
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.Notifications,   // "long-press, pull-up" → notification shade
        ),
    )

    /** Short-video / music — snappy (optimistic tap on for instant play/pause). */
    val MEDIA = KeyMapProfile(
        id = "media",
        name = "Media",
        gestureConfig = GestureConfig(
            multiTapWindowMs = 280,
            comboWindowMs = 300,
            optimisticSingleTap = true,            // play/pause should feel instant
            awaitCombos = true,
            awaitLongPressCombos = true,
            longPressFollowupWindowMs = 400,
        ),
        map = systemSlots + mapOf(
            Gesture.TAP                   to GlassAction.MediaPlayPause,
            Gesture.DOUBLE_TAP            to GlassAction.Back,
            Gesture.SWIPE_UP              to GlassAction.MediaPrev,
            Gesture.SWIPE_DOWN            to GlassAction.MediaNext,
            Gesture.LONG_PRESS            to GlassAction.VolumeUp,         // quick +1
            Gesture.DOUBLE_TAP_SWIPE_UP   to GlassAction.TakePhoto,
            Gesture.DOUBLE_TAP_SWIPE_DOWN to GlassAction.AskVisualAI,
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.EnterVolumeModal, // continuous volume adjust
        ),
        triggerPackages = listOf(
            // Populated by the user at runtime from the settings UI; typically Douyin / YouTube /
            // music apps installed on the glasses.
        ),
    )

    /** Teleprompter / translate / long-form reading. */
    val READER = KeyMapProfile(
        id = "reader",
        name = "Reader",
        gestureConfig = GestureConfig(
            optimisticSingleTap = true,
            awaitCombos = true,
            awaitLongPressCombos = true,
        ),
        map = systemSlots + mapOf(
            Gesture.TAP                   to GlassAction.Confirm,
            Gesture.DOUBLE_TAP            to GlassAction.Back,
            Gesture.SWIPE_UP              to GlassAction.NavPrev,            // prev page / line
            Gesture.SWIPE_DOWN            to GlassAction.NavNext,
            Gesture.LONG_PRESS            to GlassAction.Home,
            Gesture.DOUBLE_TAP_SWIPE_UP   to GlassAction.TakePhoto,
            Gesture.DOUBLE_TAP_SWIPE_DOWN to GlassAction.OpenTranslate,      // reading context → translate
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.OpenChat,
        ),
        triggerPackages = listOf(
            // Reader-ish activities on Rokid (Sprite Launcher's WordTips / Translate page activities).
            // Will be matched against full Activity name later, see ModeManager TODO.
            "com.rokid.os.sprite.launcher",
        ),
    )

    /** Aggressive low-latency profile. Long-press is immediate (no follow-up window) → no LP combos.
     *  Trade-off: lose DOUBLE_TAP (set to None) and combo gestures; gain instant tap + instant LP. */
    val FAST = KeyMapProfile(
        id = "fast",
        name = "Fast",
        gestureConfig = GestureConfig(
            optimisticSingleTap = true,
            awaitCombos = false,
            awaitLongPressCombos = false,   // ← LONG_PRESS = Back, instant
            comboWindowMs = 0,
            enableTripleTap = true,         // still need triple for ProfileCycle (the only way back to a normal mode)
            enableQuadrupleTap = true,
            enableDoubleLongPress = false,  // ForceReconnect via settings page in this profile
        ),
        map = systemSlots + mapOf(
            Gesture.TAP                   to GlassAction.Confirm,
            Gesture.DOUBLE_TAP            to GlassAction.None,
            Gesture.SWIPE_UP              to GlassAction.NavPrev,
            Gesture.SWIPE_DOWN            to GlassAction.NavNext,
            Gesture.LONG_PRESS            to GlassAction.Back,
            Gesture.DOUBLE_TAP_SWIPE_UP   to GlassAction.None,
            Gesture.DOUBLE_TAP_SWIPE_DOWN to GlassAction.None,
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.None,
        ),
    )

    val ALL = listOf(NAVIGATION, MEDIA, READER, FAST)
}
