package com.halo.ring.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.halo.ring.core.gesture.SystemKeyDispatcher
import java.lang.ref.WeakReference

/**
 * Singleton key-event dispatcher that targets the currently-foreground Halo Ring [Activity]'s
 * window. Wired by [com.halo.ring.MainActivity] in `onResume` (set ref) / `onPause` (clear ref).
 * Consumed by [com.halo.ring.core.gesture.InteractionRouter] via the `systemKeyDispatcher` slot.
 *
 * **Why** (v0.3 P1, Doc/19 §3.P1): for the 4 BASE gestures the ring should behave as a wireless
 * extension of the Rokid temple touchpad — fire `KEYCODE_DPAD_CENTER` / `KEYCODE_BACK` /
 * `KEYCODE_DPAD_RIGHT` / `KEYCODE_DPAD_LEFT` and let Compose's native focus + click handling
 * deal with the rest. No custom focus-manager state, no agent dependency.
 *
 * **Threading**: [SystemKeyDispatcher.dispatch] is called from the scheduler thread (gesture
 * pipeline). All KeyEvent dispatch hops to the main thread because [Activity.dispatchKeyEvent]
 * is main-only. The return is fire-and-forget (we don't synchronously know whether Compose
 * consumed it).
 */
object ActivitySystemKeyDispatcher : SystemKeyDispatcher {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activityRef: WeakReference<Activity>? = null

    /** Called from `MainActivity.onResume`. */
    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /** Called from `MainActivity.onPause`. The slot stays clear until the next `onResume`. */
    fun detach() {
        activityRef = null
    }

    override fun dispatch(keyCode: Int): Boolean {
        val activity = activityRef?.get() ?: return false
        mainHandler.post {
            // Compose handles KEYCODE_DPAD_CENTER → click on the focused element, BACK → onBackPressed,
            // DPAD_LEFT/RIGHT/UP/DOWN → focusManager.moveFocus(...). Send a paired DOWN+UP so the
            // OS treats it as a discrete tap rather than a held key.
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, /* repeat */ 0)
            val up   = KeyEvent(now, now, KeyEvent.ACTION_UP,   keyCode, /* repeat */ 0)
            try {
                activity.dispatchKeyEvent(down)
                activity.dispatchKeyEvent(up)
            } catch (_: Throwable) {
                // The activity may have just been torn down (race between onPause clearing the
                // ref and our scheduler-thread call). Silently swallow — caller has no recovery.
            }
        }
        return true   // optimistic — we dispatched, even if Compose chooses to ignore
    }
}
