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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.halo.ring.R
import com.halo.ring.ui.Cta
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloRingTheme
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

/**
 * First-run wizard — mandatory, no skip.
 *
 * Interaction paradigm (2026-05-28):
 *   - ONE button visible at a time.
 *   - If the app can auto-detect completion → auto-advance (no CONTINUE, no user tap).
 *
 * Step order:
 *   1. SYSTEM_ACCESS — gates in sequence:
 *        a) Accessibility (required for the pairing overlay)
 *        b) Developer Options
 *        c) Wi-Fi radio on
 *        d) Wireless Debugging
 *        e) ADB pairing → post-pairing Wi-Fi off
 *   2. KEEP_ALIVE — battery-optimisation exemption (direct system popup; auto-advances once on).
 *      The background-unrestricted appop is granted silently by the agent — no App-info tail.
 *   3. PAIR ring — BLE scan.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)   // InputModeManager.requestInputMode
@Composable
fun FirstRunWizardScreen(
    onOpenDeveloperSettings: () -> Unit = {},
    onOpenBuildNumber: () -> Unit = {},
    devOptionsEnabled: Boolean = false,
    wifiConnected: Boolean = false,
    onOpenWifiSettings: () -> Unit = {},
    wirelessDebugEnabled: Boolean = false,
    agentReady: Boolean = false,
    onStartAdbPairing: () -> Unit = {},
    adbStatus: String = "",
    onOpenAccessibilitySettings: () -> Unit = {},
    accessibilityEnabled: Boolean = false,
    onRequestBatteryExemption: () -> Unit = {},
    batteryExempted: Boolean = false,
    onStartRingPairing: () -> Unit = {},
    onCompleted: () -> Unit = {},
    isReRecovery: Boolean = false,
    pairedMac: String? = null,
    ringConnected: Boolean = false,
    onStartRingReconnect: () -> Unit = {},
    onStartAdbReconnect: () -> Unit = {},
) {
    @Suppress("UNUSED_EXPRESSION") onStartRingPairing

    HaloRingTheme {
        var step by remember { mutableStateOf(WizardStep.SYSTEM_ACCESS) }
        val focus = remember { FocusRequester() }
        // Force keyboard/focus input mode so the focus ring shows even when a mouse is attached
        // (a pointer device drops the window into touch mode → no highlight; Zack 2026-06-01).
        val inputModeManager = LocalInputModeManager.current

        // Re-focus whenever observable state changes so the AR focus ring lands on the
        // new button after each gate auto-advances.
        androidx.compose.runtime.LaunchedEffect(
            step, adbStatus, batteryExempted, devOptionsEnabled,
            wirelessDebugEnabled, agentReady, accessibilityEnabled, wifiConnected,
        ) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            repeat(5) {
                kotlinx.coroutines.delay(100)
                val ok = try { focus.requestFocus(); true } catch (_: Throwable) { false }
                if (ok) return@LaunchedEffect
            }
        }

        // (SYSTEM_ACCESS auto-skip when the agent is already alive is handled inside
        // SystemAccessStep — it advances immediately, with no standalone "paired" screen.)

        // Ring already paired AND connected → skip the final step entirely. If paired but
        // disconnected (common right after a reboot — the BLE link drops while the service
        // restarts) we DON'T skip; the PAIR step shows the reconnect UI instead.
        androidx.compose.runtime.LaunchedEffect(step, pairedMac, ringConnected) {
            if (step == WizardStep.PAIR && pairedMac != null && ringConnected) onCompleted()
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
                    accessibilityEnabled = accessibilityEnabled,
                    devOptionsEnabled = devOptionsEnabled,
                    wifiConnected = wifiConnected,
                    wirelessDebugEnabled = wirelessDebugEnabled,
                    agentReady = agentReady,
                    status = adbStatus,
                    isReRecovery = isReRecovery,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenBuildNumber = onOpenBuildNumber,
                    onOpenWifiSettings = onOpenWifiSettings,
                    onOpenWirelessDebug = onOpenDeveloperSettings,
                    onStartPairing = onStartAdbPairing,
                    onStartReconnect = onStartAdbReconnect,
                    onNext = { step = WizardStep.KEEP_ALIVE },
                )
                WizardStep.KEEP_ALIVE -> KeepAliveStep(
                    batteryExempted = batteryExempted,
                    onRequestBattery = onRequestBatteryExemption,
                    onNext = { step = WizardStep.PAIR },
                )
                WizardStep.PAIR -> when {
                    // Paired but disconnected → reconnect (no re-pairing needed).
                    pairedMac != null && !ringConnected -> RingReconnectStep(
                        ringConnected = ringConnected,
                        onReconnect = onStartRingReconnect,
                        onConnected = onCompleted,
                    )
                    // Paired + connected is handled by the skip LaunchedEffect above (renders nothing).
                    pairedMac != null -> Unit
                    // Never paired → full BLE pairing.
                    else -> {
                        Text(stringResource(R.string.wizard_pair_title), style = HaloType.Title)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.wizard_pair_body), style = HaloType.Caption)
                        Spacer(Modifier.height(10.dp))
                        RingPairingScreen(onPaired = onCompleted)
                    }
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

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Text(
        text = (if (ok) "✓ " else "✗ ") + label,
        style = HaloType.Caption.copy(color = if (ok) HaloColors.Accent else HaloColors.Mute),
    )
}

/**
 * Sequential sub-gates for system access. Exactly one button visible at a time.
 *
 * Gate order (idle path):
 *   1. Accessibility      → auto-detect → fall through
 *   2. Developer Options  → auto-detect → fall through
 *   3. Wi-Fi radio        → auto-detect → fall through
 *   4. Wireless Debugging → auto-detect → fall through
 *   5. [START PAIRING]
 *
 * Post-pairing:
 *   Wi-Fi on  → only "TURN OFF WI-FI"
 *   Wi-Fi off → only "CONTINUE"
 *
 * In-progress / error / agentReady states intercept before the gate sequence.
 */
