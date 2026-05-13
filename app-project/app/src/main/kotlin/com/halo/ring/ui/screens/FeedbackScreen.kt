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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

@Immutable
data class FeedbackPrefs(
    /** Show recognised gesture in the HUD for ~800 ms after each. Off by default; on briefly after pairing. */
    val gestureHintHud: Boolean = false,
    val clickSoundOnModeSwitch: Boolean = true,
    val ringLedFeedback: Boolean = true,
    val hudPosition: HudPosition = HudPosition.TOP_RIGHT,
    val hudDurationMs: Int = 2000,
    /** Auto-enable [gestureHintHud] for the first 5 minutes after pairing. */
    val autoHintAfterPairing: Boolean = true,
)

enum class HudPosition(val label: String) {
    TOP_RIGHT("Top-right"), TOP_CENTER("Top-centre"), BOTTOM_RIGHT("Bottom-right"),
}

/**
 * Settings → Feedback. The home for all user-facing feedback toggles. The first row — the
 * gesture-hint HUD — is the headline (Doc/08-ui-design.md §10).
 */
@Composable
fun FeedbackScreen(
    prefs: FeedbackPrefs,
    focusedIndex: Int = 0,
    onToggle: (FeedbackPrefField) -> Unit = {},
    onCyclePosition: () -> Unit = {},
    onCycleDuration: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {

        // Headline row: gesture-hint HUD
        FocusableRow(
            focused = focusedIndex == 0,
            onClick = { onToggle(FeedbackPrefField.GESTURE_HINT) },
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show recognised gesture (HUD)", style = HaloType.Body)
                Text("800 ms after each gesture", style = HaloType.Caption.copy(
                    fontSize = 12.sp,
                ))
            }
            OnOff(prefs.gestureHintHud)
        }
        Divider()

        FocusableRow(
            focused = focusedIndex == 1,
            onClick = { onToggle(FeedbackPrefField.CLICK_SOUND) },
        ) {
            Text("Click sound on mode switch", style = HaloType.Body)
            OnOff(prefs.clickSoundOnModeSwitch)
        }
        Divider()

        FocusableRow(
            focused = focusedIndex == 2,
            onClick = { onToggle(FeedbackPrefField.RING_LED) },
        ) {
            Text("Ring LED feedback", style = HaloType.Body)
            OnOff(prefs.ringLedFeedback)
        }
        Divider()

        FocusableRow(
            focused = focusedIndex == 3,
            onClick = onCyclePosition,
        ) {
            Text("HUD position", style = HaloType.Body)
            Text(prefs.hudPosition.label, style = HaloType.RowVal)
        }
        Divider()

        FocusableRow(
            focused = focusedIndex == 4,
            onClick = onCycleDuration,
        ) {
            Text("HUD duration", style = HaloType.Body)
            Text(formatDuration(prefs.hudDurationMs), style = HaloType.RowVal)
        }
        Divider()

        FocusableRow(
            focused = focusedIndex == 5,
            onClick = { onToggle(FeedbackPrefField.AUTO_HINT_AFTER_PAIRING) },
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-enable hints for 5 min after pairing", style = HaloType.Body)
                Text("teaches new wearers the gesture set", style = HaloType.Caption.copy(
                    fontSize = 12.sp,
                ))
            }
            OnOff(prefs.autoHintAfterPairing)
        }
        Divider()

        Spacer(Modifier.height(12.dp))
        Text(
            "Hints fire only while the screen is on.",
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
    Text(
        text = if (on) "ON" else "OFF",
        style = HaloType.RowVal.copy(
            color = if (on) HaloColors.Accent else HaloColors.Mute,
        ),
    )
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}

private fun formatDuration(ms: Int): String = when {
    ms >= 1000 -> "${ms / 1000} s"
    else -> "${ms} ms"
}
