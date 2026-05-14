@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

/**
 * Settings → Test Arena. A practice ground where the wearer can perform any ring gesture
 * and immediately see which `Gesture` was recognised — no real action is dispatched (we don't
 * fire `NavPrev`/`Confirm`/etc.; the resolved action is held in the foreground service but we
 * just *display* what came in).
 *
 * **Auto-scroll** (audit-pass 2026-05-13t): when a row lights up, we ask the outer scrollable
 * (HaloRingApp's `verticalScroll(rememberScrollState())`) to scroll the matching row into view
 * via `BringIntoViewRequester`. Without this the user can light up a long-press combo at the
 * bottom of the list and not see it because the viewport hasn't moved. Compose's
 * BringIntoViewRequester is the documented API for "I'm a leaf composable, please scroll my
 * nearest scrollable ancestor to me" (no need to drill an outer ScrollState reference down).
 *
 * **Visible exit** (audit-pass 2026-05-13t): a CTA at the bottom of the screen invokes the
 * caller-provided `onExit` which pops the navstack. Wearers can still double-tap to back out;
 * the CTA is for the "I'm new and forgot the gesture" case + for mouse / touch QA.
 */
@Composable
fun TestArenaScreen(
    onExit: () -> Unit = {},
) {
    val graph = LocalAppGraph.current
    var lastGesture by remember { mutableStateOf<Gesture?>(null) }
    var lastFiredAtMs by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    // One BringIntoViewRequester per gesture row. The map is stable across recomposition.
    val requesters = remember {
        Gesture.values().associateWith { BringIntoViewRequester() }
    }

    // Subscribe to the recognised-gesture flow. Each emission:
    //   1. Updates [lastGesture] → per-row highlight + auto-scroll request to the active row.
    //   2. Audit-pass 2026-05-14w: **DOUBLE_TAP always exits Test Arena**, regardless of how
    //      the user has bound DOUBLE_TAP in their active profile. This mirrors the universal
    //      "double-tap = back" convention the rest of the app uses (Navigation profile default)
    //      and gives the wearer a single hardcoded escape gesture even if they've remapped
    //      DOUBLE_TAP to something else in their profile. No button needed — glasses have no
    //      touchscreen, so a button at the bottom of a 12-row grid was useless anyway.
    LaunchedEffect(graph) {
        if (graph == null) return@LaunchedEffect
        graph.recognisedGestureFlow.collectLatest { g ->
            if (g == Gesture.DOUBLE_TAP) {
                onExit()
                return@collectLatest
            }
            lastGesture = g
            lastFiredAtMs = System.currentTimeMillis()
            scope.launch {
                runCatching { requesters[g]?.bringIntoView() }
            }
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

        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))

        // All 12 vocab gestures in canonical order — same enum order as Gesture so user sees
        // a stable list. The freshly-recognised one is `lastGesture`; the rest stay neutral.
        // The DOUBLE_TAP row is special: when the user fires it the LaunchedEffect above will
        // [onExit] before we ever paint the highlight, which is the desired UX — DOUBLE_TAP
        // means "leave", not "show me this gesture's name".
        Gesture.values().forEach { g ->
            GestureRow(
                gesture = g,
                active = (g == lastGesture),
                requester = requesters[g]!!,
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun GestureRow(gesture: Gesture, active: Boolean, requester: BringIntoViewRequester) {
    val fg = if (active) HaloColors.Accent else HaloColors.Fg
    val tint = if (active) HaloColors.FocusTint else Color.Transparent
    val borderColor = if (active) HaloColors.Accent else HaloColors.Line
    val sideBarWidth = if (active) 3.dp else 0.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .background(tint)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
