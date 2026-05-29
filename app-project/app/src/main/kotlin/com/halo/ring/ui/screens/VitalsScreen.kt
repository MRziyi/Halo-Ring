package com.halo.ring.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.halo.ring.R
import com.halo.ring.ui.Cta
import com.halo.ring.ui.LocalAppGraph
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
    /** Sport-session state — kept on the model for the HUD sport tick; the home UI no longer
     *  surfaces a workout control (removed 2026-05-29 per Zack: not used on glasses). */
    val sportActive: Boolean    = false,
    val sportType: Int          = 1,
    val sportDurationSec: Int?  = null,
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
    /** Healthcheck composite — SBP / DBP in mmHg. Populated when `0x69 type=0x05` converges. */
    val systolic: Int? = null,
    val diastolic: Int? = null,
    /** Body / skin temperature in °C. Populated if the temp opcode ever converges (RT08: progress-only). */
    val temperature: Float? = null,
    /** Monotonic clock (SystemClock.uptimeMillis) of the most-recent reading. */
    val capturedAtMs: Long = 0L,
    val measuring: Boolean = false,
    /** True iff the most recent attempt finished with `err=0x02` wear-detect-fail. */
    val wearDetectFail: Boolean = false,
    /** Live activity counters from `0x73 sub=0x12 ACTIVITY_TOTAL` push (SPEC v3 §5.3).
     *  - [activitySteps]: today's step total
     *  - [activityKcal]: today's kcal (= mcal/1000; on RT08 = `steps × 36 mcal` exactly, not a
     *    physiological estimate — caveat that to the user)
     *  - [activityMeters]: integer meters (NOT km) */
    val activitySteps: Int? = null,
    val activityKcal: Float? = null,
    val activityMeters: Int? = null,
    /** True when a `0x77` workout session is active on the ring (between START and STOP). Drives
     *  the HUD sport tick; the home UI no longer has a workout control. */
    val sportActive: Boolean = false,
    val sportType: Int = 1,
    val sportDurationSec: Int? = null,
    /** Most-recent accelerometer magnitude in g (1.0 = at rest, ≈0 = free-fall, >2 = impact). */
    val lastAccelMagnitudeG: Float? = null,
)

/**
 * The VITALS tab content — compact, secondary by design (Zack 2026-05-29: the big-number hero was
 * "too big / secondary info"). One row of the two vitals the firmware actually produces, a
 * measured-ago caption, the MEASURE CTA, and a single optional line of today's passive activity.
 *
 * SPEC v3 §10.5 (the only protocol ground truth): on RT08_3.10.46 only **HR + SpO₂** compute real
 * values from `0x69`. HRV/Stress, blood-pressure and temperature are advertised by the capability
 * bitmap but their measurement `val` stays 0 — so they're not shown (a cell that can only ever read
 * "—" is a lie). MEASURE starts HR (`0x69[01,01]`) and SpO₂ (`0x69[03,01]`) in parallel — both PPG
 * passes run together — so one button yields both. Snapshots are on-demand only (PPG LED on only
 * during the measurement; never continuous — Doc/04 §7).
 */
@Composable
fun VitalsScreen(
    state: VitalsState,
    capabilities: Set<String> = emptySet(),
    /** Whether the ring is currently worn. When false the readout guides the user to put it on
     *  rather than grinding MEASURE to a wear-detect-fail. */
    worn: Boolean = true,
) {
    val showSpo2 = capabilities.isEmpty() || "bloodOxygen" in capabilities
    val graph = LocalAppGraph.current
    val dash = stringResource(R.string.common_dash)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = ScreenPadding, end = ScreenPadding),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            VitalInline(
                value = state.heartRateBpm?.toString() ?: dash,
                unit = stringResource(R.string.vitals_unit_bpm),
                label = stringResource(R.string.vitals_heart_rate_label),
                valueColor = if (state.heartRateBpm != null) HaloColors.Accent else HaloColors.Mute,
                modifier = Modifier.weight(1f),
            )
            if (showSpo2) {
                VitalInline(
                    value = state.spo2Pct?.toString() ?: dash,
                    unit = stringResource(R.string.vitals_unit_percent),
                    label = stringResource(R.string.vitals_spo2_label),
                    valueColor = if (state.spo2Pct != null) HaloColors.Fg else HaloColors.Mute,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                state.measuring -> stringResource(R.string.vitals_measuring).lowercase()
                !worn           -> stringResource(R.string.vitals_put_ring_on)
                state.measuredMinutesAgo == null -> stringResource(R.string.common_never)
                state.measuredMinutesAgo == 0    -> stringResource(R.string.vitals_measured_just_now)
                else -> stringResource(R.string.vitals_measured_min_ago, state.measuredMinutesAgo)
            },
            style = HaloType.Caption,
        )

        Spacer(Modifier.height(12.dp))
        Cta(
            text = stringResource(if (state.measuring) R.string.vitals_measuring else R.string.vitals_measure_now),
            // Guard: a measurement while off-finger just grinds to a wear-detect-fail; the caption
            // above already tells the user to put the ring on.
            onClick = { if (worn) graph.bleClient.requestVitalsSnapshot() },
        )

        // One compact line of today's passive activity — real, "free" data from `0x73` sub-18
        // ACTIVITY_TOTAL (SPEC §5.3). Hidden until the ring has pushed at least one count this
        // session (showing 0 / 0.0 would be a lie).
        if (state.stepsToday > 0 || state.distanceKmToday > 0f) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.vitals_today_summary,
                    "%,d".format(state.stepsToday),
                    "%.1f".format(state.distanceKmToday),
                ),
                style = HaloType.Caption,
            )
        }
    }
}

/** A compact metric: medium value + unit on one baseline, small uppercase label beneath. Smaller
 *  than the old 39sp hero — vitals are secondary on the redesigned home. */
@Composable
private fun VitalInline(
    value: String,
    unit: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = HaloType.MetricMid.copy(color = valueColor))
            Spacer(Modifier.width(3.dp))
            Text(unit, style = HaloType.MetricUnit, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(label.uppercase(), style = HaloType.MetricKey)
    }
}
