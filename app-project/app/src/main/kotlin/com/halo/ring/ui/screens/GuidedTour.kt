package com.halo.ring.ui.screens

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.R
import com.halo.ring.core.gesture.Gesture
import com.halo.ring.di.InputSource
import com.halo.ring.ui.Cta
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.ui.hud.gestureFriendlyText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Interactive operation tour (audit-2026-05-13r). Earlier revisions taught the gesture
 * *vocabulary* in isolation ("Swipe UP = ↑"). The user fed back that what they actually
 * needed was *composition* coaching: "how do I switch between the three tabs? how do I
 * move down a list and enter a row? how do I go back?"
 *
 * Each step now shows a **mini-UI mock** of the actual app in two states:
 *   - BEFORE — gray phantom of the screen with focus on element A.
 *   - AFTER  — same screen with focus on element B (or "entered" / "back to list" etc.),
 *              rendered in mint-green with a ✓ check.
 *
 * The user does the real gesture (when a ring is connected and we get a match on
 * `AppGraph.recognisedGestureFlow`), or taps NEXT (when no live input source). Either path
 * triggers the BEFORE → AFTER transition + advances to the next step after 900 ms.
 *
 * All user-visible strings are sourced from `strings.xml` so the tour follows the
 * app-level locale set in `HaloRingApplication`.
 */
@Composable
fun GuidedTour(
    onDismiss: () -> Unit,
    onSelectTab: (com.halo.ring.ui.AppTab) -> Unit,
    onPush: (com.halo.ring.ui.SubScreen) -> Unit,
    onPopAll: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val inputSource = remember(graph) { graph?.currentInputSource() ?: InputSource.NONE }
    val steps = remember(inputSource) { buildSteps(inputSource) }
    var stepIdx by remember { mutableStateOf(0) }
    val step = steps[stepIdx]

    var phase by remember { mutableStateOf(Phase.BEFORE) }
    var wrongGesture by remember { mutableStateOf<Gesture?>(null) }

    LaunchedEffect(stepIdx) {
        phase = Phase.BEFORE
        wrongGesture = null
        step.contextTab?.let { onSelectTab(it) }
        step.contextDrill?.let { onPush(it) }
    }

    // Listen for real gestures (when ring is connected). Matching gesture → AFTER → advance.
    LaunchedEffect(stepIdx, graph) {
        if (graph == null) return@LaunchedEffect
        val target = step.matchGesture ?: return@LaunchedEffect
        graph.recognisedGestureFlow.collectLatest { g ->
            if (phase != Phase.BEFORE) return@collectLatest
            if (g == target) {
                phase = Phase.AFTER
                delay(900)
                advance(stepIdx, steps, { stepIdx = it }) { onPopAll(); onDismiss() }
            } else {
                wrongGesture = g
                phase = Phase.HINT
                delay(800)
                if (phase == Phase.HINT) {
                    wrongGesture = null
                    phase = Phase.BEFORE
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Dim layer
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)))

        // Mini-UI mock — vertically centred, slightly above geometric centre so the callout
        // has room. Tight bounding box with subtle border so it reads as a screen-mock, not
        // floating elements.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp, start = ScreenPadding, end = ScreenPadding)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PhantomFrame(highlighted = phase == Phase.AFTER) {
                step.phantom(phase)
            }
            if (phase == Phase.AFTER) {
                Spacer(Modifier.height(8.dp))
                Text("✓", style = HaloType.Title.copy(color = HaloColors.Accent, fontSize = 32.sp, fontWeight = FontWeight.Bold))
            }
        }

        // Callout
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(HaloColors.Bg)
                    .border(
                        width = 1.dp,
                        color = when (phase) {
                            Phase.AFTER -> HaloColors.Accent
                            Phase.HINT  -> HaloColors.Warn
                            Phase.BEFORE -> HaloColors.Accent
                        },
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(step.titleRes), style = HaloType.Body)
                        Text(
                            stringResource(R.string.tour_step_counter, stepIdx + 1, steps.size),
                            style = HaloType.Caption.copy(color = HaloColors.Mute),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    val instruction = when (phase) {
                        Phase.AFTER  -> stringResource(R.string.tour_got_it)
                        Phase.HINT   -> stringResource(
                            R.string.tour_wrong_gesture,
                            wrongGesture?.let { gestureFriendlyText(it) } ?: "",
                        )
                        Phase.BEFORE -> step.instructionFor(inputSource)
                    }
                    val color = when (phase) {
                        Phase.AFTER  -> HaloColors.Accent
                        Phase.HINT   -> HaloColors.Warn
                        Phase.BEFORE -> HaloColors.Fg
                    }
                    Text(instruction, style = HaloType.Caption.copy(color = color))
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Cta(
                            text = stringResource(R.string.common_skip),
                            modifier = Modifier.weight(1f),
                            onClick = { onPopAll(); onDismiss() },
                        )
                        // NEXT is always visible. With a live ring, you can perform the
                        // gesture OR tap NEXT — both work. Without one (testing on phone),
                        // NEXT is the only way forward.
                        Spacer(Modifier.width(8.dp))
                        Cta(
                            text = stringResource(
                                if (stepIdx == steps.size - 1) R.string.common_done
                                else R.string.common_next,
                            ),
                            modifier = Modifier.weight(1f),
                            focused = true,
                            onClick = {
                                // Pre-flash the AFTER state when user taps NEXT so they still
                                // see the transition, then advance after 600 ms.
                                phase = Phase.AFTER
                            },
                        )
                    }
                }
            }
        }
    }

    // When NEXT triggered manual advance, give the AFTER state ~600 ms then move on.
    LaunchedEffect(phase, stepIdx) {
        if (phase == Phase.AFTER && step.matchGesture == null) {
            delay(600)
            advance(stepIdx, steps, { stepIdx = it }) { onPopAll(); onDismiss() }
        } else if (phase == Phase.AFTER && step.matchGesture != null) {
            // Live-gesture path handles its own advance via the collectLatest above. But if
            // the user tapped NEXT to skip the live demo, we still want to advance.
            delay(600)
            advance(stepIdx, steps, { stepIdx = it }) { onPopAll(); onDismiss() }
        }
    }
}

