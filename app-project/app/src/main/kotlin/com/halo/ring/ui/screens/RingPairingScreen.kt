package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.halo.ring.R
import com.halo.ring.core.ble.DiscoveredDevice
import com.halo.ring.ui.Cta
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.ScreenPadding
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Ring pairing picker (burn-in fix 2026-05-27). User opens this from the first-run wizard's pair
 * step or from `Settings → Ring → PAIR…`. Lists all R0x-family BLE devices in range; tap one to
 * persist the MAC + start a real connection.
 *
 * Without this screen, the BLE client would scan unfiltered and auto-connect to the first
 * name-keyword-matching device on the airwaves — which, in a crowded RF environment, may not be
 * the user's actual ring. Now the user explicitly picks.
 */
@Composable
fun RingPairingScreen(
    onPaired: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    // Subscribe to the BLE client's discovery stream.
    val devices = remember { MutableStateFlow<List<DiscoveredDevice>>(emptyList()) }
    val deviceList by devices.collectAsState()
    // Connection-progress state for the post-tap stage (burn-in 2026-05-27 user feedback: don't
    // dismiss the picker the moment the user taps PAIR — show "Connecting…" → "Reading device
    // info…" → "Ready" and only auto-advance once bootstrap is stable).
    var pairingState by remember { mutableStateOf<PairingState>(PairingState.Picking) }
    val ringInfo by graph.ringInfoFlow.collectAsState()
    val capabilities by graph.ringCapabilitiesFlow.collectAsState()
    val connState = remember { MutableStateFlow(com.halo.ring.core.ble.ConnectionState.DISCONNECTED) }
    val currentConn by connState.collectAsState()

    DisposableEffect(Unit) {
        val devSub = graph.bleClient.discoveredDevices().subscribe { list -> devices.value = list }
        val connSub = graph.bleClient.connectionState().subscribe { s -> connState.value = s }
        graph.bleClient.startDiscovery()
        onDispose {
            devSub.unsubscribe()
            connSub.unsubscribe()
            // Only halt discovery if the user backed out without committing. After a commit, the
            // BLE client is already in scan-and-connect mode for the chosen MAC; calling
            // stopDiscovery here would tear that down.
            if (pairingState is PairingState.Picking) graph.bleClient.stopDiscovery()
        }
    }

    // Auto-advance when bootstrap is fully done. "Done" = BLE READY + capability bitmap arrived
    // (so we know the command-channel is actually responding, not just a stale TLS-only ready).
    androidx.compose.runtime.LaunchedEffect(currentConn, capabilities, ringInfo) {
        if (pairingState !is PairingState.Connecting) return@LaunchedEffect
        when {
            currentConn == com.halo.ring.core.ble.ConnectionState.READY && capabilities.isNotEmpty() -> {
                pairingState = PairingState.Done
                kotlinx.coroutines.delay(800)
                onPaired()
            }
            currentConn == com.halo.ring.core.ble.ConnectionState.READY ->
                pairingState = PairingState.ReadingInfo
            currentConn == com.halo.ring.core.ble.ConnectionState.CONNECTING ->
                pairingState = PairingState.Connecting
            currentConn == com.halo.ring.core.ble.ConnectionState.SCANNING ->
                pairingState = PairingState.Scanning
            else -> Unit  // keep current
        }
    }

    var selected by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.ring_pairing_title),
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        Text(
            text = when (pairingState) {
                PairingState.Picking      -> stringResource(R.string.ring_pairing_subtitle)
                PairingState.Scanning     -> stringResource(R.string.ring_pairing_scanning_paired)
                PairingState.Connecting   -> stringResource(R.string.ring_pairing_connecting)
                PairingState.ReadingInfo  -> stringResource(R.string.ring_pairing_reading_info)
                PairingState.Done         -> stringResource(R.string.ring_pairing_done)
            },
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(12.dp))

        if (pairingState is PairingState.Picking) {
            if (deviceList.isEmpty()) {
                Text(
                    text = stringResource(R.string.ring_pairing_scanning),
                    style = HaloType.Caption.copy(color = HaloColors.Mute),
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }
            deviceList.forEach { d ->
                val isSelected = selected == d.macAddress
                FocusableRow(onClick = { selected = d.macAddress }) {
                    Column(Modifier.padding(end = 8.dp).weight(1f)) {
                        Text(
                            text = d.advertisedName ?: stringResource(R.string.ring_pairing_unknown_device),
                            style = HaloType.Body.copy(color = if (isSelected) HaloColors.Accent else HaloColors.Fg),
                        )
                        Text(
                            text = "${d.macAddress.takeLast(8)}  •  ${d.rssiDbm} dBm",
                            style = HaloType.Caption,
                        )
                    }
                    if (isSelected) Text("●", style = HaloType.Body.copy(color = HaloColors.Accent))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
            }
        } else {
            // Post-tap: show the device name + a step indicator so the user knows what's happening.
            val mac = selected
            val name = deviceList.firstOrNull { it.macAddress == mac }?.advertisedName
                ?: ringInfo.advertisedName ?: mac?.takeLast(8) ?: "—"
            Column(Modifier.padding(horizontal = ScreenPadding)) {
                Text(name, style = HaloType.Body.copy(color = HaloColors.Accent))
                Text(
                    text = when (pairingState) {
                        PairingState.Scanning     -> "1/3 " + stringResource(R.string.ring_pairing_scanning_paired)
                        PairingState.Connecting   -> "2/3 " + stringResource(R.string.ring_pairing_connecting)
                        PairingState.ReadingInfo  -> "3/3 " + stringResource(R.string.ring_pairing_reading_info)
                        PairingState.Done         -> "✓ " + stringResource(R.string.ring_pairing_done)
                        else                      -> ""
                    },
                    style = HaloType.Caption,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            Cta(
                text = when (pairingState) {
                    PairingState.Picking ->
                        stringResource(if (selected == null) R.string.ring_pairing_pick_first else R.string.ring_pairing_confirm)
                    PairingState.Scanning, PairingState.Connecting, PairingState.ReadingInfo ->
                        stringResource(R.string.ring_pairing_connecting)
                    PairingState.Done ->
                        stringResource(R.string.ring_pairing_done)
                },
                onClick = {
                    if (pairingState !is PairingState.Picking) return@Cta
                    val mac = selected ?: return@Cta
                    val name = deviceList.firstOrNull { it.macAddress == mac }?.advertisedName
                    pairingState = PairingState.Connecting   // optimistic — the LaunchedEffect refines
                    scope.launch {
                        graph.ringPairingPrefs.setPaired(mac, name)
                        graph.bleClient.stopDiscovery()
                        graph.bleClient.setPairedMac(mac)
                        graph.bleClient.start()
                    }
                },
            )
        }
    }
}

/** Pairing UI's post-tap progress state. Drives the subtitle + Cta label so the user doesn't
 *  stare at a frozen "PAIR" button while bootstrap runs through 3-4 stages over ~5-10 seconds. */
private sealed interface PairingState {
    /** Initial — list discovered devices, wait for tap. */
    data object Picking : PairingState
    /** Scan started for the chosen MAC; waiting for GATT connect. */
    data object Scanning : PairingState
    /** GATT connect in progress. */
    data object Connecting : PairingState
    /** GATT connected; reading HW/FW rev + capability bitmaps. */
    data object ReadingInfo : PairingState
    /** Bootstrap fully complete; auto-dismiss in 800 ms. */
    data object Done : PairingState
}
