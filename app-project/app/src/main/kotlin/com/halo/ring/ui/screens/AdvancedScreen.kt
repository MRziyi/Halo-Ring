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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.R
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding
import kotlinx.coroutines.delay

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
    /** Touch-IC idle-sleep timeout in minutes (SPEC v3 §4.10 `sleepMin`): how long the ring's touch
     *  sensor stays awake after the last touch before sleeping to save battery. Shorter = better ring
     *  battery, slower first-touch wake. Surfaced in Settings → Power & Connection → Ring sleep. */
    val touchSleepMin: Int = com.halo.ring.core.ble.R08Protocol.DEFAULT_TOUCH_SLEEP_MIN,
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

/** Live setup-state for the Advanced screen's critical rows, so each can show whether it's already
 *  satisfied (✓) — instead of a re-run silently no-op'ing — and guard an accidental re-run. Pushed
 *  down from MainActivity's `refreshSetupState` polling. */
@androidx.compose.runtime.Immutable
data class SetupStatus(
    val batteryExempted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val agentReady: Boolean = false,
)

/**
 * Settings → Advanced (2026-05-29 reorg): the home for rarely-touched bits. No toggles — the
 * latency-measurement switch + CSV exports were removed (Zack: use debug tools instead). Holds:
 * External plugins (Doc/18, moved in here), plus the seldom-needed system deep-links (accessibility
 * settings, battery exemption, re-run the ADB wizard).
 */
@Composable
fun AdvancedScreen(
    status: SetupStatus = SetupStatus(),
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
        // Critical system-access rows: show current state, and when already satisfied require a
        // SECOND tap to re-run — so a stray confirm doesn't silently re-fire a disruptive action and
        // the wearer gets feedback instead of a dead tap (Zack 2026-06-01).
        SetupActionRow(
            title = stringResource(R.string.advanced_a11y_title),
            statusOk = status.accessibilityEnabled,
            okDesc = stringResource(R.string.advanced_a11y_ok),
            needDesc = stringResource(R.string.advanced_a11y_desc),
        ) { onActionTriggered(AdvancedAction.DEEP_LINK_ACCESSIBILITY) }
        SetupActionRow(
            title = stringResource(R.string.advanced_battery_title),
            statusOk = status.batteryExempted,
            okDesc = stringResource(R.string.advanced_battery_ok),
            needDesc = stringResource(R.string.advanced_battery_desc),
        ) { onActionTriggered(AdvancedAction.DEEP_LINK_BATTERY_EXEMPTION) }
        SetupActionRow(
            title = stringResource(R.string.advanced_adb_title),
            statusOk = status.agentReady,
            okDesc = stringResource(R.string.advanced_adb_ok),
            needDesc = stringResource(R.string.advanced_adb_desc),
        ) { onActionTriggered(AdvancedAction.REOPEN_ADB_WIZARD) }
        ActionRow(stringResource(R.string.bt_internet_open_android_settings), stringResource(R.string.bt_internet_open_android_settings_desc)) {
            onActionTriggered(AdvancedAction.OPEN_ANDROID_SETTINGS)
        }
    }
}

/**
 * A critical-action row that's state-aware (Zack 2026-06-01). It shows whether the thing is already
 * satisfied (✓ [okDesc], accent) or still needs doing ([needDesc], mute). When already satisfied,
 * re-running is usually accidental and often a no-op, so we guard it like
 * [com.halo.ring.ui.ConfirmCta]: the first tap ARMS (desc → "tap again to re-run", red) and a second
 * tap within the window fires; it auto-disarms. When NOT satisfied, a single tap runs straight away
 * (you want to fix it). The state changing out from under an armed row disarms it.
 */
@Composable
private fun SetupActionRow(
    title: String,
    statusOk: Boolean,
    okDesc: String,
    needDesc: String,
    onAct: () -> Unit,
) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3_000L); armed = false } }
    LaunchedEffect(statusOk) { armed = false }
    val desc = when {
        armed    -> stringResource(R.string.advanced_confirm_rerun)
        statusOk -> okDesc
        else     -> needDesc
    }
    val descColor = when {
        armed    -> HaloColors.Bad
        statusOk -> HaloColors.Accent
        else     -> HaloColors.Mute
    }
    FocusableRow(onClick = {
        when {
            !statusOk -> onAct()                    // needs doing → just do it
            armed     -> { armed = false; onAct() } // armed re-run → fire
            else      -> armed = true               // already OK → arm + hint
        }
    }) {
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            Text(title, style = HaloType.Body)
            Text(desc, style = HaloType.Caption.copy(fontSize = 11.sp, color = descColor))
        }
        Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
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
