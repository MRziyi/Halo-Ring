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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.halo.ring.R
import com.halo.ring.ui.Cta
import com.halo.ring.ui.ListRow
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.MetricCell
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

@Immutable
data class VitalsState(
    val heartRateBpm: Int?      = null,
    val spo2Pct: Int?           = null,
    val stressIndex: Int?       = null,
    val measuredMinutesAgo: Int? = null,
    val measuring: Boolean      = false,
    val stepsToday: Int         = 0,
    val caloriesToday: Float    = 0f,
    val distanceKmToday: Float  = 0f,
)

/**
 * Most-recent vitals snapshot the foreground service has collected from the BLE pipeline. Updated
 * once per [com.halo.ring.core.ble.R08BleClient.requestVitalsSnapshot] cycle. Pass into
 * [VitalsScreen] via the [VitalsState] mapping in [com.halo.ring.MainActivity].
 */
@Immutable
data class VitalsSnapshot(
    val heartRateBpm: Int? = null,
    val spo2Pct: Int? = null,
    val stressIndex: Int? = null,
    /** Monotonic clock (SystemClock.uptimeMillis) of the most-recent reading. */
    val capturedAtMs: Long = 0L,
    val measuring: Boolean = false,
)

/**
 * Job 1: physiological data dashboard. Top: big numbers (HR / SpO₂ / stress). One CTA to take a
 * fresh snapshot. Bottom: passive today-activity list.
 *
 * Snapshots are ONLY taken on demand — the PPG LED is only on during a [Cta] tap. Never
 * continuous, see Doc/06-performance-and-power.md §3.4.
 */
@Composable
fun VitalsScreen(
    state: VitalsState,
    focusedIndex: Int = 0,
) {
    // Reads the BLE client directly off [LocalAppGraph] instead of taking a callback parameter
    // (A-4 refactor) — the screen always wants to talk to the same singleton, so threading the
    // callback through HaloRingApp → AppTab.VITALS branch was pure ceremony.
    val graph = LocalAppGraph.current
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
    ) {
        // Big metric row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetricCell(
                value = state.heartRateBpm?.toString() ?: stringResource(R.string.common_dash),
                unit = stringResource(R.string.vitals_unit_bpm),
                label = stringResource(R.string.vitals_heart_rate_label),
                valueColor = if (state.heartRateBpm != null) HaloColors.Accent else HaloColors.Mute,
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                value = state.spo2Pct?.toString() ?: stringResource(R.string.common_dash),
                unit = stringResource(R.string.vitals_unit_percent),
                label = stringResource(R.string.vitals_spo2_label),
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                value = state.stressIndex?.toString() ?: stringResource(R.string.common_dash),
                label = stringResource(R.string.vitals_stress_label),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(6.dp))

        val mins = state.measuredMinutesAgo
        Text(
            modifier = Modifier.padding(horizontal = ScreenPadding),
            text = when {
                state.measuring -> stringResource(R.string.vitals_measuring).lowercase()
                mins == null   -> stringResource(R.string.common_never)
                mins == 0      -> stringResource(R.string.vitals_measured_just_now)
                else           -> stringResource(R.string.vitals_measured_min_ago, mins)
            },
            style = HaloType.Caption,
        )

        Spacer(Modifier.height(18.dp))

        Cta(
            text = stringResource(if (state.measuring) R.string.vitals_measuring else R.string.vitals_measure_now),
            modifier = Modifier.padding(horizontal = ScreenPadding),
            focused = focusedIndex == 0,
            onClick = { graph.bleClient.requestVitalsSnapshot() },
        )

        Spacer(Modifier.height(18.dp))

        // Today's passive activity (zero extra cost — ring counts these continuously)
        Column {
            ListRow(
                key = stringResource(R.string.vitals_steps_today),
                value = "%,d".format(state.stepsToday),
                focused = focusedIndex == 1,
            )
            ListRow(
                key = stringResource(R.string.vitals_calories),
                value = stringResource(R.string.vitals_calories_unit, "%.0f".format(state.caloriesToday)),
                focused = focusedIndex == 2,
            )
            ListRow(
                key = stringResource(R.string.vitals_distance),
                value = stringResource(R.string.vitals_distance_unit, "%.1f".format(state.distanceKmToday)),
                focused = focusedIndex == 3,
            )
        }
    }
}
