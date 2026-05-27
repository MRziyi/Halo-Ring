@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.R
import com.halo.ring.core.gesture.Gesture
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.ui.hud.actionFriendlyText
import com.halo.ring.ui.hud.gestureFriendlyText

/**
 * Settings → Test Arena. Practice ground for the wearer's ring gestures — every recognised
 * gesture is shown in a big hero card (Doc/ui-mockup.html §L), plus a 12-chip grid below that
 * lights up the chip for each gesture the wearer has tried at least once.
 *
 * No real action is dispatched here. The resolved action name + synth latency come from
 * [com.halo.ring.di.RecognisedGesture] which the foreground service publishes alongside its
 * normal pipeline work — purely informational, no side effects.
 *
 * **Exit gesture** (audit-pass-x-test-arena): the design requires *two consecutive DOUBLE_TAPs*
 * to leave. Single double-tap means "I'm practising my double-tap" — exiting on the first one
 * defeats the whole purpose of a practice screen. The second one within
 * [DOUBLE_DOUBLE_TAP_WINDOW_MS] of the first triggers [onExit]. Glasses have no touchscreen so
 * no on-screen exit button.
 */
@Composable
fun TestArenaScreen(
    onExit: () -> Unit = {},
) {
    val graph = LocalAppGraph.current

    // The set of gestures the wearer has practiced this session (lights up the chip grid).
    var practiced by remember { mutableStateOf(emptySet<Gesture>()) }
    // Burn-in 2026-05-27: live atomic-gesture echo. The synthesizer waits ~280ms multi-tap
    // window before committing TAP, which perceptibly lags Test Arena's feedback. Show what
    // the firmware *just* reported (TOUCH / SWIPE_UP / SWIPE_DOWN / LONG_PRESS) immediately, so
    // the user gets sub-50ms acknowledgement. The synthesized gesture below supersedes once
    // it lands.
    var lastAtomic by remember { mutableStateOf<com.halo.ring.core.gesture.RawGesture?>(null) }
    var lastAtomicAtMs by remember { mutableStateOf(0L) }
    // Time of the previous DOUBLE_TAP (System.currentTimeMillis). Two in a row within
    // DOUBLE_DOUBLE_TAP_WINDOW_MS = exit.
    var lastDoubleTapMs by remember { mutableStateOf(0L) }
    // What the hero card renders right now. Held for HERO_HOLD_MS after each recognition, then
    // fades back to the "waiting" prompt — the chip grid below carries persistent state.
    var heroVisible by remember { mutableStateOf<com.halo.ring.di.RecognisedGesture?>(null) }
    val totalGestures = remember { Gesture.values().size }

    // Subscribe to the raw atomic-gesture stream for immediate feedback.
    LaunchedEffect(graph) {
        if (graph == null) return@LaunchedEffect
        graph.rawAtomicGestureFlow.collect { raw ->
            lastAtomic = raw
            lastAtomicAtMs = System.currentTimeMillis()
        }
    }

    LaunchedEffect(graph) {
        if (graph == null) return@LaunchedEffect
        graph.lastRecognisedFlow.collect { recognised ->
            recognised ?: return@collect
            val g = recognised.gesture
            // Double-double-tap exit (see kdoc). The first DOUBLE_TAP still lights up the chip
            // + shows in the hero card — only the SECOND one inside the window exits, so the
            // wearer's intent stays clear.
            if (g == Gesture.DOUBLE_TAP) {
                val now = System.currentTimeMillis()
                if (now - lastDoubleTapMs < DOUBLE_DOUBLE_TAP_WINDOW_MS) {
                    onExit()
                    return@collect
                }
                lastDoubleTapMs = now
            }
            practiced = practiced + g
            heroVisible = recognised
            // Hold the hero for HERO_HOLD_MS; if no newer gesture supersedes, fade. We compare
            // identity (===) so a re-recognition of the same gesture extends the hold naturally.
            val mine = recognised
            kotlinx.coroutines.delay(HERO_HOLD_MS)
            if (heroVisible === mine) heroVisible = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        // ── header ──────────────────────────────────────────────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
            Text(stringResource(R.string.test_arena_title), style = HaloType.Title)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.test_arena_subtitle), style = HaloType.Caption)
        }

        Spacer(Modifier.height(12.dp))

        // ── hero card: LAST GESTURE → Action · NN ms ────────────────────────────────────────
        HeroCard(heroVisible, modifier = Modifier.padding(horizontal = ScreenPadding))

        // ── live atomic-gesture echo (burn-in 2026-05-27) ────────────────────────────────────
        // Sub-50ms acknowledgement of what the firmware just reported. Fades after 1s; the hero
        // card above will catch up with the synthesizer's verdict (TAP / DOUBLE_TAP / etc.).
        val now = System.currentTimeMillis()
        val atomicVisible = lastAtomic
        if (atomicVisible != null && now - lastAtomicAtMs < 1000L) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "raw: ${atomicVisible.name}",
                style = HaloType.Caption.copy(color = HaloColors.Accent, fontSize = 12.sp),
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── progress header ─────────────────────────────────────────────────────────────────
        val done = practiced.size
        Text(
            text = stringResource(R.string.test_arena_progress, done, totalGestures),
            style = HaloType.Caption.copy(
                color = HaloColors.Mute,
                fontSize = 14.sp,
                letterSpacing = 1.sp,
            ),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(8.dp))

        // ── chip grid — one per Gesture, lit when practiced, accented when "active" right now ──
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Gesture.values().forEach { g ->
                GestureChip(
                    gesture = g,
                    done = g in practiced,
                    active = heroVisible?.gesture == g,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── footer hint ─────────────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.test_arena_inactive_hint),
                style = HaloType.Caption.copy(color = HaloColors.Mute, fontSize = 14.sp),
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.fillMaxWidth(0f))
            Box(
                modifier = Modifier
                    .border(width = 1.dp, color = HaloColors.Line)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.test_arena_phase0_pill),
                    style = HaloType.Caption.copy(
                        color = HaloColors.Mute,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                    ),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

