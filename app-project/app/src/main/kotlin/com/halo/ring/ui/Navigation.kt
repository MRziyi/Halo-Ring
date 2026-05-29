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
    /** v0.4: the former Status tab. */
    object StatusInfo : SubScreen
    object Feedback : SubScreen
    object Profiles : SubScreen
    data class ProfileEditor(val profileId: String) : SubScreen
    data class ActionPicker(val profileId: String, val gesture: Gesture) : SubScreen
    object SystemGestures : SubScreen
    data class GesturePicker(val slot: SystemGestureSlot) : SubScreen
    object Ring : SubScreen
    /** Pairing picker — scans for nearby R0x-family rings, user taps to commit. Reached from
     *  RingScreen "PAIR" CTA or from the first-run wizard's pair step. (burn-in fix 2026-05-27) */
    object RingPairing : SubScreen
    object Power : SubScreen
    /** Settings → Bluetooth internet — re-enable the phone's BT "Internet access" (manual + on-boot). */
    object BluetoothInternet : SubScreen
    object Advanced : SubScreen
    object About : SubScreen
    object VitalsPrefs : SubScreen
    /** Settings → Language (audit-2026-05-13o). */
    object Language : SubScreen
    /** Settings → Test Arena — practice ground showing live recognised gestures (audit-q). */
    object TestArena : SubScreen
    /** Settings → External plugins (Doc/18 §8.2) — lists discovered plugin apps + refresh CTA. */
    object ExternalPlugins : SubScreen
    // SubScreen.Guide removed in audit-2026-05-13p. The interactive GuidedTour was further
    // deleted in v0.4 (Doc/20 §4) — Test Arena does the job better, on demand.
}

/** The 5 always-on system gesture slots (Doc/05 §5). */
enum class SystemGestureSlot(val title: String, val description: String) {
    WAKE("Wake screen", "Tap to wake the glasses' display (screen-off only)."),
    SLEEP("Sleep screen", "Put the display back to sleep. Hard to fire accidentally."),
    PROFILE_CYCLE("Cycle profile", "Manual override for active profile."),
    PEEK_HUD("Peek HUD", "Briefly show connection / battery / current mode."),
    /** Audit-pass 2026-05-14w: replaced FORCE_RECONNECT (now a button in Settings → Ring). */
    AI_ASSISTANT("AI assistant", "Wake the everyday voice / chat AI on the glasses."),
}
