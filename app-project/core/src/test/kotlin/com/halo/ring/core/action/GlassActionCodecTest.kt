package com.halo.ring.core.action

import kotlin.test.Test
import kotlin.test.assertEquals

class GlassActionCodecTest {

    @Test fun `data-object actions encode to their simpleName`() {
        assertEquals("NavPrev",  GlassActionCodec.encode(GlassAction.NavPrev))
        assertEquals("Confirm",  GlassActionCodec.encode(GlassAction.Confirm))
        assertEquals("Back",     GlassActionCodec.encode(GlassAction.Back))
        assertEquals("None",     GlassActionCodec.encode(GlassAction.None))
        assertEquals("ScreenSleep", GlassActionCodec.encode(GlassAction.ScreenSleep))
    }

    @Test fun `LaunchApp encodes pkg after a colon`() {
        assertEquals("LaunchApp:com.example.app", GlassActionCodec.encode(GlassAction.LaunchApp("com.example.app")))
    }

    @Test fun `Shell encodes raw command after a colon`() {
        assertEquals("Shell:input keyevent 26", GlassActionCodec.encode(GlassAction.Shell("input keyevent 26")))
    }

    @Test fun `data-object actions round-trip via encode then decode`() {
        // A spot-check across the 5 categories.
        val all = listOf(
            GlassAction.NavPrev, GlassAction.NavNext, GlassAction.Confirm,
            GlassAction.Back, GlassAction.Home, GlassAction.Recents, GlassAction.Menu,
            GlassAction.VolumeUp, GlassAction.MediaPlayPause, GlassAction.BrightnessUp,
            GlassAction.OpenCamera, GlassAction.AskVisualAI, GlassAction.OpenTranslate,
            GlassAction.ScreenSleep, GlassAction.ScreenWake, GlassAction.PeekHud,
            GlassAction.EnterVolumeModal, GlassAction.EnterRecentsModal,
            GlassAction.None,
        )
        for (a in all) {
            assertEquals(a, GlassActionCodec.decode(GlassActionCodec.encode(a)), "round-trip failed for $a")
        }
    }

    @Test fun `LaunchApp round-trips with its pkg argument`() {
        val a = GlassAction.LaunchApp("com.halo.ring.rokid")
        assertEquals(a, GlassActionCodec.decode(GlassActionCodec.encode(a)))
    }

    @Test fun `Shell round-trips with its raw cmd`() {
        val a = GlassAction.Shell("am start -n com.example/.Activity")
        assertEquals(a, GlassActionCodec.decode(GlassActionCodec.encode(a)))
    }

    @Test fun `ModalSentinel encodes by its simpleName`() {
        assertEquals("Exit",   GlassActionCodec.encode(ModalSentinel.Exit))
        assertEquals("Cancel", GlassActionCodec.encode(ModalSentinel.Cancel))
        assertEquals(ModalSentinel.Exit,   GlassActionCodec.decode("Exit"))
        assertEquals(ModalSentinel.Cancel, GlassActionCodec.decode("Cancel"))
    }

    @Test fun `unknown strings decode to None (forgiving for corrupt prefs)`() {
        assertEquals(GlassAction.None, GlassActionCodec.decode(""))
        assertEquals(GlassAction.None, GlassActionCodec.decode("WhateverFromOlderVersion"))
        assertEquals(GlassAction.None, GlassActionCodec.decode("BadTag:with:colons"))
    }

    @Test fun `LaunchApp with an empty pkg still round-trips`() {
        // Edge case: user could in principle write an empty package; the decoder should preserve it.
        assertEquals(GlassAction.LaunchApp(""), GlassActionCodec.decode("LaunchApp:"))
    }

    @Test fun `Shell containing colons preserves the entire suffix`() {
        // "Shell:ls /a:b" — the only delimiter is the first colon; the rest is the cmd verbatim.
        val a = GlassAction.Shell("ls /a:b")
        assertEquals("Shell:ls /a:b", GlassActionCodec.encode(a))
        assertEquals(a, GlassActionCodec.decode("Shell:ls /a:b"))
    }
}
