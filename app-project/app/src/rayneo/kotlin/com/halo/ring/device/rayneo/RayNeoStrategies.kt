package com.halo.ring.device.rayneo

import android.content.Context
import android.view.KeyEvent
import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.device.A11yGlobalAction
import com.halo.ring.core.device.DisplayAdapter
import com.halo.ring.core.device.FeatureIntents
import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.device.InjectionPrimitive
import com.halo.ring.core.device.WearStateProvider

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
        else -> null
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
        GlassAction.Home      -> listOf(InjectionPrimitive.A11yGlobal(A11yGlobalAction.HOME))
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
        GlassAction.TakePhoto      -> intents.takePhoto()
        GlassAction.AskVisualAI    -> intents.askVisualAI()
        GlassAction.OpenTranslate  -> intents.openTranslate()
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

        // Modal sentinels are consumed inside InteractionRouter; never reach the action mapper.
        is com.halo.ring.core.action.ModalSentinel -> emptyList()
    }

    private fun key(kc: Int) = InjectionPrimitive.Key(kc)
}

/**
 * Placeholder. RayNeo X3 Pro's launcher / camera / AI Intent strings aren't publicly documented
 * (no public system decompilation like rokid-docs). Fill these in by:
 *
 *   adb shell pm list packages | grep -iv 'android\|google\|qualcomm'
 *   adb shell dumpsys activity top    # while navigating the system UI
 *
 * See §17.5 / §18.7. As-is, falls back to `monkey` (which uses LAUNCHER intent-filter resolution).
 */
class RayNeoFeatureIntents : FeatureIntents {
    override fun openCamera()    = emptyList<InjectionPrimitive>()
    override fun takePhoto()     = listOf(InjectionPrimitive.Key(KeyEvent.KEYCODE_CAMERA))
    override fun askVisualAI()   = emptyList<InjectionPrimitive>()
    override fun openTranslate() = emptyList<InjectionPrimitive>()
    override fun openChat()      = emptyList<InjectionPrimitive>()
    override fun openMusic()     = emptyList<InjectionPrimitive>()
    override fun openSettings()  = listOf(InjectionPrimitive.Shell("am start -a android.settings.SETTINGS"))
    override fun openGallery()   = emptyList<InjectionPrimitive>()
    override fun launchApp(pkg: String) = listOf(
        InjectionPrimitive.Shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
    )
}

class RayNeoWearStateProvider(private val ctx: Context) : WearStateProvider {
    // TODO: subscribe to the RayNeo ARSDK 佩戴检测 module if the AAR is available; otherwise
    //       fall back to ACTION_SCREEN_ON/OFF as a proxy.
    override fun isWorn(): Boolean = true
    override fun observe(onChange: (Boolean) -> Unit): () -> Unit { onChange(true); return {} }
}
