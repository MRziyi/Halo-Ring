package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.halo.ring.R
import com.halo.ring.ui.Cta
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloRingTheme
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

/**
 * First-run wizard, v0.4 — **complete on-device setup, no computer, mandatory (no skip)**.
 *
 * The whole app is useless without the agent + a paired ring, so the wizard is a hard gate
 * (user mandate 2026-05-27: "skip 无意义，必须经 wizard 才运行"). Each step shows **exactly one
 * actionable button at a time**, strictly sequenced by live state, so the wearer is never given
 * a wall of buttons ("别一上来给太多"):
 *
 *  1. **System access** — sequential sub-gate:
 *       dev-options OFF  → only "Enable Developer Options" (jump to Build number)
 *       wireless-debug OFF → only "Enable Wireless Debugging"
 *       both ON          → only "Start pairing"
 *       (agent already alive → "✓ done, continue" — no re-pairing)
 *  2. **Keep-alive** — battery exemption (auto-detected) then auto-start, gated in order.
 *  3. **Pair ring** — pick the R0x ring.
 */
@Composable
fun FirstRunWizardScreen(
    onOpenDeveloperSettings: () -> Unit = {},
    onOpenBuildNumber: () -> Unit = {},
    devOptionsEnabled: Boolean = false,
    wirelessDebugEnabled: Boolean = false,
    agentReady: Boolean = false,
    onStartAdbPairing: () -> Unit = {},
    adbStatus: String = "",
    onOpenAccessibilitySettings: () -> Unit = {},
    accessibilityEnabled: Boolean = false,
    onRequestBatteryExemption: () -> Unit = {},
    batteryExempted: Boolean = false,
    onOpenAutostartSettings: () -> Unit = {},
    onStartRingPairing: () -> Unit = {},
    onCompleted: () -> Unit = {},
) {
    @Suppress("UNUSED_EXPRESSION") onOpenAccessibilitySettings
    @Suppress("UNUSED_EXPRESSION") accessibilityEnabled
    @Suppress("UNUSED_EXPRESSION") onStartRingPairing

    HaloRingTheme {
        var step by remember { mutableStateOf(WizardStep.SYSTEM_ACCESS) }
        val focus = remember { FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(step, adbStatus, batteryExempted, devOptionsEnabled, wirelessDebugEnabled, agentReady) {
            repeat(5) {
                kotlinx.coroutines.delay(100)
                val ok = try { focus.requestFocus(); true } catch (_: Throwable) { false }
                if (ok) return@LaunchedEffect
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().background(HaloColors.Bg)
                .focusRequester(focus).focusGroup()
                .padding(horizontal = ScreenPadding, vertical = 16.dp),
        ) {
            StepIndicator(step)
            Spacer(Modifier.height(14.dp))
            when (step) {
                WizardStep.SYSTEM_ACCESS -> SystemAccessStep(
                    devOptionsEnabled = devOptionsEnabled,
                    wirelessDebugEnabled = wirelessDebugEnabled,
                    agentReady = agentReady,
                    status = adbStatus,
                    onOpenBuildNumber = onOpenBuildNumber,
                    onOpenWirelessDebug = onOpenDeveloperSettings,
                    onStartPairing = onStartAdbPairing,
                    onNext = { step = WizardStep.KEEP_ALIVE },
                )
                WizardStep.KEEP_ALIVE -> KeepAliveStep(
                    batteryExempted = batteryExempted,
                    onRequestBattery = onRequestBatteryExemption,
                    onOpenAutostart = onOpenAutostartSettings,
                    onNext = { step = WizardStep.PAIR },
                )
                WizardStep.PAIR -> {
                    Text(stringResource(R.string.wizard_pair_title), style = HaloType.Title)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.wizard_pair_body), style = HaloType.Caption)
                    Spacer(Modifier.height(10.dp))
                    // Pairing completes via onPaired (BLE READY + capabilities). No skip button —
                    // a paired ring is required for the app to do anything.
                    RingPairingScreen(onPaired = onCompleted)
                }
            }
        }
    }
}

private enum class WizardStep { SYSTEM_ACCESS, KEEP_ALIVE, PAIR }

