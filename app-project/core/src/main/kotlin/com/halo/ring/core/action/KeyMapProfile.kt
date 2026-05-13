package com.halo.ring.core.action

import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.GestureConfig

/**
 * One "profile" = a [Gesture] → [GlassAction] table + the timing/optimistic knobs that should apply
 * while this profile is active + the apps that auto-activate it (when AccessibilityService is on
 * and reads the foreground package — see §15.2).
 *
 * @param triggerPackages package names whose foreground state should auto-switch us into this profile.
 *                        Empty list ⇒ "not auto-activated; only manual / explicit."
 */
data class KeyMapProfile(
    val id: String,
    val name: String,
    val map: Map<Gesture, GlassAction>,
    val gestureConfig: GestureConfig = GestureConfig(),
    val triggerPackages: List<String> = emptyList(),
) {
    fun actionFor(gesture: Gesture): GlassAction = map[gesture] ?: GlassAction.None

    /**
     * Returns a copy with [gesture] bound to [action]. Used by the settings UI when the user picks
     * a new action from the picker. Setting [action] to [GlassAction.None] explicitly unbinds the
     * slot (kept in the map so the editor can still render "(no action)" for it).
     */
    fun withMapping(gesture: Gesture, action: GlassAction): KeyMapProfile =
        copy(map = map.toMutableMap().apply { put(gesture, action) })
}
