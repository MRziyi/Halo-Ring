package com.halo.ring.di

import android.content.Context
import com.halo.ring.device.rokid.RokidActionMapper
import com.halo.ring.device.rokid.RokidDisplayAdapter
import com.halo.ring.device.rokid.RokidFeatureIntents
import com.halo.ring.device.rokid.RokidWearStateProvider
import com.halo.ring.inject.AccessibilityBackend
import com.halo.ring.inject.AppProcessAgentBackend
import com.halo.ring.inject.InotifydScriptBackend
import com.halo.ring.core.DeviceProfile

/** Rokid flavor — wires the Rokid-specific strategies into the graph. */
object DeviceFlavorBindings {
    fun create(context: Context, detected: DeviceProfile): Bindings {
        val intents = RokidFeatureIntents()
        val mapper = RokidActionMapper(intents)
        return Bindings(
            displayAdapter = RokidDisplayAdapter(),
            mapper         = mapper,
            wearProvider   = RokidWearStateProvider(context),
            featureIntents = intents,
            backends       = listOf(
                AppProcessAgentBackend(mapper),
                AccessibilityBackend(mapper),
                InotifydScriptBackend(mapper),
                // TODO: ShizukuBackend / PollScriptBackend (further fallbacks)
            ),
        )
    }
}
