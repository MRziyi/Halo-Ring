package com.halo.ring

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.halo.ring.di.AppGraph
import com.halo.ring.ui.screens.AppLanguage
import com.halo.ring.ui.screens.AppPrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class HaloRingApplication : Application() {
    lateinit var graph: AppGraph
        private set
    lateinit var appPrefs: AppPrefsStore
        private set

    /** Lives for the app process lifetime; used to load/save user prefs to DataStore. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Apply the saved language preference BEFORE any Activity inflates a Compose tree, so
        // the very first frame renders in the chosen language. runBlocking on Application.onCreate
        // is fine — this is process startup, not a UI-thread hot path; reading one DataStore key
        // takes ~5-10 ms.
        appPrefs = AppPrefsStore(this)
        val initialLanguage = runCatching { runBlocking { appPrefs.languageFlow.first() } }
            .getOrElse { AppLanguage.SYSTEM }
        applyLanguage(initialLanguage)
        // Subsequent changes (Settings → Language) flow through this collector.
        appScope.launch {
            appPrefs.languageFlow.drop(1).collectLatest { applyLanguage(it) }
        }

        graph = AppGraph.create(this)
        wireProfilePersistence(graph.profilesFlow, ::loadProfiles, graph.profilesPrefs::saveProfiles, "profiles")
        wireProfilePersistence(graph.systemGesturesFlow, ::loadSystemGestures, graph.profilesPrefs::saveSystemGestures, "system-gestures")
        wireProfilePersistence(graph.advancedPrefsFlow, { graph.advancedPrefs.flow.first() }, graph.advancedPrefs::save, "advanced-prefs")
        wireProfilePersistence(graph.vitalsPrefsFlow,  { graph.vitalsPrefs.flow.first() },   graph.vitalsPrefs::save,   "vitals-prefs")
    }

    /**
     * Translate [AppLanguage] → [LocaleListCompat] and push to AppCompat. `SYSTEM` =
     * `getEmptyLocaleList()` which tells Android "no app override, fall back to system locale".
     * AppCompat triggers Activity recreation on change, so Compose rebinds with the new strings
     * automatically; no manual `recreate()` needed.
     */
    private fun applyLanguage(lang: AppLanguage) {
        val list = when (lang) {
            AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            else               -> LocaleListCompat.forLanguageTags(lang.tag ?: "")
        }
        AppCompatDelegate.setApplicationLocales(list)
        Log.i("HaloRingApp", "language applied: ${lang.name} → ${if (list.isEmpty) "(system)" else list.toLanguageTags()}")
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
