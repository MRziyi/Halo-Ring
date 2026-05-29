package com.halo.ring.core.action

import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.GestureConfig

/**
 * Built-in profiles, fully specified across all 12 gestures (R08-Remote-Design.md §25.2).
 *
 * System-level gestures (TRIPLE_TAP = WakeSystemAI, plus the now-free LONG_PRESS_SWIPE_DOWN /
 * DOUBLE_LONG_PRESS slots) are left as [GlassAction.None] here — TRIPLE_TAP is intercepted by
 * [com.halo.ring.core.gesture.InteractionRouter] before the profile; the free slots are rebindable.
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
        // System-wide gestures shared by every profile (shown as "(system)" in the editor, not
        // per-profile rebindable).
        Gesture.TRIPLE_TAP             to GlassAction.Screenshot,  // 系统三击截屏 (user 2026-05-28)
        Gesture.LONG_PRESS_SWIPE_DOWN  to GlassAction.None,        // free (ScreenSleep is on LONG_PRESS)
        Gesture.DOUBLE_LONG_PRESS      to GlassAction.None,        // free (rebind to a plugin call via picker)
    )

    /** Default for system-UI navigation: precise (no optimistic tap), full combo support. The
     *  *fallback* profile — ModeManager returns to this one whenever the foreground app doesn't
     *  match any other profile's triggerPackages. */
    val NAVIGATION = KeyMapProfile(
        id = "navigation",
        name = "Navigation",
        gestureConfig = GestureConfig(
            multiTapWindowMs = 300,
            comboWindowMs = 400,
            optimisticSingleTap = false,
            awaitCombos = true,
            awaitLongPressCombos = true,
            longPressFollowupWindowMs = 60,
            enableTripleTap = true,
            enableDoubleLongPress = true,
        ),
        map = systemSlots + mapOf(
            Gesture.TAP                   to GlassAction.Confirm,
            Gesture.DOUBLE_TAP            to GlassAction.Back,
            Gesture.SWIPE_UP              to GlassAction.NavPrev,
            Gesture.SWIPE_DOWN            to GlassAction.NavNext,
            // LONG_PRESS in the fallback profile = system screen-sleep (handled by InteractionRouter
            // before the profile; this None is just for clarity). Only Navigation owns sleep.
            Gesture.LONG_PRESS            to GlassAction.None,
            // Subtitle + AI on the easy single-tap-swipe pair. DOUBLE_TAP_SWIPE_* is freed for the
            // user's own custom bindings (picker → e.g. a Constellation plugin call).
            Gesture.TAP_SWIPE_UP          to GlassAction.OpenSubtitle,     // 单击上 → Rokid 字幕 (live caption)
            Gesture.TAP_SWIPE_DOWN        to GlassAction.OpenAIAssistant,  // 单击下 → AI
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.Notifications,    // "long-press, pull-up" → notification shade
        ),
        triggerPackages = emptyList(),   // fallback profile; matches whenever nothing else does
    )

    /** Music / video. **useSystemKeyEvents = false** so the base gestures route through THIS map
     *  (not the hard-locked DPAD passthrough) — that's what makes single-tap actually play/pause and
     *  swipes do volume on a Bluetooth-music remote page (user 2026-05-28: "音乐单击并没有暂停").
     *  optimisticSingleTap is OFF so a double-tap doesn't fire PlayPause-then-Back. */
    val MEDIA = KeyMapProfile(
        id = "media",
        name = "Media",
        gestureConfig = GestureConfig(
            multiTapWindowMs = 300,
            comboWindowMs = 400,
            optimisticSingleTap = false,           // keep double-tap = exit reliable
            awaitCombos = true,
            awaitLongPressCombos = true,
            longPressFollowupWindowMs = 60,
            useSystemKeyEvents = false,            // route TAP / swipes through this map, not DPAD
        ),
        map = systemSlots + mapOf(
            Gesture.TAP                   to GlassAction.MediaPlayPause,   // single-tap = play/pause
            Gesture.DOUBLE_TAP            to GlassAction.Back,             // double-tap = exit (consistent w/ Nav)
            Gesture.SWIPE_UP              to GlassAction.VolumeUp,
            Gesture.SWIPE_DOWN            to GlassAction.VolumeDown,
            // 单击上/下 = prev/next track (user 2026-05-28: "音乐手势很别扭，单击上上一曲，单击下下一曲").
            Gesture.TAP_SWIPE_UP          to GlassAction.MediaPrev,
            Gesture.TAP_SWIPE_DOWN        to GlassAction.MediaNext,
            Gesture.LONG_PRESS            to GlassAction.None,             // freed (track-change moved to tap-swipe)
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
            // optimistic OFF so the single-tap-swipe combo can form (a pending TAP must be held to
            // detect a following swipe). Bare page-nav swipes still fire instantly; only the bare
            // TAP=Confirm waits out the multi-tap window.
            optimisticSingleTap = false,
            awaitCombos = true,
            awaitLongPressCombos = true,
        ),
        map = systemSlots + mapOf(
            Gesture.TAP                   to GlassAction.Confirm,
            Gesture.DOUBLE_TAP            to GlassAction.Back,
            Gesture.SWIPE_UP              to GlassAction.NavPrev,            // prev page / line
            Gesture.SWIPE_DOWN            to GlassAction.NavNext,
            // brightness on long-press (reading-light affordance); not shadowed by sleep (Reader ≠ fallback).
            Gesture.LONG_PRESS            to GlassAction.BrightnessUp,
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.BrightnessDown,
            // Primary combos on the easy single-tap-swipe pair (consistent with Nav/Media).
            Gesture.TAP_SWIPE_UP          to GlassAction.OpenSubtitle,       // 单击上 → live caption
            Gesture.TAP_SWIPE_DOWN        to GlassAction.OpenTranslate,      // reading context → translate
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
            enableTripleTap = true,         // triple = system Sprite AI
            enableDoubleLongPress = false,  // disabled here for latency
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

    // Camera profile dropped 2026-05-29 (user "不用相机的profile了"): the Sprite camera bounces when
    // launched externally so it could never hold the profile, and AOSP camera2 wasn't wanted. The
    // camera shortcut became "Subtitle" (Rokid live-caption) instead — see TAP_SWIPE_UP bindings.

    // Fast profile dropped 2026-05-27 — base gestures are already instant system KeyEvents.
    val ALL = listOf(NAVIGATION, MEDIA, READER)

    /** Profile to fall back to when the foreground app matches none of the others'
     *  triggerPackages. The id (not the data) is exposed so `ModeManager` can resolve it after
     *  the user has customised profiles. */
    const val DEFAULT_FALLBACK_ID = "navigation"
}
