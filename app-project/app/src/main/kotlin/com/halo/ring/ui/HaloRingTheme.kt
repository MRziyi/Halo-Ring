package com.halo.ring.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The 8 design tokens (Doc/08-ui-design.md §2). Anything not here is composed from these.
 * Pure black background, white text, mute grey for secondary, green accent matching the ring LED.
 */
object HaloColors {
    val Bg     = Color(0xFF000000)
    val Fg     = Color(0xFFFFFFFF)
    val Mute   = Color(0xFF8A8A8A)
    val Accent = Color(0xFF5EE08C)
    val AccentDim = Color(0xFF2A5A36)
    val Warn   = Color(0xFFFFB84D)
    val Bad    = Color(0xFFFF7C7C)
    val Line   = Color(0xFF2A2A2A)
    /** 7% accent over Bg — the focused-row tint. */
    val FocusTint = Color(0x125EE08C)
}

/** Type scale (Doc/08-ui-design.md §2). System sans-serif, tabular nums for metrics.
 *
 *  On-glasses sizing (2026-05-27): the original scale (Title 24 / Body 17 / floor 16 sp) was
 *  derived from the RayNeo 16 sp guideline, but on the actual Rokid 480×640 waveguide at ~30° FOV
 *  it renders far too large ("字号还是大"). Scaled the whole table down ~30% per the user's
 *  on-device call. This intentionally goes below the RayNeo 16 sp floor — acceptable because we're
 *  optimising for the Rokid panel the user is actually wearing; revisit per-flavor if RayNeo
 *  rendering needs the larger floor back. */
object HaloType {
    val Title    = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = HaloColors.Fg)
    val Body     = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = HaloColors.Fg)
    val Caption  = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = HaloColors.Mute)
    val Tab      = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = HaloColors.Mute, letterSpacing = 1.sp)
    val TabActive= Tab.copy(color = HaloColors.Fg)
    val Mono     = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                             color = HaloColors.Mute)
    val Metric   = TextStyle(fontSize = 39.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, color = HaloColors.Fg)
    /** Mid-size metric for the compact VITALS tab — vitals are secondary on the redesigned home, so
     *  they no longer use the 39sp hero. */
    val MetricMid = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, color = HaloColors.Fg)
    val MetricUnit = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, color = HaloColors.Mute)
    val MetricKey  = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal, color = HaloColors.Mute, letterSpacing = 1.sp)
    val RowKey   = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = HaloColors.Mute)
    val RowVal   = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HaloColors.Fg)
}

/**
 * Compose root theme. Forces a dark scheme over our tokens — keeps any inadvertently-used
 * Material widget from showing white panels.
 */
@Composable
fun HaloRingTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        background = HaloColors.Bg,
        surface = HaloColors.Bg,
        onBackground = HaloColors.Fg,
        onSurface = HaloColors.Fg,
        primary = HaloColors.Accent,
        onPrimary = HaloColors.Bg,
        outline = HaloColors.Line,
    )
    val typography = Typography(
        titleLarge = HaloType.Title,
        bodyLarge = HaloType.Body,
        bodyMedium = HaloType.Body,
        labelLarge = HaloType.Tab,
        labelSmall = HaloType.Caption,
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

/**
 * The universal focus indicator: a 2 dp green left bar + 7% accent tint over the entire item.
 * Used by every focusable row, CTA, tab, etc. Applies whatever the receiving modifier already is.
 *
 * Usage:
 * ```
 * Row(modifier = Modifier
 *     .focusable()
 *     .focusableRow(isFocused) { ... onClick ... }
 *     .padding(...)
 * )
 * ```
 */
fun Modifier.haloFocus(focused: Boolean): Modifier {
    if (!focused) return this
    return this
        .background(HaloColors.FocusTint)
        .drawBehind {
            // 2 dp green bar on the left edge — guaranteed visible even on low-resolution displays
            val barWidth = 2.dp.toPx()
            drawRect(
                color = HaloColors.Accent,
                topLeft = Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(barWidth, size.height),
            )
        }
}
