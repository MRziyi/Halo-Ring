package com.halo.ring.core.inject

import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.GlassAction

/**
 * One way to actually deliver an action to the glasses. Multiple may be available at once — the
 * [com.halo.ring.core.action.ActionRouter] picks the cheapest/fastest that can satisfy each action.
 *
 * Concrete backends live in :app:
 *  - `AppProcessAgentBackend` — talks to our app_process agent over LocalSocket. ~1–3ms per call.
 *    Provides NAVIGATE / KEY_EVENT / TAP_SWIPE / LAUNCH_INTENT / SHELL / BACK / HOME / RECENTS.
 *  - `ShizukuBackend`         — same capability set, via Shizuku's binder. Only if user installed it.
 *  - `InotifydScriptBackend`  — fall-back: write a command file → inotifyd-triggered shell runs `input`/`am`.
 *  - `PollScriptBackend`      — last-resort 0.05s polling script.
 *  - `AccessibilityBackend`   — only BACK / HOME / RECENTS / NOTIFICATIONS. Doesn't need ADB.
 *    Also feeds foreground-package events back to [com.halo.ring.core.action.ModeManager.onForegroundPackage].
 */
interface ExecutorBackend {
    val id: String
    /** Higher = preferred. Tiebreak: AppProcessAgent (100) > Shizuku (90) > Accessibility (80) >
     *  InotifydScript (60) > PollScript (40). */
    val priority: Int

    /** Probe + report what this backend can currently do. May change at runtime (ADB connection
     *  drops → agent goes from full set → empty). */
    fun capabilities(): Set<Capability>

    /** Cheap "is the connection / service alive". */
    fun isReady(): Boolean

    /**
     * Perform the action. Returns true on success, false if the backend declined (e.g. it lost
     * readiness mid-dispatch). The router will try the next-best backend that can satisfy [action].
     */
    suspend fun perform(action: GlassAction): Boolean
}
