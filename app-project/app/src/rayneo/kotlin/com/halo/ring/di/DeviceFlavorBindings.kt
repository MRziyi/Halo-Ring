package com.halo.ring.di

import android.content.Context
import com.halo.ring.device.rayneo.RayNeoActionMapper
import com.halo.ring.device.rayneo.RayNeoDisplayAdapter
import com.halo.ring.device.rayneo.RayNeoFeatureIntents
import com.halo.ring.device.rayneo.RayNeoWearStateProvider
import com.halo.ring.inject.AccessibilityBackend
import com.halo.ring.inject.AppProcessAgentBackend
import com.halo.ring.inject.InotifydScriptBackend
import com.halo.ring.core.DeviceProfile

/** RayNeo flavor — wires the X3 Pro strategies into the graph.
 *
 *  v0.4: TempleFocusBridge deleted along with InAppFocusController (Doc/20 §4-§6). RayNeo
 *  temple-touchpad input is currently TBD — the user's app Activity will receive the temple
 *  events natively via standard Android KeyEvents (Compose FocusManager handles DPAD on the
 *  config screens). Re-wire Mercury TouchDispatcher here in a future revision if real-device
 *  testing shows the temple needs explicit decoding (Doc/04 §11). */
object DeviceFlavorBindings {
    fun create(context: Context, detected: DeviceProfile): Bindings {
        val intents = RayNeoFeatureIntents()
        val mapper = RayNeoActionMapper(intents)
        return Bindings(
            displayAdapter = RayNeoDisplayAdapter(),
            mapper         = mapper,
            wearProvider   = RayNeoWearStateProvider(context),
            featureIntents = intents,
            backends       = listOf(
                AppProcessAgentBackend(mapper),
                // RayNeo: AIOS SELinux blocks the agent's self-bootstrap, so the accessibility
                // dispatchGesture path is the primary nav injector → enable tap/swipe here.
                AccessibilityBackend(mapper, gesturesEnabled = true),
                InotifydScriptBackend(mapper),
            ),
        )
    }
}