private fun advance(
    idx: Int,
    steps: List<TourStep>,
    onAdvance: (Int) -> Unit,
    onDone: () -> Unit,
) {
    if (idx >= steps.size - 1) onDone() else onAdvance(idx + 1)
}

private enum class Phase { BEFORE, AFTER, HINT }

@Composable
private fun PhantomFrame(highlighted: Boolean, content: @Composable () -> Unit) {
    val border = if (highlighted) HaloColors.Accent else HaloColors.Line
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x66000000))
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .width(240.dp),
    ) {
        content()
    }
}

// ── Mini-UI building blocks ──────────────────────────────────────────────────────────────────

@Composable
private fun MiniTabStrip(activeIdx: Int, tabsFocused: Boolean) {
    val labels = listOf(
        stringResource(R.string.tab_vitals),
        stringResource(R.string.tab_settings),
        stringResource(R.string.tab_status),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEachIndexed { i, label ->
                val color = when {
                    i == activeIdx && tabsFocused -> HaloColors.Accent
                    i == activeIdx                -> HaloColors.Fg
                    else                          -> HaloColors.Mute
                }
                Text(label, style = HaloType.Tab.copy(color = color, fontSize = 10.sp))
            }
        }
        Spacer(Modifier.height(2.dp))
        // Underline strip
        Row(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            if (i == activeIdx)
                                (if (tabsFocused) HaloColors.Accent else HaloColors.Mute)
                            else HaloColors.Line,
                        ),
                )
            }
        }
    }
}

