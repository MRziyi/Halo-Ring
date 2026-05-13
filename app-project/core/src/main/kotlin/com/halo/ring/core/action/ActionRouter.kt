package com.halo.ring.core.action

import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.inject.ExecutorBackend

/**
 * Glue layer: takes an abstract [GlassAction], asks the device-specific [GlassActionMapper] how to
 * inject it on *this* glasses model, then routes to the best ready [ExecutorBackend] that has the
 * required [Capability].
 */
class ActionRouter(
    private val mapper: GlassActionMapper,
    private val backendsProvider: () -> List<ExecutorBackend>,
) {
    /**
     * Returns the [ExecutorBackend] that performed it, or null if no backend could.
     * The caller decides what to do with that (log, retry, surface a warning in the HUD).
     */
    suspend fun dispatch(action: GlassAction): ExecutorBackend? {
        if (action is GlassAction.None) return null
        // Ask the mapper what kind of injection this action becomes on this device. The router
        // doesn't need to know the concrete bytes/keycodes; the backend will call back into the
        // mapper at perform-time. But we DO need to know the required capability — the mapper
        // can downgrade or short-circuit (e.g. on RayNeo, NavPrev needs TAP_SWIPE not KEY_EVENT).
        val needs = mapper.capabilityFor(action) ?: action.needs

        val candidates = backendsProvider()
            .filter { needs in it.capabilities() && it.isReady() }
            .sortedByDescending { it.priority }

        for (b in candidates) {
            if (b.perform(action)) return b
        }
        return null
    }
}
