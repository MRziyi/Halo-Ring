package com.halo.ring.core.action

import com.halo.ring.core.gesture.Gesture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises the ModeManager lifecycle behaviours that aren't covered by KeyMapProfileTest:
 * triple-tap cycle, the 5 s manual lock, auto-switch by foreground package, manual-lock-vs-auto
 * ordering, and the upsert/remove CRUD path. Doc/05 §4.2.
 */
class ModeManagerTest {

    private fun threeProfiles() = listOf(
        DefaultProfiles.NAVIGATION,
        DefaultProfiles.MEDIA.copy(triggerPackages = listOf("com.r08.media")),
        DefaultProfiles.READER.copy(triggerPackages = listOf("com.r08.reader")),
    )

    private class FakeClock(start: Long = 0L) {
        var now: Long = start
        val supplier: () -> Long = { now }
    }

    @Test fun `starts on the initial profile`() {
        val mm = ModeManager(threeProfiles(), DefaultProfiles.NAVIGATION.id, FakeClock().supplier)
        assertEquals(DefaultProfiles.NAVIGATION.id, mm.active().id)
    }

    @Test fun `cycleNext walks the list and wraps`() {
        val clock = FakeClock()
        val mm = ModeManager(threeProfiles(), DefaultProfiles.NAVIGATION.id, clock.supplier)
        mm.cycleNext(); assertEquals(DefaultProfiles.MEDIA.id, mm.active().id)
        clock.now += 10_000  // walk past the manual lock between cycles
        mm.cycleNext(); assertEquals(DefaultProfiles.READER.id, mm.active().id)
        clock.now += 10_000
        mm.cycleNext(); assertEquals(DefaultProfiles.NAVIGATION.id, mm.active().id)
    }

    @Test fun `cycleNext arms the manual lock`() {
        val clock = FakeClock()
        val mm = ModeManager(threeProfiles(), DefaultProfiles.NAVIGATION.id, clock.supplier)
        mm.cycleNext()  // now on Media; lock until t=5_000
        clock.now = 4_999
        mm.onForegroundPackage("com.r08.reader")
        assertEquals(DefaultProfiles.MEDIA.id, mm.active().id, "manual lock must hold for ~5 s")
    }

    @Test fun `manual lock expires after manualLockMs`() {
        val clock = FakeClock()
        val mm = ModeManager(threeProfiles(), DefaultProfiles.NAVIGATION.id, clock.supplier)
        mm.cycleNext()  // Media; lock until 5_000
        clock.now = 5_001
        mm.onForegroundPackage("com.r08.reader")
        assertEquals(DefaultProfiles.READER.id, mm.active().id, "auto-switch should fire after lock")
    }

    @Test fun `switchTo also arms the manual lock`() {
        val clock = FakeClock()
        val mm = ModeManager(threeProfiles(), DefaultProfiles.NAVIGATION.id, clock.supplier)
        mm.switchTo(DefaultProfiles.MEDIA.id)
        clock.now = 1_000
        mm.onForegroundPackage("com.r08.reader")
        assertEquals(DefaultProfiles.MEDIA.id, mm.active().id)
    }

    @Test fun `switchTo to the same profile is a no-op (no lock, no notify)`() {
        val clock = FakeClock()
        val mm = ModeManager(threeProfiles(), DefaultProfiles.NAVIGATION.id, clock.supplier)
        val notifications = mutableListOf<String>()
        mm.observe { notifications += it.id }
        notifications.clear()
        mm.switchTo(DefaultProfiles.NAVIGATION.id)
        assertTrue(notifications.isEmpty())
        // Auto-switch must still work — no rogue lock.
        mm.onForegroundPackage("com.r08.media")
        assertEquals(DefaultProfiles.MEDIA.id, mm.active().id)
    }

    @Test fun `onForegroundPackage with null does nothing`() {
        val mm = ModeManager(threeProfiles(), DefaultProfiles.NAVIGATION.id, FakeClock().supplier)
        mm.onForegroundPackage(null)
        assertEquals(DefaultProfiles.NAVIGATION.id, mm.active().id)
    }

