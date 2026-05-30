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
 * Settings → 手势组合设置 (Gesture combos). Three independent combo groups, each with an on/off
 * switch + its own timing window (Zack 2026-05-30):
 *
 *  - **单击组合** (`enableTapSwipe`, default ON): the single-tap-then-swipe combos TAP_SWIPE_UP/DOWN.
 *    Its window (`multiTapWindowMs`) also governs DOUBLE_TAP vs TRIPLE_TAP disambiguation, so it
 *    always applies — DOUBLE_TAP itself is never disabled.
 *  - **双击组合** (`enableDoubleTapSwipe`, default OFF): DOUBLE_TAP_SWIPE_UP/DOWN, window
 *    `comboWindowMs` (300 ms).
 *  - **长按组合** (`awaitLongPressCombos`, default OFF): LONG_PRESS_SWIPE_UP/DOWN + DOUBLE_LONG_PRESS,
 *    window `longPressFollowupWindowMs`.
 *
 * A window is greyed when its group is off (and has no effect) — except 单击组合's, which always
 * applies. Edits go to the **active** profile's [GestureConfig] via [onActiveProfileUpdated]; the
 * Profiles editor greys the matching gesture bindings when a group is off.
 *
 * No DataStore code here — edits flow through [com.halo.ring.di.AppGraph.profilesFlow] which
 * [ProfilesPrefsStore] persists.
 */
@Composable
fun PowerConnectionScreen(
    activeProfile: KeyMapProfile,
    onActiveProfileUpdated: (KeyMapProfile) -> Unit = {},
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

        // ── 单击组合 ──────────────────────────────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.combo_single_title))
        SwitchRow(
            title = stringResource(R.string.combo_enable),
            description = stringResource(R.string.combo_single_desc),
            on = cfg.enableTapSwipe,
            onToggle = { update(activeProfile, onActiveProfileUpdated) { it.copy(enableTapSwipe = !it.enableTapSwipe) } },
        )
        WindowRow(
            title = stringResource(R.string.combo_single_window),
            ms = cfg.multiTapWindowMs.toInt(),
            enabled = true,   // always applies (DOUBLE/TRIPLE-tap disambiguation), even when off
            onTap = { update(activeProfile, onActiveProfileUpdated) {
                it.copy(multiTapWindowMs = cycleNext(MULTI_TAP_PRESETS, cfg.multiTapWindowMs))
            } },
        )

        // ── 双击组合 ──────────────────────────────────────────────────────────────────────────────
        Spacer(Modifier.height(10.dp))
        SectionHeader(stringResource(R.string.combo_double_title))
        SwitchRow(
            title = stringResource(R.string.combo_enable),
            description = stringResource(R.string.combo_double_desc),
            on = cfg.enableDoubleTapSwipe,
            onToggle = { update(activeProfile, onActiveProfileUpdated) { it.copy(enableDoubleTapSwipe = !it.enableDoubleTapSwipe) } },
        )
        WindowRow(
            title = stringResource(R.string.combo_double_window),
            ms = cfg.comboWindowMs.toInt(),
            enabled = cfg.enableDoubleTapSwipe,
            onTap = { update(activeProfile, onActiveProfileUpdated) {
                it.copy(comboWindowMs = cycleNext(COMBO_PRESETS, cfg.comboWindowMs))
            } },
        )

        // ── 长按组合 ──────────────────────────────────────────────────────────────────────────────
        Spacer(Modifier.height(10.dp))
        SectionHeader(stringResource(R.string.combo_long_title))
        SwitchRow(
            title = stringResource(R.string.combo_enable),
            description = stringResource(R.string.combo_long_desc),
            on = cfg.awaitLongPressCombos,
            onToggle = { update(activeProfile, onActiveProfileUpdated) { it.copy(awaitLongPressCombos = !it.awaitLongPressCombos) } },
        )
        WindowRow(
            title = stringResource(R.string.combo_long_window),
            ms = cfg.longPressFollowupWindowMs.toInt(),
            enabled = cfg.awaitLongPressCombos,
            onTap = { update(activeProfile, onActiveProfileUpdated) {
                it.copy(longPressFollowupWindowMs = cycleNext(LP_FOLLOWUP_PRESETS, cfg.longPressFollowupWindowMs))
            } },
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
        style = HaloType.Caption.copy(color = HaloColors.Accent),
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, description: String, on: Boolean, onToggle: () -> Unit) {
    FocusableRow(onClick = onToggle) {
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            Text(title, style = HaloType.Body)
            Text(description, style = HaloType.Caption.copy(fontSize = 11.sp))
        }
        HaloSwitch(on = on)
    }
    Divider()
}

/** Tap-to-cycle window row. Greyed (and inert) when [enabled] is false. */
@Composable
private fun WindowRow(title: String, ms: Int, enabled: Boolean, onTap: () -> Unit) {
    FocusableRow(onClick = { if (enabled) onTap() }) {
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            Text(
                title,
                style = HaloType.Body.copy(color = if (enabled) HaloColors.Fg else HaloColors.Mute),
            )
        }
        Text(
            stringResource(R.string.power_window_unit_ms, ms),
            style = HaloType.RowVal.copy(color = if (enabled) HaloColors.Accent else HaloColors.Mute),
        )
    }
    Divider()
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}

private val MULTI_TAP_PRESETS   = longArrayOf(180L, 200L, 220L, 280L, 340L, 400L)
private val COMBO_PRESETS       = longArrayOf(200L, 250L, 300L, 400L, 500L)
private val LP_FOLLOWUP_PRESETS  = longArrayOf(40L, 60L, 120L, 200L, 300L, 400L)

private fun cycleNext(presets: LongArray, current: Long): Long {
    val i = presets.indexOf(current)
    return if (i < 0) presets.first() else presets[(i + 1) % presets.size]
}
