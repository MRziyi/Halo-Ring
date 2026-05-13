package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.sp
import com.halo.ring.ui.Cta
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloRingTheme
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

/**
 * First-run wizard (Doc/13 §B9, mockup §5). 5 steps in order:
 *
 *  1. **Welcome** — sets expectations ("Setup takes about a minute").
 *  2. **ADB bootstrap** — explain wireless debugging, deep-link to OS Developer options, then run
 *     the embedded pairing flow (currently stubbed — Doc/13 §B12).
 *  3. **Accessibility** — optional; deep-link to OS Accessibility settings to enable auto-switch.
 *  4. **Battery exemption** — required so Doze doesn't kill the resident service.
 *  5. **Pair the ring** — scan for `R08_xxxx`, write TOUCH_ENABLE, confirm LED ack.
 *
 * Each step is a pair of `Title + Body + CTA(s)`. Skip is allowed on optional steps (Accessibility);
 * required steps gate the "Next" CTA on success.
 *
 * The wizard takes a callback-style API rather than knowing about the graph directly — it's
 * driven by the host (`MainActivity`).
 */
@Composable
fun FirstRunWizardScreen(
    onOpenDeveloperSettings: () -> Unit = {},
    onStartAdbPairing: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    onRequestBatteryExemption: () -> Unit = {},
    onStartRingPairing: () -> Unit = {},
    onCompleted: () -> Unit = {},
) {
    HaloRingTheme {
        var step by remember { mutableStateOf(WizardStep.WELCOME) }

        Column(
            modifier = Modifier.fillMaxSize().background(HaloColors.Bg)
                .padding(horizontal = ScreenPadding, vertical = 18.dp),
        ) {
            StepIndicator(step)
            Spacer(Modifier.height(20.dp))

            when (step) {
                WizardStep.WELCOME -> WelcomeStep(onNext = { step = WizardStep.ADB })
                WizardStep.ADB -> AdbStep(
                    onOpenSettings = onOpenDeveloperSettings,
                    onStartPairing = onStartAdbPairing,
                    onSkip = { step = WizardStep.ACCESSIBILITY },
                    onNext = { step = WizardStep.ACCESSIBILITY },
                )
                WizardStep.ACCESSIBILITY -> AccessibilityStep(
                    onOpenSettings = onOpenAccessibilitySettings,
                    onSkip = { step = WizardStep.BATTERY },
                    onNext = { step = WizardStep.BATTERY },
                )
                WizardStep.BATTERY -> BatteryStep(
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
        WizardStep.values().forEach { s ->
            val active = s.ordinal <= current.ordinal
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (active) HaloColors.Accent else HaloColors.Line),
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Text("Welcome to R08 Remote", style = HaloType.Title)
    Spacer(Modifier.height(12.dp))
    Text(
        "Setup takes about a minute. We'll walk through 5 quick steps:\n\n" +
            "1. Welcome (you're here).\n" +
            "2. Enable wireless ADB so we can install the injection agent.\n" +
            "3. (Optional) Turn on Accessibility for auto-profile-switch.\n" +
            "4. Exempt R08 Remote from battery optimisation.\n" +
            "5. Pair your QRing R08 ring.",
        style = HaloType.Body,
    )
    Spacer(Modifier.height(24.dp))
    Cta("GET STARTED", focused = true, onClick = onNext)
}

@Composable
private fun AdbStep(
    onOpenSettings: () -> Unit,
    onStartPairing: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Text("Step 2 of 5 — Wireless ADB", style = HaloType.Title)
    Spacer(Modifier.height(8.dp))
    Text(
        "We need to push a small ~10 KB agent that injects key/touch events into the glasses. " +
            "It runs once via ADB and stays resident — no daily ADB connection needed.",
        style = HaloType.Body,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "On the glasses:  Settings → Developer options → Wireless debugging → Pair device with pairing code.",
        style = HaloType.Caption,
    )
    Spacer(Modifier.height(16.dp))
    Cta("OPEN DEVELOPER OPTIONS", onClick = onOpenSettings)
    Spacer(Modifier.height(8.dp))
    Cta("RUN PAIRING FLOW", focused = true, onClick = onStartPairing)
    Spacer(Modifier.height(8.dp))
    Cta("SKIP (manual ADB later)", onClick = onSkip)
    Spacer(Modifier.height(4.dp))
    Cta("CONTINUE (pairing succeeded)", onClick = onNext)
}

@Composable
private fun AccessibilityStep(
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Text("Step 3 of 5 — Accessibility (optional)", style = HaloType.Title)
    Spacer(Modifier.height(8.dp))
    Text(
        "Enabling Accessibility unlocks two things:\n" +
            "• Auto-switch profiles by foreground app (e.g. Reader profile when you open Translate)\n" +
            "• A fallback for Back / Home / Recents if the agent ever dies\n\n" +
            "You can skip this and enable it later in Settings → Advanced.",
        style = HaloType.Body,
    )
    Spacer(Modifier.height(20.dp))
    Cta("OPEN ACCESSIBILITY SETTINGS", focused = true, onClick = onOpenSettings)
    Spacer(Modifier.height(8.dp))
    Cta("SKIP", onClick = onSkip)
    Spacer(Modifier.height(4.dp))
    Cta("CONTINUE (enabled)", onClick = onNext)
}

@Composable
private fun BatteryStep(onRequest: () -> Unit, onNext: () -> Unit) {
    Text("Step 4 of 5 — Battery exemption", style = HaloType.Title)
    Spacer(Modifier.height(8.dp))
    Text(
        "Android's Doze mode will kill the BLE service after a few hours of idle if we're not " +
            "exempted. We don't hold a CPU wakelock — the ring's BLE notifies wake the CPU on demand.",
        style = HaloType.Body,
    )
    Spacer(Modifier.height(20.dp))
    Cta("ALLOW BACKGROUND", focused = true, onClick = onRequest)
    Spacer(Modifier.height(8.dp))
    Cta("CONTINUE", onClick = onNext)
}

@Composable
private fun PairRingStep(onStartPairing: () -> Unit, onFinish: () -> Unit) {
    Text("Step 5 of 5 — Pair the ring", style = HaloType.Title)
    Spacer(Modifier.height(8.dp))
    Text(
        "Take the ring off the cradle and slip it on. We'll scan for an R08_xxxx advertisement, " +
            "connect, and confirm with two LED blinks. Should take ≤3 seconds.",
        style = HaloType.Body,
    )
    Spacer(Modifier.height(20.dp))
    Cta("SCAN FOR RING", focused = true, onClick = onStartPairing)
    Spacer(Modifier.height(8.dp))
    Cta("FINISH SETUP", onClick = onFinish)
    Spacer(Modifier.height(8.dp))
    Text(
        "If the ring isn't found, check it's not on the cradle (charging disables BLE).",
        style = HaloType.Caption,
    )
}
