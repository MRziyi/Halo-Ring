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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
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
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = "Vitals",
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )

        ToggleRow(
            title = "Show heart-rate on HUD",
            description = "Render the latest HR in the corner of the glasses' display.",
            on = prefs.showHrOnHud,
            onToggle = { onUpdated(prefs.copy(showHrOnHud = !prefs.showHrOnHud)) },
        )
        ToggleRow(
            title = "Activity overlay",
            description = "Show steps / calories alongside HR. Costs no extra ring power.",
            on = prefs.activityOverlay,
            onToggle = { onUpdated(prefs.copy(activityOverlay = !prefs.activityOverlay)) },
        )

        // Auto-snapshot interval — tap to cycle
        FocusableRow(onClick = {
            onUpdated(prefs.copy(
                autoSnapshotIntervalMin = cycleNextInterval(prefs.autoSnapshotIntervalMin),
            ))
        }) {
            Column(Modifier.padding(end = 8.dp)) {
                Text("Auto-snapshot interval", style = HaloType.Body)
                Text(
                    "Periodic HR/SpO₂/stress sample. PPG LED only on during the read.",
                    style = HaloType.Caption.copy(fontSize = 11.sp),
                )
            }
            Text(
                text = formatInterval(prefs.autoSnapshotIntervalMin),
                style = HaloType.RowVal.copy(color = HaloColors.Accent),
            )
        }
        Divider()

        ToggleRow(
            title = "CSV export",
            description = "Append every reading to an internal CSV (export from Settings → Advanced).",
            on = prefs.csvExportEnabled,
            onToggle = { onUpdated(prefs.copy(csvExportEnabled = !prefs.csvExportEnabled)) },
        )
        ToggleRow(
            title = "Pause when off-finger",
            description = "Skip auto-snapshots while the ring isn't worn. Saves ring battery.",
            on = prefs.wearDetectionEnabled,
            onToggle = { onUpdated(prefs.copy(wearDetectionEnabled = !prefs.wearDetectionEnabled)) },
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "Continuous HR is intentionally not supported — the PPG LED would halve ring battery.",
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

@Composable
private fun ToggleRow(title: String, description: String, on: Boolean, onToggle: () -> Unit) {
    FocusableRow(onClick = onToggle) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(title, style = HaloType.Body)
            Text(description, style = HaloType.Caption.copy(fontSize = 11.sp))
        }
        Text(
            text = if (on) "ON" else "OFF",
            style = HaloType.RowVal.copy(color = if (on) HaloColors.Accent else HaloColors.Mute),
        )
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

private fun formatInterval(minutes: Int): String = when {
    minutes == 0     -> "Manual only"
    minutes < 60     -> "${minutes} min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else             -> "${minutes / 60} h ${minutes % 60} min"
}
