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

    /**
     * Rokid-specific honesty overrides (audit-pass-x+1):
     *
     *  - `EnterAIDictateModal`: the `AIDictateModal` is a skeleton — `onEnter` opens the
     *    Sprite Chat page (real), but the in-modal `TAP` doesn't drive a mic-capture pipeline
     *    (Doc/05 §6 audio integration is deferred). Mark UNSUPPORTED so users don't bind it
     *    expecting dictation and get "tap re-launches chat".
     *  - `AskVisualAI`: targets `com.rokid.visualaidemo.ACTION_START` — visualaidemo is a
     *    third-party reference APK from Rokid, not always pre-installed. Mark BEST_EFFORT.
     */
    override fun supportLevel(action: GlassAction): GlassActionMapper.SupportLevel = when (action) {
        GlassAction.EnterAIDictateModal -> GlassActionMapper.SupportLevel.UNSUPPORTED
        GlassAction.AskVisualAI         -> GlassActionMapper.SupportLevel.BEST_EFFORT
        else                            -> super.supportLevel(action)
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

        // ── external-plugin actions (Doc/18) ──
        // Dispatched out-of-band by [HaloRingService] via [com.halo.ring.plugin.PluginTrigger];
        // never reach the executor backend. Same pattern as PeekHud — intercepted upstream.
        is GlassAction.PluginAction -> emptyList()

        // Modal sentinels are consumed inside InteractionRouter; never reach the action mapper.
        is com.halo.ring.core.action.ModalSentinel -> emptyList()
    }

    private fun key(kc: Int) = InjectionPrimitive.Key(kc)
}

/** From rokid-docs `yodaos/docs/apps/sprite-launcher.md` (§12.1). */
class RokidFeatureIntents : FeatureIntents {
    private val launcher = "com.rokid.os.sprite.launcher"

    /**
     * Open the **Rokid Sprite** camera (user wants Rokid's, not AOSP Camera2).
     * ⚠️ On-device caveat (2026-05-28): launching `CameraPageActivity` by component bounces back to
     * the Sprite home in all external-launch tests (am start ±flags, launcher cmd broadcast) — the
     * Sprite camera's proper entry is the app-grid or a companion-phone BLE scene command, neither
     * reachable from our app. If it bounces in real use too, the only camera that *stays* is AOSP
     * Camera2 (`am start -a android.media.action.STILL_IMAGE_CAMERA`) — swap back to that if needed.
     */
    override fun openCamera() = listOf(InjectionPrimitive.StartActivity("$launcher/.page.camera.CameraPageActivity"))

    /**
     * The camera ignores synthetic key/touch injection (only the real hardware shutter captures) and
     * there's no exported headless-capture intent, so a gesture can't snap a photo directly.
     * `takePhoto` therefore just **opens the camera** (same as [openCamera]); the Camera profile
     * auto-activates (HUD shows "Camera") and the wearer shutters with the temple/hardware key.
     */
    override fun takePhoto() = openCamera()
    /** Everyday voice/chat AI — Rokid Sprite's ChatPageActivity. Distinct from visual AI which
     *  takes a camera frame as context (`askVisualAI`). Audit-pass 2026-05-14w. */
    override fun openAIAssistant() = listOf(InjectionPrimitive.StartActivity("$launcher/.page.chat.ChatPageActivity"))
    /** The glasses' **native** AI agent — same as the system's long-press-temple wake. Sends the
     *  Sprite AI start broadcast (`bare-metal-docs/01-key-events.md`: ACTION_AI_START). */
    override fun wakeSystemAI() = listOf(InjectionPrimitive.Broadcast("com.android.action.ACTION_AI_START"))
    override fun askVisualAI() = listOf(InjectionPrimitive.Broadcast("com.rokid.visualaidemo.ACTION_START"))
    override fun openTranslate() = listOf(InjectionPrimitive.StartActivity("$launcher/.page.translate.TranslatePageActivity"))
    /** Rokid 字幕 / live caption — Sprite AccessibilityPageActivity (verified: launches + stays). */
    override fun openSubtitle() = listOf(InjectionPrimitive.StartActivity("$launcher/.page.accessibility.AccessibilityPageActivity"))
    /** Sprite Chat page — same as `openAIAssistant()`. Kept distinct so user-bindable bindings can
     *  reference the "open the chat UI explicitly" semantic vs the more abstract "wake AI" one. */
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
        // The `is_take_on` sysprop is Rokid's authoritative wear signal. The forehead
        // TYPE_PROXIMITY sensor proved actively WRONG on this unit: it reads "far" while the
        // glasses ARE worn, so it kept calling overrideWorn(false). Because both sources write the
        // single override slot in [ScreenWearProxy] (last-writer-wins, NOT confidence-ordered as the
        // kdoc implied), the bogus proximity value raced past the correct sysprop '1' and forced
        // worn=false — which made PowerPolicy tear down the ring link within seconds of every
        // connect (the "连上就断 / 一直 connecting" bug, on-device 2026-05-29). So only fall back to
        // the proximity sensor when the sysprop is genuinely unavailable.
        val sysPropActive = sysProp.start()
        if (!sysPropActive) {
            Log.i(TAG, "is_take_on sysprop unavailable — falling back to proximity sensor for wear")
            proximity.start()
        }
    }

    override fun isWorn(): Boolean = proxy.isWorn()
    override fun observe(onChange: (Boolean) -> Unit): () -> Unit = proxy.observe(onChange)

    private companion object { private const val TAG = "RokidWear" }
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

    /** @return true if the sysprop is present + now driving wear overrides; false if unavailable
     *  (caller should fall back to a supplementary signal). */
    fun start(): Boolean {
        try {
            val cls = Class.forName("android.os.SystemProperties")
            sysPropGet = cls.getMethod("get", String::class.java)
            // Probe once at startup to confirm the property exists; if absent, don't burn cycles polling.
            val initial = read()
            if (initial == null) {
                Log.i(TAG, "Rokid wear property unavailable on this device — fallback to ScreenWearProxy.")
                return false
            }
            Log.i(TAG, "Rokid wear property reads '$initial' — polling every ${POLL_INTERVAL_MS / 1000}s.")
            proxy.overrideWorn(initial == "1")
            lastReported = initial == "1"
            scheduleNext()
            return true
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "SystemProperties reflection failed: ${e.message}")
            return false
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

