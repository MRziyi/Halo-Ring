package com.halo.ring.device.rayneo

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.device.A11yGlobalAction
import com.halo.ring.core.device.DisplayAdapter
import com.halo.ring.core.device.FeatureIntents
import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.device.InjectionPrimitive
import com.halo.ring.core.device.WearStateProvider
import com.halo.ring.device.ProximityWearSource
import com.halo.ring.device.ScreenWearProxy

/**
 * RayNeo X3 Pro (RayNeo AIOS 2.0, Android 12+, dual-eye 1280×480 MicroLED).
 *
 * Input model (R08-Remote-Design.md §17.2): temple touchpad delivers raw `MotionEvent`s to the
 * focused app, and the system UI's `TouchDispatcher` (ARSDK `TempleAction` pipeline) does its own
 * gesture detection + focus management. So navigation = **swipe MotionEvents**, not DPAD key
 * events. The X3 Pro *might* also accept DPAD keys (focus model is the same); verify on device.
 */

class RayNeoDisplayAdapter : DisplayAdapter {
    override val isBinocular = true
    override val contentWidthPx = 640
    override val contentHeightPx = 480
}

class RayNeoActionMapper(private val intents: FeatureIntents) : GlassActionMapper {

    override fun capabilityFor(action: GlassAction): Capability? = when (action) {
        // X3 Pro navigation = swipe MotionEvent → TAP_SWIPE-capable backend.
        GlassAction.NavPrev, GlassAction.NavNext, GlassAction.NavLeft, GlassAction.NavRight,
        GlassAction.Confirm -> Capability.TAP_SWIPE
        // RayNeo can't inject KeyEvents (no agent), so re-route the KEY_EVENT defaults that have an
        // accessibility-global equivalent onto the a11y backend:
        //  - Home → GLOBAL_ACTION_HOME   - ScreenSleep → GLOBAL_ACTION_LOCK_SCREEN
        // (NOTIFICATIONS is the project's "a11y can do this global" capability, reused here like
        // Screenshot/QuickSettings do.) Volume/brightness/media stay KEY_EVENT → RayNeoSystemBackend.
        GlassAction.Home        -> Capability.HOME
        GlassAction.ScreenSleep -> Capability.NOTIFICATIONS
        else -> null
    }

    /**
     * RayNeo-specific honesty overrides (audit-pass-x+1):
     *
     *  - `EnterAIDictateModal`: same as Rokid — modal is a skeleton (no mic pipeline). UNSUPPORTED.
     *  - `OpenAIAssistant`: best-effort `am start -a VOICE_SEARCH_HANDS_FREE`; may fall through
     *    to RayNeo's bundled assistant on AIOS 2.0 but unverified. BEST_EFFORT (kdoc on
     *    [RayNeoFeatureIntents.openAIAssistant] explains the trade-off).
     *  - `OpenCamera` / `OpenMusic` / `OpenGallery`: standard AOSP Intents (`STILL_IMAGE_CAMERA`,
     *    `MEDIA_PLAY_FROM_SEARCH`, `VIEW image/star`). They open something on every Android device,
     *    but on RayNeo X3 Pro that "something" may be the system chooser rather than the
     *    first-party RayNeo Camera/Music/Gallery apps. BEST_EFFORT until on-device verification
     *    discovers the real package names (Doc/11 §B6).
     *  - `Screenshot`: uses A11yGlobal `TAKE_SCREENSHOT` (API 30+) which works on stock Android
     *    but RayNeo AIOS may suppress accessibility service screenshot privileges per their
     *    "no third-party screenshot" policy (typical of OEM-customised launchers). BEST_EFFORT.
     */
    override fun supportLevel(action: GlassAction): GlassActionMapper.SupportLevel = when (action) {
        // Modal is a skeleton (no mic pipeline) on both flavors.
        GlassAction.EnterAIDictateModal -> GlassActionMapper.SupportLevel.UNSUPPORTED
        // First-party launches (camera/gallery/music/translate/subtitle/live-AI) all verified to
        // launch on ARGF20 2026-06-14 via RayNeoSystemBackend → fully supported (no longer best-
        // effort). Screenshot works via the a11y global. Volume/brightness/media via the backend.
        else                            -> super.supportLevel(action)
    }

