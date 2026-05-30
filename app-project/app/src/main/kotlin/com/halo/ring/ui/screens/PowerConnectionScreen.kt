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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.R
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloSwitch
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.ui.hud.profileFriendlyText
import com.halo.ring.core.action.KeyMapProfile
import com.halo.ring.core.gesture.GestureConfig

/**
 * Settings → Power & Connection (mockup §3 H). Three groups, top to bottom:
 *
 *  1. **Timing windows** — sliders for `multiTapWindowMs` / `comboWindowMs` /
 *     `longPressFollowupWindowMs`. Edits apply to the **active** profile only — different profiles
 *     are tuned independently (Navigation prefers precise; Fast prefers instant).
 *  2. **Latency switches** — `optimisticSingleTap` / `awaitCombos` / `awaitLongPressCombos` per
 *     [GestureConfig]. Toggles update the active profile's [GestureConfig] and re-publish via
 *     `onProfileUpdated`.
 *  3. **Connection** — informational text about the BLE-interval policy (managed by
 *     [com.halo.ring.core.power.PowerPolicy] automatically; no slider).
 *
 * No DataStore-specific code here — edits go through [com.halo.ring.di.AppGraph.profilesFlow]
 * which [ProfilesPrefsStore] then persists.
 */
@Composable
fun PowerConnectionScreen(
    activeProfile: KeyMapProfile,
    onActiveProfileUpdated: (KeyMapProfile) -> Unit = {},
    /** Touch-IC idle-sleep timeout in minutes (SPEC v3 §4.10). Wearer-tunable ring-battery knob. */
    touchSleepMin: Int = com.halo.ring.core.ble.R08Protocol.DEFAULT_TOUCH_SLEEP_MIN,
    onTouchSleepMinChanged: (Int) -> Unit = {},
) {
    val cfg = activeProfile.gestureConfig

    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.power_title),
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        Text(
            stringResource(R.string.power_active_profile_caption, profileFriendlyText(activeProfile)),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(12.dp))

        SectionHeader(stringResource(R.string.power_section_timing_label))
        CycleRow(
            title = stringResource(R.string.power_multi_tap_window),
            value = stringResource(R.string.power_window_unit_ms, cfg.multiTapWindowMs.toInt()),
            description = stringResource(R.string.power_multi_tap_desc),
            onTap = { update(activeProfile, onActiveProfileUpdated) {
                it.copy(multiTapWindowMs = cycleNext(MULTI_TAP_PRESETS, cfg.multiTapWindowMs))
            } },
        )
        CycleRow(
            title = stringResource(R.string.power_combo_window),
            value = stringResource(R.string.power_window_unit_ms, cfg.comboWindowMs.toInt()),
            description = stringResource(R.string.power_combo_desc),
            onTap = { update(activeProfile, onActiveProfileUpdated) {
                it.copy(comboWindowMs = cycleNext(COMBO_PRESETS, cfg.comboWindowMs))
            } },
        )
        CycleRow(
            title = stringResource(R.string.power_long_press_followup),
            value = stringResource(R.string.power_window_unit_ms, cfg.longPressFollowupWindowMs.toInt()),
            description = stringResource(R.string.power_long_press_followup_desc),
            onTap = { update(activeProfile, onActiveProfileUpdated) {
                it.copy(longPressFollowupWindowMs = cycleNext(LP_FOLLOWUP_PRESETS, cfg.longPressFollowupWindowMs))
            } },
        )

        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.power_section_latency_label))
        // Audit-pass 2026-05-14u: read the negation from `it` (the live cfg parameter passed
        // through `update`'s transform), not from the captured outer `cfg`. The outer val gets
        // re-bound on every recomposition, but the lambda factory had the previous-frame `cfg`
        // baked in until Compose re-created it — which led to "the first click works but the
        // second silently re-sets the same value" under some Compose recomposition timings.
        ToggleRow(
            title = stringResource(R.string.power_optimistic_single_tap),
            description = stringResource(R.string.power_optimistic_desc),
            on = cfg.optimisticSingleTap,
            onToggle = { update(activeProfile, onActiveProfileUpdated) { it.copy(optimisticSingleTap = !it.optimisticSingleTap) } },
        )
        ToggleRow(
            title = stringResource(R.string.power_await_combos_title),
            description = stringResource(R.string.power_await_combos_desc),
            on = cfg.awaitCombos,
            onToggle = { update(activeProfile, onActiveProfileUpdated) { it.copy(awaitCombos = !it.awaitCombos) } },
        )
        ToggleRow(
            title = stringResource(R.string.power_await_lp_combos),
            description = stringResource(R.string.power_await_lp_combos_desc),
            on = cfg.awaitLongPressCombos,
            onToggle = { update(activeProfile, onActiveProfileUpdated) { it.copy(awaitLongPressCombos = !it.awaitLongPressCombos) } },
        )

        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.power_section_ring_label))
        // SPEC v3 §4.10 touch-IC sleep timeout. The ring's touch sensor sleeps this long after the
        // last touch; shorter saves ring battery, longer keeps the first touch instant. Tap to cycle.
        CycleRow(
            title = stringResource(R.string.power_ring_sleep_title),
            value = stringResource(R.string.power_ring_sleep_unit_min, touchSleepMin),
            description = stringResource(R.string.power_ring_sleep_desc),
            onTap = { onTouchSleepMinChanged(cycleNextInt(TOUCH_SLEEP_PRESETS, touchSleepMin)) },
        )

        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.power_section_connection_label))
        Text(
            stringResource(R.string.power_connection_auto_body),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
        )
    }
}

