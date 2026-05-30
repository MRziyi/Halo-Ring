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
    /**
     * Master switch for the app-driven **vitals auto-detection** (HR + SpO₂ measured together every
     * [autoSnapshotIntervalMin]). Default ON. Replaces the old ring-autonomous HR-only monitor —
     * when on, the app owns the cadence and measures the whole vitals group, not just HR.
     */
    val vitalsAutoDetectEnabled: Boolean = true,
    /** Vitals auto-detection cadence in minutes — one HR+SpO₂ group per interval. Default 60. */
    val autoSnapshotIntervalMin: Int = 60,
    val csvExportEnabled: Boolean = false,
    val wearDetectionEnabled: Boolean = true,
    /**
     * The ring's own autonomous HR-every-30min PPG (`0x16 HR-auto`, SPEC v3 §4.4). Default OFF now —
     * superseded by the app-driven [vitalsAutoDetectEnabled] (which also measures SpO₂). Kept as a
     * field for DataStore back-compat; the service writes `0x16 [02, 00, …]` to disable it on
     * bootstrap so the ring doesn't ALSO run its own 30-min HR LED on top of the app cadence.
     */
    val ringAutonomousPpgEnabled: Boolean = false,
    /**
     * User's daily step target (SPEC v3 §4.9 `0x21 TargetSetting`). Firmware silently drops
     * values < 100; we coerce. Default 5000.
     */
    val dailyStepTarget: Int = 5000,
    /**
     * v0.4 C6 (Doc/20 §9): subscribe to the ring's accelerometer `0xA1` telemetry stream so the
     * AccelProcessor can detect free-fall / impact / wrist-shake. Adds ~64 B/s BLE traffic.
     * Default OFF — opt-in.
     */
    val spatialFeaturesEnabled: Boolean = false,
)

/** Preset cadences (minutes) for vitals auto-detection. Off is handled by the master toggle, so the
 *  interval cycle no longer includes 0. */
val AUTO_SNAPSHOT_INTERVALS: IntArray = intArrayOf(15, 30, 60, 120, 240)

class VitalsPrefsStore(private val context: Context) {

    private object Keys {
        val ShowHrOnHud         = booleanPreferencesKey("show_hr_on_hud")
        val ActivityOverlay     = booleanPreferencesKey("activity_overlay")
        val VitalsAutoDetect    = booleanPreferencesKey("vitals_auto_detect_enabled")
        val AutoSnapshotInterval = intPreferencesKey("auto_snapshot_interval_min")
        val CsvExportEnabled    = booleanPreferencesKey("csv_export_enabled")
        val WearDetectionEnabled = booleanPreferencesKey("wear_detection_enabled")
        val RingAutonomousPpg    = booleanPreferencesKey("ring_autonomous_ppg")
        val DailyStepTarget      = intPreferencesKey("daily_step_target")
        val SpatialFeatures      = booleanPreferencesKey("spatial_features_enabled")
    }

    val flow: Flow<VitalsPrefs> = context.vitalsPrefsDataStore.data.map { p ->
        VitalsPrefs(
            showHrOnHud              = p[Keys.ShowHrOnHud]         ?: false,
            activityOverlay          = p[Keys.ActivityOverlay]     ?: true,
            vitalsAutoDetectEnabled  = p[Keys.VitalsAutoDetect]    ?: true,
            // Off is now the master toggle's job, so a persisted 0 (the old "manual only" sentinel)
            // migrates to the 60-min default; the toggle carries the on/off state.
            autoSnapshotIntervalMin  = (p[Keys.AutoSnapshotInterval] ?: 60).let { if (it <= 0) 60 else it },
            csvExportEnabled         = p[Keys.CsvExportEnabled]    ?: false,
            wearDetectionEnabled     = p[Keys.WearDetectionEnabled] ?: true,
            ringAutonomousPpgEnabled = p[Keys.RingAutonomousPpg]   ?: false,
            dailyStepTarget          = p[Keys.DailyStepTarget]     ?: 5000,
            spatialFeaturesEnabled   = p[Keys.SpatialFeatures]     ?: false,
        )
    }

    suspend fun save(prefs: VitalsPrefs) {
        context.vitalsPrefsDataStore.edit { p ->
            p[Keys.ShowHrOnHud]          = prefs.showHrOnHud
            p[Keys.ActivityOverlay]      = prefs.activityOverlay
            p[Keys.VitalsAutoDetect]     = prefs.vitalsAutoDetectEnabled
            p[Keys.AutoSnapshotInterval] = prefs.autoSnapshotIntervalMin
            p[Keys.CsvExportEnabled]     = prefs.csvExportEnabled
            p[Keys.WearDetectionEnabled] = prefs.wearDetectionEnabled
            p[Keys.RingAutonomousPpg]    = prefs.ringAutonomousPpgEnabled
            p[Keys.DailyStepTarget]      = prefs.dailyStepTarget.coerceAtLeast(100)
            p[Keys.SpatialFeatures]      = prefs.spatialFeaturesEnabled
        }
    }
}
