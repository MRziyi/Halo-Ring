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
     * Translate a Mercury `TempleAction` (passed via reflection so :main doesn't depend on Mercury
     * AAR) into a [GlassAction] and dispatch via [InAppFocusController]. Returns true if the action
     * was routed in-app (caller suppresses default temple-touchpad system handling), false if it
     * should fall through to whatever Mercury / the system would do by default.
     *
     * Templ action class name + simpleName mapping:
     *
     *  - `SlideForward`   → `GlassAction.NavNext`
     *  - `SlideBackward`  → `GlassAction.NavPrev`
     *  - `SlideUpwards`   → `GlassAction.NavPrev` (vertical slide — same direction as SWIPE_UP)
     *  - `SlideDownwards` → `GlassAction.NavNext`
     *  - `Click`          → `GlassAction.Confirm`
     *  - `DoubleClick`    → `GlassAction.Back`
     *  - `LongClick`      → no-op (let Mercury handle long-press menus)
     *  - `TripleClick`    → `GlassAction.ProfileCycle` (mirrors ring system-gesture)
     *
     * (Doc/03 §2.2 — match to ring system-gesture defaults so users have one model.)
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
