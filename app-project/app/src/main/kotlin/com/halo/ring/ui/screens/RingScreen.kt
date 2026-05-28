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
import com.halo.ring.R
import com.halo.ring.di.RingInfo
import com.halo.ring.ui.Cta
import com.halo.ring.ui.ListRow
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings → Ring (mockup §3 G).
 *
 * Read-only telemetry on top — MAC, firmware, signal, battery, advertised name — followed by
 * three actions:
 *  - **Find ring** — blink the LED twice ([blinkLed]) so a misplaced ring becomes locatable.
 *  - **Shutdown** — power off the ring. User must re-cradle to bring it back. **Destructive.**
 *  - **Forget** — drop the MAC whitelist so a fresh pairing flow can start. **Destructive.**
 *
 * Values come from [com.halo.ring.di.AppGraph.ringInfoFlow]. Until the ring has been seen at
 * least once, MAC/firmware/RSSI are rendered as `—`.
 */
@Composable
fun RingScreen(
    info: RingInfo,
    onOpenPairing: () -> Unit = {},
    /** v0.4 C4 (Doc/20 §8): SPEC v3 capability bitmap. Rendered as a "Capabilities" caption at
     *  the bottom so users can see what their ring's firmware claims to support. */
    capabilities: Set<String> = emptySet(),
) {
    // A-4: read the BLE client directly off LocalAppGraph instead of taking three callback
    // parameters. The three actions always target the same singleton, so threading them through
    // HaloRingApp's SETTINGS_RING branch was pure ceremony.
    val graph = LocalAppGraph.current
    val pairingScope = rememberCoroutineScope()
    val dash = stringResource(R.string.common_dash)
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = info.advertisedName ?: stringResource(R.string.ring_default_name),
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        Text(
            text = stringResource(if (info.connected) R.string.ring_status_connected else R.string.ring_status_disconnected),
            style = HaloType.Caption.copy(
                color = if (info.connected) HaloColors.Accent else HaloColors.Bad,
            ),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(8.dp))

        ListRow(stringResource(R.string.ring_mac),      info.macAddress ?: dash)
        ListRow(stringResource(R.string.ring_firmware), info.firmwareVersion ?: dash)
        ListRow(stringResource(R.string.ring_signal),   info.rssiDbm?.let { stringResource(R.string.ring_signal_unit_dbm, it) } ?: dash)
        ListRow(stringResource(R.string.ring_battery),  info.batteryPct?.let { stringResource(R.string.ring_battery_pct, it) } ?: dash,
                valueColor = batteryColor(info.batteryPct))

        Spacer(Modifier.height(20.dp))
        // Burn-in fix 2026-05-27: PAIR / RE-PAIR opens the picker so the user can choose which
        // R0x ring on the airwaves is theirs. Without this CTA the service would never auto-scan
        // (it gates on the persisted MAC), and the only way to recover was to wipe app data.
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            Cta(text = stringResource(
                if (info.macAddress == null) R.string.ring_pair_short else R.string.ring_repair_short
            ), onClick = onOpenPairing)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            // SPEC v3 §4.9: 0x50 [0x55, 0xAA] — ring LED flashes briefly. Was previously wired to
            // 0x10 (BindSuccess marker — no-op) and earlier 0x06 (DND, returns 0xEE on RT08).
            Cta(text = stringResource(R.string.ring_find_short), onClick = { graph.bleClient.findRing() })
        }
        Spacer(Modifier.height(8.dp))
        // Audit-pass 2026-05-14w: ForceReconnect used to live on the DOUBLE_LONG_PRESS system slot,
        // but BLE auto-reconnect handles 99% of disconnects so the slot was reallocated to
        // OpenAIAssistant. The remaining 1% — stuck BLE stack — is rare enough that a button buried
        // in Settings is the right home. stop() + start() = full pipeline teardown + rescan.
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            Cta(text = stringResource(R.string.ring_reconnect_short), onClick = {
                graph.bleClient.stop()
                graph.bleClient.start()
            })
        }
        Spacer(Modifier.height(8.dp))
        // Audit-pass 2026-05-27z: Shutdown CTA removed. SPEC v3 §4.2 — `0x08 [0x01]` is destructive
        // and was previously wired to `0x0F` which is OTA-mode entry (would brick the ring). R08
        // has no display + auto-sleeps when idle; users power it down by physically docking it on
        // the charging cradle. Keeping a destructive button in Settings was a misfeature.
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            // Burn-in fix 2026-05-27: Forget now also clears the persisted paired MAC. Without
            // this, the service would still auto-scan + lock onto the old MAC on every restart.
            // After Forget, the user must explicitly tap PAIR to choose a new ring.
            Cta(text = stringResource(R.string.ring_forget_short), danger = true, onClick = {
                pairingScope.launch {
                    graph.bleClient.stop()
                    graph.bleClient.setPairedMac(null)
                    graph.ringPairingPrefs.clear()
                }
                onOpenPairing()   // drop user straight into the picker since the ring is now unset
            })
        }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.ring_screen_footer),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        if (capabilities.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ring_capabilities_header),
                style = HaloType.RowKey,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
            Text(
                text = capabilities.sorted().joinToString(", "),
                style = HaloType.Caption,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
            )
        }
    }
}

private fun batteryColor(pct: Int?) = when {
    pct == null -> HaloColors.Fg
    pct <= 5    -> HaloColors.Bad
    pct <= 20   -> HaloColors.Warn
    else        -> HaloColors.Fg
}
