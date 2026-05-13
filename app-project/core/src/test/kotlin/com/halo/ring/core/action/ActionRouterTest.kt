package com.halo.ring.core.action

import com.halo.ring.core.device.A11yGlobalAction
import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.device.InjectionPrimitive
import com.halo.ring.core.inject.ExecutorBackend
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the ActionRouter's backend selection logic — the part of the pipeline that decides
 * "which transport delivers this action right now." Doc/04 §5.
 */
class ActionRouterTest {

    /** A mapper that just echoes the default [GlassAction.needs] as required capability. */
    private val trivialMapper = object : GlassActionMapper {
        override fun capabilityFor(action: GlassAction): Capability? = action.needs
        override fun primitives(action: GlassAction): List<InjectionPrimitive> = emptyList()
    }

    /** A test backend that records dispatch attempts and can be tuned per-test. */
    private class TestBackend(
        override val id: String,
        override val priority: Int,
        private val caps: Set<Capability>,
        private val ready: Boolean = true,
        private val succeed: Boolean = true,
    ) : ExecutorBackend {
        var calls: Int = 0
        var lastAction: GlassAction? = null
        override fun capabilities(): Set<Capability> = caps
        override fun isReady(): Boolean = ready
        override suspend fun perform(action: GlassAction): Boolean {
            calls++
            lastAction = action
            return succeed
        }
    }

    @Test fun `picks the highest-priority backend that has the capability`() = runBlocking {
        val low = TestBackend("low", priority = 40, caps = setOf(Capability.NAVIGATE))
        val high = TestBackend("high", priority = 100, caps = setOf(Capability.NAVIGATE))
        val r = ActionRouter(trivialMapper) { listOf(low, high) }
        val winner = r.dispatch(GlassAction.NavPrev)
        assertEquals(high, winner)
        assertEquals(0, low.calls, "low-priority backend must not be tried when the high one succeeds")
    }

    @Test fun `falls back to the next backend when the preferred one fails`() = runBlocking {
        val first = TestBackend("first", priority = 100, caps = setOf(Capability.NAVIGATE), succeed = false)
        val second = TestBackend("second", priority = 80, caps = setOf(Capability.NAVIGATE), succeed = true)
        val r = ActionRouter(trivialMapper) { listOf(first, second) }
        val winner = r.dispatch(GlassAction.NavPrev)
        assertEquals(second, winner)
        assertEquals(1, first.calls, "first backend was tried once before falling through")
    }

    @Test fun `skips backends missing the required capability`() = runBlocking {
        val keyOnly = TestBackend("k", priority = 100, caps = setOf(Capability.KEY_EVENT))
        val navBackend = TestBackend("n", priority = 80, caps = setOf(Capability.NAVIGATE))
        val r = ActionRouter(trivialMapper) { listOf(keyOnly, navBackend) }
        val winner = r.dispatch(GlassAction.NavPrev)
        assertEquals(navBackend, winner, "should fall through to the capability-matching backend")
        assertEquals(0, keyOnly.calls)
    }

    @Test fun `skips backends that are not ready`() = runBlocking {
        val notReady = TestBackend("nr", priority = 100, caps = setOf(Capability.NAVIGATE), ready = false)
        val ready = TestBackend("r", priority = 80, caps = setOf(Capability.NAVIGATE), ready = true)
        val r = ActionRouter(trivialMapper) { listOf(notReady, ready) }
        assertEquals(ready, r.dispatch(GlassAction.NavPrev))
        assertEquals(0, notReady.calls)
    }

    @Test fun `returns null when no backend matches`() = runBlocking {
        val wrong = TestBackend("w", priority = 100, caps = setOf(Capability.KEY_EVENT))
        val r = ActionRouter(trivialMapper) { listOf(wrong) }
        assertNull(r.dispatch(GlassAction.NavPrev))
        assertEquals(0, wrong.calls)
    }

    @Test fun `returns null when all candidates reject`() = runBlocking {
        val a = TestBackend("a", priority = 100, caps = setOf(Capability.NAVIGATE), succeed = false)
        val b = TestBackend("b", priority = 50, caps = setOf(Capability.NAVIGATE), succeed = false)
        val r = ActionRouter(trivialMapper) { listOf(a, b) }
        assertNull(r.dispatch(GlassAction.NavPrev))
        assertEquals(1, a.calls)
        assertEquals(1, b.calls)
    }

    @Test fun `GlassAction None dispatches to no backend`() = runBlocking {
        val any = TestBackend("any", priority = 100, caps = setOf(Capability.NAVIGATE))
        val r = ActionRouter(trivialMapper) { listOf(any) }
        assertNull(r.dispatch(GlassAction.None))
        assertEquals(0, any.calls)
    }

    @Test fun `mapper can override capabilityFor — RayNeo NavPrev needs TAP_SWIPE`() = runBlocking {
        // On RayNeo, NavPrev needs TAP_SWIPE rather than NAVIGATE (Doc/04 §4.2).
        val rayneoMapper = object : GlassActionMapper {
            override fun capabilityFor(action: GlassAction): Capability? =
                if (action is GlassAction.NavPrev) Capability.TAP_SWIPE else action.needs
            override fun primitives(action: GlassAction): List<InjectionPrimitive> = emptyList()
        }
        val keyBackend = TestBackend("key", priority = 100, caps = setOf(Capability.NAVIGATE, Capability.KEY_EVENT))
        val swipeBackend = TestBackend("swipe", priority = 50, caps = setOf(Capability.TAP_SWIPE))
        val r = ActionRouter(rayneoMapper) { listOf(keyBackend, swipeBackend) }
        val winner = r.dispatch(GlassAction.NavPrev)
        assertEquals(swipeBackend, winner, "mapper's TAP_SWIPE override must steer past the higher-priority KEY backend")
        assertEquals(0, keyBackend.calls)
    }

    @Test fun `backendsProvider is consulted on every dispatch — backend list can be dynamic`() = runBlocking {
        var generation = 0
        val gen1 = TestBackend("gen1", priority = 100, caps = setOf(Capability.NAVIGATE))
        val gen2 = TestBackend("gen2", priority = 100, caps = setOf(Capability.NAVIGATE))
        val r = ActionRouter(trivialMapper) {
            if (generation == 0) listOf(gen1) else listOf(gen2)
        }
        r.dispatch(GlassAction.NavPrev); assertEquals(1, gen1.calls)
        generation = 1
        r.dispatch(GlassAction.NavPrev); assertEquals(1, gen2.calls)
        assertEquals(1, gen1.calls, "gen1 should not be called after the provider switched")
    }

    @Test fun `priority ties - first in the provider list wins (sortedByDescending is stable)`() = runBlocking {
        val first = TestBackend("first", priority = 100, caps = setOf(Capability.NAVIGATE))
        val second = TestBackend("second", priority = 100, caps = setOf(Capability.NAVIGATE))
        val r = ActionRouter(trivialMapper) { listOf(first, second) }
        assertEquals(first, r.dispatch(GlassAction.NavPrev))
        assertEquals(0, second.calls)
    }
}