@Composable
private fun MiniListRow(label: String, focused: Boolean, success: Boolean = false) {
    val accentBg = when {
        success -> HaloColors.Accent.copy(alpha = 0.18f)
        focused -> HaloColors.FocusTint
        else    -> Color.Transparent
    }
    val barColor = when {
        success -> HaloColors.Accent
        focused -> HaloColors.Accent
        else    -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accentBg)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(width = 2.dp, height = 14.dp).background(barColor))
        Spacer(Modifier.width(8.dp))
        Text(label, style = HaloType.Caption.copy(
            color = if (success) HaloColors.Accent else HaloColors.Fg,
            fontSize = 11.sp,
        ))
    }
}

/** A 4-row list with a focus indicator on [focusedIdx]. If [enteredIdx] is non-null, render
 *  a "detail screen" instead of the list (used after TAP/Enter). */
@Composable
private fun MiniList(focusedIdx: Int, enteredIdx: Int? = null) {
    val labels = listOf(
        stringResource(R.string.settings_section_profiles),
        stringResource(R.string.settings_section_system_gestures),
        stringResource(R.string.settings_section_ring),
        stringResource(R.string.settings_section_power),
    )
    if (enteredIdx != null) {
        Column {
            Text("›  ${labels[enteredIdx]}", style = HaloType.Body.copy(color = HaloColors.Accent, fontSize = 12.sp))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.tour_phantom_inside), style = HaloType.Caption.copy(color = HaloColors.Mute, fontSize = 10.sp))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.tour_phantom_detail_content), style = HaloType.Caption.copy(color = HaloColors.Mute, fontSize = 10.sp))
        }
    } else {
        Column {
            labels.forEachIndexed { i, lbl ->
                MiniListRow(lbl, focused = (i == focusedIdx))
            }
        }
    }
}

// ── Phantom scene per step ───────────────────────────────────────────────────────────────────

/** A whole BEFORE/AFTER mini-UI for each step. Drawn inside the [PhantomFrame]. */
private typealias Phantom = @Composable (Phase) -> Unit

private val phantomWelcome: Phantom = { _ ->
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("◉", style = HaloType.Title.copy(color = HaloColors.Accent, fontSize = 48.sp))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.app_name), style = HaloType.Body.copy(fontSize = 14.sp))
    }
}

private val phantomMoveDown: Phantom = { phase ->
    Column {
        MiniTabStrip(activeIdx = 1, tabsFocused = false)
        Spacer(Modifier.height(8.dp))
        // BEFORE: focus on row 0. AFTER: focus on row 1.
        MiniList(focusedIdx = if (phase == Phase.AFTER) 1 else 0)
    }
}

private val phantomMoveUp: Phantom = { phase ->
    Column {
        MiniTabStrip(activeIdx = 1, tabsFocused = false)
        Spacer(Modifier.height(8.dp))
        // BEFORE: row 2. AFTER: row 1.
        MiniList(focusedIdx = if (phase == Phase.AFTER) 1 else 2)
    }
}

private val phantomEnter: Phantom = { phase ->
    Column {
        MiniTabStrip(activeIdx = 1, tabsFocused = false)
        Spacer(Modifier.height(8.dp))
        if (phase == Phase.AFTER) {
            MiniList(focusedIdx = 0, enteredIdx = 0)
        } else {
            MiniList(focusedIdx = 0)
        }
    }
}

private val phantomBack: Phantom = { phase ->
    Column {
        MiniTabStrip(activeIdx = 1, tabsFocused = false)
        Spacer(Modifier.height(8.dp))
        if (phase == Phase.AFTER) {
            MiniList(focusedIdx = 0)              // back to list
        } else {
            MiniList(focusedIdx = 0, enteredIdx = 0)  // inside a detail
        }
    }
}

private val phantomEnterTabStrip: Phantom = { phase ->
    Column {
        MiniTabStrip(activeIdx = 1, tabsFocused = phase == Phase.AFTER)
        Spacer(Modifier.height(8.dp))
        // BEFORE: focus on row 0 of content. AFTER: focus on tab strip (no row focused).
        MiniList(focusedIdx = if (phase == Phase.AFTER) -1 else 0)
    }
}

