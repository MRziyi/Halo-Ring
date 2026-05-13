package com.halo.ring.core.inject

import com.halo.ring.core.device.A11yGlobalAction
import com.halo.ring.core.device.InjectionPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentWireProtocolTest {

    @Test fun `Key primitive encodes to KEY keycode`() {
        assertEquals("KEY 19", AgentWireProtocol.encode(InjectionPrimitive.Key(19)))
        assertEquals("KEY 4",  AgentWireProtocol.encode(InjectionPrimitive.Key(4)))
    }

    @Test fun `Tap primitive encodes coordinates`() {
        assertEquals("TAP 320 240", AgentWireProtocol.encode(InjectionPrimitive.Tap(320, 240)))
    }

    @Test fun `Swipe primitive encodes endpoints and duration`() {
        assertEquals(
            "SWIPE 400 240 240 240 60",
            AgentWireProtocol.encode(InjectionPrimitive.Swipe(400, 240, 240, 240, 60))
        )
    }

    @Test fun `StartActivity without extras becomes AM start -n component`() {
        assertEquals(
            "AM start -n com.rokid.os.sprite.launcher/.page.camera.CameraPageActivity",
            AgentWireProtocol.encode(
                InjectionPrimitive.StartActivity("com.rokid.os.sprite.launcher/.page.camera.CameraPageActivity")
            )
        )
    }

    @Test fun `StartActivity with extras serialises each as --es key value`() {
        val s = AgentWireProtocol.encode(
            InjectionPrimitive.StartActivity(
                "pkg/.Activity",
                mapOf("cmd" to "open_app", "pkg" to "com.example.app"),
            )
        )
        assertEquals(
            "AM start -n pkg/.Activity --es cmd open_app --es pkg com.example.app", s,
        )
    }

    @Test fun `StartActivity extras containing whitespace are shell-quoted`() {
        val s = AgentWireProtocol.encode(
            InjectionPrimitive.StartActivity("pkg/.A", mapOf("msg" to "hello world"))
        )
        assertEquals("AM start -n pkg/.A --es msg \"hello world\"", s)
    }

    @Test fun `Broadcast encodes action plus k=v extras`() {
        assertEquals(
            "BC com.rokid.visualaidemo.ACTION_START",
            AgentWireProtocol.encode(InjectionPrimitive.Broadcast("com.rokid.visualaidemo.ACTION_START"))
        )
        assertEquals(
            "BC com.example.app.cmd cmd=open_app pkg=com.example",
            AgentWireProtocol.encode(InjectionPrimitive.Broadcast(
                "com.example.app.cmd",
                mapOf("cmd" to "open_app", "pkg" to "com.example"),
            ))
        )
    }

    @Test fun `Shell wraps the raw command into SH form`() {
        assertEquals(
            "SH input keyevent 26",
            AgentWireProtocol.encode(InjectionPrimitive.Shell("input keyevent 26"))
        )
    }

    @Test fun `A11yGlobal is not encodable - agent skips it so a fallback primitive can fire`() {
        assertNull(AgentWireProtocol.encode(InjectionPrimitive.A11yGlobal(A11yGlobalAction.BACK)))
        assertNull(AgentWireProtocol.encode(InjectionPrimitive.A11yGlobal(A11yGlobalAction.HOME)))
    }
}
