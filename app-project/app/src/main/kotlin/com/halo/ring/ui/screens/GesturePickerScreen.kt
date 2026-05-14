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
import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.SystemGestures

/**
 * Settings → System Gestures → <Slot>: pick a [Gesture] for this system slot. Last row is
 * "(disabled)" to clear the binding (allowed; e.g. user doesn't want a sleep gesture).
 *
 * The picker shows an inline conflict marker `⚠ already bound to <other slot>` next to any gesture
 * that's currently bound to a different slot, so the user can rebind both slots in one flow without
 * surprises.
 */
@Composable
fun GesturePickerScreen(
    slot: SystemGestureSlot,
    currentGestures: SystemGestures,
    onGestureSelected: (Gesture?) -> Unit = {},
) {
    val coreSlot = slot.toCore()
    val currentBinding = currentGestures.gestureFor(coreSlot)

    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = slot.title,
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        Text(
            text = slot.description,
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        Gesture.values().forEach { g ->
            val selected = g == currentBinding
            val conflictSlot = currentGestures.conflict(g, exclude = coreSlot)
            FocusableRow(onClick = { onGestureSelected(g) }) {
                Text(
                    text = (if (selected) "● " else "") + gestureFriendlyText(g),
                    style = HaloType.Body.copy(
                        color = if (selected) HaloColors.Accent else HaloColors.Fg,
                    ),
                )
                if (conflictSlot != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠ ", style = HaloType.Caption.copy(color = HaloColors.Warn))
                        Text(
                            text = stringResource(R.string.gesture_picker_in_use_by, stringResource(conflictSlot.uiTitleRes())),
                            style = HaloType.Caption.copy(color = HaloColors.Warn),
                        )
                    }
                } else if (selected) {
                    Text(stringResource(R.string.profiles_active_badge), style = HaloType.Caption.copy(color = HaloColors.Mute))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        }

        // Disable / clear binding row
        FocusableRow(onClick = { onGestureSelected(null) }) {
            Text(
                stringResource(R.string.gesture_picker_disable_slot),
                style = HaloType.Body.copy(color = HaloColors.Mute),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
    }
}

private fun SystemGestureSlot.toCore(): SystemGestures.Slot = when (this) {
    SystemGestureSlot.WAKE            -> SystemGestures.Slot.WAKE
    SystemGestureSlot.SLEEP           -> SystemGestures.Slot.SLEEP
    SystemGestureSlot.PROFILE_CYCLE   -> SystemGestures.Slot.PROFILE_CYCLE
    SystemGestureSlot.PEEK_HUD        -> SystemGestures.Slot.PEEK_HUD
    SystemGestureSlot.AI_ASSISTANT    -> SystemGestures.Slot.AI_ASSISTANT
}

private fun SystemGestures.Slot.uiTitleRes(): Int = when (this) {
    SystemGestures.Slot.WAKE            -> R.string.system_gestures_slot_wake
    SystemGestures.Slot.SLEEP           -> R.string.system_gestures_slot_sleep
    SystemGestures.Slot.PROFILE_CYCLE   -> R.string.system_gestures_slot_cycle
    SystemGestures.Slot.PEEK_HUD        -> R.string.system_gestures_slot_peek
    SystemGestures.Slot.AI_ASSISTANT    -> R.string.system_gestures_slot_ai_assistant
}
