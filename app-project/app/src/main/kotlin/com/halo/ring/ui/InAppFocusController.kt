package com.halo.ring.ui

import android.os.Handler
import android.os.Looper
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
    }

    /** True iff there's a foreground attachment we can route to. */
    val isActive: Boolean get() = focusManager.get() != null

    /** True when focus is currently on the tab strip (vs the content list). Set by [TabBar]'s
     *  `onFocusChanged` handler. Drives the contextual remap of `NavPrev`/`NavNext` to tab cycling. */
    @Volatile var focusOnTabStrip: Boolean = false

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
                true
            }
            GlassAction.NavNext -> {
                if (focusOnTabStrip && tabs != null) mainHandler.post { tabs.next() }
                else postFocus(FocusDirection.Down)
                true
            }
            // NavLeft / NavRight have no default-profile binding (no left/right swipes from the
            // ring), but user-defined profiles can still bind them.
            GlassAction.NavLeft  -> { postFocus(FocusDirection.Left);  true }
            GlassAction.NavRight -> { postFocus(FocusDirection.Right); true }

            // Confirm: Compose's Modifier.clickable() automatically maps DPAD_CENTER / Enter on a
            // focused element to onClick. The agent injects DPAD_CENTER for Confirm anyway, which
            // arrives at our Activity, finds the focused composable, fires its onClick. We don't
            // need to handle Confirm here — let it flow through the normal Android key path.
            GlassAction.Confirm  -> false

            GlassAction.Back -> {
                // back.pop() returns whether anything was popped — we need the answer *now* to
                // decide if we should let the action fall through to the executor (KEYCODE_BACK at
                // the OS level). Compose Snapshot reads from background threads are safe, but to
                // be defensive we keep the back/exit indirection synchronous and rely on the
                // BackController impl to not touch Compose state directly. HaloRingApp's pop() reads
                // its local `state` only.
                back?.pop() ?: false
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
