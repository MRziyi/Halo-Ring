package com.halo.ring

import android.app.Application
import android.util.Log
import com.halo.ring.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HaloRingApplication : Application() {
    lateinit var graph: AppGraph
        private set

    /** Lives for the app process lifetime; used to load/save user prefs to DataStore. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.create(this)
        wireProfilePersistence(graph.profilesFlow, ::loadProfiles, graph.profilesPrefs::saveProfiles, "profiles")
        wireProfilePersistence(graph.systemGesturesFlow, ::loadSystemGestures, graph.profilesPrefs::saveSystemGestures, "system-gestures")
        wireProfilePersistence(graph.advancedPrefsFlow, { graph.advancedPrefs.flow.first() }, graph.advancedPrefs::save, "advanced-prefs")
        wireProfilePersistence(graph.vitalsPrefsFlow,  { graph.vitalsPrefs.flow.first() },   graph.vitalsPrefs::save,   "vitals-prefs")
    }

    /**
     * Persistence bridge: seed the flow from DataStore on first read, then write any subsequent
     * flow change back to DataStore. The `drop(1)` skips the initial seeded value so we don't echo
     * it straight back.
     */
    private fun <T> wireProfilePersistence(
        flow: MutableStateFlow<T>,
        load: suspend () -> T,
        save: suspend (T) -> Unit,
        tag: String,
    ) {
        appScope.launch {
            val initial = runCatching { load() }
                .onFailure { Log.w("HaloRingApp", "$tag load failed: ${it.message}") }
                .getOrNull()
            if (initial != null) flow.value = initial
            flow.drop(1).collectLatest { v ->
                runCatching { save(v) }
                    .onFailure { Log.w("HaloRingApp", "$tag save failed: ${it.message}") }
            }
        }
    }

    private suspend fun loadProfiles() = graph.profilesPrefs.profilesFlow.first()
    private suspend fun loadSystemGestures() = graph.profilesPrefs.systemGesturesFlow.first()
}
