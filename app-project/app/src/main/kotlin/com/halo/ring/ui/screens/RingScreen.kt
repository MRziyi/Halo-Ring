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
import com.halo.ring.di.RingInfo
import com.halo.ring.ui.Cta
import com.halo.ring.ui.ListRow
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

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
) {
    // A-4: read the BLE client directly off LocalAppGraph instead of taking three callback
    // parameters. The three actions always target the same singleton, so threading them through
    // HaloRingApp's SETTINGS_RING branch was pure ceremony.
    val graph = LocalAppGraph.current
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = info.advertisedName ?: "Ring",
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        Text(
            text = if (info.connected) "Connected" else "Disconnected",
            style = HaloType.Caption.copy(
                color = if (info.connected) HaloColors.Accent else HaloColors.Bad,
            ),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(8.dp))

        ListRow("MAC",      info.macAddress ?: "—")
        ListRow("Firmware", info.firmwareVersion ?: "—")
        ListRow("Signal",   info.rssiDbm?.let { "$it dBm" } ?: "—")
        ListRow("Battery",  info.batteryPct?.let { "$it %" } ?: "—",
                valueColor = batteryColor(info.batteryPct))

        Spacer(Modifier.height(20.dp))
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            Cta(text = "FIND RING", onClick = { graph.bleClient.blinkLed() })
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            Cta(text = "SHUTDOWN", danger = true, onClick = { graph.bleClient.shutdownRing() })
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            // Forget = drop current connection + start a fresh scan. The MAC whitelist that
            // would normally pin to the previous ring is gated by the bonded device list in
            // AndroidR08BleClient, which start() consults; a clean stop/start cycle is the
            // simplest "release this ring" semantics.
            Cta(text = "FORGET", danger = true, onClick = {
                graph.bleClient.stop()
                graph.bleClient.start()
            })
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Shutdown powers the ring off — you'll need to put it on the cradle briefly to wake it.\n" +
                "Forget drops the MAC whitelist so a different ring can be paired.",
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

private fun batteryColor(pct: Int?) = when {
    pct == null -> HaloColors.Fg
    pct <= 20   -> HaloColors.Warn
    pct <= 5    -> HaloColors.Bad
    else        -> HaloColors.Fg
}
