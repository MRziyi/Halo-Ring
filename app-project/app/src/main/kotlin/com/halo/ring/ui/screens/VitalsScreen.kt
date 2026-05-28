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
    /** v0.4 C5 — sport-session state surface for [VitalsScreen]. */
    val sportActive: Boolean    = false,
    val sportType: Int          = 1,
    val sportDurationSec: Int?  = null,
)

/** v0.4 C5 — kick off a sport session and flip the UI-side `sportActive` flag immediately so the
 *  user sees feedback before the first `0x78` tick arrives (a few seconds later). */
private fun startSport(graph: com.halo.ring.di.AppGraph?, type: Int) {
    if (graph == null) return
    graph.vitalsSnapshotFlow.value = graph.vitalsSnapshotFlow.value.copy(
        sportActive = true,
        sportType = type,
        sportDurationSec = 0,
    )
    graph.bleClient.startSportSession(type)
}

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
    /** v0.4 C5: true when a `0x77` workout session is active on the ring (between START and STOP). */
    val sportActive: Boolean = false,
    /** Sport type the user picked when starting the session (Walk=1 / Run=2 / Cycle=3 / Other=99
     *  is the Halo Ring mapping; the ring just echoes whatever byte we sent). */
    val sportType: Int = 1,
    /** During a `0x77` sport session, the live duration the ring is reporting via `0x78` ticks. */
    val sportDurationSec: Int? = null,
    /** Most-recent accelerometer magnitude in g (1.0 = at rest, ≈0 = free-fall, >2 = impact). */
    val lastAccelMagnitudeG: Float? = null,
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
    /** v0.4 C4 (Doc/20 §8): SPEC v3 capability bitmap. Hides metric cells the ring's firmware
     *  doesn't actually support. Empty set = show everything (back-compat for tests / dev rigs). */
    capabilities: Set<String> = emptySet(),
    /** v0.4 C5: live sport-session state. Source of truth = `vitalsSnapshotFlow`; threaded in
     *  via [com.halo.ring.MainActivity]. */
    sportActive: Boolean = false,
    sportType: Int = 1,
    sportDurationSec: Int? = null,
) {
    val showStress = capabilities.isEmpty() || "hrv" in capabilities
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
            if (showStress) {
                MetricCell(
                    value = state.stressIndex?.toString() ?: stringResource(R.string.common_dash),
                    label = stringResource(R.string.vitals_stress_label),
                    modifier = Modifier.weight(1f),
                )
            } else {
                // Reserve the column so HR / SpO2 don't expand to the full width when Stress is
                // gated out — keeps the layout consistent across rings with and without HRV.
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            }
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

        Spacer(Modifier.height(20.dp))

        // ─── v0.4 C5 — workout (sport-session) sub-section ──────────────────────────────────
        Text(
            text = stringResource(R.string.vitals_sport_section).uppercase(),
            style = HaloType.Caption.copy(color = HaloColors.Mute),
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
        )
        if (sportActive) {
            val mins = (sportDurationSec ?: 0) / 60
            val secs = (sportDurationSec ?: 0) % 60
            Text(
                text = stringResource(R.string.vitals_sport_active, "%02d:%02d".format(mins, secs)),
                style = HaloType.Body.copy(color = HaloColors.Accent),
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
            )
            Cta(
                text = stringResource(R.string.vitals_sport_stop),
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                onClick = {
                    graph.bleClient.stopSportSession(sportType)
                    if (graph != null) {
                        graph.vitalsSnapshotFlow.value = graph.vitalsSnapshotFlow.value.copy(
                            sportActive = false,
                            sportDurationSec = null,
                        )
                    }
                },
                danger = true,
            )
        } else {
            // Quick-start buttons — sport_type 1=walk, 2=run, 3=cycle per SPEC v3 §4.8 echo.
            // Stacked VERTICALLY (not a Row): the ring only has up/down swipes (no left/right),
            // so a horizontal row of buttons would be unreachable. On-glasses fix 2026-05-27.
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                Cta(
                    text = stringResource(R.string.vitals_sport_start_walk),
                    onClick = { startSport(graph, type = 1) },
                )
                Spacer(Modifier.height(6.dp))
                Cta(
                    text = stringResource(R.string.vitals_sport_start_run),
                    onClick = { startSport(graph, type = 2) },
                )
                Spacer(Modifier.height(6.dp))
                Cta(
                    text = stringResource(R.string.vitals_sport_start_cycle),
                    onClick = { startSport(graph, type = 3) },
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // Today's passive activity. Audit-pass 2026-05-13t: pedometer streaming from the ring
        // isn't decoded yet (Doc/07 §3 — needs the steps/cal/dist BLE notifs hooked up). The
        // VitalsState fields default to 0; showing "0 / 0 kcal / 0.0 km" would be a lie, so we
        // surface the dash placeholder + a "(coming soon)" caption until the wiring lands.
        val dash = stringResource(R.string.common_dash)
        val hasActivityData = state.stepsToday > 0 || state.caloriesToday > 0f || state.distanceKmToday > 0f
        Column {
            ListRow(
                key = stringResource(R.string.vitals_steps_today),
                value = if (hasActivityData) "%,d".format(state.stepsToday) else dash,
                focused = focusedIndex == 1,
            )
            ListRow(
                key = stringResource(R.string.vitals_calories),
                value = if (hasActivityData) stringResource(R.string.vitals_calories_unit, "%.0f".format(state.caloriesToday)) else dash,
                focused = focusedIndex == 2,
            )
            ListRow(
                key = stringResource(R.string.vitals_distance),
                value = if (hasActivityData) stringResource(R.string.vitals_distance_unit, "%.1f".format(state.distanceKmToday)) else dash,
                focused = focusedIndex == 3,
            )
            if (!hasActivityData) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.common_coming_soon),
                    style = HaloType.Caption.copy(color = HaloColors.Mute),
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }
        }
    }
}
