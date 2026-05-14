package com.halo.ring.ui

import android.app.Activity
import android.util.Log

/**
 * Optional bridge that lets the **glasses' own temple touchpad** drive in-app focus when the ring
 * is unavailable / disconnected.
 *
 * ## Why this exists
 *
 * The ring is the primary input. Ring gestures route through `GestureSynthesizer` → `InteractionRouter`
 * → [InAppFocusController.route] when MainActivity is foreground, calling
 * [androidx.compose.ui.focus.FocusManager.moveFocus] directly. **None of this involves Mercury SDK.**
 *
 * The temple touchpad is a separate input source. On X3 Pro it produces raw `MotionEvent`s that
 * Mercury SDK's `TouchDispatcher` interprets as `TempleAction.SlideForward / SlideBackward / Click
 * / LongClick` (Doc/03 §2.2). Plain `Modifier.focusable()` doesn't see those events because Mercury
 * intercepts them before the View hierarchy.
 *
 * **What this bridge does**: when set, the rayneo flavor installs a Mercury `TouchDispatcher`
 * on the foreground Activity and translates each `TempleAction` into the same `GlassAction` the
 * ring would have produced, then dispatches via [InAppFocusController.route]. So temple-driven
 * nav uses the exact same focus / action plumbing as ring-driven nav.
 *
 * **What this bridge does NOT do**: it does NOT register a parallel `FocusHolder` mirroring every
 * Compose element. Compose's own focus traversal is what we drive via [InAppFocusController]; the
 * bridge just feeds events into it from a second input source.
 *
 * ## Wiring
 *
 * - `MainActivity.onResume` calls [TempleFocusBridge.attachOptional] — the rokid flavor's impl is
 *   a no-op; rayneo's impl reflectively constructs the Mercury TouchDispatcher and registers a
 *   callback that calls [feedTempleAction].
 * - `onPause` calls [detachOptional] to release the dispatcher.
 *
 * ## Today's state
 *
 * The bridge interface + no-op default + the rayneo flavor's `RayNeoTempleFocusBridge` stub is in
 * place. The actual Mercury wiring (constructing `TouchDispatcher(Source.Activity)` + delivering
 * `MotionEvent`s through `Activity.dispatchTouchEvent` interception) **needs hardware verification**
 * before it can be claimed correct — see Doc/03 §2.2 + Doc/11 §B2 (RayNeo bring-up checklist).
 */
interface TempleFocusBridge {
    /** Called from MainActivity.onResume. Implementations may install dispatchers / receivers. */
    fun attach(activity: Activity)
    /** Called from MainActivity.onPause. */
    fun detach(activity: Activity)

    /**
     * Forward a raw [android.view.MotionEvent] from the Activity's `dispatchTouchEvent` into the
     * bridge so it can be decoded. The rayneo flavor feeds events into Mercury SDK's
     * `TouchDispatcher`, which then calls back via its `CommonTouchCallback` and from there into
     * [feedTempleAction]. The rokid flavor returns false (no decoding needed — temple is mapped
     * to DPAD keys at the system level).
     *
     * Return value: `true` if the event was consumed by the bridge and should NOT propagate to
     * the rest of the View tree, `false` to let the event continue normally. The MainActivity
     * always lets the event through even when we consume it — Mercury's contract is "we see the
     * event AND the View tree sees it"; suppression is decided by [feedTempleAction]'s return.
     */
    fun forwardMotionEvent(ev: android.view.MotionEvent): Boolean = false

    /**
     * Translate a Mercury `TempleAction` (identified by its class simpleName) into a
     * [GlassAction] and dispatch via [InAppFocusController]. Returns true if the action was
     * routed in-app (caller suppresses default temple-touchpad system handling), false if it
     * should fall through to whatever Mercury / the system would do by default.
     *
     * Templ action class name → action mapping:
     *
     *  - `SlideForward`   → `GlassAction.NavNext`   ← intent = "next item"
     *  - `SlideBackward`  → `GlassAction.NavPrev`   ← intent = "prev item"
     *  - `SlideUpwards`   → `GlassAction.NavPrev`   (vertical slide — same direction as SWIPE_UP)
     *  - `SlideDownwards` → `GlassAction.NavNext`
     *  - `Click`          → `GlassAction.Confirm`
     *  - `DoubleClick`    → `GlassAction.Back`
     *  - `LongClick`      → no-op (let Mercury surface its QuickControl center)
     *  - `TripleClick`    → `GlassAction.ProfileCycle` (mirrors ring system-gesture)
     *
     * **DO NOT rename SlideForward/SlideBackward to "Left/Right".** RayNeo's "Natural Mode"
     * system setting flips Slide direction physically; our mapping is keyed on *intent*
     * (next/prev), not direction, so it stays correct under both modes. (Audit-2026-05-13s,
     * cross-ref MercurySDK API ref §5.8.)
     */
    fun feedTempleAction(simpleName: String): Boolean {
        val action = com.halo.ring.core.device.mapTempleActionToGlassAction(simpleName) ?: return false
        if (!InAppFocusController.isActive) return false
        return InAppFocusController.route(action)
    }
}

/** No-op default used by the rokid flavor (no temple touchpad on Rokid — the temple is mapped to
 *  DPAD keys at the system level, which Compose's focusable already handles). */
object NoopTempleFocusBridge : TempleFocusBridge {
    override fun attach(activity: Activity) = Unit
    override fun detach(activity: Activity) = Unit
}

/**
 * Process-wide installable bridge. The flavor-specific [com.halo.ring.di.DeviceFlavorBindings] can
 * set [current] from the bridge factory; [com.halo.ring.MainActivity] reads it in
 * `onResume`/`onPause` to attach/detach.
 *
 * Default is the no-op. Replace this with a real bridge as part of flavor-specific bindings —
 * rayneo flavor's `RayNeoTempleFocusBridge` should be wired here.
 */
object TempleFocusBridgeHolder {
    @Volatile var current: TempleFocusBridge = NoopTempleFocusBridge
        private set

    fun install(bridge: TempleFocusBridge) {
        Log.i(TAG, "TempleFocusBridge installed: ${bridge::class.simpleName}")
        current = bridge
    }

    private const val TAG = "TempleFocusBridge"
}
