package com.halo.ring.core.device

import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.GlassAction

/**
 * The four "what's different per glasses" hooks. Concrete impls live in :app's flavor source sets
 * (`src/rokid/` and `src/rayneo/`). See R08-Remote-Design.md §18.3 / §19.
 */

/** Decides how a [GlassAction] is realised on this device — and which capability that needs. */
interface GlassActionMapper {
    /**
     * The capability the action requires *on this device*. Lets us override the default in
     * [GlassAction.needs] when the device transport differs (Rokid `NavPrev` = KEY_EVENT-ish via
     * NAVIGATE; RayNeo `NavPrev` = TAP_SWIPE via NAVIGATE).
     */
    fun capabilityFor(action: GlassAction): Capability?

    /** Render the action as a list of injection primitives the backend will execute in order. */
    fun primitives(action: GlassAction): List<InjectionPrimitive>

    /**
     * Three-tier honesty about what this device can actually do (audit-pass-x+1):
     *
     *  - [SupportLevel.SUPPORTED] — verified or near-verified working. Picker shows normally.
     *  - [SupportLevel.BEST_EFFORT] — primitives are present but the underlying Intent/package
     *    is unverified on this device (e.g. RayNeo X3 Pro `OpenAIAssistant` uses the standard
     *    `VOICE_SEARCH_HANDS_FREE` Intent; may or may not surface Gemini). Picker shows a
     *    "(beta)" tag so the user knows binding it is exploratory.
     *  - [SupportLevel.UNSUPPORTED] — primitives are empty OR the action is a known-incomplete
     *    skeleton (e.g. `EnterAIDictateModal` opens chat on entry but the mic-capture pipeline
     *    isn't wired). Picker greys out + "(coming soon)".
     *
     * Default: in-app pseudo-actions (`PeekHud`, `ProfileCycle`, `ForceReconnect`, the
     * `Enter*Modal` sentinels, and `None`) are SUPPORTED because [com.halo.ring.core.gesture.InteractionRouter]
     * intercepts them before they reach this mapper. Everything else is SUPPORTED iff
     * [primitives] yields a non-empty list, otherwise UNSUPPORTED. Per-flavor mappers override
     * for action-specific overrides.
     */
    fun supportLevel(action: GlassAction): SupportLevel = when (action) {
        GlassAction.PeekHud, GlassAction.ProfileCycle, GlassAction.ForceReconnect,
        GlassAction.EnterVolumeModal, GlassAction.EnterBrightnessModal,
        GlassAction.EnterRecentsModal, GlassAction.EnterAIDictateModal,
        GlassAction.None,
        -> SupportLevel.SUPPORTED
        // External-plugin actions (Doc/18): also intercepted in HaloRingService before reaching
        // the mapper. Always SUPPORTED at this layer — Profile editor may overlay a "(missing)"
        // marker if the owning plugin is no longer installed, but that's a PluginRegistry concern
        // and not a per-device capability question.
        is GlassAction.PluginAction -> SupportLevel.SUPPORTED
        else -> if (primitives(action).isNotEmpty()) SupportLevel.SUPPORTED else SupportLevel.UNSUPPORTED
    }

    /** Backwards-compat shim: SUPPORTED + BEST_EFFORT both count as "the picker should let the
     *  user bind it". UNSUPPORTED greys out. Existing call sites continue to work. */
    fun supports(action: GlassAction): Boolean = supportLevel(action) != SupportLevel.UNSUPPORTED

    enum class SupportLevel { SUPPORTED, BEST_EFFORT, UNSUPPORTED }
}

/** Tiny IR. The agent supports all of these (see :agent README); other backends do their best. */
sealed interface InjectionPrimitive {
    /** Press-and-release a single Android keycode. */
    data class Key(val keycode: Int) : InjectionPrimitive
    /** A touch tap at screen coords. */
    data class Tap(val x: Int, val y: Int) : InjectionPrimitive
    /** A swipe from (x1,y1) to (x2,y2) over [durationMs]. Use the shortest the system accepts. */
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int = 60) : InjectionPrimitive
    /** Launch an Activity / Service / Broadcast. */
    data class StartActivity(val component: String, val extras: Map<String, String> = emptyMap()) : InjectionPrimitive
    data class Broadcast(val action: String, val extras: Map<String, String> = emptyMap()) : InjectionPrimitive
    /** Raw shell. Escape hatch for things we haven't modelled. */
    data class Shell(val cmd: String) : InjectionPrimitive
    /** AccessibilityService global action: BACK / HOME / RECENTS / NOTIFICATIONS / QUICK_SETTINGS. */
    data class A11yGlobal(val action: A11yGlobalAction) : InjectionPrimitive
}

enum class A11yGlobalAction {
    BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS,
    LOCK_SCREEN,        // API 28+
    TAKE_SCREENSHOT,    // API 30+
    POWER_DIALOG,
}

/** "Are the glasses being worn?" Drives the power state machine (§14.3 P4) + ring handover (§18.4). */
interface WearStateProvider {
    /** Latest known state — true ⇒ on head. */
    fun isWorn(): Boolean

    /** Subscribe to changes. */
    fun observe(onChange: (worn: Boolean) -> Unit): () -> Unit
}

/** Per-device [GlassAction] → Intent / activity / broadcast map. Populated from §12.1 (Rokid) and
 *  by on-device discovery for RayNeo X3 Pro (§17.5 / §18.7). */
interface FeatureIntents {
    /** Returns the primitives that open the feature, or empty if not supported on this device. */
    fun openCamera(): List<InjectionPrimitive>
    fun takePhoto(): List<InjectionPrimitive>
    /** Wake the everyday voice/chat AI (Rokid Chat / RayNeo Gemini). Distinct from [askVisualAI]
     *  which is camera-grounded VQA. */
    fun openAIAssistant(): List<InjectionPrimitive>
    /** Wake the glasses' **native** system AI agent (Rokid: Sprite AI via ACTION_AI_START broadcast).
     *  Distinct from [openAIAssistant], which opens a specific chat app/page. */
    fun wakeSystemAI(): List<InjectionPrimitive>
    fun askVisualAI(): List<InjectionPrimitive>
    fun openTranslate(): List<InjectionPrimitive>
    /** Rokid live-caption / 字幕 page. Empty on devices without one. */
    fun openSubtitle(): List<InjectionPrimitive>
    fun openChat(): List<InjectionPrimitive>
    fun openMusic(): List<InjectionPrimitive>
    fun openSettings(): List<InjectionPrimitive>
    fun openGallery(): List<InjectionPrimitive>
    fun launchApp(pkg: String): List<InjectionPrimitive>
}

/** How our app renders its own UI on this device (mono vs binocular). The Compose content is shared;
 *  only the *wrapping* differs. */
interface DisplayAdapter {
    /** True ⇒ the device renders left/right halves of the surface as the two eyes. Our adapter then
     *  wraps content in the platform's mirror view (RayNeo ARSDK `BaseMirrorActivity` / DIY two-halves). */
    val isBinocular: Boolean
    /** Logical content area (in pixels) the UI should target. */
    val contentWidthPx: Int
    val contentHeightPx: Int
}