private val phantomCycleTab: Phantom = { phase ->
    Column {
        // BEFORE: SETTINGS active + tab strip focused. AFTER: STATUS active + tab strip focused.
        MiniTabStrip(activeIdx = if (phase == Phase.AFTER) 2 else 1, tabsFocused = true)
        Spacer(Modifier.height(8.dp))
        MiniList(focusedIdx = -1)
    }
}

private val phantomLongPress: Phantom = { phase ->
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("━━━", style = HaloType.Title.copy(
            color = if (phase == Phase.AFTER) HaloColors.Accent else HaloColors.Mute,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
        ))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                if (phase == Phase.AFTER) R.string.tour_phantom_long_press_after
                else R.string.tour_phantom_long_press_before,
            ),
            style = HaloType.Caption.copy(
                color = if (phase == Phase.AFTER) HaloColors.Accent else HaloColors.Mute,
                fontSize = 11.sp,
            ),
        )
    }
}

private val phantomTriple: Phantom = { phase ->
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(
                    if (phase == Phase.AFTER) HaloColors.Accent else HaloColors.Mute,
                ))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                if (phase == Phase.AFTER) R.string.tour_phantom_triple_after
                else R.string.tour_phantom_triple_before,
            ),
            style = HaloType.Caption.copy(
                color = if (phase == Phase.AFTER) HaloColors.Accent else HaloColors.Mute,
                fontSize = 11.sp,
            ),
        )
    }
}

/** Step "Enter the chosen tab": BEFORE = tab strip focused, no content row focused. AFTER =
 *  tab strip unfocused, focus dropped onto the first content row. Mirrors what TAP does when
 *  `InAppFocusController.focusOnTabStrip == true`. */
private val phantomEnterChosenTab: Phantom = { phase ->
    Column {
        MiniTabStrip(activeIdx = 1, tabsFocused = phase == Phase.BEFORE)
        Spacer(Modifier.height(8.dp))
        MiniList(focusedIdx = if (phase == Phase.AFTER) 0 else -1)
    }
}

/** Step "Leave the app": BEFORE = app root list. AFTER = home / launcher placeholder.
 *  Visualises moveTaskToBack(true) at the root level. */
private val phantomLeave: Phantom = { phase ->
    if (phase == Phase.AFTER) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("⌂", style = HaloType.Title.copy(color = HaloColors.Accent, fontSize = 48.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.tour_phantom_leave_after),
                style = HaloType.Caption.copy(color = HaloColors.Accent, fontSize = 11.sp),
            )
        }
    } else {
        Column {
            MiniTabStrip(activeIdx = 1, tabsFocused = false)
            Spacer(Modifier.height(8.dp))
            MiniList(focusedIdx = 0)
        }
    }
}

private val phantomDone: Phantom = { _ ->
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("✓", style = HaloType.Title.copy(color = HaloColors.Accent, fontSize = 48.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.tour_phantom_all_set), style = HaloType.Body.copy(fontSize = 14.sp))
    }
}

// ── Step data ────────────────────────────────────────────────────────────────────────────────

private data class TourStep(
    @StringRes val titleRes: Int,
    val phantom: Phantom,
    val matchGesture: Gesture?,
    @StringRes val ringInstructionRes: Int,
    @StringRes val templeInstructionRes: Int,
    val contextTab: com.halo.ring.ui.AppTab? = null,
    val contextDrill: com.halo.ring.ui.SubScreen? = null,
) {
    @Composable
    fun instructionFor(s: InputSource): String {
        val ring = stringResource(ringInstructionRes)
        val temple = stringResource(templeInstructionRes)
        val noRingHint = stringResource(R.string.tour_no_ring_hint)
        return when (s) {
            InputSource.RING -> ring
            InputSource.TEMPLE -> temple
            InputSource.NONE -> "$ring $noRingHint"
        }
    }
}

