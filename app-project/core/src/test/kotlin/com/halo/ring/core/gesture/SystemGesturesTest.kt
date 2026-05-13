package com.halo.ring.core.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemGesturesTest {

    @Test fun `defaults match the design's recommended slots`() {
        val sg = SystemGestures()
        assertEquals(Gesture.LONG_PRESS,            sg.wake)
        assertEquals(Gesture.LONG_PRESS_SWIPE_DOWN, sg.sleep)
        assertEquals(Gesture.TRIPLE_TAP,            sg.profileCycle)
        assertEquals(Gesture.QUADRUPLE_TAP,         sg.peekHud)
        assertEquals(Gesture.DOUBLE_LONG_PRESS,     sg.forceReconnect)
    }

    @Test fun `withSlot rebinds the targeted slot, leaves the others`() {
        val before = SystemGestures()
        val after = before.withSlot(SystemGestures.Slot.WAKE, Gesture.SWIPE_UP)
        assertEquals(Gesture.SWIPE_UP, after.wake)
        assertEquals(before.sleep,         after.sleep)
        assertEquals(before.profileCycle,  after.profileCycle)
        assertEquals(before.peekHud,       after.peekHud)
        assertEquals(before.forceReconnect, after.forceReconnect)
    }

    @Test fun `withSlot can clear a slot by passing null`() {
        val after = SystemGestures().withSlot(SystemGestures.Slot.PEEK_HUD, null)
        assertNull(after.peekHud)
    }

    @Test fun `gestureFor returns the current binding for each slot`() {
        val sg = SystemGestures(
            wake = Gesture.SWIPE_UP,
            sleep = Gesture.SWIPE_DOWN,
            profileCycle = Gesture.TAP,
            peekHud = Gesture.DOUBLE_TAP,
            forceReconnect = Gesture.TRIPLE_TAP,
        )
        assertEquals(Gesture.SWIPE_UP,    sg.gestureFor(SystemGestures.Slot.WAKE))
        assertEquals(Gesture.SWIPE_DOWN,  sg.gestureFor(SystemGestures.Slot.SLEEP))
        assertEquals(Gesture.TAP,         sg.gestureFor(SystemGestures.Slot.PROFILE_CYCLE))
        assertEquals(Gesture.DOUBLE_TAP,  sg.gestureFor(SystemGestures.Slot.PEEK_HUD))
        assertEquals(Gesture.TRIPLE_TAP,  sg.gestureFor(SystemGestures.Slot.FORCE_RECONNECT))
    }

    // ── conflict detection ──────────────────────────────────────────────────────────────────────

    @Test fun `conflict reports the slot already bound to a gesture`() {
        val sg = SystemGestures()  // defaults
        // TRIPLE_TAP is bound to PROFILE_CYCLE in the defaults.
        assertEquals(SystemGestures.Slot.PROFILE_CYCLE, sg.conflict(Gesture.TRIPLE_TAP))
    }

    @Test fun `conflict returns null for a gesture that is free`() {
        val sg = SystemGestures()
        // None of the defaults use a bare DOUBLE_TAP.
        assertNull(sg.conflict(Gesture.DOUBLE_TAP))
    }

    @Test fun `conflict excludes the slot the user is rebinding`() {
        // Self-conflict check: rebinding WAKE to its current value (LONG_PRESS) should NOT report
        // a conflict, because the user hasn't actually changed anything.
        val sg = SystemGestures()
        assertNull(sg.conflict(Gesture.LONG_PRESS, exclude = SystemGestures.Slot.WAKE))
        // But asking "is LONG_PRESS bound to anything but WAKE?" should still say no.
        assertEquals(SystemGestures.Slot.WAKE, sg.conflict(Gesture.LONG_PRESS))
    }

    @Test fun `conflict reports the FIRST matching slot in declaration order`() {
        // If we deliberately bind two slots to the same gesture (shouldn't happen via the UI's
        // conflict check, but the data type doesn't prevent it), conflict() returns the first.
        val sg = SystemGestures(wake = Gesture.SWIPE_UP, sleep = Gesture.SWIPE_UP)
        assertEquals(SystemGestures.Slot.WAKE, sg.conflict(Gesture.SWIPE_UP))
    }
}
