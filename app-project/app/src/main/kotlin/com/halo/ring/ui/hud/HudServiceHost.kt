package com.halo.ring.ui.hud

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The three view-tree owners ComposeView needs, bundled into one object for use from a [Service]
 * (which is not a [LifecycleOwner] by default). Keep one instance per service.
 *
 * - Lifecycle is pinned at [Lifecycle.State.RESUMED] for the host's whole life — ComposeView only
 *   uses the lifecycle to decide when to compose; we always want the HUD to compose.
 * - The ViewModelStore is empty (the HUD doesn't use any ViewModels).
 * - The SavedStateRegistry is created + restored eagerly so ComposeView doesn't crash on first use.
 *
 * Call [destroy] from `Service.onDestroy` to release lifecycle observers.
 */
class HudServiceHost : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