    @Test fun `onForegroundPackage that matches no triggerPackages is a no-op`() {
        val mm = ModeManager(threeProfiles(), DefaultProfiles.NAVIGATION.id, FakeClock().supplier)
        mm.onForegroundPackage("com.unknown.app")
        assertEquals(DefaultProfiles.NAVIGATION.id, mm.active().id)
    }

    @Test fun `onForegroundPackage that matches the active profile is a no-op (no notify churn)`() {
        val clock = FakeClock(start = 100_000)  // way past any manual lock
        val withTriggers = threeProfiles().let { all ->
            all.map { if (it.id == DefaultProfiles.NAVIGATION.id) it.copy(triggerPackages = listOf("com.r08.nav")) else it }
        }
        val mm = ModeManager(withTriggers, DefaultProfiles.NAVIGATION.id, clock.supplier)
        val notifications = mutableListOf<String>()
        mm.observe { notifications += it.id }
        notifications.clear()
        mm.onForegroundPackage("com.r08.nav")
        assertTrue(notifications.isEmpty())
    }

    @Test fun `observe replays the active profile immediately on subscribe`() {
        val mm = ModeManager(threeProfiles(), DefaultProfiles.MEDIA.id, FakeClock().supplier)
        val seen = mutableListOf<String>()
        mm.observe { seen += it.id }
        assertEquals(listOf(DefaultProfiles.MEDIA.id), seen)
    }

    @Test fun `unsubscribed listeners stop receiving updates`() {
        val clock = FakeClock()
        val mm = ModeManager(threeProfiles(), DefaultProfiles.NAVIGATION.id, clock.supplier)
        val seen = mutableListOf<String>()
        val cancel = mm.observe { seen += it.id }
        seen.clear()
        cancel()
        mm.cycleNext()
        assertTrue(seen.isEmpty())
    }

    @Test fun `upsert replaces the active profile and re-notifies listeners`() {
        val clock = FakeClock()
        val mm = ModeManager(threeProfiles(), DefaultProfiles.NAVIGATION.id, clock.supplier)
        val seen = mutableListOf<KeyMapProfile>()
        mm.observe { seen += it }
        seen.clear()
        val renamed = DefaultProfiles.NAVIGATION.copy(name = "Nav (custom)")
        mm.upsert(renamed)
        assertEquals(1, seen.size)
        assertEquals("Nav (custom)", seen.single().name)
        assertSame(renamed.gestureConfig, mm.active().gestureConfig)
    }

    @Test fun `upsert of an inactive profile does not notify`() {
        val clock = FakeClock()
        val mm = ModeManager(threeProfiles(), DefaultProfiles.NAVIGATION.id, clock.supplier)
        val seen = mutableListOf<KeyMapProfile>()
        mm.observe { seen += it }
        seen.clear()
        mm.upsert(DefaultProfiles.MEDIA.copy(name = "Media (custom)"))
        assertTrue(seen.isEmpty(), "upserting an inactive profile should not retrigger listeners")
    }

    @Test fun `remove never leaves the list empty`() {
        val clock = FakeClock()
        val mm = ModeManager(
            initialProfiles = listOf(DefaultProfiles.NAVIGATION),
            initialActiveId = DefaultProfiles.NAVIGATION.id,
            nowMs = clock.supplier,
        )
        mm.remove(DefaultProfiles.NAVIGATION.id)
        assertEquals(DefaultProfiles.NAVIGATION.id, mm.active().id, "the sole profile cannot be removed")
    }

    @Test fun `remove of active profile falls back to index 0`() {
        val clock = FakeClock()
        val mm = ModeManager(threeProfiles(), DefaultProfiles.READER.id, clock.supplier)
        mm.remove(DefaultProfiles.READER.id)
        // After removing the third profile, the active index should fall back into range.
        assertNotEquals(DefaultProfiles.READER.id, mm.active().id)
        assertEquals(DefaultProfiles.NAVIGATION.id, mm.active().id)
    }
}
