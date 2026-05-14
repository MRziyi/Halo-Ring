package com.halo.ring.core.gesture

import com.halo.ring.core.action.ActionRouter
import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.action.ModeManager
import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.device.InjectionPrimitive
import com.halo.ring.core.inject.ExecutorBackend
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Routing-layer tests. Covers the four layers (screen-off fast path → system → modal → profile) and
 * the foreground-bypass shortcut (Doc/08-ui-design.md §9.1).
 */
class InteractionRouterTest {

    /** Records the GlassActions the ActionRouter would have dispatched. */
    private class RecordingBackend : ExecutorBackend {
        override val id = "rec"
        override val priority = 100
        val dispatched = mutableListOf<GlassAction>()
        override fun capabilities() = Capability.values().toSet()
        override fun isReady() = true
        override suspend fun perform(action: GlassAction): Boolean {
            dispatched += action
            return true
        }
    }

    /** Trivial GlassActionMapper that always reports the action's stated capability. */
    private class NoopMapper : GlassActionMapper {
        override fun capabilityFor(action: GlassAction): Capability? = action.needs
        override fun primitives(action: GlassAction): List<InjectionPrimitive> = emptyList()
    }

    private fun fixture(
        screenOn: Boolean = true,
    ): Quad {
        val backend = RecordingBackend()
        val router = ActionRouter(NoopMapper()) { listOf(backend) }
        val mm = ModeManager()
        val ir = InteractionRouter(
            modeManager = mm,
            actionRouter = router,
            onPeekHud = {},
            
        )
        ir.screenOn = screenOn
        return Quad(ir, mm, router, backend)
    }
    private data class Quad(
        val router: InteractionRouter,
        val modeManager: ModeManager,
        val actionRouter: ActionRouter,
        val backend: RecordingBackend,
    )

    // ── screen-off fast path ───────────────────────────────────────────────────────────────────

    @Test fun `screen-off wake gesture fires ScreenWake and is consumed`() = runBlocking<Unit> {
        val (ir, _, _, b) = fixture(screenOn = false)
        val rawSeen = mutableListOf<Pair<RawGesture, Boolean>>()
        ir.onScreenOffGesture = { raw, woke -> rawSeen += raw to woke }
        val consumed = ir.onRawWhileScreenOff(RawGesture.LONG_PRESS)
        assertTrue(consumed)
        assertEquals(listOf<GlassAction>(GlassAction.ScreenWake), b.dispatched)
        assertEquals(listOf(RawGesture.LONG_PRESS to true), rawSeen)
    }

    @Test fun `screen-off non-wake raw is silently dropped`() = runBlocking<Unit> {
        val (ir, _, _, b) = fixture(screenOn = false)
        val seen = mutableListOf<Pair<RawGesture, Boolean>>()
        ir.onScreenOffGesture = { r, w -> seen += r to w }
        val consumed = ir.onRawWhileScreenOff(RawGesture.TOUCH)
        assertTrue(consumed)
        assertTrue(b.dispatched.isEmpty())
        assertEquals(listOf(RawGesture.TOUCH to false), seen)
    }

    @Test fun `when screen is on the screen-off path returns false (not consumed)`() = runBlocking<Unit> {
        val (ir, _, _, _) = fixture(screenOn = true)
        assertEquals(false, ir.onRawWhileScreenOff(RawGesture.LONG_PRESS))
    }

    // ── system layer ───────────────────────────────────────────────────────────────────────────

    @Test fun `TRIPLE_TAP cycles the profile and never reaches the backend`() = runBlocking<Unit> {
        val (ir, mm, _, b) = fixture()
        val firstActive = mm.active().id
        ir.onGesture(Gesture.TRIPLE_TAP)
        assertTrue(mm.active().id != firstActive, "profile should have cycled")
        assertTrue(b.dispatched.isEmpty(), "system-level cycle must not hit the backend")
    }

    @Test fun `LONG_PRESS_SWIPE_DOWN routes to ScreenSleep system action`() = runBlocking<Unit> {
        val (ir, _, _, b) = fixture()
        ir.onGesture(Gesture.LONG_PRESS_SWIPE_DOWN)
        assertEquals(listOf<GlassAction>(GlassAction.ScreenSleep), b.dispatched)
    }

    // ── profile layer ──────────────────────────────────────────────────────────────────────────

    @Test fun `TAP in default Navigation profile dispatches Confirm to the backend`() = runBlocking<Unit> {
        val (ir, _, _, b) = fixture()
        ir.onGesture(Gesture.TAP)
        assertEquals(listOf<GlassAction>(GlassAction.Confirm), b.dispatched)
    }

    // ── foreground bypass (inAppShortCircuit) ──────────────────────────────────────────────────

    @Test fun `inAppShortCircuit returning true prevents backend dispatch`() = runBlocking<Unit> {
        val (ir, _, _, b) = fixture()
        val shortCircuited = mutableListOf<GlassAction>()
        ir.inAppShortCircuit = { action ->
            shortCircuited += action
            true   // claim we handled it
        }
        ir.onGesture(Gesture.TAP)
        assertEquals(listOf<GlassAction>(GlassAction.Confirm), shortCircuited.toList())
        assertTrue(b.dispatched.isEmpty(), "backend must be skipped when shortcut returns true")
    }

    @Test fun `inAppShortCircuit returning false falls through to backend dispatch`() = runBlocking<Unit> {
        val (ir, _, _, b) = fixture()
        ir.inAppShortCircuit = { false }
        ir.onGesture(Gesture.TAP)
        assertEquals(listOf<GlassAction>(GlassAction.Confirm), b.dispatched)
    }

    @Test fun `inAppShortCircuit is NOT consulted for in-app pseudo-actions`() = runBlocking<Unit> {
        // PeekHud / ProfileCycle — handled by the router itself (no dispatch() call). The
        // dispatch() function early-returns for these so the in-app shortcut isn't even given the
        // chance to handle them. (ForceReconnect used to be in this list; it was retired from the
        // system slot in audit-pass 2026-05-14w in favour of OpenAIAssistant, which IS routed
        // through dispatch() because it's a real Intent-launching action.)
        val (ir, _, _, b) = fixture()
        val seen = mutableListOf<GlassAction>()
        ir.inAppShortCircuit = { seen += it; true }
        ir.onGesture(Gesture.TRIPLE_TAP)    // ProfileCycle, handled before dispatch()
        ir.onGesture(Gesture.QUADRUPLE_TAP) // PeekHud,      handled before dispatch()
        assertTrue(seen.isEmpty(), "in-app pseudos are returned-before-dispatch")
        assertTrue(b.dispatched.isEmpty())
    }

    @Test fun `DOUBLE_LONG_PRESS triggers OpenAIAssistant via the standard dispatch path`() = runBlocking<Unit> {
        // Audit-pass 2026-05-14w: the DOUBLE_LONG_PRESS system slot now points at
        // GlassAction.OpenAIAssistant (was ForceReconnect). It's routed through dispatch() so
        // the per-flavor FeatureIntents impl can handle the actual launch — distinct from PeekHud
        // / ProfileCycle which the router handles itself.
        val (ir, _, _, b) = fixture()
        ir.onGesture(Gesture.DOUBLE_LONG_PRESS)
        assertEquals(listOf<GlassAction>(GlassAction.OpenAIAssistant), b.dispatched)
    }
}
