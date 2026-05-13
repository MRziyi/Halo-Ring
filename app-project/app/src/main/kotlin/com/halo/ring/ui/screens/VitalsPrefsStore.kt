package com.halo.ring.ui.screens

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vitalsPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "r08-vitals-prefs")

/**
 * User preferences for the Vitals tab (mockup §3 I).
 *
 * - [showHrOnHud]: HR % shown in the HUD's peek/glance view (not the per-gesture HUD).
 * - [activityOverlay]: show steps/calories overlay on top of HR.
 * - [autoSnapshotIntervalMin]: 0 = manual only. Periodic on-demand measurement at the chosen
 *   interval; longer intervals = better ring battery.
 * - [csvExportEnabled]: append every reading to an internal CSV (settings → Advanced → Export).
 * - [wearDetectionEnabled]: gate the periodic auto-snapshot on the wear-state provider (the ring
 *   isn't useful when off-finger). On by default.
 */
@Immutable
data class VitalsPrefs(
    val showHrOnHud: Boolean = false,
    val activityOverlay: Boolean = true,
    val autoSnapshotIntervalMin: Int = 0,
    val csvExportEnabled: Boolean = false,
    val wearDetectionEnabled: Boolean = true,
)

/** The cycle of preset intervals for [VitalsPrefs.autoSnapshotIntervalMin] (0 = off). */
val AUTO_SNAPSHOT_INTERVALS: IntArray = intArrayOf(0, 5, 15, 30, 60, 240)

class VitalsPrefsStore(private val context: Context) {

    private object Keys {
        val ShowHrOnHud         = booleanPreferencesKey("show_hr_on_hud")
        val ActivityOverlay     = booleanPreferencesKey("activity_overlay")
        val AutoSnapshotInterval = intPreferencesKey("auto_snapshot_interval_min")
        val CsvExportEnabled    = booleanPreferencesKey("csv_export_enabled")
        val WearDetectionEnabled = booleanPreferencesKey("wear_detection_enabled")
    }

    val flow: Flow<VitalsPrefs> = context.vitalsPrefsDataStore.data.map { p ->
        VitalsPrefs(
            showHrOnHud              = p[Keys.ShowHrOnHud]         ?: false,
            activityOverlay          = p[Keys.ActivityOverlay]     ?: true,
            autoSnapshotIntervalMin  = p[Keys.AutoSnapshotInterval] ?: 0,
            csvExportEnabled         = p[Keys.CsvExportEnabled]    ?: false,
            wearDetectionEnabled     = p[Keys.WearDetectionEnabled] ?: true,
        )
    }

    suspend fun save(prefs: VitalsPrefs) {
        context.vitalsPrefsDataStore.edit { p ->
            p[Keys.ShowHrOnHud]          = prefs.showHrOnHud
            p[Keys.ActivityOverlay]      = prefs.activityOverlay
            p[Keys.AutoSnapshotInterval] = prefs.autoSnapshotIntervalMin
            p[Keys.CsvExportEnabled]     = prefs.csvExportEnabled
            p[Keys.WearDetectionEnabled] = prefs.wearDetectionEnabled
        }
    }
}
