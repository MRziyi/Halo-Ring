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
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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

@Immutable
data class FeedbackPrefs(
    /** Show recognised gesture in the HUD for ~800 ms after each. Off by default; on briefly after pairing. */
    val gestureHintHud: Boolean = false,
    val clickSoundOnModeSwitch: Boolean = true,
    // Off by default (2026-05-28): the ring LED no longer blinks on profile switch unless the user
    // opts in here — it was needless battery drain given how often profiles auto-switch now.
    val ringLedFeedback: Boolean = false,
    val hudPosition: HudPosition = HudPosition.TOP_RIGHT,
    val hudDurationMs: Int = 2000,
    /** Vertical HUD nudge in "body" units (0..[HUD_OFFSET_MAX]). Top-anchored positions move DOWN,
     *  bottom-anchored move UP — so Halo Ring's HUD can clear a plugin's own HUD (Constellation).
     *  Default 2 ≈ two pill-heights down from the top. */
    val hudOffsetSteps: Int = 2,
    /** Auto-enable [gestureHintHud] for the first 5 minutes after pairing. */
    val autoHintAfterPairing: Boolean = true,
)

/** Max HUD vertical offset in body units (each ≈ one pill-height). */
const val HUD_OFFSET_MAX = 5

enum class HudPosition(@StringRes val labelRes: Int) {
    TOP_RIGHT(R.string.feedback_hud_pos_top_right),
    TOP_LEFT(R.string.feedback_hud_pos_top_left),
    TOP_CENTER(R.string.feedback_hud_pos_top_center),
    BOTTOM_RIGHT(R.string.feedback_hud_pos_bottom_right),
    BOTTOM_LEFT(R.string.feedback_hud_pos_bottom_left),
}

/**
 * Settings → Feedback. The home for all user-facing feedback toggles. The first row — the
 * gesture-hint HUD — is the headline (Doc/08-ui-design.md §10).
 */
@Composable
fun FeedbackScreen(
    prefs: FeedbackPrefs,
    onToggle: (FeedbackPrefField) -> Unit = {},
    onCyclePosition: () -> Unit = {},
    onCycleOffset: () -> Unit = {},
    onCycleDuration: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {

        // Headline row: gesture-hint HUD
        FocusableRow(
            onClick = { onToggle(FeedbackPrefField.GESTURE_HINT) },
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.feedback_gesture_hint), style = HaloType.Body)
                Text(stringResource(R.string.feedback_gesture_hint_subtitle), style = HaloType.Caption.copy(
                    fontSize = 12.sp,
                ))
            }
            OnOff(prefs.gestureHintHud)
        }
        Divider()

        FocusableRow(
            onClick = { onToggle(FeedbackPrefField.CLICK_SOUND) },
        ) {
            Text(stringResource(R.string.feedback_click_sound), style = HaloType.Body)
            OnOff(prefs.clickSoundOnModeSwitch)
        }
        Divider()

        FocusableRow(
            onClick = { onToggle(FeedbackPrefField.RING_LED) },
        ) {
            Text(stringResource(R.string.feedback_ring_led), style = HaloType.Body)
            OnOff(prefs.ringLedFeedback)
        }
        Divider()

        FocusableRow(
            onClick = onCyclePosition,
        ) {
            Text(stringResource(R.string.feedback_hud_position), style = HaloType.Body)
            Text(stringResource(prefs.hudPosition.labelRes), style = HaloType.RowVal)
        }
        Divider()

        FocusableRow(onClick = onCycleOffset) {
            Text(stringResource(R.string.feedback_hud_offset), style = HaloType.Body)
            Text(prefs.hudOffsetSteps.toString(), style = HaloType.RowVal)
        }
        Divider()

        FocusableRow(
            onClick = onCycleDuration,
        ) {
            Text(stringResource(R.string.feedback_hud_duration), style = HaloType.Body)
            Text(formatDuration(prefs.hudDurationMs), style = HaloType.RowVal)
        }
        Divider()

        FocusableRow(
            onClick = { onToggle(FeedbackPrefField.AUTO_HINT_AFTER_PAIRING) },
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.feedback_auto_hint), style = HaloType.Body)
                Text(stringResource(R.string.feedback_auto_hint_subtitle), style = HaloType.Caption.copy(
                    fontSize = 12.sp,
                ))
            }
            OnOff(prefs.autoHintAfterPairing)
        }
        Divider()

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.feedback_footer),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

enum class FeedbackPrefField {
    GESTURE_HINT, CLICK_SOUND, RING_LED, HUD_POSITION, HUD_DURATION, AUTO_HINT_AFTER_PAIRING,
}

@Composable
private fun OnOff(on: Boolean) {
    HaloSwitch(on = on)
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}

@Composable
private fun formatDuration(ms: Int): String = when {
    ms >= 1000 -> stringResource(R.string.feedback_duration_seconds, ms / 1000)
    else -> stringResource(R.string.feedback_duration_ms, ms)
}
