package com.halo.ring.core.device

import com.halo.ring.core.action.GlassAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TempleActionMappingTest {

    @Test fun `slide forward maps to NavNext`() {
        assertEquals(GlassAction.NavNext, mapTempleActionToGlassAction("SlideForward"))
    }

    @Test fun `slide downwards X3 vertical maps to NavNext`() {
        // X3 Pro adds vertical slides; SlideDownwards is the same conceptual direction as the
        // ring's SWIPE_DOWN, which the default Navigation profile binds to NavNext.
        assertEquals(GlassAction.NavNext, mapTempleActionToGlassAction("SlideDownwards"))
    }

    @Test fun `slide backward and upwards map to NavPrev`() {
        assertEquals(GlassAction.NavPrev, mapTempleActionToGlassAction("SlideBackward"))
        assertEquals(GlassAction.NavPrev, mapTempleActionToGlassAction("SlideUpwards"))
    }

    @Test fun `click maps to Confirm`() {
        assertEquals(GlassAction.Confirm, mapTempleActionToGlassAction("Click"))
    }

    @Test fun `double click maps to Back`() {
        assertEquals(GlassAction.Back, mapTempleActionToGlassAction("DoubleClick"))
    }

    @Test fun `triple click maps to ProfileCycle`() {
        assertEquals(GlassAction.ProfileCycle, mapTempleActionToGlassAction("TripleClick"))
    }

    @Test fun `long click is unmapped`() {
        // Let Mercury handle long-press menus natively — don't shadow.
        assertNull(mapTempleActionToGlassAction("LongClick"))
    }

    @Test fun `idle and unrecognised actions are null`() {
        assertNull(mapTempleActionToGlassAction("Idle"))
        assertNull(mapTempleActionToGlassAction("ActionDown"))
        assertNull(mapTempleActionToGlassAction("ActionUp"))
        assertNull(mapTempleActionToGlassAction("DoubleFingerClick"))
        assertNull(mapTempleActionToGlassAction("DoubleFingerLongClick"))
        assertNull(mapTempleActionToGlassAction(""))
        assertNull(mapTempleActionToGlassAction("MoveUp"))
        assertNull(mapTempleActionToGlassAction("SlideContinuous"))
    }
}
