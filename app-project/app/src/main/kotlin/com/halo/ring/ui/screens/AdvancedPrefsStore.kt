package com.halo.ring.ui.screens

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.advancedPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "r08-advanced-prefs")

/**
 * DataStore-backed persistence for [AdvancedPrefs] (Doc/13 §B7 + follow-up). Three boolean toggles
 * for the debug HUD, latency-measurement mode, and the phase-3 spatial-mode kill switch.
 */
class AdvancedPrefsStore(private val context: Context) {

    private object Keys {
        val DebugHud = booleanPreferencesKey("debug_hud_enabled")
        val Latency  = booleanPreferencesKey("latency_measurement_enabled")
        val Spatial  = booleanPreferencesKey("spatial_mode_enabled")
        val BtInternetAutoBoot = booleanPreferencesKey("bt_internet_auto_boot")
        val TouchSleepMin = intPreferencesKey("touch_sleep_min")
    }

    val flow: Flow<AdvancedPrefs> = context.advancedPrefsDataStore.data.map { p ->
        AdvancedPrefs(
            debugHudEnabled           = p[Keys.DebugHud] ?: false,
            latencyMeasurementEnabled = p[Keys.Latency]  ?: false,
            spatialModeEnabled        = p[Keys.Spatial]  ?: false,
            btInternetAutoBoot        = p[Keys.BtInternetAutoBoot] ?: false,
            touchSleepMin             = p[Keys.TouchSleepMin] ?: com.halo.ring.core.ble.R08Protocol.DEFAULT_TOUCH_SLEEP_MIN,
        )
    }

    suspend fun save(prefs: AdvancedPrefs) {
        context.advancedPrefsDataStore.edit { p ->
            p[Keys.DebugHud] = prefs.debugHudEnabled
            p[Keys.Latency]  = prefs.latencyMeasurementEnabled
            p[Keys.Spatial]  = prefs.spatialModeEnabled
            p[Keys.BtInternetAutoBoot] = prefs.btInternetAutoBoot
            p[Keys.TouchSleepMin]      = prefs.touchSleepMin
        }
    }
}
