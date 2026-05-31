package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.halo.ring.R
import com.halo.ring.di.RingInfo
import com.halo.ring.ui.ConfirmCta
import com.halo.ring.ui.Cta
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.ScreenPadding

/** The three top-level surfaces of the home. RING leads because reconnect / find are the
 *  most-used actions (Zack 2026-05-29); VITALS is the physiological readout; MORE holds the rest. */
enum class HomeTab(val labelRes: Int) {
    RING(R.string.tab_ring),
    VITALS(R.string.tab_vitals),
    MORE(R.string.tab_more),
}

/**
 * The Config Activity root — a horizontal 3-tab home (RING · VITALS · MORE) sized for the glasses'
 * wide, short display: the tab strip is fixed and each tab fits one screen with no vertical
 * scrolling.
 *
 * Each tab owns its own settings (Zack 2026-05-29): RING carries ring + power config, VITALS carries
 * vitals config, MORE is the genuinely-misc rest. Settings open as drill-in [SubScreen]s.
 *
 * **Tab switching is the in-app LONG_PRESS context gesture only** (see InteractionRouter §0b) — the
 * tab strip is a non-focusable indicator, so swipe up/down focus stays purely on the active tab's
 * content (Zack: "focus should never land on a tab"). Within a tab: swipe = focus nav, TAP =
 * select, DOUBLE_TAP = back. Content scrolls (e.g. the long MORE list); the tab strip stays fixed.
 */
@Composable
fun HomeDashboardScreen(
    homeTab: HomeTab,
    vitals: VitalsState,
    ringInfo: RingInfo,
    capabilities: Set<String> = emptySet(),
    onOpenRingDetails: () -> Unit = {},
    onOpenPower: () -> Unit = {},
    onOpenVitalsSettings: () -> Unit = {},
    onSectionSelected: (SettingsSection) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TabStrip(selected = homeTab)
        Spacer(Modifier.height(6.dp))
        // Content is a direct child of the focus group (HaloRingApp) — NO scroll container in
        // between, or it intercepts the initial focus request and the cursor never lands on the
        // first row (Zack 2026-05-29: "RING 一开始没 focus 移动不了"). The Rokid panel is 480×640
        // portrait, so each tab fits without scrolling.
        when (homeTab) {
            HomeTab.RING -> RingHomeContent(
                info = ringInfo,
                onOpenRingDetails = onOpenRingDetails,
                onOpenPower = onOpenPower,
            )
            HomeTab.VITALS -> VitalsTabContent(
                vitals = vitals,
                capabilities = capabilities,
                worn = ringInfo.worn != false,   // unknown (null) → treat as worn, don't block MEASURE
                onOpenVitalsSettings = onOpenVitalsSettings,
            )
            HomeTab.MORE -> SettingsRootScreen(onSectionSelected = onSectionSelected)
        }
    }
}

/** Horizontal tab strip — a NON-focusable indicator (switching is the long-press gesture). The
 *  active segment is accent text + a 2 dp accent underline; the rest are mute. */
@Composable
private fun TabStrip(selected: HomeTab) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            HomeTab.values().forEach { tab ->
                val active = tab == selected
                Box(
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(tab.labelRes),
                        style = if (active) HaloType.TabActive.copy(color = HaloColors.Accent) else HaloType.Tab,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            HomeTab.values().forEach { tab ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(if (tab == selected) HaloColors.Accent else HaloColors.Line),
                )
            }
        }
    }
}

/** RING tab — live status + the two most-used quick actions (reconnect, find), then drill-ins to
 *  the full ring + power settings. */
@Composable
private fun RingHomeContent(
    info: RingInfo,
    onOpenRingDetails: () -> Unit,
    onOpenPower: () -> Unit,
) {
    val graph = LocalAppGraph.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text = info.advertisedName ?: stringResource(R.string.ring_default_name),
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildString {
                if (info.charging == true) {
                    // On the dock: charging leads, then %. (Other status is moot while docked.)
                    append(stringResource(R.string.ring_status_charging))
                    info.batteryPct?.let { append(" · "); append(stringResource(R.string.ring_battery_pct, it)) }
                } else {
                    append(stringResource(if (info.connected) R.string.ring_status_connected else R.string.ring_status_disconnected))
                    // Surface the actionable "not worn" state; RSSI lives in Ring details.
                    if (info.connected && info.worn == false) { append(" · "); append(stringResource(R.string.ring_wear_off)) }
                    info.batteryPct?.let { append(" · "); append(stringResource(R.string.ring_battery_pct, it)) }
                    // (Removed 2026-05-29) The "NN ms" conn-interval read used to live here, but this
                    // firmware emits notifies only on gestures (not periodically), so the estimator
                    // mostly measured the idle gap *between* gestures, not the link interval — it read
                    // 185–1300 ms for what is really a 15–500 ms link. Misleading as a glanceable
                    // diagnostic, so it's gone.
                }
            },
            style = HaloType.Caption.copy(
                color = if (info.connected || info.charging == true) HaloColors.Accent else HaloColors.Bad,
            ),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(12.dp))
        // FIND RING goes FIRST now (Zack 2026-05-31): it's harmless — just blinks the LED — so it's
        // the safe default for the top button, where a stray focus-confirm is most likely. RECONNECT
        // (which tears the BLE pipeline down + rescans) used to be here and got mis-fired.
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            Cta(text = stringResource(R.string.ring_find_short), onClick = { graph.bleClient.findRing() })
        }
        Spacer(Modifier.height(6.dp))
        // RECONNECT is critical/disruptive (tears the BLE pipeline down + rescans), so it's
        // tap-twice-to-confirm via ConfirmCta. (Zack 2026-05-31)
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            ConfirmCta(
                text = stringResource(R.string.ring_reconnect_short),
                confirmText = stringResource(R.string.ring_reconnect_confirm),
                onConfirm = {
                    // stop() + start() = full pipeline teardown + rescan (same as Ring details).
                    graph.bleClient.stop()
                    graph.bleClient.start()
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        DrillRow(R.string.ring_info_title, onOpenRingDetails)
        DrillRow(R.string.settings_section_power, onOpenPower)
    }
}

/** VITALS tab — the compact readout (HR · SpO₂ + MEASURE + today), then a drill-in to vitals
 *  settings. */
@Composable
private fun VitalsTabContent(
    vitals: VitalsState,
    capabilities: Set<String>,
    worn: Boolean,
    onOpenVitalsSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        VitalsScreen(state = vitals, capabilities = capabilities, worn = worn)
        Spacer(Modifier.height(12.dp))
        DrillRow(R.string.settings_section_vitals_prefs, onOpenVitalsSettings)
    }
}

/** A settings drill-in row: label + chevron, with a hairline divider beneath. */
@Composable
private fun DrillRow(labelRes: Int, onClick: () -> Unit) {
    FocusableRow(onClick = onClick) {
        Text(stringResource(labelRes), style = HaloType.Body)
        Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}