@Composable
private fun SystemAccessStep(
    accessibilityEnabled: Boolean,
    devOptionsEnabled: Boolean,
    wifiConnected: Boolean,
    wirelessDebugEnabled: Boolean,
    agentReady: Boolean,
    status: String,
    isReRecovery: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenBuildNumber: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenWirelessDebug: () -> Unit,
    onStartPairing: () -> Unit,
    onStartReconnect: () -> Unit,
    onNext: () -> Unit,
) {
    // ── RayNeo: accessibility-only system access (no ADB agent) ─────────────────────────────────
    // On RayNeo X3 Pro the app_process agent can't bootstrap (AIOS SELinux blocks the adb setprops),
    // so injection runs entirely through the accessibility service. None of the ADB prerequisites
    // (Developer Options, Wi-Fi radio, Wireless Debugging, ADB pairing) apply — gating on them was
    // the "老是要开发者权限" nag. Require ONLY accessibility, then advance. Rokid is untouched.
    if (com.halo.ring.BuildConfig.DEVICE_FLAVOR == "rayneo") {
        Text(stringResource(R.string.wizard_adb_intro_title), style = HaloType.Title)
        Spacer(Modifier.height(8.dp))
        if (!accessibilityEnabled) {
            Text(stringResource(R.string.wizard_a11y_gate_body), style = HaloType.Caption)
            Spacer(Modifier.height(12.dp))
            Cta(stringResource(R.string.wizard_a11y_cta_enable), onClick = onOpenAccessibilitySettings)
        } else {
            StatusLine(stringResource(R.string.advanced_a11y_ok), ok = true)
            androidx.compose.runtime.LaunchedEffect(Unit) { onNext() }
        }
        return
    }

    Text(stringResource(R.string.wizard_adb_intro_title), style = HaloType.Title)
    Spacer(Modifier.height(8.dp))

    // Active bootstrap just finished (✓) → final sub-step: have the user turn Wi-Fi OFF, proving
    // the agent now runs on the Wi-Fi-independent loopback transport (migrated to the persistent
    // tcp port during bootstrap). MUST be checked before the agentReady short-circuit below: the
    // agent comes up the instant bootstrap succeeds, so agentReady flips true here too — checking
    // it first stranded a fresh setup on a no-action "paired" screen (removed).
    if (status.startsWith("✓")) {
        if (wifiConnected) {
            Text(stringResource(R.string.wizard_wifi_off_title), style = HaloType.Title)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wizard_wifi_off_body), style = HaloType.Caption)
            Spacer(Modifier.height(16.dp))
            Cta(stringResource(R.string.wizard_wifi_off_cta), onClick = onOpenWifiSettings)
        } else {
            // Wi-Fi off → agent keeps running on the persistent loopback port. Confirm + advance.
            StatusLine(stringResource(R.string.wizard_wifi_off_done), ok = true)
            androidx.compose.runtime.LaunchedEffect(Unit) { onNext() }
        }
        return
    }

    // Pairing / reconnect in progress — show status text, no button.
    if (status.isNotBlank() && !status.startsWith("✗")) {
        val runningTitle = if (isReRecovery) R.string.wizard_adb_reconnecting_title
                           else R.string.wizard_adb_running_title
        Text(stringResource(runningTitle), style = HaloType.Title)
        Spacer(Modifier.height(10.dp))
        Text(status, style = HaloType.Body)
        return
    }

    // Pairing / reconnect failed — only TRY AGAIN.
    if (status.startsWith("✗")) {
        Text(
            status.removePrefix("✗").trim(),
            style = HaloType.Body.copy(color = HaloColors.Bad),
        )
        Spacer(Modifier.height(16.dp))
        Cta(
            stringResource(R.string.wizard_adb_try_again_cta),
            onClick = if (isReRecovery) onStartReconnect else onStartPairing,
        )
        return
    }

    // Agent already alive from a previous session (re-entry; no bootstrap in flight) → advance
    // straight through. No standalone "already paired" screen: it needed no action and just stalled.
    if (agentReady) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onNext() }
        return
    }

    // ── Idle gate sequence ────────────────────────────────────────────────────────────────
    // Each unmet gate shows its ONE button and returns. Met gates fall through silently.

    // Gate 1: Accessibility (must be first — the pairing overlay needs this service).
    if (!accessibilityEnabled) {
        Text(stringResource(R.string.wizard_a11y_gate_body), style = HaloType.Caption)
        Spacer(Modifier.height(12.dp))
        Cta(stringResource(R.string.wizard_a11y_cta_enable), onClick = onOpenAccessibilitySettings)
        return
    }

    // Gate 2: Developer Options.
    if (!devOptionsEnabled) {
        Cta(stringResource(R.string.wizard_sys_enable_devopts_cta), onClick = onOpenBuildNumber)
        return
    }

    // Gate 3: Wi-Fi radio on (needed for ADB to bind its port).
    if (!wifiConnected) {
        Text(stringResource(R.string.wizard_wifi_body), style = HaloType.Caption)
        Spacer(Modifier.height(12.dp))
        Cta(stringResource(R.string.wizard_wifi_cta), onClick = onOpenWifiSettings)
        return
    }

    // Gate 4: Wireless Debugging enabled.
    if (!wirelessDebugEnabled) {
        Cta(stringResource(R.string.wizard_adb_open_settings_cta), onClick = onOpenWirelessDebug)
        return
    }

    // All prereqs met — single action button (pairing vs reconnect depending on context).
    if (isReRecovery) {
        Cta(stringResource(R.string.wizard_adb_reconnect_cta), onClick = onStartReconnect)
    } else {
        Cta(stringResource(R.string.wizard_adb_start_pairing_cta), onClick = onStartPairing)
    }
}

