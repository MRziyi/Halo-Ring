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

/** RayNeo flavor — wires the X3 Pro strategies into the graph. */
object DeviceFlavorBindings {
    fun create(context: Context, detected: DeviceProfile): Bindings {
        val intents = RayNeoFeatureIntents()
        val mapper = RayNeoActionMapper(intents)
        // Install the temple-touchpad → in-app-focus bridge so when MainActivity is foreground,
        // X3 Pro users can also navigate with the temple touchpad as a fallback to the ring.
        // Today this only sets up the scaffolding (Mercury reflective lookup + lifecycle hooks);
        // the actual touch-event flow needs hardware verification — see Doc/11 §B2.1.
        com.halo.ring.ui.TempleFocusBridgeHolder.install(
            com.halo.ring.ui.RayNeoTempleFocusBridge()
        )
        return Bindings(
            displayAdapter = RayNeoDisplayAdapter(),
            mapper         = mapper,
            wearProvider   = RayNeoWearStateProvider(context),
            featureIntents = intents,
            backends       = listOf(
                AppProcessAgentBackend(mapper),
                AccessibilityBackend(mapper),
                InotifydScriptBackend(mapper),
            ),
        )
    }
}
