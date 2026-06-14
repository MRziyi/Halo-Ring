package com.halo.ring.device.rayneo

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.inject.ExecutorBackend

/**
 * RayNeo-only app-level backend for the actions that map to `Capability.KEY_EVENT` — volume,
 * media-transport and brightness. On RayNeo X3 Pro the app_process agent can't bootstrap (AIOS
 * SELinux), so nothing can inject raw KeyEvents; but these particular "keys" all have first-party
 * Android APIs that need no shell:
 *
 *  - Volume / mute  → [AudioManager.adjustStreamVolume] with `FLAG_SHOW_UI` (system volume bar).
 *  - Media keys     → [AudioManager.dispatchMediaKeyEvent] (routes to the active MediaSession).
 *  - Brightness     → `Settings.System.SCREEN_BRIGHTNESS` (verified working via shell; needs the
 *                     WRITE_SETTINGS app-op — best-effort, fails gracefully if not granted).
 *
 * Reports [Capability.KEY_EVENT] so the router sends those actions here; for KEY_EVENT actions it
 * doesn't implement (e.g. Menu) it returns false and the router falls through (no worse than today,
 * where nothing handled them). Rokid never constructs this backend — it injects keys via the agent.
 */
class RayNeoSystemBackend(private val context: Context) : ExecutorBackend {

    override val id = "rayneo-system"
    // Above Accessibility (80); the agent (100) reports no capabilities on RayNeo so this wins for
    // KEY_EVENT actions in practice.
    override val priority = 95

    // KEY_EVENT → volume/media/brightness; LAUNCH_INTENT → first-party app launches (both have
    // first-party Android APIs needing no shell, which RayNeo lacks).
    override fun capabilities(): Set<Capability> = setOf(Capability.KEY_EVENT, Capability.LAUNCH_INTENT)

    override fun isReady(): Boolean = true

    private val audio: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override suspend fun perform(action: GlassAction): Boolean = when (action) {
        GlassAction.VolumeUp   -> adjustVolume(AudioManager.ADJUST_RAISE)
        GlassAction.VolumeDown -> adjustVolume(AudioManager.ADJUST_LOWER)
        GlassAction.ToggleMute -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
        GlassAction.MediaPlayPause -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        GlassAction.MediaNext      -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        GlassAction.MediaPrev      -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        GlassAction.BrightnessUp   -> adjustBrightness(+BRIGHTNESS_STEP)
        GlassAction.BrightnessDown -> adjustBrightness(-BRIGHTNESS_STEP)
        // ── first-party app launches (package names verified on ARGF20 2026-06-14) ──
        GlassAction.OpenCamera   -> launch(PKG_CAMERA)
        GlassAction.OpenGallery  -> launch(PKG_GALLERY)
        GlassAction.OpenMusic    -> launch(PKG_MUSIC)
        GlassAction.OpenTranslate -> launch(PKG_TRANSLATE)
        GlassAction.OpenSubtitle -> launch(PKG_WORDPROMPT)
        // RayNeo's Live-AI app is the camera-grounded visual assistant + chat entry point.
        GlassAction.AskVisualAI, GlassAction.OpenChat,
        GlassAction.OpenAIAssistant, GlassAction.WakeSystemAI -> launch(PKG_LIVE_AI)
        GlassAction.OpenSettings -> launch(PKG_SETTINGS)
        is GlassAction.LaunchApp -> launch(action.pkg)
        else -> false   // not ours → router falls through
    }

    private fun launch(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: run {
                Log.w(TAG, "no launch intent for $pkg"); return false
            }
        return try {
            context.startActivity(intent); true
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity($pkg) failed: ${t.message}"); false
        }
    }

    private fun adjustVolume(direction: Int): Boolean {
        val am = audio ?: return false
        return try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "adjustStreamVolume failed: ${t.message}"); false
        }
    }

    private fun mediaKey(keycode: Int): Boolean {
        val am = audio ?: return false
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keycode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keycode))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchMediaKeyEvent failed: ${t.message}"); false
        }
    }

    private fun adjustBrightness(delta: Int): Boolean {
        val cr = context.contentResolver
        return try {
            // Manual mode so a fixed value sticks (auto-brightness would override it).
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS,
                (cur + delta).coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX))
            true
        } catch (t: Throwable) {
            // SecurityException if WRITE_SETTINGS isn't granted — degrade quietly.
            Log.w(TAG, "brightness write failed (WRITE_SETTINGS?): ${t.message}"); false
        }
    }

    private companion object {
        const val TAG = "RayNeoSysBackend"
        const val BRIGHTNESS_STEP = 32   // ~12% of 0-255 per press
        const val BRIGHTNESS_MIN = 10    // never fully dark (the panel can't recover via touch)
        const val BRIGHTNESS_MAX = 255
        // First-party RayNeo X3 Pro packages (each verified to launch via LAUNCHER on ARGF20).
        const val PKG_CAMERA = "com.leiniao.camera"
        const val PKG_GALLERY = "com.rayneo.gallery"
        const val PKG_MUSIC = "com.rayneo.media"
        const val PKG_TRANSLATE = "com.ffalcon.translate"
        const val PKG_WORDPROMPT = "com.ffalcon.wordprompt"   // teleprompter (closest to "subtitle")
        const val PKG_LIVE_AI = "com.rayneo.live.ai"          // visual AI / assistant / chat
        const val PKG_SETTINGS = "com.android.settings"
    }
}