/**
 * Keep-alive step — battery-optimisation exemption only (Zack 2026-06-01).
 *
 * The old "background unrestricted / auto-start" gate that dumped the user into App info to hunt for
 * a toggle is gone — that appop is now granted silently by the agent (`grantKeepAlive`) during the
 * SYSTEM_ACCESS bootstrap. The one remaining piece needs a user confirm because Android requires it
 * for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, so we show ONE direct-system-popup CTA:
 *   Not exempted → only "ALLOW BATTERY" (fires the system allow-dialog, like Constellation-Glass).
 *   Exempted     → show ✓ + auto-advance (no CONTINUE; often already exempt from the onCreate ask).
 */
@Composable
private fun KeepAliveStep(
    batteryExempted: Boolean,
    onRequestBattery: () -> Unit,
    onNext: () -> Unit,
) {
    Text(stringResource(R.string.wizard_keepalive_title), style = HaloType.Title)
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.wizard_keepalive_body), style = HaloType.Caption)
    Spacer(Modifier.height(14.dp))

    if (!batteryExempted) {
        Text(stringResource(R.string.wizard_keepalive_battery_desc), style = HaloType.Caption)
        Spacer(Modifier.height(12.dp))
        Cta(stringResource(R.string.wizard_keepalive_battery_cta), onClick = onRequestBattery)
        return
    }

    // Exempt (granted by the popup or already-on) → confirm + advance. No "find it in Settings" tail.
    StatusLine(stringResource(R.string.wizard_keepalive_battery_row), ok = true)
    androidx.compose.runtime.LaunchedEffect(Unit) { onNext() }
}

