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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding
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
) {
    val cfg = activeProfile.gestureConfig

    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = "Power & Connection",
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        Text(
            "Editing the active profile: ${activeProfile.name}",
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(12.dp))

        SectionHeader("Timing windows")
        CycleRow(
            title = "Multi-tap window",
            value = "${cfg.multiTapWindowMs} ms",
            description = "Max gap between TOUCHes that count as one multi-tap.",
            onTap = { update(activeProfile, onActiveProfileUpdated) {
                it.copy(multiTapWindowMs = cycleNext(MULTI_TAP_PRESETS, cfg.multiTapWindowMs))
            } },
        )
        CycleRow(
            title = "Combo window",
            value = "${cfg.comboWindowMs} ms",
            description = "After a double-tap, wait this long for a following swipe.",
            onTap = { update(activeProfile, onActiveProfileUpdated) {
                it.copy(comboWindowMs = cycleNext(COMBO_PRESETS, cfg.comboWindowMs))
            } },
        )
        CycleRow(
            title = "Long-press follow-up",
            value = "${cfg.longPressFollowupWindowMs} ms",
            description = "After a LONG_PRESS, wait for a swipe or 2nd long-press.",
            onTap = { update(activeProfile, onActiveProfileUpdated) {
                it.copy(longPressFollowupWindowMs = cycleNext(LP_FOLLOWUP_PRESETS, cfg.longPressFollowupWindowMs))
            } },
        )

        Spacer(Modifier.height(12.dp))
        SectionHeader("Latency switches")
        ToggleRow(
            title = "Optimistic single tap",
            description = "Fire TAP immediately on the 1st touch (latency ~0 ms; double-tap still works, but TAP fires too).",
            on = cfg.optimisticSingleTap,
            onToggle = { update(activeProfile, onActiveProfileUpdated) { it.copy(optimisticSingleTap = !cfg.optimisticSingleTap) } },
        )
        ToggleRow(
            title = "Await double-tap combos",
            description = "Wait the combo window after a double-tap so DOUBLE_TAP_SWIPE_* can fire.",
            on = cfg.awaitCombos,
            onToggle = { update(activeProfile, onActiveProfileUpdated) { it.copy(awaitCombos = !cfg.awaitCombos) } },
        )
        ToggleRow(
            title = "Await long-press combos",
            description = "Wait the follow-up window after LONG_PRESS so LONG_PRESS_SWIPE_* + DOUBLE_LONG_PRESS can fire.",
            on = cfg.awaitLongPressCombos,
            onToggle = { update(activeProfile, onActiveProfileUpdated) { it.copy(awaitLongPressCombos = !cfg.awaitLongPressCombos) } },
        )

        Spacer(Modifier.height(12.dp))
        SectionHeader("Connection (automatic)")
        Text(
            "BLE interval is HIGH (15–30 ms) for ~10 s after each gesture and BALANCED (75–100 ms) " +
                "otherwise. Touch IC disables when the user takes the glasses off. After 5 minutes off-wrist " +
                "the ring disconnects entirely.",
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
        Column(Modifier.padding(end = 8.dp)) {
            Text(title, style = HaloType.Body)
            Text(description, style = HaloType.Caption.copy(fontSize = 11.sp))
        }
        Text(value, style = HaloType.RowVal.copy(color = HaloColors.Accent))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}

private val MULTI_TAP_PRESETS  = longArrayOf(180L, 220L, 280L, 340L, 400L)
private val COMBO_PRESETS      = longArrayOf(0L, 200L, 300L, 400L, 500L)
private val LP_FOLLOWUP_PRESETS = longArrayOf(0L, 300L, 400L, 500L, 600L)

/** Returns the next preset after [current] (wrap-around). Falls back to the first preset if
 *  [current] isn't in the list. */
private fun cycleNext(presets: LongArray, current: Long): Long {
    val i = presets.indexOf(current)
    return if (i < 0) presets.first() else presets[(i + 1) % presets.size]
}

@Composable
private fun ToggleRow(title: String, description: String, on: Boolean, onToggle: () -> Unit) {
    FocusableRow(onClick = onToggle) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(title, style = HaloType.Body)
            Text(description, style = HaloType.Caption.copy(fontSize = 11.sp))
        }
        Text(
            text = if (on) "ON" else "OFF",
            style = HaloType.RowVal.copy(color = if (on) HaloColors.Accent else HaloColors.Mute),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}
