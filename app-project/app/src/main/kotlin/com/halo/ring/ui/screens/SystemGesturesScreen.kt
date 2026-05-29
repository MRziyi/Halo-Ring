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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.halo.ring.R
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.ui.SystemGestureSlot
import com.halo.ring.ui.hud.gestureFriendlyText
import com.halo.ring.core.gesture.SystemGestures

/**
 * Settings → System Gestures (mockup §3 F). 5 fixed rows for the always-on overrides:
 * wake / sleep / cycle / peek / reconnect. Tap a row to rebind via [GesturePickerScreen].
 */
@Composable
fun SystemGesturesScreen(
    gestures: SystemGestures,
    onSlotTapped: (SystemGestureSlot) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.system_gestures_title),
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        // Only wake + sleep remain meaningful (2026-05-29) — profile-cycle / peek-HUD / AI-assistant
        // slots were retired (profiles auto-switch; triple-tap = screenshot). Hide the dead ones.
        listOf(SystemGestureSlot.WAKE, SystemGestureSlot.SLEEP).forEach { uiSlot ->
            val coreSlot = uiSlot.toCore()
            val bound = gestures.gestureFor(coreSlot)
            val conflictSlot = bound?.let { gestures.conflict(it, exclude = coreSlot) }

            FocusableRow(onClick = { onSlotTapped(uiSlot) }) {
                Text(stringResource(uiSlot.titleRes()), style = HaloType.Body)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conflictSlot != null) {
                        Text(
                            text = "⚠ ",
                            style = HaloType.Body.copy(color = HaloColors.Warn),
                        )
                    }
                    Text(
                        text = bound?.let { gestureFriendlyText(it) } ?: stringResource(R.string.system_gestures_disabled),
                        style = HaloType.RowVal.copy(
                            color = if (bound == null) HaloColors.Mute else HaloColors.Fg,
                        ),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.system_gestures_footer),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

private fun SystemGestureSlot.titleRes(): Int = when (this) {
    SystemGestureSlot.WAKE            -> R.string.system_gestures_slot_wake
    SystemGestureSlot.SLEEP           -> R.string.system_gestures_slot_sleep
    SystemGestureSlot.PROFILE_CYCLE   -> R.string.system_gestures_slot_cycle
    SystemGestureSlot.PEEK_HUD        -> R.string.system_gestures_slot_peek
    SystemGestureSlot.AI_ASSISTANT    -> R.string.system_gestures_slot_ai_assistant
}

private fun SystemGestureSlot.toCore(): SystemGestures.Slot = when (this) {
    SystemGestureSlot.WAKE            -> SystemGestures.Slot.WAKE
    SystemGestureSlot.SLEEP           -> SystemGestures.Slot.SLEEP
    SystemGestureSlot.PROFILE_CYCLE   -> SystemGestures.Slot.PROFILE_CYCLE
    SystemGestureSlot.PEEK_HUD        -> SystemGestures.Slot.PEEK_HUD
    SystemGestureSlot.AI_ASSISTANT    -> SystemGestures.Slot.AI_ASSISTANT
}
