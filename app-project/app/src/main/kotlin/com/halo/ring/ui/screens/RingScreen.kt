@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.R
import com.halo.ring.di.RingInfo
import com.halo.ring.ui.ConfirmCta
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
    /** Touch-IC idle-sleep timeout in minutes (SPEC v3 §4.10). A ring-hardware power knob. */
    touchSleepMin: Int = com.halo.ring.core.ble.R08Protocol.DEFAULT_TOUCH_SLEEP_MIN,
    onTouchSleepMinChanged: (Int) -> Unit = {},
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

        // Detail-only telemetry. Connection state / battery / interval / Find / Reconnect live on the
        // RING tab itself (one level up), so they're NOT repeated here (Zack 2026-05-29 — de-dup).
        ListRow(stringResource(R.string.ring_mac),      info.macAddress ?: dash)
        ListRow(stringResource(R.string.ring_firmware), info.firmwareVersion ?: dash)
        ListRow(stringResource(R.string.ring_signal),   info.rssiDbm?.let { stringResource(R.string.ring_signal_unit_dbm, it) } ?: dash)

        // Touch-IC idle-sleep timeout (SPEC v3 §4.10): tap to cycle. Shorter saves ring battery; the
        // touch sensor sleeps this long after the last touch.
        com.halo.ring.ui.FocusableRow(onClick = { onTouchSleepMinChanged(cycleTouchSleep(touchSleepMin)) }) {
            Column(Modifier.padding(end = 8.dp).weight(1f)) {
                Text(stringResource(R.string.ring_sleep_title), style = HaloType.Body)
                Text(stringResource(R.string.ring_sleep_desc), style = HaloType.Caption.copy(fontSize = 11.sp))
            }
            Text(
                stringResource(R.string.ring_sleep_unit_min, touchSleepMin),
                style = HaloType.RowVal.copy(color = HaloColors.Accent),
            )
        }

        // Capabilities (info) sit with the other telemetry — ABOVE the actions — so the focusable
        // PAIR/FORGET stay last. If they trailed the buttons, focus-driven scroll couldn't reach
        // them (Zack 2026-05-29: "滑到 Forget 就到底，能力看不到").
        if (capabilities.isNotEmpty()) {
            // No explicit divider here — the signal ListRow above already draws its own trailing
            // divider; a second one made a double line (Zack 2026-05-29). Just a gap + the header.
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.ring_capabilities_header),
                style = HaloType.RowKey,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
            Spacer(Modifier.height(6.dp))
            // Wrapped chips instead of one long comma line — the list (~17 firmware flags decoded
            // from the SetTime + 0x3C capability bitmaps, SPEC v3 §3) overflowed a single row.
            FlowRow(
                modifier = Modifier.padding(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                capabilities.sorted().forEach { cap ->
                    Text(
                        text = cap,
                        style = HaloType.Caption.copy(color = HaloColors.Accent),
                        modifier = Modifier
                            .background(HaloColors.AccentDim, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        // PAIR / RE-PAIR opens the picker to choose which R0x ring is yours; FORGET clears the
        // persisted MAC so a fresh pairing can start. Both are unique to this screen + stay LAST.
        // First-time PAIR (no ring yet) is a single tap — nothing to lose. RE-PAIR + FORGET are
        // disruptive when you already have a working ring, so they're tap-twice-to-confirm. (Zack 2026-05-31)
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            if (info.macAddress == null) {
                Cta(text = stringResource(R.string.ring_pair_short), onClick = onOpenPairing)
            } else {
                ConfirmCta(
                    text = stringResource(R.string.ring_repair_short),
                    confirmText = stringResource(R.string.ring_repair_confirm),
                    onConfirm = onOpenPairing,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            ConfirmCta(
                text = stringResource(R.string.ring_forget_short),
                confirmText = stringResource(R.string.ring_forget_confirm),
                danger = true,
                onConfirm = {
                    pairingScope.launch {
                        graph.bleClient.stop()
                        graph.bleClient.setPairedMac(null)
                        graph.ringPairingPrefs.clear()
                    }
                    onOpenPairing()   // drop user straight into the picker since the ring is now unset
                },
            )
        }
    }
}

/** Touch-IC sleep-timeout presets in minutes (SPEC v3 §4.10 `sleepMin`). */
private val TOUCH_SLEEP_PRESETS = intArrayOf(1, 2, 5, 10, 15, 30)
private fun cycleTouchSleep(current: Int): Int {
    val i = TOUCH_SLEEP_PRESETS.indexOf(current)
    return if (i < 0) TOUCH_SLEEP_PRESETS.first() else TOUCH_SLEEP_PRESETS[(i + 1) % TOUCH_SLEEP_PRESETS.size]
}