private fun buildSteps(source: InputSource): List<TourStep> {
    val live = source == InputSource.RING
    return listOf(
        TourStep(
            titleRes = R.string.tour_welcome_title,
            phantom = phantomWelcome,
            matchGesture = null,
            ringInstructionRes   = R.string.tour_welcome_ring,
            templeInstructionRes = R.string.tour_welcome_temple,
        ),
        TourStep(
            titleRes = R.string.tour_move_down_title,
            phantom = phantomMoveDown,
            matchGesture = if (live) Gesture.SWIPE_DOWN else null,
            ringInstructionRes   = R.string.tour_move_down_ring,
            templeInstructionRes = R.string.tour_move_down_temple,
        ),
        TourStep(
            titleRes = R.string.tour_move_up_title,
            phantom = phantomMoveUp,
            matchGesture = if (live) Gesture.SWIPE_UP else null,
            ringInstructionRes   = R.string.tour_move_up_ring,
            templeInstructionRes = R.string.tour_move_up_temple,
        ),
        TourStep(
            titleRes = R.string.tour_enter_row_title,
            phantom = phantomEnter,
            matchGesture = if (live) Gesture.TAP else null,
            ringInstructionRes   = R.string.tour_enter_row_ring,
            templeInstructionRes = R.string.tour_enter_row_temple,
        ),
        TourStep(
            titleRes = R.string.tour_back_title,
            phantom = phantomBack,
            matchGesture = if (live) Gesture.DOUBLE_TAP else null,
            ringInstructionRes   = R.string.tour_back_ring,
            templeInstructionRes = R.string.tour_back_temple,
        ),
        TourStep(
            titleRes = R.string.tour_tab_focus_title,
            phantom = phantomEnterTabStrip,
            matchGesture = if (live) Gesture.SWIPE_UP else null,
            ringInstructionRes   = R.string.tour_tab_focus_ring,
            templeInstructionRes = R.string.tour_tab_focus_temple,
        ),
        TourStep(
            titleRes = R.string.tour_tab_cycle_title,
            phantom = phantomCycleTab,
            matchGesture = if (live) Gesture.SWIPE_DOWN else null,
            ringInstructionRes   = R.string.tour_tab_cycle_ring,
            templeInstructionRes = R.string.tour_tab_cycle_temple,
        ),
        TourStep(
            titleRes = R.string.tour_enter_tab_title,
            phantom = phantomEnterChosenTab,
            matchGesture = if (live) Gesture.TAP else null,
            ringInstructionRes   = R.string.tour_enter_tab_ring,
            templeInstructionRes = R.string.tour_enter_tab_temple,
        ),
        TourStep(
            titleRes = R.string.tour_long_press_title,
            phantom = phantomLongPress,
            matchGesture = if (live) Gesture.LONG_PRESS else null,
            ringInstructionRes   = R.string.tour_long_press_ring,
            templeInstructionRes = R.string.tour_long_press_temple,
        ),
        TourStep(
            titleRes = R.string.tour_triple_title,
            phantom = phantomTriple,
            matchGesture = if (live) Gesture.TRIPLE_TAP else null,
            ringInstructionRes   = R.string.tour_triple_ring,
            templeInstructionRes = R.string.tour_triple_temple,
        ),
        TourStep(
            titleRes = R.string.tour_leave_title,
            phantom = phantomLeave,
            matchGesture = if (live) Gesture.DOUBLE_TAP else null,
            ringInstructionRes   = R.string.tour_leave_ring,
            templeInstructionRes = R.string.tour_leave_temple,
        ),
        TourStep(
            titleRes = R.string.tour_done_title,
            phantom = phantomDone,
            matchGesture = null,
            ringInstructionRes   = R.string.tour_done_body,
            templeInstructionRes = R.string.tour_done_body,
        ),
    )
}
