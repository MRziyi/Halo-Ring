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

@androidx.compose.runtime.Immutable
data class AdvancedPrefs(
    val debugHudEnabled: Boolean = false,
    val latencyMeasurementEnabled: Boolean = false,
    val spatialModeEnabled: Boolean = false,
)

enum class AdvancedAction {
    DEEP_LINK_ACCESSIBILITY,
    DEEP_LINK_BATTERY_EXEMPTION,
    REOPEN_ADB_WIZARD,
    EXPORT_LATENCY_LOG,
}

/**
 * Settings → Advanced (mockup §3 J). The "developer" pane:
 *  - 3 boolean toggles (Debug HUD / Latency measurement / Spatial mode — phase-3, warned)
 *  - 4 actions: deep-link to OS Accessibility settings, deep-link to battery exemption, re-run the
 *    ADB bootstrap wizard, export the latency-measurement CSV.
 *
 * Persistence: the toggle state is owned by the caller (MainActivity); deep-link actions are
 * fire-and-forget Intents emitted via [onActionTriggered].
 */
@Composable
fun AdvancedScreen(
    prefs: AdvancedPrefs,
    onToggleDebugHud: () -> Unit = {},
    onToggleLatency: () -> Unit = {},
    onToggleSpatial: () -> Unit = {},
    onActionTriggered: (AdvancedAction) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = "Advanced",
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )

        ToggleRow(
            title = "Debug HUD",
            description = "Show RSSI, BLE interval, RTT, drop count, active backend.",
            on = prefs.debugHudEnabled,
            onToggle = onToggleDebugHud,
        )
        ToggleRow(
            title = "Latency measurement",
            description = "Log per-stage breakdown for the next 20 gestures to CSV (Doc/06 §4).",
            on = prefs.latencyMeasurementEnabled,
            onToggle = onToggleLatency,
        )
        ToggleRow(
            title = "Spatial mode (phase 3)",
            description = "Air gestures from the IMU. Burns battery — only enable if you need it.",
            on = prefs.spatialModeEnabled,
            onToggle = onToggleSpatial,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Actions")

        ActionRow("Open Accessibility settings", "Enable R08 Remote in the OS Settings list.") {
            onActionTriggered(AdvancedAction.DEEP_LINK_ACCESSIBILITY)
        }
        ActionRow("Battery exemption", "Allow R08 Remote to run in Doze.") {
            onActionTriggered(AdvancedAction.DEEP_LINK_BATTERY_EXEMPTION)
        }
        ActionRow("Re-run ADB bootstrap", "Push the agent dex + grant WRITE_SECURE_SETTINGS again.") {
            onActionTriggered(AdvancedAction.REOPEN_ADB_WIZARD)
        }
        ActionRow("Export latency log (CSV)", "Available after you've used Latency measurement.") {
            onActionTriggered(AdvancedAction.EXPORT_LATENCY_LOG)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = HaloType.Caption.copy(color = HaloColors.Mute),
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
    )
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
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}

@Composable
private fun ActionRow(title: String, description: String, onClick: () -> Unit) {
    FocusableRow(onClick = onClick) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(title, style = HaloType.Body)
            Text(description, style = HaloType.Caption.copy(fontSize = 11.sp))
        }
        Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}
