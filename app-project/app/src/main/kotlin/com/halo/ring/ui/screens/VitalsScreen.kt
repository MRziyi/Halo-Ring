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
import androidx.compose.ui.unit.dp
import com.halo.ring.ui.Cta
import com.halo.ring.ui.ListRow
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
    onMeasureNow: () -> Unit = {},
    onExportLog: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
    ) {
        // Big metric row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetricCell(
                value = state.heartRateBpm?.toString() ?: "—",
                unit = "bpm",
                label = "Heart rate",
                valueColor = if (state.heartRateBpm != null) HaloColors.Accent else HaloColors.Mute,
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                value = state.spo2Pct?.toString() ?: "—",
                unit = "%",
                label = "SpO₂",
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                value = state.stressIndex?.toString() ?: "—",
                label = "Stress",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            modifier = Modifier.padding(horizontal = ScreenPadding),
            text = when {
                state.measuring -> "measuring…"
                state.measuredMinutesAgo == null -> "no recent reading"
                state.measuredMinutesAgo == 0 -> "measured just now"
                state.measuredMinutesAgo < 60 -> "measured ${state.measuredMinutesAgo} min ago"
                else -> "measured ${state.measuredMinutesAgo / 60} h ago"
            },
            style = HaloType.Caption,
        )

        Spacer(Modifier.height(18.dp))

        Cta(
            text = if (state.measuring) "MEASURING…" else "MEASURE NOW",
            modifier = Modifier.padding(horizontal = ScreenPadding),
            focused = focusedIndex == 0,
            onClick = onMeasureNow,
        )

        Spacer(Modifier.height(18.dp))

        // Today's passive activity (zero extra cost — ring counts these continuously)
        Column {
            ListRow(
                key = "Steps today",
                value = "%,d".format(state.stepsToday),
                focused = focusedIndex == 1,
            )
            ListRow(
                key = "Calories",
                value = "${"%.0f".format(state.caloriesToday)} kcal",
                focused = focusedIndex == 2,
            )
            ListRow(
                key = "Distance",
                value = "${"%.1f".format(state.distanceKmToday)} km",
                focused = focusedIndex == 3,
            )
        }
    }
}
