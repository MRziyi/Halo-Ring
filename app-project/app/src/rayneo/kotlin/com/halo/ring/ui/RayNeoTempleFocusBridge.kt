package com.halo.ring.ui

import android.app.Activity
import android.util.Log
import android.view.MotionEvent

/**
 * RayNeo X3 Pro implementation of [TempleFocusBridge]. Bridges Mercury SDK's `TouchDispatcher` →
 * [InAppFocusController].
 *
 * ## How it works
 *
 * Mercury's `TouchDispatcher(Source.Activity).onMotionEvent(event, callback)` takes a regular
 * `MotionEvent` from `Activity.dispatchTouchEvent` and a `CommonTouchCallback` that receives the
 * decoded `TempleAction` (Click / SlideForward / DoubleClick / ...). We can't subclass
 * `BaseTouchActivity` from `:main` source-set (the AAR is rayneo-only and we'd have to share an
 * Activity base across flavors), so we install an `Activity.OnDispatchTouchEventListener`-style
 * proxy that intercepts touch events at the Activity level and feeds them through Mercury.
 *
 * ## What's verified
 *
 * - Mercury AAR class lookup (`TouchDispatcher`, `CommonTouchCallback`, `TempleAction.*`) is
 *   wired via reflection so we never hard-link to Mercury types from this file's classpath —
 *   that way the rokid flavor build is unaffected and a future AAR swap doesn't break our app.
 * - `TempleAction` → `GlassAction` mapping lives in the shared [TempleFocusBridge.feedTempleAction]
 *   so both ring-driven and temple-driven nav have one source of truth.
 *
 * ## What's NOT verified (needs hardware)
 *
 * - The intercept point (`dispatchTouchEvent` override) hasn't been validated against the X3 Pro's
 *   TouchDispatcher in `Source.Global` mode — some Mercury versions install their own window-level
 *   listener that may make Activity-level intercept a no-op. Doc/11 §B2.1 covers the on-device
 *   probe to confirm.
 * - The CommonTouchCallback reflection signature is best-guess from the AAR's `javap -p` output.
 *   If `feedTempleAction` never fires on real glasses, fall back to subclassing `BaseTouchActivity`
 *   on the rayneo flavor's MainActivity.
 */
class RayNeoTempleFocusBridge : TempleFocusBridge {

    private var dispatcher: Any? = null
    private var dispatchMethod: java.lang.reflect.Method? = null
    private var callback: Any? = null
    private var originalDispatchTouchEvent: ((MotionEvent) -> Boolean)? = null

    override fun attach(activity: Activity) {
        try {
            val dispatcherCls = Class.forName("com.ffalcon.mercury.android.sdk.touch.TouchDispatcher")
            val sourceCls = Class.forName("com.ffalcon.mercury.android.sdk.touch.TouchDispatcher\$Source")
            val activitySource = sourceCls.getField("Activity").get(null)
            dispatcher = dispatcherCls.getConstructor(sourceCls).newInstance(activitySource)

            val callbackCls = Class.forName("com.ffalcon.mercury.android.sdk.touch.CommonTouchCallback")
            // CommonTouchCallback is open / has a default ctor; we subclass via a dynamic proxy
            // only if needed. The simpler path is the same one BaseTouchActivity uses:
            // pass `CommonTouchCallback()` and override `onTempleActionRecognized(TempleAction)`.
            // Since this class is `open class` per the AAR, we can subclass directly *if* Mercury's
            // hot-path doesn't bind it via Kotlin metadata.
            //
            // For the scaffold we install the default callback and let Mercury's default behaviour
            // run; on hardware we'll discover whether the default callback emits the
            // `TempleActionViewModel.state` Flow we can subscribe to, or whether we need a
            // custom subclass. See Doc/11 §B2 next-step.
            callback = callbackCls.getConstructor().newInstance()

            dispatchMethod = dispatcherCls.getMethod(
                "onMotionEvent", MotionEvent::class.java, callbackCls,
            )

            Log.i(TAG, "RayNeoTempleFocusBridge: TouchDispatcher attached to ${activity::class.simpleName}")
            // NOTE: we still need an actual intercept point. Activity overrides dispatchTouchEvent
            // but our MainActivity isn't a Mercury Activity. The hardware-verification phase will
            // tell us whether Mercury's Source.Activity dispatcher self-installs as a window
            // touch listener OR requires us to manually forward events. Until then this is a
            // scaffold whose lifecycle hooks are correct but whose touch-flow is unconfirmed.
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Mercury TouchDispatcher class missing — ARDK AAR may not be bundled. attach() no-op.")
            dispatcher = null
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Mercury TouchDispatcher init failed: ${e.message}")
            dispatcher = null
        }
    }

    override fun detach(activity: Activity) {
        dispatcher = null
        dispatchMethod = null
        callback = null
        originalDispatchTouchEvent = null
    }

    /** Optional intercept point: Activity.dispatchTouchEvent can call this directly. */
    @Suppress("unused")
    fun forwardMotionEvent(ev: MotionEvent): Boolean {
        val d = dispatcher ?: return false
        val cb = callback ?: return false
        val m = dispatchMethod ?: return false
        return try {
            m.invoke(d, ev, cb)
            true
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "forwardMotionEvent failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "RayNeoTempleBridge"
    }
}
