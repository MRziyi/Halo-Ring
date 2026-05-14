package com.halo.ring.core.action

import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.GestureConfig

/**
 * Built-in profiles, fully specified across all 12 gestures (R08-Remote-Design.md §25.2).
 *
 * System-level gestures (TRIPLE_TAP, QUADRUPLE_TAP, LONG_PRESS_SWIPE_DOWN, DOUBLE_LONG_PRESS) are
 * intentionally left as [GlassAction.None] here — [com.halo.ring.core.gesture.InteractionRouter]
 * intercepts them before they reach the profile.
 *
 * Audit-pass 2026-05-14w redesign of profile bindings + triggerPackages:
 *  - DOUBLE_LONG_PRESS system slot is now [GlassAction.OpenAIAssistant] (was ForceReconnect).
 *  - DefaultProfiles.NAVIGATION's `DOUBLE_TAP_SWIPE_DOWN` swapped from AskVisualAI →
 *    OpenAIAssistant: everyday voice/chat AI is more useful than camera-grounded VQA for the
 *    average user. AskVisualAI stays in the picker for users who want it.
 *  - MEDIA's swipes now do volume (most-used media op) instead of track-change; long-press and
 *    its combo do track navigation. Screenshot moved to DOUBLE_TAP_SWIPE_UP (more useful than
 *    photo while in a media app).
 *  - READER's long-press does brightness (reading-light affordance); kept Translate on
 *    DOUBLE_TAP_SWIPE_DOWN.
 *  - triggerPackages populated with actual Rokid Sprite + common AOSP music/video/reader
 *    packages so Media / Reader auto-activate without manual setup.
 */
object DefaultProfiles {

    private val systemSlots: Map<Gesture, GlassAction> = mapOf(
        // System-level gestures: intercepted by InteractionRouter. Bound to None here so users see
        // them in the mapping UI as "(system)" and can't accidentally double-bind.
        Gesture.TRIPLE_TAP             to GlassAction.None,   // ↳ ProfileCycle
        Gesture.QUADRUPLE_TAP          to GlassAction.None,   // ↳ PeekHud
        Gesture.LONG_PRESS_SWIPE_DOWN  to GlassAction.None,   // ↳ ScreenSleep
        Gesture.DOUBLE_LONG_PRESS      to GlassAction.None,   // ↳ OpenAIAssistant (was ForceReconnect)
    )

    /** Default for system-UI navigation: precise (no optimistic tap), full combo support. The
     *  *fallback* profile — ModeManager returns to this one whenever the foreground app doesn't
     *  match any other profile's triggerPackages. */
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
            // Audit-pass 2026-05-14w: was AskVisualAI; everyday AI more useful than camera-grounded
            // VQA. The DOUBLE_LONG_PRESS system slot also points to OpenAIAssistant, so users have
            // two entry points (one combo, one system gesture).
            Gesture.DOUBLE_TAP_SWIPE_DOWN to GlassAction.OpenAIAssistant,
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.Notifications,   // "long-press, pull-up" → notification shade
        ),
        triggerPackages = emptyList(),   // fallback profile; matches whenever nothing else does
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
            // Audit-pass 2026-05-14w: swipes now do VOLUME (most-frequent media op while listening),
            // track-change moved to long-press + combo. Bet: people adjust volume more than they
            // skip tracks.
            Gesture.SWIPE_UP              to GlassAction.VolumeUp,
            Gesture.SWIPE_DOWN            to GlassAction.VolumeDown,
            Gesture.LONG_PRESS            to GlassAction.MediaNext,
            // Audit-pass 2026-05-14w: was TakePhoto; in a media app the screenshot is far more
            // likely (capture lyrics, video frame) than a camera photo.
            Gesture.DOUBLE_TAP_SWIPE_UP   to GlassAction.Screenshot,
            Gesture.DOUBLE_TAP_SWIPE_DOWN to GlassAction.MediaPrev,
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.Notifications,
        ),
        // Auto-activates when foreground app matches. Audit-pass 2026-05-14w: populated with
        // Sprite Music + common AOSP music/video apps. `ModeManager.onForegroundPackage` does
        // prefix matching, so a partial pkg name still triggers (e.g. "com.spotify" matches
        // "com.spotify.music" and "com.spotify.tv.android").
        triggerPackages = listOf(
            // Rokid Sprite-bundled music page
            "com.rokid.os.sprite.launcher.page.music",
            // Common consumer-side music + short-video apps that users might mirror to glasses
            "com.spotify.music",
            "com.spotify.tv.android",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "tv.danmaku.bili",         // 哔哩哔哩
            "com.ss.android.ugc.aweme", // 抖音
            "com.smile.gifmaker",       // 快手
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
            // Audit-pass 2026-05-14w: was Home; brightness is the universal "reading light"
            // affordance and long-press is the most natural place for it.
            Gesture.LONG_PRESS            to GlassAction.BrightnessUp,
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.BrightnessDown,
            Gesture.DOUBLE_TAP_SWIPE_UP   to GlassAction.TakePhoto,          // capture an interesting passage
            Gesture.DOUBLE_TAP_SWIPE_DOWN to GlassAction.OpenTranslate,      // reading context → translate
        ),
        triggerPackages = listOf(
            // Rokid Sprite reader-ish surfaces
            "com.rokid.os.sprite.launcher.page.translate",
            "com.rokid.os.sprite.launcher.page.wordtips",
            // Common AOSP readers
            "com.adobe.reader",
            "com.amazon.kindle",
            "com.google.android.apps.books",
            "com.android.htmlviewer",     // standalone PDF/HTML viewer
            "com.microsoft.office.officehubrow",
            // Generic browsers when in reader mode
            "com.android.chrome",
            "org.mozilla.firefox",
        ),
    )

    /** Aggressive low-latency profile. Long-press is immediate (no follow-up window) → no LP combos.
     *  Trade-off: lose DOUBLE_TAP (set to None) and combo gestures; gain instant tap + instant LP.
     *  No auto-trigger — user picks it manually for latency-critical sessions. */
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
            enableDoubleLongPress = false,  // OpenAIAssistant on DOUBLE_LONG_PRESS is disabled here for latency
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
        triggerPackages = emptyList(),   // never auto-activates — user picks Fast explicitly
    )

    val ALL = listOf(NAVIGATION, MEDIA, READER, FAST)

    /** Profile to fall back to when the foreground app matches none of the others'
     *  triggerPackages. The id (not the data) is exposed so `ModeManager` can resolve it after
     *  the user has customised profiles. */
    const val DEFAULT_FALLBACK_ID = "navigation"
}
