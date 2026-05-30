package com.halo.ring.core.action

import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.GestureConfig

/**
 * Built-in profiles, auto-selected by the foreground app (no manual switching).
 *
 * Shared via [systemSlots] in every profile — these are just unbound free slots, not system-level:
 *  - `LONG_PRESS_SWIPE_DOWN` / `DOUBLE_LONG_PRESS` → free (None), user-rebindable (e.g. a plugin call).
 *
 * Current bindings (v0.6, 2026-05-29):
 *  - **Navigation** (fallback): base nav; `LONG_PRESS` = system screen-sleep (fallback-only, via
 *    InteractionRouter); `TAP_SWIPE_UP` = Subtitle, `TAP_SWIPE_DOWN` = AI; `LONG_PRESS_SWIPE_UP` =
 *    Notifications. `DOUBLE_TAP_SWIPE_*` left free for user-custom.
 *  - **Media** (`useSystemKeyEvents=false`, so base gestures route through this map): TAP=play/pause,
 *    DOUBLE_TAP=Back, swipes=volume, `TAP_SWIPE_UP/DOWN`=prev/next track.
 *  - **Reader**: page-nav swipes; LONG_PRESS=brightness; `TAP_SWIPE_UP`=Subtitle, `TAP_SWIPE_DOWN`=Translate.
 *  - **Camera**: auto-activates on the Sprite CameraActivity (restored 2026-05-29 once we found the
 *    non-bouncing launch path). TAP→DPAD_CENTER passes through as shutter.
 *  - `DOUBLE_TAP` = Back (exit) in every profile — invariant. Also wakes the screen when off (system).
 *  - `TRIPLE_TAP` is **unbound by default** in every profile — reserved for the user to set in the
 *    editor (user 2026-05-29: tried Screenshot then Home as presets; both dropped — Home injection
 *    silently filtered by Android's policy on this Rokid build, so triple-tap stays empty for now).
 *
 * NOTE: these are *seed* defaults. Once the wearer's profiles are persisted ([ProfilesPrefsStore]),
 * changing these does NOT retroactively update a device — use Settings → Profiles → "Restore default
 * bindings" to re-seed after an update.
 */
object DefaultProfiles {

