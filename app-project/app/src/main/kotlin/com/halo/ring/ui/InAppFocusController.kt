package com.halo.ring.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import com.halo.ring.core.action.GlassAction
import java.util.concurrent.atomic.AtomicReference

/**
 * In-app fast path for GlassActions (Doc/08-ui-design.md §9.1). When [MainActivity] is foreground,
 * [com.halo.ring.service.HaloRingService] routes navigation-class actions here instead of
 * through the executor backend → agent → InputDispatcher loop. Same effect, ~50 ms instead of
 * ~80 ms, no dependency on the agent being alive.
 *
 * Set the active [FocusManager] in `MainActivity.onResume`; clear it in `onPause`.
 */
object InAppFocusController {

    private val focusManager = AtomicReference<FocusManager?>(null)
    private val backController = AtomicReference<BackController?>(null)
    private val tabController = AtomicReference<TabController?>(null)
    /** [route] is called from the gesture pipeline (scheduler thread). Compose's [FocusManager],
     *  [BackController], and [TabController] are all main-thread-only — post the actual mutation. */
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(focusManager: FocusManager, back: BackController, tabs: TabController) {
        this.focusManager.set(focusManager)
        this.backController.set(back)
        this.tabController.set(tabs)
    }

    fun detach() {
        focusManager.set(null)
        backController.set(null)
        tabController.set(null)
        tone.getAndSet(null)?.let { try { it.release() } catch (_: Throwable) {} }
    }

    /** True iff there's a foreground attachment we can route to. */
    val isActive: Boolean get() = focusManager.get() != null

    /** True when focus is currently on the tab strip (vs the content list). Set by [TabBar]'s
     *  `onFocusChanged` handler. Drives the contextual remap of `NavPrev`/`NavNext` to tab cycling. */
    @Volatile var focusOnTabStrip: Boolean = false

    /** When true, play a brief tone on every accepted nav / tab-cycle / confirm-to-content. Mirrors
     *  Rokid Sprite Launcher's per-focus-move beep (`ToneGenerator(STREAM_NOTIFICATION, ...)`,
     *  TONE_PROP_BEEP, ~30 ms) — see Rokid `sprite-launcher.md` §"Activity Hierarchy". Synced from
     *  [com.halo.ring.ui.screens.FeedbackPrefs.clickSoundOnModeSwitch] by the foreground service so
     *  the user's single "click sound" pref governs both mode-switch feedback and focus-move
     *  feedback. Audit-2026-05-13s. */
    @Volatile var clickSoundEnabled: Boolean = false

    /** Lazy because [ToneGenerator] reserves an audio session — only allocate if the user has the
     *  click-sound pref on. Released in [detach]. */
    private val tone = AtomicReference<ToneGenerator?>(null)

    private fun playClick() {
        if (!clickSoundEnabled) return
        // P3-17: post to main. The first ToneGenerator construction binds an audio session and can
        // take 5–15 ms — when it ran inline on the scheduler thread, the very first focus-move per
        // session paid that cost on the gesture-dispatch hot path. Posting unblocks the pipeline;
        // the beep latency (audible) doesn't change because audio is async anyway.
        mainHandler.post {
            var t = tone.get()
            if (t == null) {
                t = try {
                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35)
                } catch (e: RuntimeException) {
                    Log.w("InAppFocusController", "ToneGenerator unavailable: ${e.message}")
                    null
                }
                if (t != null) tone.compareAndSet(null, t)
            }
            try { t?.startTone(ToneGenerator.TONE_PROP_BEEP, 30) } catch (_: RuntimeException) {}
        }
    }

    /**
     * Try to route [action] through the in-app focus system. Returns true if handled — caller
     * should then NOT dispatch through the executor backend.
     *
     * **Threading**: this is called from the scheduler thread (the gesture pipeline). All actual
     * mutations of [FocusManager] / [BackController] / [TabController] are posted to the main
     * thread because Compose state and FocusManager.moveFocus are main-thread-only. The `Boolean`
     * we return is "did we accept this action" (and therefore "skip the executor backend"), not
     * "did the action complete synchronously" — the focus move happens shortly after.
     *
     * Tab-strip awareness: when [focusOnTabStrip] is true, `NavPrev` cycles tabs left and `NavNext`
     * cycles tabs right (since the ring has no left/right swipes — Doc/08-ui-design.md §9.1.1).
     */
    fun route(action: GlassAction): Boolean {
        val fm = focusManager.get() ?: return false
        val back = backController.get()
        val tabs = tabController.get()

        // Decide synchronously what we'd accept; do the mutation on the main thread.
        fun postFocus(direction: FocusDirection) = mainHandler.post { fm.moveFocus(direction) }

        return when (action) {
            GlassAction.NavPrev -> {
                if (focusOnTabStrip && tabs != null) mainHandler.post { tabs.prev() }
                else postFocus(FocusDirection.Up)
                playClick()
                true
            }
            GlassAction.NavNext -> {
                if (focusOnTabStrip && tabs != null) mainHandler.post { tabs.next() }
                else postFocus(FocusDirection.Down)
                playClick()
                true
            }
            // NavLeft / NavRight have no default-profile binding (no left/right swipes from the
            // ring), but user-defined profiles can still bind them.
            GlassAction.NavLeft  -> { postFocus(FocusDirection.Left);  playClick(); true }
            GlassAction.NavRight -> { postFocus(FocusDirection.Right); playClick(); true }

            // Confirm:
            //   - On tab strip (audit-2026-05-13s): the user has cycled to a tab via SWIPE_UP /
            //     DOWN, which already changed `state.tab` and the visible content below. TAP
            //     should commit + drop focus into the first content row so they can continue
            //     navigating the tab's content. Compose's `moveFocus(Down)` walks down out of
            //     the tab strip into the next focusable element (= first content row).
            //   - Otherwise: let DPAD_CENTER flow through to the focused widget's Modifier.clickable
            //     onClick via the standard Android key-event path.
            GlassAction.Confirm  -> {
                if (focusOnTabStrip) {
                    postFocus(FocusDirection.Down)
                    playClick()
                    true
                } else {
                    false
                }
            }

            GlassAction.Back -> {
                // back.pop() returns whether anything was popped. If yes → handled, don't fall
                // through. If no (we're at the root) → drop the Activity back to the system
                // launcher via back.exit() so the wearer doesn't get stuck inside our app
                // (audit-2026-05-13s: previously fell through to the agent's KEYCODE_BACK,
                // which on glasses is often a no-op or just minimises ambiguously).
                if (back?.pop() == true) {
                    true
                } else {
                    if (back != null) {
                        mainHandler.post { back.exit() }
                        true
                    } else false
                }
            }
            GlassAction.Home -> {
                if (back != null) { mainHandler.post { back.exit() }; true } else false
            }
            else -> false
        }
    }
}

/** A two-step "go back / exit" so the navigator can decide whether to pop a sub-screen or finish
 *  the Activity. */
interface BackController {
    /** Try to pop one screen. If there's nothing to pop (we're at the root), return false. */
    fun pop(): Boolean
    /** Leave the app entirely. */
    fun exit()
}

/** Top-level tab switching. */
interface TabController {
    fun prev()
    fun next()
    fun select(tab: AppTab)
    val current: AppTab
}
