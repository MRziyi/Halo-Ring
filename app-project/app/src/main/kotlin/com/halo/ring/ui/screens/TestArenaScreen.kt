package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.halo.ring.R
import com.halo.ring.core.gesture.Gesture
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.ui.hud.gestureFriendlyRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Settings → Test Arena. A practice ground where the wearer can perform any ring gesture
 * and immediately see which `Gesture` was recognised — no real action is dispatched (we don't
 * fire `NavPrev`/`Confirm`/etc.; the resolved action is held in the foreground service but we
 * just *display* what came in). Useful for:
 *
 *  - Learning the ring's gesture vocabulary by doing.
 *  - Debugging "why did my long-press not register?" — the user sees that it landed as
 *    `LONG_PRESS_SWIPE_DOWN` because their finger drifted, and can recalibrate.
 *  - Verifying after profile changes that triple-tap / quadruple-tap are still set up right.
 *
 * Glasses-display compliance:
 *  - 12 rows, each ~36 dp tall, scrolls vertically inside the outer HaloRingApp scroll.
 *  - Inactive rows are `HaloColors.Mute` (#8a8a8a, ~30 % perceived) — keeps APL low.
 *  - The freshly-recognised row flashes `HaloColors.Accent` for 2 s, then fades back.
 *  - 16 sp font floor; one mint-green accent only on the recognised row.
 *  - `clickable` not used here — rows are display-only. The screen accepts the ring's actions
 *    naturally because all gestures pass through `InteractionRouter` → backend; this screen
 *    just subscribes to a SharedFlow side-channel for display.
 */
@Composable
fun TestArenaScreen() {
    val graph = LocalAppGraph.current
    var lastGesture by remember { mutableStateOf<Gesture?>(null) }
    var lastFiredAtMs by remember { mutableStateOf(0L) }

    // Subscribe to the recognised-gesture flow. Each emission updates [lastGesture] which
    // drives the per-row highlight via simple equality. A 2 s timer fades the highlight
    // back to inactive so the row doesn't stay accent forever — that would conflict with
    // the next gesture's intent.
    LaunchedEffect(graph) {
        if (graph == null) return@LaunchedEffect
        graph.recognisedGestureFlow.collectLatest { g ->
            lastGesture = g
            lastFiredAtMs = System.currentTimeMillis()
            delay(2_000)
            if (System.currentTimeMillis() - lastFiredAtMs >= 2_000) {
                lastGesture = null  // fade
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Column(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
            Text(stringResource(R.string.test_arena_title), style = HaloType.Title)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.test_arena_subtitle),
                style = HaloType.Caption,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.test_arena_inactive_hint),
                style = HaloType.Caption.copy(color = HaloColors.Mute),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))

        // All 12 vocab gestures in canonical order — same enum order as Gesture so user sees
        // a stable list. The freshly-recognised one is `lastGesture`; the rest stay neutral.
        Gesture.values().forEach { g ->
            GestureRow(
                gesture = g,
                active = (g == lastGesture),
            )
        }
    }
}

@Composable
private fun GestureRow(gesture: Gesture, active: Boolean) {
    val fg = if (active) HaloColors.Accent else HaloColors.Fg
    val tint = if (active) HaloColors.FocusTint else Color.Transparent
    val borderColor = if (active) HaloColors.Accent else HaloColors.Line
    val sideBarWidth = if (active) 3.dp else 0.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left accent bar so the row visually screams when active. 3 dp matches HaloFocus's
        // standard treatment, but with no padding before it so it sits flush.
        Box(
            modifier = Modifier
                .size(width = sideBarWidth, height = 24.dp)
                .background(borderColor),
        )
        Spacer(Modifier.size(ScreenPadding - sideBarWidth))
        Text(
            stringResource(gestureFriendlyRes(gesture)),
            style = HaloType.Body.copy(color = fg),
            modifier = Modifier.padding(end = 8.dp),
        )
        Spacer(Modifier.fillMaxWidth(0f))
        if (active) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(HaloColors.Accent))
                Text(stringResource(R.string.common_just_now), style = HaloType.Caption.copy(color = HaloColors.Accent))
            }
        } else {
            Text(
                stringResource(gesture.shortHintRes()),
                style = HaloType.Caption.copy(color = HaloColors.Mute),
            )
        }
        Spacer(Modifier.size(ScreenPadding))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}

/** Short hint of how to perform the gesture — shown faintly to the right of each idle row
 *  as a teaching cue. */
private fun Gesture.shortHintRes(): Int = when (this) {
    Gesture.TAP                   -> R.string.gesture_tap
    Gesture.DOUBLE_TAP            -> R.string.gesture_double_tap
    Gesture.TRIPLE_TAP            -> R.string.gesture_triple_tap
    Gesture.QUADRUPLE_TAP         -> R.string.gesture_quadruple_tap
    Gesture.SWIPE_UP              -> R.string.gesture_swipe_up
    Gesture.SWIPE_DOWN            -> R.string.gesture_swipe_down
    Gesture.LONG_PRESS            -> R.string.gesture_long_press
    Gesture.DOUBLE_TAP_SWIPE_UP   -> R.string.gesture_double_tap_swipe_up
    Gesture.DOUBLE_TAP_SWIPE_DOWN -> R.string.gesture_double_tap_swipe_down
    Gesture.LONG_PRESS_SWIPE_UP   -> R.string.gesture_long_press_swipe_up
    Gesture.LONG_PRESS_SWIPE_DOWN -> R.string.gesture_long_press_swipe_down
    Gesture.DOUBLE_LONG_PRESS     -> R.string.gesture_double_long_press
}