@Composable
private fun StepIndicator(current: WizardStep) {
    Row {
        WizardStep.values().forEach { s ->
            val active = s.ordinal <= current.ordinal
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (active) HaloColors.Accent else HaloColors.Line),
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** A ✓/✗ status line. */
@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Text(
        text = (if (ok) "✓ " else "✗ ") + label,
        style = HaloType.Caption.copy(color = if (ok) HaloColors.Accent else HaloColors.Mute),
    )
}

@Composable
private fun SystemAccessStep(
    devOptionsEnabled: Boolean,
    wirelessDebugEnabled: Boolean,
    agentReady: Boolean,
    status: String,
    onOpenBuildNumber: () -> Unit,
    onOpenWirelessDebug: () -> Unit,
    onStartPairing: () -> Unit,
    onNext: () -> Unit,
) {
    Text(stringResource(R.string.wizard_adb_intro_title), style = HaloType.Title)
    Spacer(Modifier.height(8.dp))

    // Already set up (agent alive or pairing reported success) → one CONTINUE button, no re-pairing.
    if (agentReady || status.startsWith("✓")) {
        Text(stringResource(R.string.wizard_adb_paired_title), style = HaloType.Body.copy(color = HaloColors.Accent))
        Spacer(Modifier.height(16.dp))
        Cta(stringResource(R.string.wizard_adb_continue_cta), onClick = onNext)
        return
    }

    // Pairing in progress.
    if (status.isNotBlank() && !status.startsWith("✗")) {
        Text(stringResource(R.string.wizard_adb_running_title), style = HaloType.Title)
        Spacer(Modifier.height(10.dp))
        Text(status, style = HaloType.Body)
        return
    }

    // Pairing failed → single TRY AGAIN (no skip).
    if (status.startsWith("✗")) {
        Text(status.removePrefix("✗").trim(), style = HaloType.Body.copy(color = HaloColors.Bad))
        Spacer(Modifier.height(16.dp))
        Cta(stringResource(R.string.wizard_adb_try_again_cta), onClick = onStartPairing)
        return
    }

    // Otherwise: the strict sequential sub-gate. Show the live status of both prerequisites,
    // then EXACTLY ONE button for the next thing the user must do.
    Text(stringResource(R.string.wizard_adb_intro_body), style = HaloType.Caption)
    Spacer(Modifier.height(12.dp))
    StatusLine(stringResource(R.string.wizard_sys_devopts_label), devOptionsEnabled)
    StatusLine(stringResource(R.string.wizard_sys_wireless_label), wirelessDebugEnabled)
    Spacer(Modifier.height(16.dp))

    when {
        !devOptionsEnabled ->
            Cta(stringResource(R.string.wizard_sys_enable_devopts_cta), onClick = onOpenBuildNumber)
        !wirelessDebugEnabled ->
            Cta(stringResource(R.string.wizard_adb_open_settings_cta), onClick = onOpenWirelessDebug)
        else ->
            Cta(stringResource(R.string.wizard_adb_start_pairing_cta), onClick = onStartPairing)
    }
}

@Composable
private fun KeepAliveStep(
    batteryExempted: Boolean,
    onRequestBattery: () -> Unit,
    onOpenAutostart: () -> Unit,
    onNext: () -> Unit,
) {
    Text(stringResource(R.string.wizard_keepalive_title), style = HaloType.Title)
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.wizard_keepalive_body), style = HaloType.Caption)
    Spacer(Modifier.height(14.dp))

    StatusLine(stringResource(R.string.wizard_keepalive_battery_row), batteryExempted)

    // Strict order: battery first (auto-detected), then auto-start, then CONTINUE. One button at a time.
    if (!batteryExempted) {
        Text(stringResource(R.string.wizard_keepalive_battery_desc), style = HaloType.Caption)
        Spacer(Modifier.height(12.dp))
        Cta(stringResource(R.string.wizard_keepalive_battery_cta), onClick = onRequestBattery)
        return
    }

    Spacer(Modifier.height(10.dp))
    Text("• " + stringResource(R.string.wizard_keepalive_autostart_row), style = HaloType.Body)
    Text(stringResource(R.string.wizard_keepalive_autostart_desc), style = HaloType.Caption)
    Spacer(Modifier.height(12.dp))
    Cta(stringResource(R.string.wizard_keepalive_autostart_cta), onClick = onOpenAutostart)
    Spacer(Modifier.height(10.dp))
    Cta(stringResource(R.string.wizard_keepalive_done_cta), onClick = onNext)
}
