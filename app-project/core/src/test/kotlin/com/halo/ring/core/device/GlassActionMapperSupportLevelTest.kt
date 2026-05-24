package com.halo.ring.core.device

import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.GlassAction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Defensive tests for the default [GlassActionMapper.supportLevel] tri-state semantics. Ensures
 * future flavor mappers don't accidentally regress the contract:
 *  - in-app pseudo-actions (`PeekHud` / `ProfileCycle` / `ForceReconnect` / `Enter*Modal` / `None`)
 *    are always SUPPORTED regardless of `primitives` (the router intercepts them).
 *  - all other actions are SUPPORTED iff `primitives` is non-empty, else UNSUPPORTED.
 *  - `supports()` shim treats SUPPORTED + BEST_EFFORT as bindable, UNSUPPORTED as not.
 */
class GlassActionMapperSupportLevelTest {

    private open class FakeMapper(private val primitivesByAction: Map<GlassAction, List<InjectionPrimitive>>) : GlassActionMapper {
        override fun capabilityFor(action: GlassAction): Capability? = null
        override fun primitives(action: GlassAction): List<InjectionPrimitive> =
            primitivesByAction[action] ?: emptyList()
    }

    @Test fun `non-empty primitives → SUPPORTED`() {
        val m = FakeMapper(mapOf(
            GlassAction.NavPrev to listOf(InjectionPrimitive.Key(keycode = 19))
        ))
        assertEquals(GlassActionMapper.SupportLevel.SUPPORTED, m.supportLevel(GlassAction.NavPrev))
        assertEquals(true, m.supports(GlassAction.NavPrev))
    }

    @Test fun `empty primitives → UNSUPPORTED`() {
        val m = FakeMapper(emptyMap())
        assertEquals(GlassActionMapper.SupportLevel.UNSUPPORTED, m.supportLevel(GlassAction.NavPrev))
        assertEquals(false, m.supports(GlassAction.NavPrev))
    }

    @Test fun `in-app pseudo-actions are SUPPORTED even with empty primitives`() {
        val m = FakeMapper(emptyMap())
        listOf(
            GlassAction.PeekHud,
            GlassAction.ProfileCycle,
            GlassAction.ForceReconnect,
            GlassAction.EnterVolumeModal,
            GlassAction.EnterBrightnessModal,
            GlassAction.EnterRecentsModal,
            GlassAction.EnterAIDictateModal,
            GlassAction.None,
        ).forEach { action ->
            assertEquals(
                GlassActionMapper.SupportLevel.SUPPORTED,
                m.supportLevel(action),
                "expected $action SUPPORTED by default contract",
            )
        }
    }

    @Test fun `subclass can override to BEST_EFFORT`() {
        val m = object : FakeMapper(mapOf(
            GlassAction.OpenAIAssistant to listOf(InjectionPrimitive.Shell("am start ..."))
        )) {
            override fun supportLevel(action: GlassAction) = when (action) {
                GlassAction.OpenAIAssistant -> GlassActionMapper.SupportLevel.BEST_EFFORT
                else -> super.supportLevel(action)
            }
        }
        assertEquals(GlassActionMapper.SupportLevel.BEST_EFFORT, m.supportLevel(GlassAction.OpenAIAssistant))
        // Best-effort is still bindable per the supports() shim.
        assertEquals(true, m.supports(GlassAction.OpenAIAssistant))
    }

    @Test fun `subclass can override an in-app pseudo-action to UNSUPPORTED`() {
        // Mirrors the EnterAIDictateModal case: the modal is a skeleton; even though the default
        // contract says in-app pseudo-actions are SUPPORTED, the flavor mapper is allowed to
        // demote the action so the picker greys it out.
        val m = object : FakeMapper(emptyMap()) {
            override fun supportLevel(action: GlassAction) = when (action) {
                GlassAction.EnterAIDictateModal -> GlassActionMapper.SupportLevel.UNSUPPORTED
                else -> super.supportLevel(action)
            }
        }
        assertEquals(GlassActionMapper.SupportLevel.UNSUPPORTED, m.supportLevel(GlassAction.EnterAIDictateModal))
        assertEquals(false, m.supports(GlassAction.EnterAIDictateModal))
    }
}