/** Short window before we stop showing the indefinite "connecting" text and offer a retry —
 *  kept brief so the user isn't left staring at a spinner. */
private const val RING_RECONNECT_TIMEOUT_MS = 8_000L

/**
 * Ring is paired but the BLE link is down (typical right after a reboot — the link drops while the
 * service restarts). Auto-fire a reconnect and wait a short window:
 *   - link comes back → advance.
 *   - still down after [RING_RECONNECT_TIMEOUT_MS] → show a timeout note + a single TRY AGAIN button.
 * Reconnect failing occasionally is expected; a retry almost always succeeds, and the service keeps
 * auto-reconnecting in the background regardless, so this step is best-effort.
 */
@Composable
private fun RingReconnectStep(
    ringConnected: Boolean,
    onReconnect: () -> Unit,
    onConnected: () -> Unit,
) {
    val connectedNow by rememberUpdatedState(ringConnected)
    var attempt by remember { mutableStateOf(0) }
    var timedOut by remember { mutableStateOf(false) }

    // Link restored → finish.
    androidx.compose.runtime.LaunchedEffect(ringConnected) {
        if (ringConnected) onConnected()
    }

    // Each attempt fires one reconnect and arms a fresh timeout.
    androidx.compose.runtime.LaunchedEffect(attempt) {
        timedOut = false
        onReconnect()
        kotlinx.coroutines.delay(RING_RECONNECT_TIMEOUT_MS)
        if (!connectedNow) timedOut = true
    }

    Text(stringResource(R.string.wizard_ring_reconnect_title), style = HaloType.Title)
    Spacer(Modifier.height(8.dp))
    if (!timedOut) {
        Text(stringResource(R.string.wizard_ring_reconnect_progress), style = HaloType.Body)
    } else {
        Text(
            stringResource(R.string.wizard_ring_reconnect_timeout),
            style = HaloType.Body.copy(color = HaloColors.Bad),
        )
        Spacer(Modifier.height(16.dp))
        Cta(stringResource(R.string.wizard_ring_reconnect_retry_cta), onClick = { attempt++ })
    }
}