    // Free slots shared across all profiles — convenience defaults, not system-locked. The wearer
    // can rebind these per-profile via the editor like any other gesture.
    private val systemSlots: Map<Gesture, GlassAction> = mapOf(
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
            multiTapWindowMs = 220,        // 单击组合窗口
            comboWindowMs = 300,           // 双击组合窗口
            optimisticSingleTap = false,
            enableTapSwipe = true,         // 单击组合 ON (TAP_SWIPE_* have default actions)
            enableDoubleTapSwipe = false,  // 双击组合 OFF by default (Zack 2026-05-30)
            awaitLongPressCombos = false,  // 长按组合 OFF by default
            longPressFollowupWindowMs = 40,
            enableTripleTap = true,
            enableDoubleLongPress = true,
        ),
        map = systemSlots + mapOf(
            Gesture.TAP                   to GlassAction.Confirm,
            Gesture.DOUBLE_TAP            to GlassAction.Back,
            Gesture.TRIPLE_TAP            to GlassAction.None,             // unbound — reserved for user-custom (preset Home dropped 2026-05-29: agent's HOME injection silently filtered on this Rokid build)
            Gesture.SWIPE_UP              to GlassAction.NavPrev,
            Gesture.SWIPE_DOWN            to GlassAction.NavNext,
            // LONG_PRESS in the fallback profile = system screen-sleep (handled by InteractionRouter
            // before the profile; this None is just for clarity). Only Navigation owns sleep.
            Gesture.LONG_PRESS            to GlassAction.None,
            // Subtitle + AI on the easy single-tap-swipe pair (user 2026-05-29). DOUBLE_TAP_SWIPE_*
            // is freed for the user's own custom bindings (picker → e.g. a Constellation plugin call).
            Gesture.TAP_SWIPE_UP          to GlassAction.OpenSubtitle,     // 单击上 → Rokid 字幕 (live caption)
            Gesture.TAP_SWIPE_DOWN        to GlassAction.OpenAIAssistant,  // 单击下 → AI
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.None,            // 长按上 → None (长按组合 off by default; Zack 2026-05-30)
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
            multiTapWindowMs = 220,                // 单击组合窗口
            comboWindowMs = 300,                   // 双击组合窗口
            optimisticSingleTap = false,           // keep double-tap = exit reliable
            enableTapSwipe = true,                 // 单击组合 ON
            enableDoubleTapSwipe = false,          // 双击组合 OFF by default
            awaitLongPressCombos = false,          // 长按组合 OFF by default
            longPressFollowupWindowMs = 40,
            useSystemKeyEvents = false,            // route TAP / swipes through this map, not DPAD
        ),
        map = systemSlots + mapOf(
            Gesture.TAP                   to GlassAction.MediaPlayPause,   // single-tap = play/pause
            Gesture.DOUBLE_TAP            to GlassAction.Back,             // double-tap = exit (consistent w/ Nav)
            Gesture.TRIPLE_TAP            to GlassAction.None,             // unbound — reserved for user-custom
            Gesture.SWIPE_UP              to GlassAction.VolumeUp,
            Gesture.SWIPE_DOWN            to GlassAction.VolumeDown,
            // 单击上/下 = prev/next track (user 2026-05-28: "音乐手势很别扭，单击上上一曲，单击下下一曲").
            Gesture.TAP_SWIPE_UP          to GlassAction.MediaPrev,
            Gesture.TAP_SWIPE_DOWN        to GlassAction.MediaNext,
            Gesture.LONG_PRESS            to GlassAction.None,             // freed (track-change moved to tap-swipe)
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.None,            // 长按上 → None (长按组合 off by default)
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
            enableTapSwipe = true,         // 单击组合 ON
            enableDoubleTapSwipe = false,  // 双击组合 OFF by default
            awaitLongPressCombos = false,  // 长按组合 OFF by default
        ),
        map = systemSlots + mapOf(
            Gesture.TAP                   to GlassAction.Confirm,
            Gesture.DOUBLE_TAP            to GlassAction.Back,
            Gesture.TRIPLE_TAP            to GlassAction.None,               // unbound — reserved for user-custom
            Gesture.SWIPE_UP              to GlassAction.NavPrev,            // prev page / line
            Gesture.SWIPE_DOWN            to GlassAction.NavNext,
            // brightness on long-press (reading-light affordance); not shadowed by sleep (Reader ≠ fallback).
            Gesture.LONG_PRESS            to GlassAction.BrightnessUp,
            Gesture.LONG_PRESS_SWIPE_UP   to GlassAction.None,               // 长按上 → None (长按组合 off by default)
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
            enableTapSwipe = false,
            enableDoubleTapSwipe = false,
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

    /** Sprite camera context (`assistserver/.assist.media.page.CameraActivity` — see
     *  [RokidStrategies.openCamera]). Routes every gesture through the profile (useSystemKeyEvents
     *  = false) so the swipes can do **zoom** instead of DPAD nav — volume keys are the standard
     *  Android camera-zoom mapping. LONG_PRESS is free (user 2026-05-29 dropped video-record after
     *  the gesture didn't fit). TAP still ends up as DPAD_CENTER (Confirm → key(DPAD_CENTER)) so
     *  the shutter works. */
    val CAMERA = KeyMapProfile(
        id = "camera",
        name = "Camera",
        gestureConfig = GestureConfig(
            multiTapWindowMs = 220,
            comboWindowMs = 300,
            optimisticSingleTap = false,
            enableTapSwipe = true,         // 单击组合 ON
            enableDoubleTapSwipe = false,  // 双击组合 OFF by default
            awaitLongPressCombos = false,  // 长按组合 OFF by default
            longPressFollowupWindowMs = 40,
            // Route through the profile so SWIPE_UP/DOWN can be Volume (= zoom) instead of DPAD.
            useSystemKeyEvents = false,
        ),
        map = systemSlots + mapOf(
            Gesture.TAP                   to GlassAction.Confirm,        // shutter (Confirm → DPAD_CENTER)
            Gesture.DOUBLE_TAP            to GlassAction.Back,           // exit camera
            Gesture.TRIPLE_TAP            to GlassAction.None,           // unbound — reserved for user-custom
            Gesture.SWIPE_UP              to GlassAction.VolumeUp,       // 上滑 → zoom in (camera maps vol → zoom)
            Gesture.SWIPE_DOWN            to GlassAction.VolumeDown,     // 下滑 → zoom out
            Gesture.LONG_PRESS            to GlassAction.None,           // free (RecordVideo dropped 2026-05-29 — "手势不对")
            // TAP_SWIPE_UP/DOWN + DOUBLE_TAP_SWIPE_UP/DOWN left free for the wearer's custom bindings.
        ),
        // ModeManager matches a trigger against pkg.startsWith(trigger) OR activity.startsWith(trigger).
        // The assistserver package hosts more than just camera, so we match on the activity FQN.
        triggerPackages = listOf(
            "com.rokid.os.sprite.assist.media.page.CameraActivity",
        ),
    )

    // Fast profile dropped 2026-05-27 — base gestures are already instant system KeyEvents.
    val ALL = listOf(NAVIGATION, MEDIA, READER, CAMERA)

    /** Profile to fall back to when the foreground app matches none of the others'
     *  triggerPackages. The id (not the data) is exposed so `ModeManager` can resolve it after
     *  the user has customised profiles. */
    const val DEFAULT_FALLBACK_ID = "navigation"
}
