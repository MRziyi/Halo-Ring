package com.halo.ring.core.modal

import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.action.ModalSentinel
import com.halo.ring.core.gesture.Gesture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModalsTest {

    // ── Volume modal ─────────────────────────────────────────────────────────────────────────

    @Test fun `volume modal swipe-up emits VolumeUp`() {
        assertEquals(GlassAction.VolumeUp, VolumeModal().handle(Gesture.SWIPE_UP))
    }

    @Test fun `volume modal swipe-down emits VolumeDown`() {
        assertEquals(GlassAction.VolumeDown, VolumeModal().handle(Gesture.SWIPE_DOWN))
    }

    @Test fun `volume modal tap exits with confirmed`() {
        assertEquals(ModalSentinel.Exit, VolumeModal().handle(Gesture.TAP))
    }

    @Test fun `volume modal double-tap exits with cancelled`() {
        assertEquals(ModalSentinel.Cancel, VolumeModal().handle(Gesture.DOUBLE_TAP))
    }

    @Test fun `volume modal ignores other gestures`() {
        val m = VolumeModal()
        // Long-press, triple-tap, etc. — all None.
        assertEquals(GlassAction.None, m.handle(Gesture.LONG_PRESS))
        assertEquals(GlassAction.None, m.handle(Gesture.TRIPLE_TAP))
        assertEquals(GlassAction.None, m.handle(Gesture.QUADRUPLE_TAP))
        assertEquals(GlassAction.None, m.handle(Gesture.DOUBLE_LONG_PRESS))
    }

    @Test fun `volume modal default timeout is 3 seconds`() {
        assertEquals(3_000L, VolumeModal().timeoutMs)
    }

    // ── Brightness modal ─────────────────────────────────────────────────────────────────────

    @Test fun `brightness modal swipes emit BrightnessUp and Down`() {
        val m = BrightnessModal()
        assertEquals(GlassAction.BrightnessUp,   m.handle(Gesture.SWIPE_UP))
        assertEquals(GlassAction.BrightnessDown, m.handle(Gesture.SWIPE_DOWN))
    }

    @Test fun `brightness modal tap-doubletap exit semantics match volume`() {
        val m = BrightnessModal()
        assertEquals(ModalSentinel.Exit,   m.handle(Gesture.TAP))
        assertEquals(ModalSentinel.Cancel, m.handle(Gesture.DOUBLE_TAP))
    }

    // ── Recents modal ────────────────────────────────────────────────────────────────────────

    @Test fun `recents modal onEnter opens system recents`() {
        assertEquals(GlassAction.Recents, RecentsModal().onEnter())
    }

    @Test fun `recents modal swipes navigate between tasks`() {
        val m = RecentsModal()
        assertEquals(GlassAction.NavPrev, m.handle(Gesture.SWIPE_UP))
        assertEquals(GlassAction.NavNext, m.handle(Gesture.SWIPE_DOWN))
    }

    @Test fun `recents modal tap fires Confirm and exits in one step`() {
        val a = RecentsModal().handle(Gesture.TAP)
        // The router's contract is: receiving FireAndExit(payload) means "dispatch payload AND
        // close the modal". We verify both halves here.
        assertTrue(a is ModalSentinel.FireAndExit, "expected FireAndExit, got $a")
        assertEquals(GlassAction.Confirm, (a as ModalSentinel.FireAndExit).payload)
    }

    @Test fun `recents modal double-tap cancels without firing anything`() {
        assertEquals(ModalSentinel.Cancel, RecentsModal().handle(Gesture.DOUBLE_TAP))
    }

    @Test fun `recents modal default timeout is 5 seconds`() {
        assertEquals(5_000L, RecentsModal().timeoutMs)
    }

    // ── AIDictate modal ──────────────────────────────────────────────────────────────────────

    @Test fun `ai-dictate modal onEnter opens chat`() {
        assertEquals(GlassAction.OpenChat, AIDictateModal().onEnter())
    }

    @Test fun `ai-dictate modal double-tap exits cleanly`() {
        assertEquals(ModalSentinel.Exit, AIDictateModal().handle(Gesture.DOUBLE_TAP))
    }

    @Test fun `ai-dictate modal swipe-up cancels`() {
        assertEquals(ModalSentinel.Cancel, AIDictateModal().handle(Gesture.SWIPE_UP))
    }

    @Test fun `ai-dictate modal default timeout is 30 seconds`() {
        assertEquals(30_000L, AIDictateModal().timeoutMs)
    }
}
