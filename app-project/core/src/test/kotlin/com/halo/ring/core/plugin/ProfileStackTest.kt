package com.halo.ring.core.plugin

import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.gesture.Gesture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileStackTest {

    private fun frame(
        id: String,
        owner: String,
        bindings: Map<Gesture, GlassAction>,
        ts: Long = 0L,
    ) = ProfileStack.PushedProfile(id, owner, bindings, ts)

    @Test fun `empty stack returns null for every lookup`() {
        val s = ProfileStack()
        assertNull(s.lookup(Gesture.TAP))
        assertNull(s.lookup(Gesture.SWIPE_UP))
        assertEquals(0, s.size())
    }

    @Test fun `single push then lookup hits the binding`() {
        val s = ProfileStack()
        val act = GlassAction.PluginAction("com.x", "do", "Do")
        s.push(frame("p1", "com.x", mapOf(Gesture.TAP to act)))
        assertEquals(act, s.lookup(Gesture.TAP))
        assertNull(s.lookup(Gesture.SWIPE_UP))  // not bound → null (caller falls through)
    }

    @Test fun `LIFO — newer push wins for the same gesture`() {
        val s = ProfileStack()
        val a1 = GlassAction.PluginAction("com.x", "older", "Older")
        val a2 = GlassAction.PluginAction("com.y", "newer", "Newer")
        s.push(frame("p1", "com.x", mapOf(Gesture.TAP to a1)))
        s.push(frame("p2", "com.y", mapOf(Gesture.TAP to a2)))
        assertEquals(a2, s.lookup(Gesture.TAP))
    }

    @Test fun `fall-through — gesture not in top frame consults the next-newest`() {
        val s = ProfileStack()
        val tapAct = GlassAction.PluginAction("com.x", "tap", "Tap")
        val swipeAct = GlassAction.PluginAction("com.y", "swp", "Swipe")
        s.push(frame("p1", "com.x", mapOf(Gesture.TAP to tapAct)))
        s.push(frame("p2", "com.y", mapOf(Gesture.SWIPE_UP to swipeAct)))
        // Top frame only binds SWIPE_UP; TAP falls through to the older frame.
        assertEquals(tapAct, s.lookup(Gesture.TAP))
        assertEquals(swipeAct, s.lookup(Gesture.SWIPE_UP))
    }

    @Test fun `pop removes a specific frame and returns true`() {
        val s = ProfileStack()
        s.push(frame("p1", "com.x", mapOf(Gesture.TAP to GlassAction.None)))
        s.push(frame("p2", "com.y", mapOf(Gesture.TAP to GlassAction.None)))
        assertTrue(s.pop("com.x", "p1"))
        assertEquals(1, s.size())
        assertEquals("p2", s.snapshot()[0].profileId)
    }

    @Test fun `pop of unknown owner-id pair is a no-op returning false`() {
        val s = ProfileStack()
        s.push(frame("p1", "com.x", mapOf(Gesture.TAP to GlassAction.None)))
        assertFalse(s.pop("com.unknown", "p1"))
        assertFalse(s.pop("com.x", "wrong_id"))
        assertEquals(1, s.size())  // unchanged
    }

    @Test fun `re-pushing the same owner+id replaces in-place at top`() {
        val s = ProfileStack()
        val v1 = GlassAction.PluginAction("com.x", "v1", "v1")
        val v2 = GlassAction.PluginAction("com.x", "v2", "v2")
        s.push(frame("p1", "com.x", mapOf(Gesture.TAP to v1)))
        // Push a different frame, then re-push p1 with a new binding.
        s.push(frame("p2", "com.y", mapOf(Gesture.SWIPE_UP to GlassAction.None)))
        s.push(frame("p1", "com.x", mapOf(Gesture.TAP to v2)))
        // p1 should be at TOP (after the replace), with the v2 binding.
        assertEquals(2, s.size())
        assertEquals("p1", s.snapshot().last().profileId)
        assertEquals(v2, s.lookup(Gesture.TAP))
    }

    @Test fun `dropOwner removes every frame for that package`() {
        val s = ProfileStack()
        s.push(frame("p1", "com.x", mapOf(Gesture.TAP to GlassAction.None)))
        s.push(frame("p2", "com.x", mapOf(Gesture.SWIPE_UP to GlassAction.None)))
        s.push(frame("p3", "com.y", mapOf(Gesture.SWIPE_DOWN to GlassAction.None)))
        val removed = s.dropOwner("com.x")
        assertEquals(2, removed)
        assertEquals(1, s.size())
        assertEquals("com.y", s.snapshot()[0].ownerPackage)
    }

    @Test fun `dropOwner on a package with no frames returns 0 and changes nothing`() {
        val s = ProfileStack()
        s.push(frame("p1", "com.x", mapOf(Gesture.TAP to GlassAction.None)))
        assertEquals(0, s.dropOwner("com.unknown"))
        assertEquals(1, s.size())
    }

    @Test fun `clear empties the stack`() {
        val s = ProfileStack()
        s.push(frame("p1", "com.x", mapOf(Gesture.TAP to GlassAction.None)))
        s.push(frame("p2", "com.y", mapOf(Gesture.SWIPE_UP to GlassAction.None)))
        s.clear()
        assertEquals(0, s.size())
        assertNull(s.lookup(Gesture.TAP))
    }

    @Test fun `snapshot is a defensive copy (mutating it doesn't touch the stack)`() {
        val s = ProfileStack()
        s.push(frame("p1", "com.x", mapOf(Gesture.TAP to GlassAction.None)))
        val snap = s.snapshot().toMutableList()
        snap.clear()
        assertEquals(1, s.size())
    }
}

