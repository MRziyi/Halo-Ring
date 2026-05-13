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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.ui.SystemGestureSlot
import com.halo.ring.ui.hud.friendly
import com.halo.ring.core.gesture.SystemGestures

/**
 * Settings → System Gestures (mockup §3 F). 5 fixed rows for the always-on overrides:
 * wake / sleep / cycle / peek / reconnect. Tap a row to rebind via [GesturePickerScreen].
 *
 * Conflict detection lives in [SystemGestures.conflict]. The UI renders a small warning chip in the
 * row whose binding clashes — we never block the user from saving, but they see the consequence.
 */
@Composable
fun SystemGesturesScreen(
    gestures: SystemGestures,
    onSlotTapped: (SystemGestureSlot) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = "System gestures",
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        SystemGestureSlot.values().forEach { uiSlot ->
            val coreSlot = uiSlot.toCore()
            val bound = gestures.gestureFor(coreSlot)
            val conflictSlot = bound?.let { gestures.conflict(it, exclude = coreSlot) }

            FocusableRow(onClick = { onSlotTapped(uiSlot) }) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(uiSlot.title, style = HaloType.Body)
                    Text(
                        text = uiSlot.description,
                        style = HaloType.Caption.copy(fontSize = 11.sp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conflictSlot != null) {
                        Text(
                            text = "⚠ ",
                            style = HaloType.Body.copy(color = HaloColors.Warn),
                        )
                    }
                    Text(
                        text = bound?.friendly() ?: "(disabled)",
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
            "Wake fires on the screen-off fast path (no synthesis cost). " +
                "Sleep is intentionally hard-to-misfire — accidental sleep is more annoying than accidental wake.",
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

private fun SystemGestureSlot.toCore(): SystemGestures.Slot = when (this) {
    SystemGestureSlot.WAKE            -> SystemGestures.Slot.WAKE
    SystemGestureSlot.SLEEP           -> SystemGestures.Slot.SLEEP
    SystemGestureSlot.PROFILE_CYCLE   -> SystemGestures.Slot.PROFILE_CYCLE
    SystemGestureSlot.PEEK_HUD        -> SystemGestures.Slot.PEEK_HUD
    SystemGestureSlot.FORCE_RECONNECT -> SystemGestures.Slot.FORCE_RECONNECT
}
