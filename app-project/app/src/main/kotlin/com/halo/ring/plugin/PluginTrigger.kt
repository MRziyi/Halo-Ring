package com.halo.ring.plugin

import android.content.Context
import android.content.Intent
import android.util.Log
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.gesture.Gesture

/**
 * Sends the Doc/18 §5 `com.halo.ring.action.TRIGGER` broadcast to a plugin's `BroadcastReceiver`.
 * Pure dispatch — no state, no retries (spec: fire-and-forget). The targeted broadcast is gated
 * by `com.halo.ring.permission.SEND_PLUGIN_TRIGGER` so a spoofer can't fire the plugin's receiver
 * even if it knows the action name.
 */
object PluginTrigger {

    private const val TAG = "PluginTrigger"
    const val ACTION_TRIGGER = "com.halo.ring.action.TRIGGER"
    const val PERMISSION_SEND_TRIGGER = "com.halo.ring.permission.SEND_PLUGIN_TRIGGER"

    /** Build + send the trigger broadcast for [action], stamping the originating [gesture]
     *  (informational extra, optional per spec) and the current time. */
    fun fire(context: Context, action: GlassAction.PluginAction, gesture: Gesture) {
        val intent = Intent(ACTION_TRIGGER).apply {
            setPackage(action.pluginPackage)
            putExtra("action_id", action.actionId)
            putExtra("trigger_gesture", gesture.name)
            putExtra("trigger_ts_ms", System.currentTimeMillis())
            // FLAG_INCLUDE_STOPPED_PACKAGES so a force-stopped plugin still wakes (Android default
            // since Honeycomb is to *exclude* stopped packages from broadcasts — but for a
            // user-installed plugin we explicitly want the receiver to start its service).
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        try {
            context.sendBroadcast(intent, PERMISSION_SEND_TRIGGER)
            Log.i(TAG, "fired ${action.pluginPackage}/${action.actionId} from $gesture")
        } catch (e: SecurityException) {
            // Shouldn't happen — we declare the permission ourselves at signature level. Log so
            // a misconfigured plugin (declaring its receiver without our permission) shows up.
            Log.w(TAG, "trigger denied for ${action.pluginPackage}: ${e.message}")
        }
    }
}
