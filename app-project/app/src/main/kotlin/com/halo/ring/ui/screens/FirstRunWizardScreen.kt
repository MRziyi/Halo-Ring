package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.halo.ring.ui.Cta
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloRingTheme
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

/**
 * First-run wizard, sized for the 480×480 glasses display per [Doc/08](../../../../../../../../Doc/08-ui-design.md).
 *
 * Five steps. Each step renders **at most one primary CTA** for its current sub-state — no
 * fan-out of skip/next/retry buttons at the same time. Body text capped at ~2 lines per Doc/08
 * §1's "big text, generous space" rule.
 *
 * The code entry itself is NOT in this Compose tree — it's a system overlay
 * ([com.halo.ring.adb.AdbPairingOverlay]) so it can sit on top of the Settings →
 * Wireless-debugging pairing dialog. The Android system dialog tears down its mDNS
 * advertisement the moment it loses focus; without the overlay the user can't read the code
 * and switch apps in time.
 */
@Composable
fun FirstRunWizardScreen(
    onOpenDeveloperSettings: () -> Unit = {},
    onStartAdbPairing: () -> Unit = {},
    adbStatus: String = "",
    onOpenAccessibilitySettings: () -> Unit = {},
    accessibilityEnabled: Boolean = false,
    onRequestBatteryExemption: () -> Unit = {},
    batteryExempted: Boolean = false,
    onStartRingPairing: () -> Unit = {},
    onCompleted: () -> Unit = {},
) {
    HaloRingTheme {
        var step by remember { mutableStateOf(WizardStep.WELCOME) }

        Column(
            modifier = Modifier.fillMaxSize().background(HaloColors.Bg)
                .padding(horizontal = ScreenPadding, vertical = 20.dp),
        ) {
            if (step != WizardStep.WELCOME) {
                StepIndicator(step)
                Spacer(Modifier.height(20.dp))
            }
            when (step) {
                WizardStep.WELCOME -> WelcomeStep(onNext = { step = WizardStep.ADB })
                WizardStep.ADB -> AdbStep(
                    onOpenSettings = onOpenDeveloperSettings,
                    onStartPairing = onStartAdbPairing,
                    status = adbStatus,
                    onNext = { step = WizardStep.ACCESSIBILITY },
                )
                WizardStep.ACCESSIBILITY -> AccessibilityStep(
                    enabled = accessibilityEnabled,
                    onOpenSettings = onOpenAccessibilitySettings,
                    onNext = { step = WizardStep.BATTERY },
                )
                WizardStep.BATTERY -> BatteryStep(
                    exempted = batteryExempted,
                    onRequest = onRequestBatteryExemption,
                    onNext = { step = WizardStep.PAIR },
                )
                WizardStep.PAIR -> PairRingStep(
                    onStartPairing = onStartRingPairing,
                    onFinish = onCompleted,
                )
            }
        }
    }
}

private enum class WizardStep { WELCOME, ADB, ACCESSIBILITY, BATTERY, PAIR }

@Composable
private fun StepIndicator(current: WizardStep) {
    Row {
        WizardStep.values().drop(1).forEach { s ->
            val active = s.ordinal <= current.ordinal
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (active) HaloColors.Accent else HaloColors.Line),
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Halo Ring", style = HaloType.Title)
        Spacer(Modifier.height(28.dp))
        Cta("BEGIN", focused = true, onClick = onNext)
    }
}

private enum class AdbSubState { INTRO, RUNNING, SUCCESS, FAILED }

@Composable
private fun AdbStep(
    onOpenSettings: () -> Unit,
    onStartPairing: () -> Unit,
    status: String,
    onNext: () -> Unit,
) {
    val sub = when {
        status.isBlank() -> AdbSubState.INTRO
        status.startsWith("✓") -> AdbSubState.SUCCESS
        status.startsWith("✗") -> AdbSubState.FAILED
        else -> AdbSubState.RUNNING
    }

    when (sub) {
        AdbSubState.INTRO -> {
            Text("Install agent", style = HaloType.Title)
            Spacer(Modifier.height(10.dp))
            Text(
                "We'll try root auto-setup first. If your device isn't rooted you'll be asked " +
                    "for a 6-digit pairing code.",
                style = HaloType.Body,
            )
            Spacer(Modifier.height(24.dp))
            Cta("OPEN SETTINGS", onClick = onOpenSettings)
            Spacer(Modifier.height(10.dp))
            Cta("START PAIRING", focused = true, onClick = onStartPairing)
        }
        AdbSubState.RUNNING -> {
            Text("Pairing…", style = HaloType.Title)
            Spacer(Modifier.height(12.dp))
            Text(status, style = HaloType.Body)
        }
        AdbSubState.SUCCESS -> {
            Text("Paired", style = HaloType.Title)
            Spacer(Modifier.height(12.dp))
            Text(status.removePrefix("✓").trim(), style = HaloType.Body)
            Spacer(Modifier.height(24.dp))
            Cta("CONTINUE", focused = true, onClick = onNext)
        }
        AdbSubState.FAILED -> {
            Text("Pairing failed", style = HaloType.Title)
            Spacer(Modifier.height(12.dp))
            Text(status.removePrefix("✗").trim(), style = HaloType.Body)
            Spacer(Modifier.height(24.dp))
            Cta("TRY AGAIN", focused = true, onClick = onStartPairing)
        }
    }
}

@Composable
private fun AccessibilityStep(enabled: Boolean, onOpenSettings: () -> Unit, onNext: () -> Unit) {
    if (enabled) {
        Text("Accessibility on", style = HaloType.Title)
        Spacer(Modifier.height(10.dp))
        Text("Auto-switch by foreground app is active.", style = HaloType.Body)
        Spacer(Modifier.height(24.dp))
        Cta("CONTINUE", focused = true, onClick = onNext)
    } else {
        Text("Accessibility", style = HaloType.Title)
        Spacer(Modifier.height(10.dp))
        Text("Optional. Powers auto-switch by foreground app.", style = HaloType.Body)
        Spacer(Modifier.height(24.dp))
        Cta("OPEN SETTINGS", focused = true, onClick = onOpenSettings)
        Spacer(Modifier.height(10.dp))
        Cta("SKIP", onClick = onNext)
    }
}

@Composable
private fun BatteryStep(exempted: Boolean, onRequest: () -> Unit, onNext: () -> Unit) {
    if (exempted) {
        Text("Battery free", style = HaloType.Title)
        Spacer(Modifier.height(10.dp))
        Text("Doze won't kill the BLE link.", style = HaloType.Body)
        Spacer(Modifier.height(24.dp))
        Cta("CONTINUE", focused = true, onClick = onNext)
    } else {
        Text("Battery", style = HaloType.Title)
        Spacer(Modifier.height(10.dp))
        Text("Allow background so Doze can't kill the BLE link.", style = HaloType.Body)
        Spacer(Modifier.height(24.dp))
        Cta("ALLOW", focused = true, onClick = onRequest)
        Spacer(Modifier.height(10.dp))
        Cta("SKIP", onClick = onNext)
    }
}

@Composable
private fun PairRingStep(onStartPairing: () -> Unit, onFinish: () -> Unit) {
    Text("Pair ring", style = HaloType.Title)
    Spacer(Modifier.height(10.dp))
    Text("Wear the ring (not the cradle). Two blinks = paired.", style = HaloType.Body)
    Spacer(Modifier.height(24.dp))
    Cta("SCAN", focused = true, onClick = onStartPairing)
    Spacer(Modifier.height(10.dp))
    Cta("DONE", onClick = onFinish)
}
