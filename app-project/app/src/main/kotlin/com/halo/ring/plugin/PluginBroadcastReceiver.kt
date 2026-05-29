package com.halo.ring.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.halo.ring.core.plugin.OverlayController

/**
 * Receives Doc/18 §7 overlay activate/deactivate broadcasts from external plugin apps and drives the
 * [OverlayController]. Registered programmatically by `HaloRingService.onCreate` (not the manifest)
 * because it needs a live reference to the service's controller + scheduler thread.
 *
 * ## Protocol (Doc/18 §7)
 *  - `com.halo.ring.action.OVERLAY_ACTIVATE`  extras: `owner_package`, `profile_id`, (opt `display_name`)
 *    → the plugin's HUD is up; it now owns the ring exclusively. Re-send while up as a keepalive.
 *  - `com.halo.ring.action.OVERLAY_DEACTIVATE` extras: `owner_package`, `profile_id`
 *    → HUD closed; release the ring back to the underlying profile.
 *
 * Halo Ring forwards each captured gesture back to the owner via `com.halo.ring.action.OVERLAY_GESTURE`
 * (extra `gesture` = the raw [com.halo.ring.core.gesture.Gesture] name). The plugin assigns meaning.
 *
 * Back-compat: the legacy `PROFILE_PUSH` / `PROFILE_POP` (the old binding-stack protocol) are mapped
 * onto activate/deactivate so an un-updated plugin still toggles the overlay (its bindings_json is
 * ignored — gestures are forwarded raw now).
 *
 * Permission: registered with `RECEIVER_EXPORTED` + gated by `com.halo.ring.permission.PUSH_PROFILE`
 * (signature|privileged) so only trusted apps can take over the ring.
 */
class PluginBroadcastReceiver(
    private val overlay: OverlayController,
    private val pm: PackageManager,
    /** Posted on every mutation. The lambda marshals to the scheduler thread before touching state
     *  (HUD, foreground-inference freeze, etc.). `activated` = true for a new activation. */
    private val onOverlayChanged: (activated: Boolean) -> Unit,
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "PluginBroadcast"
        const val ACTION_OVERLAY_ACTIVATE   = "com.halo.ring.action.OVERLAY_ACTIVATE"
        const val ACTION_OVERLAY_DEACTIVATE = "com.halo.ring.action.OVERLAY_DEACTIVATE"
        /** Outgoing: Halo Ring → plugin, one per captured gesture while the overlay is active. */
        const val ACTION_OVERLAY_GESTURE    = "com.halo.ring.action.OVERLAY_GESTURE"
        // Legacy (old binding-stack protocol) — still accepted, mapped to activate/deactivate.
        const val ACTION_PROFILE_PUSH = "com.halo.ring.action.PROFILE_PUSH"
        const val ACTION_PROFILE_POP  = "com.halo.ring.action.PROFILE_POP"
        const val PERMISSION_PUSH_PROFILE = "com.halo.ring.permission.PUSH_PROFILE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_OVERLAY_ACTIVATE, ACTION_PROFILE_PUSH  -> handleActivate(intent)
            ACTION_OVERLAY_DEACTIVATE, ACTION_PROFILE_POP -> handleDeactivate(intent)
            else -> Log.w(TAG, "ignored unknown action: ${intent.action}")
        }
    }

    private fun handleActivate(intent: Intent) {
        val owner = intent.getStringExtra("owner_package")?.takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "OVERLAY_ACTIVATE missing owner_package"); return
        }
        val profileId = intent.getStringExtra("profile_id")?.takeIf { it.isNotEmpty() } ?: "overlay"
        val displayName = intent.getStringExtra("display_name")?.takeIf { it.isNotEmpty() }
            ?: appLabel(owner)
        val isNew = synchronized(overlay) {
            overlay.activate(owner, profileId, displayName, System.currentTimeMillis())
        }
        Log.i(TAG, "OVERLAY_ACTIVATE $owner/$profileId (new=$isNew)")
        onOverlayChanged(isNew)
    }

    private fun handleDeactivate(intent: Intent) {
        val owner = intent.getStringExtra("owner_package")?.takeIf { it.isNotEmpty() }
        val profileId = intent.getStringExtra("profile_id")?.takeIf { it.isNotEmpty() }
        val removed = synchronized(overlay) {
            when {
                owner != null && profileId != null -> overlay.deactivate(owner, profileId)
                owner != null                      -> overlay.deactivateOwner(owner)
                else                               -> false
            }
        }
        Log.i(TAG, "OVERLAY_DEACTIVATE $owner/$profileId removed=$removed")
        if (removed) onOverlayChanged(false)
    }

    private fun appLabel(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
