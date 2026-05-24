package com.halo.ring.testplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Reference plugin trigger receiver (Doc/18 §5). On every fire we log + toast — both visible in
 * the test harness via `adb logcat -s HaloTestPlugin` and the on-glasses Toast.
 */
class HaloTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.getStringExtra("action_id") ?: "(missing)"
        val gesture  = intent.getStringExtra("trigger_gesture") ?: "(unknown)"
        val ts       = intent.getLongExtra("trigger_ts_ms", 0L)
        val deltaMs  = if (ts > 0) System.currentTimeMillis() - ts else -1L
        Log.i("HaloTestPlugin", "TRIGGER action=$actionId gesture=$gesture latency=${deltaMs}ms")
        Toast.makeText(context, "Halo TRIGGER: $actionId ($gesture, ${deltaMs}ms)", Toast.LENGTH_SHORT).show()
    }
}
