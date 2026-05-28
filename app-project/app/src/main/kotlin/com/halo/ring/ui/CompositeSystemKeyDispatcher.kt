package com.halo.ring.ui

import android.util.Log
import com.halo.ring.core.gesture.SystemKeyDispatcher
import com.halo.ring.inject.AppProcessAgentBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * v0.4 — the base-gesture KeyEvent dispatcher used by [com.halo.ring.core.gesture.InteractionRouter].
 *
 * Routes the 4 base gestures' system KeyEvents (DPAD_CENTER / BACK / DPAD_UP / DPAD_DOWN) to the
 * right channel depending on whether the Halo Ring config Activity is foreground:
 *
 *  1. **Foreground** → [ActivitySystemKeyDispatcher] dispatches into our own window via
 *     `Activity.dispatchKeyEvent`. Fast, no agent required — this is how the ring drives Halo
 *     Ring's own config UI.
 *  2. **Not foreground** (the wearer is in Sprite Launcher / a third-party app) →
 *     [AppProcessAgentBackend.injectKey] injects the same keycode **system-wide** via
 *     `InputManager.injectInputEvent`. This is the "ring as a wireless temple touchpad for the
 *     whole glasses" path — it only works once the on-device ADB bootstrap has installed + started
 *     the agent (Doc/20; the wizard's ADB step / `bootRecoverAgent`).
 *
 * If neither channel is available (no foreground Activity AND no agent yet), the gesture is
 * dropped — the wearer needs to finish the ADB setup to control apps outside Halo Ring.
 *
 * [dispatch] is called from the scheduler thread (gesture pipeline). The activity path posts to
 * the main thread internally; the agent path is launched on [scope] (bound to the scheduler
 * dispatcher) so the suspend `injectKey` runs off the synchronous dispatch call.
 */
class CompositeSystemKeyDispatcher(
    private val agentBackend: AppProcessAgentBackend?,
    private val scope: CoroutineScope,
) : SystemKeyDispatcher {

    override fun dispatch(keyCode: Int): Boolean {
        // 1. In-app fast path. ActivitySystemKeyDispatcher returns false ONLY when no Halo Ring
        //    Activity is foreground (its WeakReference is null) — that's our "we're backgrounded"
        //    signal.
        if (ActivitySystemKeyDispatcher.dispatch(keyCode)) return true

        // 2. Out-of-app: inject system-wide via the agent.
        val agent = agentBackend
        if (agent == null) {
            Log.d(TAG, "no agent backend wired; dropping out-of-app keycode $keyCode")
            return false
        }
        scope.launch {
            val ok = agent.injectKey(keyCode)
            if (!ok) Log.d(TAG, "agent not ready; out-of-app keycode $keyCode dropped (run ADB setup)")
        }
        return true
    }

    private companion object { const val TAG = "HaloSysKey" }
}
