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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import com.halo.ring.ui.AppTab
import com.halo.ring.ui.Cta
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.ui.SubScreen

/**
 * Interactive coachmark-style onboarding tour (Doc/08 §1, audit-2026-05-13p revision).
 *
 * Earlier revisions of this guide were a static read-only cheat-sheet. Real onboarding needs to
 * *show* the user each part of the app, not just describe it. So GuidedTour:
 *
 *  1. Drives the underlying [com.halo.ring.ui.HaloRingApp] to the relevant tab + sub-screen for
 *     each step via the [onSelectTab] / [onPush] / [onPopAll] callbacks — the user sees the real
 *     screen behind a semi-transparent dim layer.
 *  2. Renders a callout card at the bottom with title + body + step-counter + NEXT / SKIP.
 *  3. Advances on TAP (NEXT) — works via ring DPAD_CENTER, temple Click, mouse, touch, Enter.
 *
 * Glasses-display conformance (Doc/03 + Doc/08):
 *  - Dim layer at 70 % opacity, leaving 30 % of underlying UI visible — keeps APL low (well
 *    under RayNeo's 13 % thermal threshold; the dim layer adds ~0 % since we're already on a
 *    pure-black canvas).
 *  - Callout card is a small (~140 dp tall) bordered box at the bottom — never centered, never
 *    occludes the line of sight.
 *  - 16 sp font floor; one mint-green accent on the NEXT CTA; everything else greyscale.
 *  - Interactive elements via Compose `clickable` only — ring + temple + mouse + touch.
 *
 * Dismissal: SKIP at any time, or DONE on the last step. Both fire [onDismiss] which the host
 * uses to set `guide_seen = true` so the tour doesn't auto-show again on next launch.
 */
@Composable
fun GuidedTour(
    onDismiss: () -> Unit,
    onSelectTab: (AppTab) -> Unit,
    onPush: (SubScreen) -> Unit,
    onPopAll: () -> Unit,
) {
    val steps = remember { tourSteps() }
    var stepIdx by remember { mutableStateOf(0) }
    val step = steps[stepIdx]

    // Drive the underlying app to the right tab + sub-screen for this step.
    LaunchedEffect(stepIdx) {
        onSelectTab(step.tab)
        // selectTab() already clears navStack; only push if this step wants a sub-screen.
        step.drill?.let { onPush(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Dim layer — semi-transparent black. AR canvas is already black, so this is mostly an
        // "interactive elements vs callout" contrast cue. ~70 % opacity is the sweet spot:
        // dimmed enough that the callout pops, but the underlying tab/screen is still legible.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000)), // 0xB3 = ~70 % opaque
        )

        // Callout — bottom-centered, 24 dp safe-area from the bottom edge, accent border.
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
                    .border(width = 1.dp, color = HaloColors.Accent, shape = RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(step.title, style = HaloType.Body)
                        Text(
                            "${stepIdx + 1} / ${steps.size}",
                            style = HaloType.Caption.copy(color = HaloColors.Mute),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(step.body, style = HaloType.Caption.copy(color = HaloColors.Fg))
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Cta(
                            text = "SKIP",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onPopAll()
                                onDismiss()
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Cta(
                            text = if (stepIdx == steps.size - 1) "DONE" else "NEXT",
                            modifier = Modifier.weight(1f),
                            // Pre-focus the NEXT button so a ring TAP advances without first
                            // navigating focus there. The user can still SWIPE_UP to focus SKIP.
                            focused = true,
                            onClick = {
                                if (stepIdx == steps.size - 1) {
                                    onPopAll()
                                    onDismiss()
                                } else {
                                    stepIdx += 1
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One step of the guided tour: where to navigate to + what to show. */
private data class TourStep(
    /** Tab to switch to when this step becomes active. */
    val tab: AppTab,
    /** Sub-screen to drill into (after switching to [tab]). Null = stay at the tab's root. */
    val drill: SubScreen? = null,
    /** Callout title — keep ≤ ~24 chars to fit comfortably on Rokid's 480-px mono display. */
    val title: String,
    /** Callout body — 1-2 short sentences. */
    val body: String,
)

/**
 * The tour script. Order is the order users see. Keep ≤ 10 steps — anything longer starts to
 * feel like a slog on first-launch.
 */
private fun tourSteps(): List<TourStep> = listOf(
    TourStep(
        tab = AppTab.VITALS,
        title = "Welcome to Halo Ring",
        body = "Quick tour of every part of the app. Tap NEXT to step through. You can SKIP anytime.",
    ),
    TourStep(
        tab = AppTab.VITALS,
        title = "VITALS tab",
        body = "Live HR / SpO2 / stress from the ring. MEASURE NOW starts a one-shot PPG snapshot.",
    ),
    TourStep(
        tab = AppTab.SETTINGS,
        title = "SETTINGS tab",
        body = "All configuration lives here. Swipe up / down to navigate; tap to enter a row.",
    ),
    TourStep(
        tab = AppTab.SETTINGS,
        drill = SubScreen.Profiles,
        title = "Profiles & Gestures",
        body = "Each profile binds the 12 ring gestures to 12 actions. Triple-tap cycles profiles.",
    ),
    TourStep(
        tab = AppTab.SETTINGS,
        drill = SubScreen.SystemGestures,
        title = "System gestures",
        body = "Always-on overrides: wake, sleep, profile-cycle, peek HUD, force reconnect.",
    ),
    TourStep(
        tab = AppTab.SETTINGS,
        drill = SubScreen.Ring,
        title = "Ring screen",
        body = "MAC, firmware, signal, battery + find-ring / shut-down / forget controls.",
    ),
    TourStep(
        tab = AppTab.SETTINGS,
        drill = SubScreen.Language,
        title = "Language",
        body = "Follow system, or pick 中文 / English. Switches instantly.",
    ),
    TourStep(
        tab = AppTab.SETTINGS,
        drill = SubScreen.About,
        title = "About",
        body = "Version + repo link + author. Re-open this tour any time from here.",
    ),
    TourStep(
        tab = AppTab.STATUS,
        title = "STATUS tab",
        body = "Connection state, negotiated BLE interval, last-gesture latency. Diagnostics.",
    ),
    TourStep(
        tab = AppTab.VITALS,
        title = "You're ready",
        body = "Tap DONE to start using Halo Ring. The ring is your remote from here on.",
    ),
)
