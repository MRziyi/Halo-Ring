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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.ui.AccentBar
import com.halo.ring.ui.ListRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

@Immutable
data class StatusState(
    val connected: Boolean = false,
    val rssiDbm: Int? = null,
    val connIntervalMs: Int? = null,
    val intervalMode: com.halo.ring.core.power.PowerPolicy.IntervalMode =
        com.halo.ring.core.power.PowerPolicy.IntervalMode.BALANCED,
    val profileName: String = "Navigation",
    val profileAutoSwitched: Boolean = false,
    val lastGesture: String? = null,
    val lastGestureAgoSec: Double? = null,
    val lastGestureLatencyMs: Int? = null,
    val activeBackend: String = "(none)",
    val backgroundDrawMa: Float = 0f,           // estimated additional mA from our app
    val droppedEventsLastHour: Int = 0,
    /** Each entry = number of gestures in that 5-minute bucket over the last hour (12 buckets). */
    val gestureHistogram: List<Int> = emptyList(),
)

/**
 * Job 3: resident-service telemetry. Lets the wearer verify the background service is alive and
 * lean. Replaces what would be a hidden debug menu on other apps.
 */
@Composable
fun StatusScreen(state: StatusState) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        ListRow(
            key = "Ring",
            value = if (state.connected) {
                val rssi = state.rssiDbm?.let { " · $it dBm" } ?: ""
                "● Connected$rssi"
            } else "● Disconnected",
            valueColor = if (state.connected) HaloColors.Accent else HaloColors.Bad,
        )
        ListRow(
            key = "Conn interval",
            value = state.connIntervalMs?.let {
                val label = when (state.intervalMode) {
                    com.halo.ring.core.power.PowerPolicy.IntervalMode.HIGH     -> "active"
                    com.halo.ring.core.power.PowerPolicy.IntervalMode.BALANCED -> "idle"
                    com.halo.ring.core.power.PowerPolicy.IntervalMode.SLOW     -> "screen-off"
                }
                "$it ms ($label)"
            } ?: "—",
        )
        ListRow(
            key = "Profile",
            value = "${state.profileName}${if (state.profileAutoSwitched) " (auto)" else ""}",
        )
        ListRow(
            key = "Last gesture",
            value = if (state.lastGesture != null && state.lastGestureAgoSec != null) {
                val lat = state.lastGestureLatencyMs?.let { " · $it ms" } ?: ""
                "${state.lastGesture} · ${"%.1f".format(state.lastGestureAgoSec)}s ago$lat"
            } else "—",
        )

        Spacer(Modifier.height(16.dp))

        // Background draw bar — caps the displayed area at 20 mA so any spike is obvious
        Column(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            Row(modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Background draw", style = HaloType.Caption,
                     modifier = Modifier.weight(1f))
                Text("+ ${"%.1f".format(state.backgroundDrawMa)} mA",
                     style = HaloType.Body)
            }
            Spacer(Modifier.height(6.dp))
            AccentBar(progress = (state.backgroundDrawMa / 20f).coerceIn(0f, 1f))
            Spacer(Modifier.height(4.dp))
            Text(
                "foreground service · no wakelock · agent: ${state.activeBackend}",
                style = HaloType.Caption.copy(fontSize = 11.sp),
            )
        }

        Spacer(Modifier.height(16.dp))

        // Sparkline: last hour of gesture activity (12 5-minute buckets).
        Column(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            Row(modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("BLE quality (last hour)", style = HaloType.Caption,
                     modifier = Modifier.weight(1f))
                Text("${state.droppedEventsLastHour} drops", style = HaloType.Body)
            }
            Spacer(Modifier.height(6.dp))
            GestureSparkline(state.gestureHistogram)
        }
    }
}

@Composable
private fun GestureSparkline(buckets: List<Int>) {
    if (buckets.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(28.dp).background(HaloColors.Line))
        return
    }
    val max = buckets.max().coerceAtLeast(1)
    Row(modifier = Modifier.fillMaxWidth().height(28.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
        buckets.forEach { count ->
            val frac = (count.toFloat() / max).coerceIn(0.05f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((28 * frac).dp)
                    .background(if (count == 0) HaloColors.Line else HaloColors.Accent),
            )
            Spacer(Modifier.width(2.dp))
        }
    }
}
