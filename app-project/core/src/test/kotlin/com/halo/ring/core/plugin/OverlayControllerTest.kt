package com.halo.ring.core.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayControllerTest {

    @Test fun `starts inactive`() {
        val c = OverlayController()
        assertFalse(c.isActive())
        assertNull(c.active())
        assertNull(c.ownerPackage())
    }

    @Test fun `activate sets the active overlay and reports new`() {
        val c = OverlayController()
        val isNew = c.activate("com.constellation.glass", "hud", "Constellation", nowMs = 1000)
        assertTrue(isNew)
        assertTrue(c.isActive())
        assertEquals("com.constellation.glass", c.ownerPackage())
        assertEquals("Constellation", c.active()?.displayName)
    }

    @Test fun `re-activate same owner+id is a keepalive (not new) and refreshes the timer`() {
        val c = OverlayController(timeoutMs = 1000)
        c.activate("p", "hud", "P", nowMs = 0)
        val isNew = c.activate("p", "hud", "P", nowMs = 800)   // keepalive within window
        assertFalse(isNew)
        assertFalse(c.expireIfStale(nowMs = 1500))             // 1500-800=700 < 1000 → still alive
        assertTrue(c.isActive())
    }

    @Test fun `activate by a different owner replaces the prior (single-active)`() {
        val c = OverlayController()
        c.activate("a", "x", "A", nowMs = 0)
        val isNew = c.activate("b", "y", "B", nowMs = 10)
        assertTrue(isNew)
        assertEquals("b", c.ownerPackage())
    }

    @Test fun `deactivate matches owner+id only`() {
        val c = OverlayController()
        c.activate("p", "hud", "P", nowMs = 0)
        assertFalse(c.deactivate("p", "other"))
        assertTrue(c.isActive())
        assertTrue(c.deactivate("p", "hud"))
        assertFalse(c.isActive())
    }

    @Test fun `deactivateOwner drops whatever that owner has`() {
        val c = OverlayController()
        c.activate("p", "hud", "P", nowMs = 0)
        assertFalse(c.deactivateOwner("other"))
        assertTrue(c.deactivateOwner("p"))
        assertFalse(c.isActive())
    }

    @Test fun `expireIfStale releases after the keepalive timeout`() {
        val c = OverlayController(timeoutMs = 1000)
        c.activate("p", "hud", "P", nowMs = 0)
        assertFalse(c.expireIfStale(nowMs = 999))
        assertTrue(c.isActive())
        assertTrue(c.expireIfStale(nowMs = 1001))   // 1001 > timeout → released
        assertFalse(c.isActive())
        assertFalse(c.expireIfStale(nowMs = 2000))  // idempotent once cleared
    }
}
