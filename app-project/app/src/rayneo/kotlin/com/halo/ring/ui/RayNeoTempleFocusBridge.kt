package com.halo.ring.ui

import android.app.Activity
import android.util.Log
import android.view.MotionEvent
import com.ffalcon.mercury.android.sdk.touch.CommonTouchCallback
import com.ffalcon.mercury.android.sdk.touch.FlingArgs
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcher

/**
 * RayNeo X3 Pro implementation of [TempleFocusBridge]. Bridges Mercury SDK's `TouchDispatcher` →
 * [InAppFocusController].
 *
 * ## How it works
 *
 * Mercury exposes:
 *  - `TouchDispatcher(Source)` — pass raw `MotionEvent`s via `onMotionEvent(event, callback)`.
 *  - `CommonTouchCallback` — abstract class with `onTPClick`, `onTPSlideForward(FlingArgs)`,
 *    `onTPDoubleClick`, etc. Each returns `Boolean`: `true` = the callback consumed the event,
 *    `false` = Mercury's default handling fires.
 *
 * We attach in `MainActivity.onResume` (constructing a `TouchDispatcher(Source.Activity)` plus our
 * own [HaloRingTouchCallback]). On every Activity `dispatchTouchEvent`, `MainActivity` forwards
 * the raw `MotionEvent` here via [forwardMotionEvent]; we hand it to `TouchDispatcher.onMotionEvent`
 * which decodes the gesture and invokes the matching `onTP*` method on our callback. The callback
 * then calls back into the shared [TempleFocusBridge.feedTempleAction] so temple-driven nav uses
 * the same `GlassAction` → `InAppFocusController.route` plumbing as ring-driven nav.
 *
 * ## What this NO LONGER does (audit-2026-05-13s)
 *
 * Earlier revisions used Java reflection to instantiate `TouchDispatcher` + subclass
 * `CommonTouchCallback`, on the (mistaken) assumption that this file might live in `:main`. It
 * doesn't — this file is in `src/rayneo/kotlin/...`, the rayneo flavor's source-set, which has
 * Mercury on its classpath via `rayneoImplementation(files("libs/mercury-release.aar"))` in
 * [app/build.gradle.kts]. Direct types are fine; reflection added zero portability and made the
 * code unreadable.
 *
 * ## Hardware-verification status
 *
 * - **Wiring path** (TouchDispatcher construction + callback delivery) is now real and compiles
 *   against the live Mercury API.
 * - **Intercept point** (`MainActivity.dispatchTouchEvent` → `forwardMotionEvent`) is implemented;
 *   what's untested on real X3 Pro hardware is whether Mercury delivers temple events to
 *   `Activity.dispatchTouchEvent` at all (some Mercury versions install a window-level listener
 *   that bypasses Activity dispatch). If [HaloRingTouchCallback] never fires on real glasses,
 *   the fallback is to make `MainActivity` subclass `BaseTouchActivity` in the rayneo flavor —
 *   see Doc/11 §B2.1.
 */
class RayNeoTempleFocusBridge : TempleFocusBridge {

    private var dispatcher: TouchDispatcher? = null
    private var callback: HaloRingTouchCallback? = null

    override fun attach(activity: Activity) {
        try {
            dispatcher = TouchDispatcher(TouchDispatcher.Source.Activity)
            callback = HaloRingTouchCallback(this)
            Log.i(TAG, "Mercury TouchDispatcher attached to ${activity::class.simpleName}")
        } catch (e: Throwable) {
            // Mercury AAR is bundled in the rayneo flavor, but if the runtime is missing
            // (e.g. running the rayneo APK on a non-RayNeo device for QA), the static init can
            // throw. Don't take MainActivity down with us.
            Log.w(TAG, "Mercury TouchDispatcher init failed (running off-device?): ${e.message}")
            dispatcher = null
            callback = null
        }
    }

    override fun detach(activity: Activity) {
        dispatcher = null
        callback = null
    }

    override fun forwardMotionEvent(ev: MotionEvent): Boolean {
        val d = dispatcher ?: return false
        val cb = callback ?: return false
        return try {
            d.onMotionEvent(ev, cb)
            // We always return false from this hook so the Activity's view-tree also sees the
            // raw MotionEvent — temple-driven nav coexists with on-screen touch (e.g. when QA-ing
            // on a phone). Suppression of the underlying touch is implicit: if the callback's
            // onTP* method returned true, Mercury treats it as consumed for its own purposes;
            // Compose's clickable() is keyed off DPAD_CENTER, not MotionEvent.ACTION_UP, so
            // there's no double-fire risk.
            false
        } catch (e: Throwable) {
            Log.w(TAG, "onMotionEvent failed: ${e.message}")
            false
        }
    }

    companion object { private const val TAG = "RayNeoTempleBridge" }
}

/**
 * Mercury `CommonTouchCallback` subclass that funnels each recognised temple action into the
 * shared [TempleFocusBridge.feedTempleAction] router. Lives in the rayneo flavor source-set
 * because it directly references Mercury types.
 *
 * Returns `true` from each handler iff [TempleFocusBridge.feedTempleAction] routed the action
 * to an in-app focus / nav target; `false` lets Mercury fall back to its default behaviour
 * (e.g. surfacing QuickControl for long-press).
 */
private class HaloRingTouchCallback(
    private val bridge: TempleFocusBridge,
) : CommonTouchCallback() {

    override fun onTPClick(): Boolean = bridge.feedTempleAction("Click")
    override fun onTPDoubleClick(): Boolean = bridge.feedTempleAction("DoubleClick")
    override fun onTPLongClick(): Boolean = false                                 // let Mercury handle long-press → QuickControl
    override fun onTPTripleClick() { bridge.feedTempleAction("TripleClick") }     // void return — fire and forget
    override fun onTPSlideForward(args: FlingArgs): Boolean = bridge.feedTempleAction("SlideForward")
    override fun onTPSlideBackward(args: FlingArgs): Boolean = bridge.feedTempleAction("SlideBackward")
    override fun onTPSlideUpwards(args: FlingArgs): Boolean = bridge.feedTempleAction("SlideUpwards")
    override fun onTPSlideDownwards(args: FlingArgs): Boolean = bridge.feedTempleAction("SlideDownwards")
}