/**
 * The big "LAST GESTURE" hero card from Doc/ui-mockup.html §L. Accent-tinted bg + line border;
 * 36 sp gesture name in accent green; small "→ action · NN ms" line under it. When nothing has
 * been recognised yet (or the hero has faded), shows a "Try a gesture" prompt at the same scale
 * so the layout doesn't jump.
 */
@Composable
private fun HeroCard(
    recognised: com.halo.ring.di.RecognisedGesture?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x0A5EE08C))  // 4% accent over Bg — matches mockup
            .border(width = 1.dp, color = HaloColors.Line)
            .padding(vertical = 18.dp, horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.test_arena_last_gesture_label),
                style = HaloType.Caption.copy(
                    color = HaloColors.Mute,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            if (recognised == null) {
                // Waiting state — same vertical size as the recognised state so the chip grid
                // below doesn't jump when the first gesture arrives.
                Text(
                    stringResource(R.string.test_arena_waiting),
                    style = HaloType.Title.copy(
                        color = HaloColors.Mute,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.test_arena_waiting_hint),
                    style = HaloType.Caption.copy(
                        color = HaloColors.Mute,
                        fontSize = 14.sp,
                    ),
                )
            } else {
                Text(
                    gestureFriendlyText(recognised.gesture),
                    style = HaloType.Title.copy(
                        color = HaloColors.Accent,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                // "→ action · NN ms". When the gesture isn't mapped in the active profile
                // (GlassAction.None), we'd show "→ — · NN ms" which reads as garbage; just
                // surface the latency alone in that case.
                val noAction = recognised.action is com.halo.ring.core.action.GlassAction.None
                val line = if (noAction) {
                    "${recognised.latencyMs} ms"
                } else {
                    stringResource(
                        R.string.test_arena_action_latency,
                        actionFriendlyText(recognised.action),
                        recognised.latencyMs,
                    )
                }
                Text(
                    line,
                    style = HaloType.Caption.copy(
                        color = HaloColors.Mute,
                        fontSize = 14.sp,
                    ),
                )
            }
        }
    }
}

/**
 * One gesture chip in the grid below the hero card. Three visual states:
 *  - **active** (the gesture in the hero right now): accent bg + Bg fg, draws attention briefly.
 *  - **done** (practiced at least once this session): accent fg + accent-dim border, no fill.
 *  - **pending** (never tried): mute fg + line border.
 *
 * Tap-free: the chip isn't clickable. The whole point of Test Arena is that the wearer fires
 * gestures from the ring, not by touching the screen.
 */
@Composable
private fun GestureChip(
    gesture: Gesture,
    done: Boolean,
    active: Boolean,
) {
    val bg = when {
        active -> HaloColors.Accent
        else   -> Color.Transparent
    }
    val fg = when {
        active -> HaloColors.Bg
        done   -> HaloColors.Accent
        else   -> HaloColors.Mute
    }
    val borderColor = when {
        active -> HaloColors.Accent
        done   -> HaloColors.AccentDim
        else   -> HaloColors.Line
    }
    val prefix = if (done || active) "✓ " else ""
    Box(
        modifier = Modifier
            .background(bg)
            .border(width = 1.dp, color = borderColor)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = prefix + gestureFriendlyText(gesture),
            style = HaloType.Caption.copy(
                color = fg,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

/** How long the hero card holds the last-gesture render before fading back to the waiting
 *  prompt. The chip below stays lit permanently — the hero is for "what JUST happened". */
private const val HERO_HOLD_MS: Long = 2_500L

/** Two DOUBLE_TAPs inside this window = exit. Wide enough that a deliberate exit feels easy;
 *  narrow enough that one stray accidental double-tap during practice won't trigger. */
private const val DOUBLE_DOUBLE_TAP_WINDOW_MS: Long = 1_500L
