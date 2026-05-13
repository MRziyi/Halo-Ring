package com.halo.ring.core.action

import com.halo.ring.core.gesture.Gesture
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyMapProfileTest {

    private val empty = KeyMapProfile(id = "p", name = "P", map = emptyMap())

    @Test fun `actionFor returns None for an unmapped gesture`() {
        assertEquals(GlassAction.None, empty.actionFor(Gesture.TAP))
    }

    @Test fun `withMapping inserts a new gesture binding`() {
        val updated = empty.withMapping(Gesture.TAP, GlassAction.Confirm)
        assertEquals(GlassAction.Confirm, updated.actionFor(Gesture.TAP))
        // original is untouched (immutable)
        assertEquals(GlassAction.None, empty.actionFor(Gesture.TAP))
    }

    @Test fun `withMapping overwrites an existing binding`() {
        val one = empty.withMapping(Gesture.TAP, GlassAction.Confirm)
        val two = one.withMapping(Gesture.TAP, GlassAction.Back)
        assertEquals(GlassAction.Back, two.actionFor(Gesture.TAP))
    }

    @Test fun `withMapping to None explicitly unbinds without removing the key`() {
        // Why preserve the key: the editor wants to render "(no action)" for explicitly unbound
        // slots vs. "(unmapped)" for never-set ones. We don't need to differentiate today but
        // future telemetry might.
        val bound = empty.withMapping(Gesture.SWIPE_UP, GlassAction.NavPrev)
        val cleared = bound.withMapping(Gesture.SWIPE_UP, GlassAction.None)
        assertEquals(GlassAction.None, cleared.actionFor(Gesture.SWIPE_UP))
        assertEquals(true, cleared.map.containsKey(Gesture.SWIPE_UP))
    }

    @Test fun `withMapping preserves all other fields`() {
        val rich = KeyMapProfile(
            id = "p", name = "P",
            map = mapOf(Gesture.TAP to GlassAction.Confirm),
            triggerPackages = listOf("com.example"),
        )
        val updated = rich.withMapping(Gesture.SWIPE_UP, GlassAction.NavPrev)
        assertEquals(rich.id, updated.id)
        assertEquals(rich.name, updated.name)
        assertEquals(rich.gestureConfig, updated.gestureConfig)
        assertEquals(rich.triggerPackages, updated.triggerPackages)
        // both bindings are present
        assertEquals(GlassAction.Confirm, updated.actionFor(Gesture.TAP))
        assertEquals(GlassAction.NavPrev, updated.actionFor(Gesture.SWIPE_UP))
    }
}
