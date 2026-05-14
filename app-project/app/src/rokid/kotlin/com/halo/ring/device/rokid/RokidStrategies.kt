package com.halo.ring.device.rokid

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
        // Audit-2026-05-13k: KEYCODE_SYSRQ (120) is NOT a screenshot key on Android — it was a Linux
        // print-screen vestige. Real path: AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT (API 30+,
        // handled by AccessibilityBackend.toGlobalAction). Rokid Glasses run Android 12 / API 32, so
        // this is universally available. Shell fallback `screencap` writes to a file but doesn't
        // surface a UI confirmation; treat as last resort.
        GlassAction.Screenshot -> listOf(
            InjectionPrimitive.A11yGlobal(A11yGlobalAction.TAKE_SCREENSHOT),
            InjectionPrimitive.Shell("screencap -p /sdcard/halo-screenshot.png"),
        )
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

/**
 * Rokid wear-state. Three signals ordered by confidence:
 *
 *  1. **System property `vendor.rkd.glasses.is_take_on`** (highest confidence) — direct read of
 *     the same value Rokid's own `com.rokid.hardware.DeviceUtils.isGlassTakeOn()` returns.
 *     Source: `research/rokid-docs/yodaos/docs/apps/sys-config.md`. Polled at 30 s by
 *     [RokidSysPropWearPoller].
 *  2. **TYPE_PROXIMITY sensor** ("psensor" forehead, standard `SensorManager`). Supplementary.
 *  3. **Screen on/off heuristic** ([ScreenWearProxy]) — fallback. 30-min screen-off ⇒ not worn.
 *
 * Higher-confidence signals call [ScreenWearProxy.overrideWorn] which supersedes the heuristic.
 */
class RokidWearStateProvider(private val ctx: Context) : WearStateProvider {
    private val proxy = ScreenWearProxy(ctx)
    private val proximity = ProximityWearSource(ctx, proxy)
    private val sysProp = RokidSysPropWearPoller(proxy)

    init {
        proximity.start()
        sysProp.start()
    }

    override fun isWorn(): Boolean = proxy.isWorn()
    override fun observe(onChange: (Boolean) -> Unit): () -> Unit = proxy.observe(onChange)
}

/**
 * Polls `SystemProperties.get("vendor.rkd.glasses.is_take_on")` every 30 s. The property is
 * vendor-readable (no root needed); same value Rokid's own privileged services use to drive
 * `glassTakeOnChange(...)` callbacks.
 *
 * Reads "1" → worn, "0" → not worn, anything else (empty / class missing on non-Rokid build) →
 * inert. [SystemProperties] is a hidden-API class but app-readable via reflection.
 */
private class RokidSysPropWearPoller(private val proxy: ScreenWearProxy) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sysPropGet: java.lang.reflect.Method? = null
    @Volatile private var lastReported: Boolean? = null

    fun start() {
        try {
            val cls = Class.forName("android.os.SystemProperties")
            sysPropGet = cls.getMethod("get", String::class.java)
            // Probe once at startup to confirm the property exists; if absent, don't burn cycles polling.
            val initial = read()
            if (initial == null) {
                Log.i(TAG, "Rokid wear property unavailable on this device — fallback to ScreenWearProxy.")
                return
            }
            Log.i(TAG, "Rokid wear property reads '$initial' — polling every ${POLL_INTERVAL_MS / 1000}s.")
            proxy.overrideWorn(initial == "1")
            lastReported = initial == "1"
            scheduleNext()
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "SystemProperties reflection failed: ${e.message}")
        }
    }

    private fun read(): String? {
        val m = sysPropGet ?: return null
        val v = try { m.invoke(null, PROP_NAME) as? String } catch (_: ReflectiveOperationException) { null }
        return if (v.isNullOrEmpty()) null else v
    }

    private fun scheduleNext() {
        handler.postDelayed({
            val v = read()
            if (v != null) {
                val worn = v == "1"
                if (worn != lastReported) {
                    lastReported = worn
                    proxy.overrideWorn(worn)
                }
            }
            scheduleNext()
        }, POLL_INTERVAL_MS)
    }

    companion object {
        private const val TAG = "RokidSysPropWearPoller"
        private const val PROP_NAME = "vendor.rkd.glasses.is_take_on"
        /** 30 s — wear changes are slow human events; polling faster wastes CPU. */
        private const val POLL_INTERVAL_MS = 30_000L
    }
}

