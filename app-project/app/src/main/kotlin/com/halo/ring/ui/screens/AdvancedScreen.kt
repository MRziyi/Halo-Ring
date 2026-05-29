package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

@androidx.compose.runtime.Immutable
data class AdvancedPrefs(
    /** No consumer; preserved for DataStore back-compat. */
    val debugHudEnabled: Boolean = false,
    /** No longer surfaced (latency toggle removed 2026-05-29); kept for DataStore back-compat. */
    val latencyMeasurementEnabled: Boolean = false,
    /** Surfaced in Vitals (spatial features) not Advanced; preserved for DataStore back-compat. */
    val spatialModeEnabled: Boolean = false,
    /** When true, re-enable the phone's Bluetooth "Internet access" (PAN) on every boot via the
     *  accessibility UI flow — Android resets that toggle on reboot. Off by default. */
    val btInternetAutoBoot: Boolean = false,
)

enum class AdvancedAction {
    DEEP_LINK_ACCESSIBILITY,
    DEEP_LINK_BATTERY_EXEMPTION,
    REOPEN_ADB_WIZARD,
    /** Run the accessibility UI flow that re-enables the phone's Bluetooth "Internet access". */
    BT_INTERNET_CONNECT_NOW,
    /** Open the Android system Settings app. */
    OPEN_ANDROID_SETTINGS,
}

/**
 * Settings → Advanced (2026-05-29 reorg): the home for rarely-touched bits. No toggles — the
 * latency-measurement switch + CSV exports were removed (Zack: use debug tools instead). Holds:
 * External plugins (Doc/18, moved in here), plus the seldom-needed system deep-links (accessibility
 * settings, battery exemption, re-run the ADB wizard).
 */
@Composable
fun AdvancedScreen(
    onActionTriggered: (AdvancedAction) -> Unit = {},
    onOpenPlugins: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.advanced_title),
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )

        ActionRow(stringResource(R.string.settings_section_external_plugins), stringResource(R.string.advanced_plugins_desc)) {
            onOpenPlugins()
        }
        ActionRow(stringResource(R.string.advanced_a11y_title), stringResource(R.string.advanced_a11y_desc)) {
            onActionTriggered(AdvancedAction.DEEP_LINK_ACCESSIBILITY)
        }
        ActionRow(stringResource(R.string.advanced_battery_title), stringResource(R.string.advanced_battery_desc)) {
            onActionTriggered(AdvancedAction.DEEP_LINK_BATTERY_EXEMPTION)
        }
        ActionRow(stringResource(R.string.advanced_adb_title), stringResource(R.string.advanced_adb_desc)) {
            onActionTriggered(AdvancedAction.REOPEN_ADB_WIZARD)
        }
        ActionRow(stringResource(R.string.bt_internet_open_android_settings), stringResource(R.string.bt_internet_open_android_settings_desc)) {
            onActionTriggered(AdvancedAction.OPEN_ANDROID_SETTINGS)
        }
    }
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
