package com.halo.ring.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.InteractionRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * v0.4 C2 — Rokid temple touchpad input via the system's ordered broadcasts.
 *
 * Per `bare-metal-docs/01-key-events.md`, Rokid Glasses publish every temple-touchpad / right-key
 * action as a system ordered broadcast (in addition to the standard Android KeyEvents the
 * foreground Activity already receives). The broadcasts work in a Service without a foreground
 * Activity — which is critical because Halo Ring's Activity is rarely visible (Doc/20 §6).
 *
 * What we route through [InteractionRouter]:
 *
 *  | Broadcast | Notes | Routed Gesture |
 *  |---|---|---|
 *  | `ACTION_SPRITE_BUTTON_CLICK` | system also dispatches `KEYCODE_DPAD_CENTER` — we just observe | (no route — base) |
 *  | `ACTION_SPRITE_BUTTON_DOUBLE_CLICK` | system-occupied for BACK; not interceptable | (no route) |
 *  | `ACTION_SPRITE_BUTTON_LONG_PRESS` | side-key long press; system has no default action | `LONG_PRESS` |
 *  | `ACTION_TWO_FINGER_SINGLE_TAP` | two-finger tap on the touchpad | `TAP` |
 *  | `ACTION_TWO_FINGER_DOUBLE_TAP` | two-finger double tap | `DOUBLE_TAP` |
 *  | `ACTION_TWO_FINGER_SWIPE_FORWARD` | two-finger forward swipe | `SWIPE_UP` |
 *  | `ACTION_TWO_FINGER_SWIPE_BACK` | two-finger back swipe | `SWIPE_DOWN` |
 *  | `ACTION_SETTINGS_KEY` | two-finger long press (the "Settings" key) | (no route — opens config Activity) |
 *  | `ACTION_AI_START` | system-occupied for Sprite AI | (no route, just log) |
 *
 * **We never call `abortBroadcast()`** — the system's default DPAD dispatch for CLICK keeps
 * working alongside us so the temple stays a first-class navigation source. We layer Halo
 * Ring's custom-gesture profile + plugin-protocol on top, without breaking native behaviour.
 *
 * The receiver is registered Rokid-flavor-only by [HaloRingService] (Rokid system broadcasts
 * don't exist on RayNeo).
 */
class RokidTempleBroadcastReceiver(
    private val router: InteractionRouter,
    /** Bound to the scheduler thread (`scheduler.coroutineDispatcher`) so we keep the router's
     *  single-thread discipline. */
    private val scope: CoroutineScope,
    /** Called when the user fires the two-finger "Settings" key gesture — host shows its config
     *  Activity (Halo Ring's icon-tap equivalent, but discoverable from inside any app). */
    private val onOpenSettings: () -> Unit = {},
) : BroadcastReceiver() {

    private val actionToGesture: Map<String, Gesture> = mapOf(
        "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"   to Gesture.LONG_PRESS,
        "com.android.action.ACTION_TWO_FINGER_SINGLE_TAP"      to Gesture.TAP,
        "com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP"      to Gesture.DOUBLE_TAP,
        "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"   to Gesture.SWIPE_UP,
        "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"      to Gesture.SWIPE_DOWN,
    )

    /** Broadcasts we observe + log but don't route into the gesture pipeline. */
    private val observeOnly = setOf(
        "com.android.action.ACTION_SPRITE_BUTTON_CLICK",        // system dispatches DPAD_CENTER too
        "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK", // system-occupied BACK
        "com.android.action.ACTION_SPRITE_BUTTON_DOWN",
        "com.android.action.ACTION_SPRITE_BUTTON_UP",
        "com.android.action.ACTION_AI_START",
    )

    /** Two-finger long press = "open the app" hint. Doc/04 §8.1 says we treat it as a legitimate
     *  entry point for Halo Ring's config Activity. */
    private val settingsAction = "com.android.action.ACTION_SETTINGS_KEY"

    fun filter(): IntentFilter = IntentFilter().apply {
        priority = 100
        actionToGesture.keys.forEach(::addAction)
        observeOnly.forEach(::addAction)
        addAction(settingsAction)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return

        when {
            action == settingsAction -> {
                Log.i(TAG, "ACTION_SETTINGS_KEY → open config Activity")
                onOpenSettings()
            }
            action in actionToGesture -> {
                val gesture = actionToGesture.getValue(action)
                Log.i(TAG, "$action → Gesture.$gesture")
                // `scope` is bound to scheduler.coroutineDispatcher → the router sees the
                // synthetic gesture on the same single thread the ring + synthesiser use. We
                // call onGesture directly (skipping the synthesiser) because the temple's
                // emitted action IS the recognised gesture — no synthesis needed.
                scope.launch { router.onGesture(gesture) }
            }
            action in observeOnly -> {
                Log.v(TAG, "observed (no route): $action")
            }
            else -> {
                Log.w(TAG, "unknown action received: $action")
            }
        }
    }

    private companion object {
        const val TAG = "HaloTempleBcast"
    }
}
