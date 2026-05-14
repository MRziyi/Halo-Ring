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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.halo.ring.ui.Cta
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

/**
 * Post-wizard operation guide (Doc/08 §1 — onboarding teaching aid). Single scrollable column
 * explaining every gesture the wearer can do + the three tabs. Shown automatically once after
 * the first-run wizard completes; re-openable from Settings → About → "Show operation guide".
 *
 * Design constraints (Doc/03 + Doc/08):
 *  - Font ≥ 16 sp throughout (Caption is 16 sp, headers larger)
 *  - Pure black canvas, single mint-green accent — keeps APL well below RayNeo's 13 % throttle
 *  - All interactive elements via Compose `clickable` only — no `pointerInput`, no drag, no
 *    multitouch (Doc/03 §1.2: Rokid has no touchscreen; X3 Pro's temple is intercepted by
 *    Mercury's `TouchDispatcher` upstream)
 *  - Single CTA at the bottom reachable via ring NavNext + Confirm
 */
@Composable
fun GuideScreen(
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HaloColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 20.dp),
    ) {
        Text("How to use Halo Ring", style = HaloType.Title)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your ring is your remote. Below are every gesture and where to look on the glasses.",
            style = HaloType.Caption,
        )

        SectionHeader("Default Navigation gestures")
        GuideRow("Tap",                  "→ Confirm")
        GuideRow("Double tap",           "→ Back")
        GuideRow("Swipe up",             "→ Previous")
        GuideRow("Swipe down",           "→ Next")
        GuideRow("Long press",           "→ Menu")
        GuideRow("Double-tap + swipe up",   "→ Take photo")
        GuideRow("Double-tap + swipe down", "→ Visual AI")
        GuideRow("Long-press + swipe up",   "→ Notifications")
        Spacer(Modifier.height(6.dp))
        Text(
            "These bindings live in the Navigation profile. Switch / customise under " +
                "Settings → Profiles & Gestures.",
            style = HaloType.Caption.copy(color = HaloColors.Mute),
        )

        SectionHeader("Always-on system gestures")
        GuideRow("Long press (screen off)",     "→ Wake the glasses")
        GuideRow("Long-press + swipe down",     "→ Sleep the screen")
        GuideRow("Triple tap",                  "→ Cycle profile")
        GuideRow("Quadruple tap",               "→ Peek HUD (ring state)")
        GuideRow("Long-press × 2",              "→ Force reconnect")
        Spacer(Modifier.height(6.dp))
        Text(
            "System gestures work regardless of profile. Rebind any of them under " +
                "Settings → System Gestures.",
            style = HaloType.Caption.copy(color = HaloColors.Mute),
        )

        SectionHeader("The three tabs")
        GuideRow("VITALS",  "Live HR / SpO2 / stress + MEASURE NOW")
        GuideRow("SETTINGS", "Profiles, ring, language, advanced…")
        GuideRow("STATUS",  "Connection, BLE interval, last gesture")
        Spacer(Modifier.height(6.dp))
        Text(
            "Swipe up past the top row to move focus onto the tab strip; another swipe " +
                "cycles tabs. Tap to commit.",
            style = HaloType.Caption.copy(color = HaloColors.Mute),
        )

        SectionHeader("HUD")
        Text(
            "The glasses show a small pill in the corner when you switch profiles, get a " +
                "low-battery warning, or the ring disconnects. Enable per-gesture hints under " +
                "Settings → Feedback while you learn the vocabulary.",
            style = HaloType.Body,
        )

        Spacer(Modifier.height(20.dp))
        Cta(text = "GOT IT", onClick = onDismiss)
        Spacer(Modifier.height(6.dp))
        Text(
            "You can re-open this guide any time from Settings → About.",
            style = HaloType.Caption.copy(color = HaloColors.Mute),
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = HaloType.Title.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified))
    Spacer(Modifier.height(2.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun GuideRow(left: String, right: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(left, style = HaloType.Body, modifier = Modifier.padding(end = 8.dp))
        Spacer(Modifier.fillMaxWidth(0f))
        Text(right, style = HaloType.Body.copy(color = HaloColors.Mute))
    }
}
