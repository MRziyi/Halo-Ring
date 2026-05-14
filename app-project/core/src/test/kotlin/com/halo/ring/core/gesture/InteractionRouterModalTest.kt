package com.halo.ring.core.gesture

import com.halo.ring.core.action.ActionRouter
import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.DefaultProfiles
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.action.ModalSentinel
import com.halo.ring.core.action.ModeManager
import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.device.InjectionPrimitive
import com.halo.ring.core.inject.ExecutorBackend
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Modal-layer coverage that complements [InteractionRouterTest]:
 *   - Modal swallowing a gesture (regular action and `None`)
 *   - Three sentinel exits (Exit / Cancel / FireAndExit)
 *   - Profile-bound `Enter*Modal` calls `onEnterModal` and never reaches a backend
 *   - System gestures preempt active modal
 *   - `onGestureRecognized` carries the resolved action including sentinels
 * Source of truth: [InteractionRouter] §2 (modal layer).
 */
class InteractionRouterModalTest {

    private class RecordingBackend : ExecutorBackend {
        override val id = "rec"
        override val priority = 100
        val dispatched = mutableListOf<GlassAction>()
        override fun capabilities() = Capability.values().toSet()
        override fun isReady() = true
        override suspend fun perform(action: GlassAction): Boolean {
            dispatched += action; return true
        }
    }

    private class NoopMapper : GlassActionMapper {
        override fun capabilityFor(action: GlassAction): Capability? = action.needs
        override fun primitives(action: GlassAction): List<InjectionPrimitive> = emptyList()
    }

    /** Programmable modal — each call to [handle] pops the next pre-loaded response. */
    private open class ScriptedModal(
        private val responses: ArrayDeque<GlassAction>,
        override val timeoutMs: Long = 3_000L,
    ) : Modal {
        val seenGestures = mutableListOf<Gesture>()
        override fun handle(gesture: Gesture): GlassAction {
            seenGestures += gesture
            return if (responses.isEmpty()) GlassAction.None else responses.removeFirst()
        }
    }

    private fun fixture(): Quad {
        val backend = RecordingBackend()
        val router = ActionRouter(NoopMapper()) { listOf(backend) }
        val mm = ModeManager()
        val modalEntries = mutableListOf<GlassAction>()
        val ir = InteractionRouter(
            modeManager = mm,
            actionRouter = router,
            onPeekHud = {},
            
            onEnterModal = { modalEntries += it },
        )
        ir.screenOn = true
        return Quad(ir, mm, backend, modalEntries)
    }

    private data class Quad(
        val router: InteractionRouter,
        val modeManager: ModeManager,
        val backend: RecordingBackend,
        val modalEntries: MutableList<GlassAction>,
    )

    @Test fun `active modal handles the gesture instead of the profile`() = runBlocking<Unit> {
        val (ir, _, b, _) = fixture()
        val modal = ScriptedModal(ArrayDeque(listOf(GlassAction.VolumeUp)))
        ir.activeModal = modal
        ir.onGesture(Gesture.SWIPE_UP)

        assertEquals(listOf(Gesture.SWIPE_UP), modal.seenGestures)
        assertEquals(listOf<GlassAction>(GlassAction.VolumeUp), b.dispatched)
        // The modal didn't exit, so it's still active.
        assertSame(modal, ir.activeModal)
    }

    @Test fun `modal returning None drops the gesture and keeps modal active`() = runBlocking<Unit> {
        val (ir, _, b, _) = fixture()
        val modal = ScriptedModal(ArrayDeque(listOf(GlassAction.None)))
        ir.activeModal = modal
        ir.onGesture(Gesture.SWIPE_UP)
        assertTrue(b.dispatched.isEmpty(), "None must not reach the backend")
        assertSame(modal, ir.activeModal)
    }

    @Test fun `ModalSentinel Exit clears activeModal without dispatching`() = runBlocking<Unit> {
        val (ir, _, b, _) = fixture()
        val modal = ScriptedModal(ArrayDeque(listOf(ModalSentinel.Exit)))
        ir.activeModal = modal
        val recognised = mutableListOf<Pair<Gesture, GlassAction>>()
        ir.onGestureRecognized = { g, a -> recognised += g to a }

        ir.onGesture(Gesture.TAP)

        assertNull(ir.activeModal, "Exit must clear the active modal")
        assertTrue(b.dispatched.isEmpty(), "Exit must not dispatch a payload")
        assertEquals(listOf<Pair<Gesture, GlassAction>>(Gesture.TAP to ModalSentinel.Exit), recognised)
    }

    @Test fun `ModalSentinel Cancel clears activeModal without dispatching`() = runBlocking<Unit> {
        val (ir, _, b, _) = fixture()
        ir.activeModal = ScriptedModal(ArrayDeque(listOf(ModalSentinel.Cancel)))
        ir.onGesture(Gesture.DOUBLE_TAP)
        assertNull(ir.activeModal)
        assertTrue(b.dispatched.isEmpty())
    }

