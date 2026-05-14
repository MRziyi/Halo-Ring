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
            text = stringResource(R.string.advanced_title),
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )

        // Audit-pass 2026-05-13t: `debugHudEnabled` is persisted but no consumer reads it
        // (there's no actual debug-HUD renderer wired). Mark disabled so we don't lie about it.
        ToggleRow(
            title = stringResource(R.string.advanced_debug_hud),
            description = stringResource(R.string.advanced_debug_hud_desc),
            on = prefs.debugHudEnabled,
            onToggle = onToggleDebugHud,
            disabled = true,
        )
        ToggleRow(
            title = stringResource(R.string.advanced_latency_measure),
            description = stringResource(R.string.advanced_latency_desc),
            on = prefs.latencyMeasurementEnabled,
            onToggle = onToggleLatency,
        )
        // Phase-3 (spatial / air gestures). Toggle has no runtime effect until phase-3 ships.
        ToggleRow(
            title = stringResource(R.string.advanced_spatial_mode),
            description = stringResource(R.string.advanced_spatial_desc),
            on = prefs.spatialModeEnabled,
            onToggle = onToggleSpatial,
            disabled = true,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader(stringResource(R.string.advanced_actions_header))

        ActionRow(stringResource(R.string.advanced_a11y_title), stringResource(R.string.advanced_a11y_desc)) {
            onActionTriggered(AdvancedAction.DEEP_LINK_ACCESSIBILITY)
        }
        ActionRow(stringResource(R.string.advanced_battery_title), stringResource(R.string.advanced_battery_desc)) {
            onActionTriggered(AdvancedAction.DEEP_LINK_BATTERY_EXEMPTION)
        }
        ActionRow(stringResource(R.string.advanced_adb_title), stringResource(R.string.advanced_adb_desc)) {
            onActionTriggered(AdvancedAction.REOPEN_ADB_WIZARD)
        }
        ActionRow(stringResource(R.string.advanced_export_title), stringResource(R.string.advanced_export_desc)) {
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
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}

@Composable
private fun ActionRow(title: String, description: String, onClick: () -> Unit) {
    FocusableRow(onClick = onClick) {
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            Text(title, style = HaloType.Body)
            Text(description, style = HaloType.Caption.copy(fontSize = 11.sp))
        }
        Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}
