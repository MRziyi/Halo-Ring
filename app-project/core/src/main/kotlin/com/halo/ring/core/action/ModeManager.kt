package com.halo.ring.core.action

import com.halo.ring.core.gesture.Gesture

/**
 * Owns the list of profiles + which one is currently active. Handles:
 * - Manual cycle on [Gesture.TRIPLE_TAP].
 * - Manual lock (~5s after a manual switch, ignore auto-switch) so the user's explicit choice sticks.
 * - Auto-switch when the foreground package matches a profile's `triggerPackages` (called by the
 *   AccessibilityBackend layer with the new foreground pkg).
 *
 * Not thread-safe; run on the same single looper as the GestureSynthesizer.
 */
class ModeManager(
    initialProfiles: List<KeyMapProfile> = DefaultProfiles.ALL,
    initialActiveId: String = DefaultProfiles.NAVIGATION.id,
    /** A monotonic-clock function for the manual-lock timeout. */
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** ~5s — after a manual switch, ignore auto-switch for this long. */
    var manualLockMs: Long = 5_000,
) {
    private val profiles: MutableList<KeyMapProfile> = initialProfiles.toMutableList()
    private var activeIndex: Int = profiles.indexOfFirst { it.id == initialActiveId }.coerceAtLeast(0)
    private var manualLockUntilMs: Long = 0L

    private val listeners = mutableListOf<(KeyMapProfile) -> Unit>()

    fun active(): KeyMapProfile = profiles[activeIndex]
    fun all(): List<KeyMapProfile> = profiles.toList()

    /** True when the active profile is the default fallback (Navigation). Used by the router to
     *  decide whether LONG_PRESS = system screen-sleep (fallback only) vs an app-profile binding. */
    fun isFallbackActive(): Boolean = active().id == DefaultProfiles.DEFAULT_FALLBACK_ID

    /** Subscribe to "active profile changed". Called with the new profile after every switch. */
    fun observe(onChange: (KeyMapProfile) -> Unit): () -> Unit {
        listeners += onChange
        onChange(active())
        return { listeners -= onChange }
    }

    /** Cycle to the next profile (typically bound to TRIPLE_TAP). Triggers the manual lock. */
    fun cycleNext() {
        activeIndex = (activeIndex + 1) % profiles.size
        manualLockUntilMs = nowMs() + manualLockMs
        notifyChanged()
    }

    /** Switch to a specific profile by id. Triggers manual lock. */
    fun switchTo(id: String) {
        val i = profiles.indexOfFirst { it.id == id }
        if (i < 0 || i == activeIndex) return
        activeIndex = i
        manualLockUntilMs = nowMs() + manualLockMs
        notifyChanged()
    }

    /**
     * Called by the AccessibilityBackend with the current foreground package name.
     *
     * Matching is **prefix-based against the package AND the foreground activity class** (2026-05-28):
     * a trigger matches if either the package or the activity starts with it. Activity matching is
     * what makes Rokid Sprite pages work — they're all `com.rokid.os.sprite.launcher` but the trigger
     * `com.rokid.os.sprite.launcher.page.music` matches the `MusicPageActivity` class. Plain apps still
     * match by package prefix (`com.spotify` matches `com.spotify.music`).
     *
     * **Fallback to default**: when the foreground pkg matches nothing AND the active profile is
     * not the default-fallback ([DefaultProfiles.DEFAULT_FALLBACK_ID], normally "navigation"),
     * switch back to the fallback. This means leaving Spotify and going back to the system
     * launcher correctly drops Media → Navigation instead of leaving us stuck on Media.
     */
    fun onForegroundPackage(pkg: String?, activity: String? = null) {
        if (pkg == null) return
        if (nowMs() < manualLockUntilMs) return  // user just chose; respect them
        // Match a trigger against the package OR the foreground activity class. The activity match
        // is essential on Rokid: every Sprite "app" (camera/music/translate/chat) shares the one
        // package com.rokid.os.sprite.launcher but has a distinct activity, so a trigger like
        // "com.rokid.os.sprite.launcher.page.music" can only be hit via the activity class.
        fun matches(trigger: String) =
            pkg.startsWith(trigger) || (activity?.startsWith(trigger) == true)
        val match = profiles.firstOrNull { p ->
            p.id != active().id && p.triggerPackages.any { trigger -> matches(trigger) }
        }
        if (match != null) {
            activeIndex = profiles.indexOf(match)
            notifyChanged()
            return
        }
        // No profile matches. If we're already on a profile with NO triggerPackages (e.g.
        // Navigation as the fallback, or Fast which the user picked manually), stay there.
        // Otherwise fall back to DEFAULT_FALLBACK_ID.
        if (active().triggerPackages.isEmpty()) return
        val fallback = profiles.indexOfFirst { it.id == DefaultProfiles.DEFAULT_FALLBACK_ID }
        if (fallback < 0 || fallback == activeIndex) return
        activeIndex = fallback
        notifyChanged()
    }

    /** CRUD for the settings UI. */
    fun upsert(p: KeyMapProfile) {
        val i = profiles.indexOfFirst { it.id == p.id }
        // Only notify when the ACTIVE profile's content actually changed — KeyMapProfile is a data
        // class, so `!=` is structural. This keeps upsert idempotent: re-applying an unchanged
        // profile (e.g. the seed during the profilesFlow → ModeManager sync on startup) is a silent
        // no-op, so it won't fire a spurious "profile switched" HUD.
        val changed = i < 0 || profiles[i] != p
        if (i < 0) profiles += p else profiles[i] = p
        if (i == activeIndex && changed) notifyChanged()
    }
    fun remove(id: String) {
        val i = profiles.indexOfFirst { it.id == id }
        if (i < 0 || profiles.size == 1) return
        profiles.removeAt(i)
        if (activeIndex >= profiles.size) activeIndex = 0
        notifyChanged()
    }

    private fun notifyChanged() { val p = active(); listeners.forEach { it(p) } }
}
