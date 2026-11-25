package com.halo.ring.ui

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.halo.ring.accessibility.HaloRingAccessibilityService
import com.halo.ring.core.gesture.SystemKeyDispatcher
import com.halo.ring.inject.AppProcessAgentBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * v0.4 — the base-gesture KeyEvent dispatcher used by [com.halo.ring.core.gesture.InteractionRouter].
 *
 * Dispatch priority (out-of-app only — in-app uses [ActivitySystemKeyDispatcher]):
 *
 *  1. **Agent** ([AppProcessAgentBackend.injectKey]) — full system-wide KeyEvent injection via
 *     `InputManager.injectInputEvent` as shell uid. Requires the ADB bootstrap to have run.
 *     On agent-not-ready, falls through to path 2.
 *
 *  2. **Accessibility service fallback** ([HaloRingAccessibilityService]) — covers the agent-down
 *     window (reboot before Wi-Fi comes up, first run). Limited to:
 *       • BACK → `performGlobalAction(GLOBAL_ACTION_BACK)`
 *       • HOME → `performGlobalAction(GLOBAL_ACTION_HOME)`
 *       • DPAD_CENTER → `ACTION_CLICK` on the focused node
 *       • DPAD_UP/DOWN → `ACTION_SCROLL_BACKWARD/FORWARD` on the focused node
 *     Not available for arbitrary keycodes (third-party app media keys etc.) — those are dropped.
 */
class CompositeSystemKeyDispatcher(
    private val agentBackend: AppProcessAgentBackend?,
    private val scope: CoroutineScope,
) : SystemKeyDispatcher {

    override fun dispatch(keyCode: Int): Boolean {
        if (ActivitySystemKeyDispatcher.dispatch(keyCode)) return true

        val agent = agentBackend
        if (agent != null) {
            scope.launch {
                val ok = agent.injectKey(keyCode)
                if (!ok) {
                    Log.d(TAG, "agent not ready; a11y fallback for keycode $keyCode")
                    injectViaA11y(keyCode)
                }
            }
            return true
        }

        // No agent wired at all — use accessibility service directly.
        scope.launch { injectViaA11y(keyCode) }
        return true
    }

    private fun injectViaA11y(keyCode: Int): Boolean {
        val a11y = HaloRingAccessibilityService.instance ?: run {
            Log.d(TAG, "no agent, no a11y service; keycode $keyCode dropped")
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK ->
                a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME ->
                a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_DPAD_CENTER ->
                a11y.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            KeyEvent.KEYCODE_DPAD_UP ->
                a11y.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
            KeyEvent.KEYCODE_DPAD_DOWN ->
                a11y.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
            else -> {
                Log.d(TAG, "a11y fallback: no mapping for keycode $keyCode")
                false
            }
        }
    }

    private companion object { const val TAG = "HaloSysKey" }
}
