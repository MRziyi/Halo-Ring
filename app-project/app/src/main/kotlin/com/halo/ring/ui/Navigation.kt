package com.halo.ring.ui

import com.halo.ring.core.gesture.Gesture

/**
 * Typed sub-screen identifier for [HaloRingApp]'s navigation stack. Each tab has its own root (when the
 * stack is empty); pushing a [SubScreen] drills in, popping returns to the parent.
 *
 * Why a typed sealed hierarchy instead of `String` keys: Profile editing / action picking / gesture
 * picking each need to carry context (which profile, which gesture slot, which system slot) — keys
 * alone would force us to invent parallel state for the context, which is exactly what data classes
 * are for.
 */
sealed interface SubScreen {
    object Feedback : SubScreen
    object Profiles : SubScreen
    data class ProfileEditor(val profileId: String) : SubScreen
    data class ActionPicker(val profileId: String, val gesture: Gesture) : SubScreen
    object SystemGestures : SubScreen
    data class GesturePicker(val slot: SystemGestureSlot) : SubScreen
    object Ring : SubScreen
    object Power : SubScreen
    object Advanced : SubScreen
    object About : SubScreen
    object VitalsPrefs : SubScreen
    /** Settings → Language (audit-2026-05-13o). */
    object Language : SubScreen
    /** Settings → Test Arena — practice ground showing live recognised gestures (audit-q). */
    object TestArena : SubScreen
    // SubScreen.Guide removed in audit-2026-05-13p — the static cheatsheet was replaced by the
    // interactive [com.halo.ring.ui.screens.GuidedTour] overlay. Re-open via About → onShowGuide
    // which propagates up to the host (MainActivity) to flip `tourActive=true`.
}

/** The 5 always-on system gesture slots (Doc/05 §5). */
enum class SystemGestureSlot(val title: String, val description: String) {
    WAKE("Wake screen", "Tap to wake the glasses' display (screen-off only)."),
    SLEEP("Sleep screen", "Put the display back to sleep. Hard to fire accidentally."),
    PROFILE_CYCLE("Cycle profile", "Manual override for active profile."),
    PEEK_HUD("Peek HUD", "Briefly show connection / battery / current mode."),
    FORCE_RECONNECT("Force reconnect", "Re-establish the BLE link if it gets confused."),
}
