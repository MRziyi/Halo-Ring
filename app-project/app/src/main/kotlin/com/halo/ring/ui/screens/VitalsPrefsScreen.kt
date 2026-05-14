package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.R
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloSwitch
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

/**
 * Settings → Vitals preferences (mockup §3 I). Five rows:
 *   - Show HR on HUD
 *   - Activity overlay (steps / calories on the HUD)
 *   - Auto-snapshot interval (cycle through presets; 0 = manual only)
 *   - CSV export of readings (toggle)
 *   - Wear detection gates auto-snapshot (toggle)
 *
 * Persistence: [VitalsPrefsStore] writes-through every change. The actual auto-snapshot scheduler
 * lives in the foreground service and reads the same flow.
 */
@Composable
fun VitalsPrefsScreen(
    prefs: VitalsPrefs,
    onUpdated: (VitalsPrefs) -> Unit = {},
) {
    // Audit-pass 2026-05-15x (P0-2/3/4):
    //   - showHrOnHud + activityOverlay: gate fields on the HUD's Peek event (rendered when the
    //     user fires PeekHud / SystemGestures.peek). HudOverlay reads them via the prefs flow.
    //   - autoSnapshotIntervalMin: HaloRingService runs a periodic requestVitalsSnapshot loop.
    //   - csvExportEnabled: HaloRingService records every Health frame into VitalsLogger; the
    //     Advanced screen's "Export vitals log" CTA writes it to Downloads/.
    //   - wearDetectionEnabled: gates the auto-snapshot loop on the wear-state provider.
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.vitals_prefs_title),
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )

        ToggleRow(
            title = stringResource(R.string.vitals_prefs_show_hr_title),
            description = stringResource(R.string.vitals_prefs_show_hr_desc),
            on = prefs.showHrOnHud,
            onToggle = { onUpdated(prefs.copy(showHrOnHud = !prefs.showHrOnHud)) },
        )
        ToggleRow(
            title = stringResource(R.string.vitals_prefs_activity_title),
            description = stringResource(R.string.vitals_prefs_activity_desc),
            on = prefs.activityOverlay,
            onToggle = { onUpdated(prefs.copy(activityOverlay = !prefs.activityOverlay)) },
        )

        // Auto-snapshot interval — cycle through AUTO_SNAPSHOT_INTERVALS on tap.
        FocusableRow(onClick = {
            onUpdated(prefs.copy(autoSnapshotIntervalMin = cycleNextInterval(prefs.autoSnapshotIntervalMin)))
        }) {
            Column(Modifier.padding(end = 8.dp).weight(1f)) {
                Text(
                    stringResource(R.string.vitals_prefs_auto_snapshot_title),
                    style = HaloType.Body,
                )
                Text(
                    stringResource(R.string.vitals_prefs_auto_snapshot_desc),
                    style = HaloType.Caption.copy(fontSize = 11.sp),
                )
            }
            Text(
                text = formatInterval(prefs.autoSnapshotIntervalMin),
                style = HaloType.Body.copy(color = HaloColors.Accent),
            )
        }
        Divider()

        ToggleRow(
            title = stringResource(R.string.vitals_prefs_csv_title),
            description = stringResource(R.string.vitals_prefs_csv_desc),
            on = prefs.csvExportEnabled,
            onToggle = { onUpdated(prefs.copy(csvExportEnabled = !prefs.csvExportEnabled)) },
        )
        ToggleRow(
            title = stringResource(R.string.vitals_prefs_pause_offfinger_title),
            description = stringResource(R.string.vitals_prefs_pause_offfinger_desc),
            on = prefs.wearDetectionEnabled,
            onToggle = { onUpdated(prefs.copy(wearDetectionEnabled = !prefs.wearDetectionEnabled)) },
        )

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.vitals_prefs_footer),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    on: Boolean,
    onToggle: () -> Unit,
    disabled: Boolean = false,
) {
    FocusableRow(onClick = { if (!disabled) onToggle() }) {
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            Text(title, style = HaloType.Body.copy(color = if (disabled) HaloColors.Mute else HaloColors.Fg))
            Text(description, style = HaloType.Caption.copy(fontSize = 11.sp))
        }
        HaloSwitch(on = on, disabled = disabled)
    }
    Divider()
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}

private fun cycleNextInterval(currentMin: Int): Int {
    val i = AUTO_SNAPSHOT_INTERVALS.indexOf(currentMin)
    return if (i < 0) AUTO_SNAPSHOT_INTERVALS.first()
    else AUTO_SNAPSHOT_INTERVALS[(i + 1) % AUTO_SNAPSHOT_INTERVALS.size]
}

@Composable
private fun formatInterval(minutes: Int): String = when {
    minutes == 0     -> stringResource(R.string.vitals_prefs_interval_manual)
    minutes < 60     -> stringResource(R.string.vitals_prefs_interval_min, minutes)
    minutes % 60 == 0 -> stringResource(R.string.vitals_prefs_interval_hour, minutes / 60)
    else             -> stringResource(R.string.vitals_prefs_interval_hour_min, minutes / 60, minutes % 60)
}