    @Test fun `ModalSentinel FireAndExit dispatches its payload and clears the modal`() = runBlocking<Unit> {
        val (ir, _, b, _) = fixture()
        ir.activeModal = ScriptedModal(ArrayDeque(listOf(ModalSentinel.FireAndExit(GlassAction.Confirm))))
        val recognised = mutableListOf<Pair<Gesture, GlassAction>>()
        ir.onGestureRecognized = { g, a -> recognised += g to a }

        ir.onGesture(Gesture.TAP)

        assertNull(ir.activeModal)
        assertEquals(listOf<GlassAction>(GlassAction.Confirm), b.dispatched)
        // onGestureRecognized fires with the unwrapped payload, NOT the sentinel.
        assertEquals(listOf<Pair<Gesture, GlassAction>>(Gesture.TAP to GlassAction.Confirm), recognised)
    }

    @Test fun `successive gestures into the same modal each get handle()`() = runBlocking<Unit> {
        val (ir, _, b, _) = fixture()
        val modal = ScriptedModal(ArrayDeque(listOf(GlassAction.VolumeUp, GlassAction.VolumeDown, ModalSentinel.Exit)))
        ir.activeModal = modal

        ir.onGesture(Gesture.SWIPE_UP)
        ir.onGesture(Gesture.SWIPE_DOWN)
        ir.onGesture(Gesture.TAP)

        assertEquals(listOf(Gesture.SWIPE_UP, Gesture.SWIPE_DOWN, Gesture.TAP), modal.seenGestures)
        assertEquals(listOf<GlassAction>(GlassAction.VolumeUp, GlassAction.VolumeDown), b.dispatched)
        assertNull(ir.activeModal)
    }

    @Test fun `system gestures preempt active modal — TRIPLE_TAP still cycles profile`() = runBlocking<Unit> {
        val (ir, mm, b, _) = fixture()
        val before = mm.active().id
        val modal = ScriptedModal(ArrayDeque(listOf(GlassAction.None)))
        ir.activeModal = modal

        ir.onGesture(Gesture.TRIPLE_TAP)

        assertSame(modal, ir.activeModal, "modal stays alive — system gesture didn't exit it")
        assertTrue(modal.seenGestures.isEmpty(), "modal should never see TRIPLE_TAP — system layer wins")
        assertTrue(mm.active().id != before, "profile cycled")
        assertTrue(b.dispatched.isEmpty())
    }

    @Test fun `profile-bound EnterVolumeModal action calls onEnterModal and never reaches backend`() = runBlocking<Unit> {
        // Wire a custom profile where LONG_PRESS_SWIPE_UP enters the volume modal.
        val (ir, mm, b, entries) = fixture()
        mm.upsert(
            DefaultProfiles.NAVIGATION.copy(
                map = DefaultProfiles.NAVIGATION.map + (Gesture.LONG_PRESS_SWIPE_UP to GlassAction.EnterVolumeModal),
            )
        )

        ir.onGesture(Gesture.LONG_PRESS_SWIPE_UP)

        assertEquals(listOf<GlassAction>(GlassAction.EnterVolumeModal), entries)
        assertTrue(b.dispatched.isEmpty(), "modal entry is a UI hook, not an injection")
        assertNull(ir.activeModal, "router doesn't auto-install the modal — the service does that in onEnterModal")
    }

    @Test fun `modal timeoutMs is the contract the foreground service must honour`() = runBlocking<Unit> {
        // The router doesn't auto-fire the timeout itself — that's the foreground service's job
        // (HaloRingService schedules scheduler.postDelayed(modal.timeoutMs) when it sets
        // ir.activeModal). This test simulates that wiring with a ManualScheduler and verifies the
        // expected end state: timer fires → activeModal == null, onExit(TIMEOUT) was invoked.
        val (ir, _, _, _) = fixture()
        val sched = ManualScheduler()
        val onExitCalls = mutableListOf<ModalExitReason>()

        val modal = object : ScriptedModal(ArrayDeque(emptyList())) {
            override val timeoutMs: Long = 3_000L
            override fun onExit(reason: ModalExitReason): GlassAction {
                onExitCalls += reason
                return GlassAction.None
            }
        }
        ir.activeModal = modal

        // Simulate what HaloRingService does on onEnterModal.
        sched.postDelayed(modal.timeoutMs) {
            if (ir.activeModal === modal) {
                val finalAction = modal.onExit(ModalExitReason.TIMEOUT)
                ir.activeModal = null
                // service would dispatch finalAction here if non-None; assert it's None for our modal
                assertSame(GlassAction.None, finalAction)
            }
        }

        sched.advanceBy(2_999)
        assertSame(modal, ir.activeModal, "modal must still be active 1 ms before timeout")
        sched.advanceBy(2)  // crosses the boundary
        assertNull(ir.activeModal, "modal must auto-exit at timeoutMs")
        assertEquals(listOf(ModalExitReason.TIMEOUT), onExitCalls)
    }

    @Test fun `gestures while screen off never reach the modal even if one is set`() = runBlocking<Unit> {
        val (ir, _, b, _) = fixture()
        ir.screenOn = false
        val modal = ScriptedModal(ArrayDeque(listOf(GlassAction.VolumeUp)))
        ir.activeModal = modal

        // Belt-and-braces guard in onGesture — early-return if !screenOn.
        ir.onGesture(Gesture.SWIPE_UP)

        assertTrue(modal.seenGestures.isEmpty())
        assertTrue(b.dispatched.isEmpty())
        assertSame(modal, ir.activeModal)
    }
}
