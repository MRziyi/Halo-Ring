package com.halo.ring.core.plugin

/**
 * Tracks the single active **overlay profile** — a Doc/18 plugin (e.g. Constellation) that has
 * signalled its on-glasses HUD is up and wants **exclusive** control of the ring.
 *
 * ## The overlay model (Doc/18 §7 — supersedes the old PROFILE_PUSH binding-stack)
 *
 * A plugin's overlay behaves like an app profile (Media / Reader), except:
 *  - it isn't inferred from the foreground app — the plugin **signals** activation when its HUD
 *    appears (it may float over any app or the home), and signals deactivation when the HUD closes;
 *  - while active it is **exclusive**: the [com.halo.ring.core.gesture.InteractionRouter] forwards
 *    every ring gesture to the owner plugin (as a raw gesture name) and lets **nothing** leak to the
 *    underlying app — no base-key passthrough, no page-flipping;
 *  - the plugin owns all semantics + on-HUD prompts. Halo Ring forwards raw gestures only; it never
 *    interprets them. (So the plugin can remap meaning per HUD-state without telling us.)
 *
 * Pure JVM (no Android) so activate/deactivate/expiry is unit-testable.
 *
 * **Single-active** by design — one HUD owns the ring at a time; a new activate replaces the prior.
 *
 * Thread-safety: the foreground service serialises mutations on the scheduler thread.
 */
class OverlayController(
    /** Backstop: if the active overlay isn't refreshed (keepalive) within this window it auto-
     *  releases, so a crashed / hung plugin can't lock the wearer out of their own ring. */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    data class Active(
        val ownerPackage: String,
        val profileId: String,
        /** Human-readable name for the activation HUD (the plugin's app label, usually). */
        val displayName: String,
        val activatedAtMs: Long,
        val lastRefreshMs: Long,
    )

    private var active: Active? = null

    fun isActive(): Boolean = active != null
    fun active(): Active? = active
    fun ownerPackage(): String? = active?.ownerPackage

    /**
     * Activate an overlay, or **refresh** (keepalive) if the same owner+id is re-sent. Returns true
     * only on a NEW activation (the caller then fires the activation HUD + freezes profile
     * inference); false for a keepalive refresh.
     */
    fun activate(owner: String, profileId: String, displayName: String, nowMs: Long): Boolean {
        val cur = active
        if (cur != null && cur.ownerPackage == owner && cur.profileId == profileId) {
            active = cur.copy(lastRefreshMs = nowMs)
            return false
        }
        active = Active(owner, profileId, displayName, nowMs, nowMs)
        return true
    }

    /** Deactivate a specific overlay (owner+id). Returns true if it was the active one. */
    fun deactivate(owner: String, profileId: String): Boolean {
        val cur = active ?: return false
        if (cur.ownerPackage == owner && cur.profileId == profileId) { active = null; return true }
        return false
    }

    /** Drop whatever this owner has active — uninstall / process death. Returns true if removed. */
    fun deactivateOwner(owner: String): Boolean {
        val cur = active ?: return false
        if (cur.ownerPackage == owner) { active = null; return true }
        return false
    }

    /** Backstop release if the active overlay went stale (no keepalive within [timeoutMs]). Returns
     *  true if it just expired. Checked on each gesture + on a periodic tick. */
    fun expireIfStale(nowMs: Long): Boolean {
        val cur = active ?: return false
        if (nowMs - cur.lastRefreshMs > timeoutMs) { active = null; return true }
        return false
    }

    fun clear() { active = null }

    companion object {
        /** 60 s with no keepalive → auto-release. Plugins should refresh every ~20-30 s while up. */
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