class PluginBindingsParserTest {

    private fun externalJson(pkg: String, actionId: String, label: String = actionId) =
        """{"type":"external","package":"$pkg","action_id":"$actionId","label":"$label"}"""

    @Test fun `empty input returns empty map`() {
        assertEquals(emptyMap(), PluginBindingsParser.parse(""))
        assertEquals(emptyMap(), PluginBindingsParser.parse("{}"))
    }

    @Test fun `non-object input returns empty map`() {
        assertEquals(emptyMap(), PluginBindingsParser.parse("not json"))
        assertEquals(emptyMap(), PluginBindingsParser.parse("[1,2,3]"))
    }

    @Test fun `single external binding parses`() {
        val json = """{"TAP": ${externalJson("com.x", "go", "Go")}}"""
        val out = PluginBindingsParser.parse(json)
        assertEquals(1, out.size)
        assertEquals(GlassAction.PluginAction("com.x", "go", "Go"), out[Gesture.TAP])
    }

    @Test fun `multiple bindings preserve all entries`() {
        val json = """
            {
              "SWIPE_UP":   ${externalJson("com.con", "hud_focus_prev", "Focus prev")},
              "SWIPE_DOWN": ${externalJson("com.con", "hud_focus_next", "Focus next")},
              "TAP":        ${externalJson("com.con", "hud_activate", "Activate")},
              "DOUBLE_TAP": ${externalJson("com.con", "hud_dismiss", "Dismiss")}
            }
        """.trimIndent()
        val out = PluginBindingsParser.parse(json)
        assertEquals(4, out.size)
        assertEquals("hud_activate", (out[Gesture.TAP] as GlassAction.PluginAction).actionId)
        assertEquals("hud_dismiss", (out[Gesture.DOUBLE_TAP] as GlassAction.PluginAction).actionId)
    }

    @Test fun `unknown gesture names are skipped silently`() {
        val json = """
            {
              "TAP": ${externalJson("com.x", "good")},
              "MIDDLE_FINGER": ${externalJson("com.x", "skipped")}
            }
        """.trimIndent()
        val out = PluginBindingsParser.parse(json)
        assertEquals(1, out.size)
        assertTrue(Gesture.TAP in out)
    }

    @Test fun `non-external types are skipped (v1 spec only supports external)`() {
        val json = """
            {
              "TAP": {"type":"builtin","name":"Confirm"},
              "SWIPE_UP": ${externalJson("com.x", "go")}
            }
        """.trimIndent()
        val out = PluginBindingsParser.parse(json)
        assertEquals(1, out.size)
        assertTrue(Gesture.SWIPE_UP in out)
    }

    @Test fun `compact aliases like LP and DT_SWIPE_UP map to canonical Gestures`() {
        val json = """
            {
              "LP": ${externalJson("com.x", "long")},
              "DT+SWIPE_UP": ${externalJson("com.x", "dtsu")},
              "QUAD_TAP": ${externalJson("com.x", "quad")}
            }
        """.trimIndent()
        val out = PluginBindingsParser.parse(json)
        assertEquals(3, out.size)
        assertTrue(Gesture.LONG_PRESS in out)
        assertTrue(Gesture.DOUBLE_TAP_SWIPE_UP in out)
        assertTrue(Gesture.QUADRUPLE_TAP in out)
    }

    @Test fun `missing required fields in a binding are skipped`() {
        val json = """
            {
              "TAP": {"type":"external","action_id":"no_pkg"},
              "SWIPE_UP": {"type":"external","package":"com.x"},
              "SWIPE_DOWN": ${externalJson("com.x", "good")}
            }
        """.trimIndent()
        val out = PluginBindingsParser.parse(json)
        assertEquals(1, out.size)
        assertTrue(Gesture.SWIPE_DOWN in out)
    }

    @Test fun `label is optional and defaults to action_id`() {
        val json = """{"TAP": {"type":"external","package":"com.x","action_id":"my_action"}}"""
        val out = PluginBindingsParser.parse(json)
        assertEquals(GlassAction.PluginAction("com.x", "my_action", "my_action"), out[Gesture.TAP])
    }

    @Test fun `whitespace and pretty-printed JSON tolerated`() {
        val json = """
            {
                "TAP" :    ${externalJson("com.x", "ok")}
            }
        """.trimIndent()
        val out = PluginBindingsParser.parse(json)
        assertEquals(1, out.size)
    }

    @Test fun `escaped quotes inside string values are decoded`() {
        val json = """{"TAP": {"type":"external","package":"com.x","action_id":"a","label":"with \"quotes\""}}"""
        val out = PluginBindingsParser.parse(json)
        assertEquals("with \"quotes\"", (out[Gesture.TAP] as GlassAction.PluginAction).label)
    }
}
