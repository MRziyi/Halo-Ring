package com.halo.ring.testplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Test-only helper: re-broadcasts as Halo Ring's PROFILE_PUSH / PROFILE_POP. Fired from the
 * harness like:
 *
 *   adb shell am broadcast -a com.halo.ring.testplugin.PUSH
 *   adb shell am broadcast -a com.halo.ring.testplugin.POP
 *
 * The point is that `am broadcast` runs as shell uid, and shell has every signature permission
 * including PUSH_PROFILE — so even though this plugin can't hold the permission directly, we
 * can still exercise the receiver-end of Doc/18 §6 end-to-end on a stock device.
 */
class PushProfileTestReceiver : BroadcastReceiver() {

    companion object {
        // Default test bindings used by the PUSH action.
        const val HUD_BINDINGS = """
            {
              "SWIPE_UP":   {"type":"external","package":"com.halo.ring.testplugin","action_id":"hud_focus_prev","label":"HUD prev"},
              "SWIPE_DOWN": {"type":"external","package":"com.halo.ring.testplugin","action_id":"hud_focus_next","label":"HUD next"},
              "TAP":        {"type":"external","package":"com.halo.ring.testplugin","action_id":"hud_activate","label":"HUD activate"},
              "DOUBLE_TAP": {"type":"external","package":"com.halo.ring.testplugin","action_id":"hud_dismiss","label":"HUD dismiss"}
            }
        """
    }

    override fun onReceive(context: Context, intent: Intent) {
        val out = when (intent.action) {
            "com.halo.ring.testplugin.PUSH" -> Intent("com.halo.ring.action.PROFILE_PUSH").apply {
                setPackage("com.halo.ring.rokid")  // also try com.halo.ring.rayneo on the other flavor
                putExtra("profile_id", "testplugin_hud")
                putExtra("owner_package", "com.halo.ring.testplugin")
                putExtra("bindings_json", HUD_BINDINGS.trimIndent())
            }
            "com.halo.ring.testplugin.POP" -> Intent("com.halo.ring.action.PROFILE_POP").apply {
                setPackage("com.halo.ring.rokid")
                putExtra("profile_id", "testplugin_hud")
                putExtra("owner_package", "com.halo.ring.testplugin")
            }
            else -> return
        }
        Log.i("HaloTestPlugin", "${intent.action} → ${out.action} → ${out.`package`}")
        // Send unprotected — the test harness has shell uid so it can fire any broadcast; Halo
        // Ring's receiver enforces the PUSH_PROFILE permission on its side. From a third-party
        // app we'd fail the permission check; via `am broadcast` we're OK.
        try {
            context.sendBroadcast(out)
        } catch (e: SecurityException) {
            Log.w("HaloTestPlugin", "sendBroadcast denied: ${e.message}")
        }
    }
}