    override fun primitives(action: GlassAction): List<InjectionPrimitive> = when (action) {
        // ── navigation (swipe MotionEvents) ──
        // The X3 Pro temple is horizontal; the system maps forward/back swipes to focus prev/next.
        // We inject short swipes (60ms — minimum that the gesture detector accepts; tune on device).
        GlassAction.NavPrev   -> listOf(InjectionPrimitive.Swipe(x1 = 400, y1 = 240, x2 = 240, y2 = 240, durationMs = 60))
        GlassAction.NavNext   -> listOf(InjectionPrimitive.Swipe(x1 = 240, y1 = 240, x2 = 400, y2 = 240, durationMs = 60))
        GlassAction.NavLeft   -> listOf(InjectionPrimitive.Swipe(x1 = 240, y1 = 200, x2 = 240, y2 = 280, durationMs = 60))
        GlassAction.NavRight  -> listOf(InjectionPrimitive.Swipe(x1 = 240, y1 = 280, x2 = 240, y2 = 200, durationMs = 60))
        GlassAction.Confirm   -> listOf(InjectionPrimitive.Tap(x = 320, y = 240))
        GlassAction.Back      -> listOf(
            InjectionPrimitive.A11yGlobal(A11yGlobalAction.BACK),
            key(KeyEvent.KEYCODE_BACK),
        )
        // RayNeo: no KeyEvent injection → use the accessibility HOME global (Key kept as a no-op
        // fallback for any future shell-capable backend).
        GlassAction.Home      -> listOf(
            InjectionPrimitive.A11yGlobal(A11yGlobalAction.HOME),
            key(KeyEvent.KEYCODE_HOME),
        )
        GlassAction.Recents   -> listOf(InjectionPrimitive.A11yGlobal(A11yGlobalAction.RECENTS))
        GlassAction.Notifications -> listOf(InjectionPrimitive.A11yGlobal(A11yGlobalAction.NOTIFICATIONS))
        GlassAction.QuickSettings -> listOf(InjectionPrimitive.A11yGlobal(A11yGlobalAction.QUICK_SETTINGS))
        GlassAction.Screenshot -> listOf(InjectionPrimitive.A11yGlobal(A11yGlobalAction.TAKE_SCREENSHOT))
        GlassAction.Menu      -> listOf(key(KeyEvent.KEYCODE_MENU))

        // ── volume / brightness / media ──
        GlassAction.VolumeUp   -> listOf(key(KeyEvent.KEYCODE_VOLUME_UP))
        GlassAction.VolumeDown -> listOf(key(KeyEvent.KEYCODE_VOLUME_DOWN))
        GlassAction.ToggleMute -> listOf(key(KeyEvent.KEYCODE_VOLUME_MUTE))
        GlassAction.BrightnessUp   -> listOf(key(KeyEvent.KEYCODE_BRIGHTNESS_UP))
        GlassAction.BrightnessDown -> listOf(key(KeyEvent.KEYCODE_BRIGHTNESS_DOWN))
        GlassAction.MediaPlayPause -> listOf(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        GlassAction.MediaPrev      -> listOf(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        GlassAction.MediaNext      -> listOf(key(KeyEvent.KEYCODE_MEDIA_NEXT))

        // ── feature intents (TBD by on-device discovery) ──
        GlassAction.OpenCamera     -> intents.openCamera()
        GlassAction.OpenAIAssistant -> intents.openAIAssistant()
        GlassAction.WakeSystemAI   -> intents.wakeSystemAI()
        GlassAction.AskVisualAI    -> intents.askVisualAI()
        GlassAction.OpenTranslate  -> intents.openTranslate()
        GlassAction.OpenSubtitle   -> intents.openSubtitle()
        GlassAction.OpenChat       -> intents.openChat()
        GlassAction.OpenMusic      -> intents.openMusic()
        GlassAction.OpenSettings   -> intents.openSettings()
        GlassAction.OpenGallery    -> intents.openGallery()
        is GlassAction.LaunchApp   -> intents.launchApp(action.pkg)

        // ── system-level (screen / power) ──
        GlassAction.ScreenSleep    -> listOf(
            key(KeyEvent.KEYCODE_SLEEP),
            InjectionPrimitive.A11yGlobal(A11yGlobalAction.LOCK_SCREEN),
        )
        GlassAction.ScreenWake     -> listOf(key(KeyEvent.KEYCODE_WAKEUP))

        // ── handled in-app ──
        GlassAction.PeekHud,
        GlassAction.ProfileCycle,
        GlassAction.ForceReconnect,
        GlassAction.EnterVolumeModal,
        GlassAction.EnterBrightnessModal,
        GlassAction.EnterRecentsModal,
        GlassAction.EnterAIDictateModal,
        GlassAction.None -> emptyList()

        is GlassAction.Shell -> listOf(InjectionPrimitive.Shell(action.cmd))

        // External-plugin actions (Doc/18) — dispatched out-of-band by HaloRingService via
        // PluginTrigger; never reach the executor backend.
        is GlassAction.PluginAction -> emptyList()

        // Modal sentinels are consumed inside InteractionRouter; never reach the action mapper.
        is com.halo.ring.core.action.ModalSentinel -> emptyList()
    }

    private fun key(kc: Int) = InjectionPrimitive.Key(kc)
}

/**
 * X3 Pro Intent map. The launcher-specific Activities for RayNeo's first-party apps (Camera, AI,
 * Translate) aren't publicly documented — see Doc/11 §B6 for the on-device discovery recipe.
 * What we CAN do today is use standard Android Intents that work on any AOSP 12 device. For
 * features without a standard Intent (visual AI / on-glasses chat / translate), the best we can
 * do is `monkey -p <pkg>` once we know the package name; until then they remain empty so the
 * `ActionRouter` falls through to the next backend instead of silently launching the wrong app.
 *
 * Standard-Android Intents below verified against AOSP 12 sources:
 *  - `android.media.action.STILL_IMAGE_CAMERA`            → opens the system camera (any one)
 *  - `android.media.action.IMAGE_CAPTURE`                 → camera in capture mode
 *  - `android.settings.SETTINGS`                          → system Settings
 *  - `android.intent.action.VIEW` + MIME image-star         → gallery (delegated to user choice)
 *  - `android.intent.action.MUSIC_PLAYER` / `android.media.action.MEDIA_PLAY_FROM_SEARCH` → music
 *
 * RayNeo's first-party apps register under category `com.rayneo.mercury.app` (verified from the
 * RayDesk reference repo's `AndroidManifest.xml`). `launchApp` therefore tries that category
 * before falling back to the standard LAUNCHER one.
 */
class RayNeoFeatureIntents : FeatureIntents {
    // Feature actions resolve to first-party RayNeo packages (verified to launch on ARGF20
    // 2026-06-14). On RayNeo these are actually executed by RayNeoSystemBackend via
    // context.startActivity (no shell on RayNeo); the Shell primitives below keep the action
    // bindable for any future shell-capable backend + make the default supportLevel report SUPPORTED.
    override fun openCamera()    = launchApp("com.leiniao.camera")
    override fun askVisualAI()   = launchApp("com.rayneo.live.ai")    // camera-grounded visual AI
    override fun openTranslate() = launchApp("com.ffalcon.translate")
    override fun openSubtitle()  = launchApp("com.ffalcon.wordprompt") // teleprompter (closest)
    override fun openChat()      = launchApp("com.rayneo.live.ai")
    override fun openAIAssistant() = launchApp("com.rayneo.live.ai")
    override fun wakeSystemAI()  = launchApp("com.rayneo.live.ai")
    override fun openMusic()     = launchApp("com.rayneo.media")
    override fun openSettings() = listOf(
        InjectionPrimitive.Shell("am start -a android.settings.SETTINGS"),
    )
    override fun openGallery()   = launchApp("com.rayneo.gallery")
    override fun launchApp(pkg: String) = listOf(
        // Try RayNeo's AppLab-specific category first (their Mercury-SDK apps register here),
        // then standard Android LAUNCHER. `monkey` picks the first match in either case.
        InjectionPrimitive.Shell("monkey -p $pkg -c com.rayneo.mercury.app 1 || monkey -p $pkg -c android.intent.category.LAUNCHER 1"),
    )
}

/**
 * RayNeo wear-state. Three signals ordered by confidence:
 *
 *  1. **Mercury SDK `MobileState.isWearing()`** (since AAR v0.2.6 per
 *     https://rayneo.gitbook.io/rayneo-devdoc/x-xi-lie/android-kai-fa/neng-li-jie-shao/pei-dai-jian-ce).
 *     Synchronous `Boolean` read backed by a ContentProvider IPC. We poll it via [MercuryWearPoller]
 *     at low frequency (~30 s) since the API doesn't expose a Flow / listener.
 *  2. **TYPE_PROXIMITY sensor** — supplementary; near/far → worn/not.
 *  3. **Screen on/off heuristic** ([ScreenWearProxy]) — fallback.
 *
 * Higher-confidence signals call [ScreenWearProxy.overrideWorn] which supersedes the heuristic.
 */
class RayNeoWearStateProvider(private val ctx: Context) : WearStateProvider {
    private val proxy = ScreenWearProxy(ctx)
    private val proximity = ProximityWearSource(ctx, proxy)
    private val mercury = MercuryWearPoller(proxy)

    init {
        proximity.start()
        mercury.start()
    }

    override fun isWorn(): Boolean = proxy.isWorn()
    override fun observe(onChange: (Boolean) -> Unit): () -> Unit = proxy.observe(onChange)
}

/**
 * Polls `com.ffalcon.mercury.android.sdk.api.MobileState.isWearing(): Boolean` on a background
 * thread every [POLL_INTERVAL_MS] and pushes changes into [ScreenWearProxy].
 *
 * Loaded via reflection so this rayneo flavor still builds + runs even if the bundled AAR is
 * older than v0.2.6 (class absent → poller stays inert, ScreenWearProxy alone drives the signal).
 *
 * The poll cost is negligible: one ContentProvider call every 30 s = a few μs of work, and
 * wear-state transitions are slow human events (minutes-scale), so 30 s grain is more than
 * enough. If a Flow-based API ships in a future AAR, swap to that and remove the poller.
 */
private class MercuryWearPoller(private val proxy: ScreenWearProxy) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isWearingMethod: java.lang.reflect.Method? = null
    private var instance: Any? = null
    @Volatile private var lastReported: Boolean? = null

    fun start() {
        try {
            val cls = Class.forName(MOBILE_STATE_CLASS)
            instance = cls.getField("INSTANCE").get(null)
            isWearingMethod = cls.getMethod("isWearing")
            Log.i(TAG, "Mercury MobileState.isWearing reflective handle resolved — wear-detection live.")
            scheduleNext()
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "Mercury MobileState not present (AAR < v0.2.6) — falling back to ScreenWearProxy only.")
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Mercury MobileState reflection failed: ${e.message}")
        }
    }

    private fun scheduleNext() {
        handler.postDelayed({ pollOnce() }, POLL_INTERVAL_MS)
    }

    private fun pollOnce() {
        // Run on a worker thread — ContentProvider.call is sync IPC and we don't want to stall
        // the main looper if the provider is slow.
        Thread({
            val worn = try {
                isWearingMethod?.invoke(instance) as? Boolean
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, "MobileState.isWearing() reflection threw: ${e.message}")
                null
            }
            if (worn != null && worn != lastReported) {
                lastReported = worn
                proxy.overrideWorn(worn)
            }
            handler.post { scheduleNext() }
        }, "halo-mercury-wear-poll").start()
    }

    companion object {
        private const val TAG = "MercuryWearPoller"
        private const val MOBILE_STATE_CLASS = "com.ffalcon.mercury.android.sdk.api.MobileState"
        /** 30 s — wear-state changes are slow human events; polling faster wastes CPU + ContentProvider IPC. */
        private const val POLL_INTERVAL_MS = 30_000L
    }
}