private inline fun update(
    profile: KeyMapProfile,
    onUpdated: (KeyMapProfile) -> Unit,
    transform: (GestureConfig) -> GestureConfig,
) {
    onUpdated(profile.copy(gestureConfig = transform(profile.gestureConfig)))
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = HaloType.Caption.copy(color = HaloColors.Mute),
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
    )
}

/** Tap-to-cycle row. The ring has no left/right swipes, so a single tap cycles through a
 *  fixed set of preset values with wraparound. */
@Composable
private fun CycleRow(
    title: String,
    value: String,
    description: String,
    onTap: () -> Unit,
) {
    FocusableRow(onClick = onTap) {
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            Text(title, style = HaloType.Body)
            Text(description, style = HaloType.Caption.copy(fontSize = 11.sp))
        }
        Text(value, style = HaloType.RowVal.copy(color = HaloColors.Accent))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}

private val MULTI_TAP_PRESETS  = longArrayOf(180L, 200L, 220L, 280L, 340L, 400L)
private val COMBO_PRESETS      = longArrayOf(0L, 200L, 300L, 400L, 500L)
private val LP_FOLLOWUP_PRESETS = longArrayOf(0L, 300L, 400L, 500L, 600L)
/** Touch-IC sleep-timeout presets in minutes (SPEC v3 §4.10 `sleepMin`). */
private val TOUCH_SLEEP_PRESETS = intArrayOf(1, 2, 5, 10, 15, 30)

private fun cycleNextInt(presets: IntArray, current: Int): Int {
    val i = presets.indexOf(current)
    return if (i < 0) presets.first() else presets[(i + 1) % presets.size]
}

/** Returns the next preset after [current] (wrap-around). Falls back to the first preset if
 *  [current] isn't in the list. */
private fun cycleNext(presets: LongArray, current: Long): Long {
    val i = presets.indexOf(current)
    return if (i < 0) presets.first() else presets[(i + 1) % presets.size]
}

@Composable
private fun ToggleRow(title: String, description: String, on: Boolean, onToggle: () -> Unit) {
    FocusableRow(onClick = onToggle) {
        // Audit-pass 2026-05-14u: give the title/description column a `weight(1f)` so the
        // HaloSwitch always has room on the right. Without weight, long descriptions like
        // "Fire TAP immediately on the 1st touch …" / "Wait the follow-up window after
        // LONG_PRESS …" pushed the switch entirely off the screen — that's why the user
        // reported "no toggle UI" for the optimistic-tap and await-long-press rows.
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            Text(title, style = HaloType.Body)
            Text(description, style = HaloType.Caption.copy(fontSize = 11.sp))
        }
        HaloSwitch(on = on)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}
