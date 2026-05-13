package com.halo.ring.device.rokid

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
 * Rokid Glasses (YodaOS-Sprite, Android 12, single right-eye display ≈ 480px wide).
 *
 * Input model (R08-Remote-Design.md §12.1): focus-based UI driven by **DPAD key events**.
 * The temple touch bar is translated to KEYCODE_DPAD_* at the system level, so we inject those.
 */

class RokidDisplayAdapter : DisplayAdapter {
    override val isBinocular = false
    override val contentWidthPx = 480
    override val contentHeightPx = 480
}

class RokidActionMapper(private val intents: FeatureIntents) : GlassActionMapper {

    override fun capabilityFor(action: GlassAction): Capability? = when (action) {
        // Navigation on Rokid = DPAD key events; satisfied by a KEY_EVENT-capable backend.
        GlassAction.NavPrev, GlassAction.NavNext, GlassAction.NavLeft, GlassAction.NavRight,
        GlassAction.Confirm -> Capability.NAVIGATE
        else -> null   // default in GlassAction.needs
    }

    override fun primitives(action: GlassAction): List<InjectionPrimitive> = when (action) {
        // ── navigation ──
        GlassAction.NavPrev    -> listOf(key(KeyEvent.KEYCODE_DPAD_UP))
        GlassAction.NavNext    -> listOf(key(KeyEvent.KEYCODE_DPAD_DOWN))
        GlassAction.NavLeft    -> listOf(key(KeyEvent.KEYCODE_DPAD_LEFT))
        GlassAction.NavRight   -> listOf(key(KeyEvent.KEYCODE_DPAD_RIGHT))
        GlassAction.Confirm    -> listOf(key(KeyEvent.KEYCODE_DPAD_CENTER))
        GlassAction.Back       -> listOf(InjectionPrimitive.A11yGlobal(A11yGlobalAction.BACK), key(KeyEvent.KEYCODE_BACK))
        GlassAction.Home       -> listOf(InjectionPrimitive.A11yGlobal(A11yGlobalAction.HOME))
        GlassAction.Recents    -> listOf(InjectionPrimitive.A11yGlobal(A11yGlobalAction.RECENTS))
        GlassAction.Notifications -> listOf(InjectionPrimitive.A11yGlobal(A11yGlobalAction.NOTIFICATIONS))
        GlassAction.QuickSettings -> listOf(InjectionPrimitive.A11yGlobal(A11yGlobalAction.QUICK_SETTINGS))
        GlassAction.Screenshot -> listOf(InjectionPrimitive.Shell("input keyevent 120"))  // KEYCODE_SYSRQ as alt path; a11y also exposes TAKE_SCREENSHOT (API 30+) — add to A11yGlobalAction later
        GlassAction.Menu       -> listOf(key(KeyEvent.KEYCODE_MENU))

        // ── volume / brightness / media ──
        GlassAction.VolumeUp   -> listOf(key(KeyEvent.KEYCODE_VOLUME_UP))
        GlassAction.VolumeDown -> listOf(key(KeyEvent.KEYCODE_VOLUME_DOWN))
        GlassAction.ToggleMute -> listOf(key(KeyEvent.KEYCODE_VOLUME_MUTE))
        GlassAction.BrightnessUp   -> listOf(key(KeyEvent.KEYCODE_BRIGHTNESS_UP))
        GlassAction.BrightnessDown -> listOf(key(KeyEvent.KEYCODE_BRIGHTNESS_DOWN))
        GlassAction.MediaPlayPause -> listOf(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        GlassAction.MediaPrev      -> listOf(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        GlassAction.MediaNext      -> listOf(key(KeyEvent.KEYCODE_MEDIA_NEXT))

        // ── feature intents (Sprite Launcher activities — see §12.1) ──
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
        GlassAction.ScreenSleep    -> listOf(key(KeyEvent.KEYCODE_SLEEP), InjectionPrimitive.A11yGlobal(A11yGlobalAction.LOCK_SCREEN))
        GlassAction.ScreenWake     -> listOf(key(KeyEvent.KEYCODE_WAKEUP))

        // ── handled in-app (no injection) ──
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

/** From rokid-docs `yodaos/docs/apps/sprite-launcher.md` (§12.1). */
class RokidFeatureIntents : FeatureIntents {
    private val launcher = "com.rokid.os.sprite.launcher"
    override fun openCamera() = listOf(InjectionPrimitive.StartActivity("$launcher/.page.camera.CameraPageActivity"))
    override fun takePhoto() = listOf(
        InjectionPrimitive.StartActivity("$launcher/.page.camera.CameraPageActivity"),
        InjectionPrimitive.Key(android.view.KeyEvent.KEYCODE_CAMERA),
    )
    override fun askVisualAI() = listOf(InjectionPrimitive.Broadcast("com.rokid.visualaidemo.ACTION_START"))
    override fun openTranslate() = listOf(InjectionPrimitive.StartActivity("$launcher/.page.translate.TranslatePageActivity"))
    override fun openChat() = listOf(InjectionPrimitive.StartActivity("$launcher/.page.chat.ChatPageActivity"))
    override fun openMusic() = listOf(InjectionPrimitive.StartActivity("$launcher/.page.music.MusicPageActivity"))
    override fun openSettings() = listOf(InjectionPrimitive.StartActivity("$launcher/.setting.SettingPageActivity"))
    override fun openGallery() = listOf(InjectionPrimitive.StartActivity("$launcher/.page.gallery.StorageImageShowActivity"))
    override fun launchApp(pkg: String) = listOf(
        InjectionPrimitive.Broadcast("$launcher.cmd", mapOf("cmd" to "open_app", "pkg" to pkg))
    )
}

class RokidWearStateProvider(private val ctx: Context) : WearStateProvider {
    // TODO: use ACTION_SCREEN_ON/OFF + Rokid's `RokidDoorReceiver` broadcast (if exposed) +
    //       proximity sensor. Until then assume worn whenever the screen is on.
    override fun isWorn(): Boolean = true
    override fun observe(onChange: (Boolean) -> Unit): () -> Unit { onChange(true); return {} }
}
