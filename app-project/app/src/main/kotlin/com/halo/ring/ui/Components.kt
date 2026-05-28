package com.halo.ring.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable building blocks for the screens. Each is a thin Composable that obeys the tokens in
 * [HaloRingTheme] — no element invents a colour or font size of its own.
 */

/** The 24 dp screen-edge padding that every full-screen content uses. */
val ScreenPadding: Dp = 24.dp

/**
 * The status row at the top of every screen. Format (left to right):
 *   ●  R08_2A3F  87%        Navigation
 *
 * `connected` controls the dot colour (green / red).
 */
@Composable
fun StatusBar(
    modifier: Modifier = Modifier,
    connected: Boolean = true,
    ringId: String = "R08_…",
    batteryPct: Int? = null,
    currentMode: String? = "Navigation",
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(
                if (connected) HaloColors.Accent else HaloColors.Bad
            ))
            Spacer(Modifier.width(10.dp))
            Text(ringId, style = HaloType.Mono.copy(color = HaloColors.Mute))
            if (batteryPct != null) {
                Spacer(Modifier.width(10.dp))
                Text("$batteryPct%", style = HaloType.Mono.copy(color = HaloColors.Fg))
            }
        }
        if (currentMode != null) {
            Text(com.halo.ring.ui.hud.profileFriendlyTextByName(currentMode), style = HaloType.Mono.copy(color = HaloColors.Mute))
        }
    }
}

/**
 * A focused-row indicator wrapper. The row's left edge gets a 2 dp green bar + 7% tint when it
 * has focus. Used by lists and CTAs alike.
 *
 * Two focus channels:
 * 1. **Compose-native focus** — the row is `clickable` (which implies `focusable`), so the agent's
 *    injected DPAD_UP/DOWN / temple-bar's DPAD keys navigate it naturally. `onFocusChanged` keeps
 *    `composeFocused` in sync. DPAD_CENTER (Confirm) fires `onClick` for free.
 * 2. **Caller-controlled** — optional `focused` parameter for cases where you want to highlight a
 *    specific row programmatically (e.g. on screen open).
 *
 * The visual indicator shows when *either* says focused, so callers don't need to care which
 * channel drove the focus.
 */
@Composable
fun FocusableRow(
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    var composeFocused by remember { mutableStateOf(false) }
    val effective = focused || composeFocused
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { composeFocused = it.isFocused }
            .clickable(onClick = onClick)      // implies focusable + DPAD_CENTER → onClick
            .haloFocus(effective)
            .padding(horizontal = ScreenPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}

/** A row of a list with `Key  →  Value` layout. */
@Composable
fun ListRow(
    key: String,
    value: String,
    focused: Boolean = false,
    valueColor: Color = HaloColors.Fg,
    onClick: () -> Unit = {},
) {
    FocusableRow(focused = focused, onClick = onClick) {
        Text(key, style = HaloType.RowKey)
        Text(value, style = HaloType.RowVal.copy(color = valueColor))
    }
    // A 1-pixel divider between rows (drawn by the box below; cheap)
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}

/** A primary-action button: bordered outline (default) or filled (when focused).
 *
 *  On-glasses fix 2026-05-27: now tracks Compose focus via `onFocusChanged` (like [FocusableRow]),
 *  not just the explicit `focused` param. Without this, DPAD focus landing on a CTA produced no
 *  visual change — the wearer couldn't tell where the cursor was ("不知道光标在哪"). When focused
 *  the button fills solid accent (green) with black text + a thicker border — unmistakable on the
 *  see-through panel. */
@Composable
fun Cta(
    text: String,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit = {},
) {
    var composeFocused by remember { mutableStateOf(false) }
    val effective = focused || composeFocused
    val accent = if (danger) HaloColors.Bad else HaloColors.Accent
    val bg = if (effective) accent else Color.Transparent
    val fg = if (effective) HaloColors.Bg else accent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { composeFocused = it.isFocused }
            .border(width = if (effective) 2.dp else 1.dp, color = accent)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // P2-10: bumped 14 → 16 sp to match the RayNeo guideline floor (HaloType kdoc). The Cta
        // is a primary-action button and renders as body copy (not a label) on every Settings →
        // Ring / Power / etc. screen — sub-floor was the one true violation in audit-2026-05-15.
        // Other sub-16sp call sites are intentional sub-elements (HaloSwitch pill, settings
        // descriptions, HUD inline captions) where the design density requires the smaller size.
        Text(
            text,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                letterSpacing = 1.sp,
            ),
        )
    }
}

/** A simple horizontal accent bar (e.g. for "BLE conn-interval" or progress). [progress] ∈ [0..1]. */
@Composable
fun AccentBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(HaloColors.Line),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(HaloColors.Accent),
        )
    }
}

/** A big metric block (e.g. HR = 72 bpm). Used in Vitals. */
@Composable
fun MetricCell(
    value: String,
    unit: String? = null,
    label: String,
    valueColor: Color = HaloColors.Fg,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = HaloType.Metric.copy(color = valueColor))
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = HaloType.MetricUnit, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        Text(label.uppercase(), style = HaloType.MetricKey)
    }
}

/**
 * The visual switch indicator used by every Settings toggle (Feedback / Power / Vitals prefs /
 * Advanced). Earlier revisions used plain ON/OFF text in `HaloType.RowVal` (17 sp semibold,
 * green vs mute). On the 480×480 / 1280×480 waveguide and on a busy phone preview the colour
 * difference alone was easy to miss; the user couldn't tell whether their tap had landed.
 * This widget makes the toggle state structural, not just chromatic:
 *
 *  - **OFF** — narrow mute-grey pill with a thin grey border, indicator dot pinned LEFT.
 *  - **ON**  — accent-tinted pill with a 1 dp accent border, indicator dot pinned RIGHT, text
 *    in accent green.
 *
 * The pill width is constant so the focused-row content doesn't reflow when the state changes.
 * If [disabled] is true, the pill renders in mute colors regardless of [on], the "ON/OFF" text
 * is replaced by the localised "coming soon" caption, and the widget no longer reads as
 * interactive.
 *
 * Audit-pass 2026-05-14u — see Doc/08-ui-design.md §6.
 */
@Composable
fun HaloSwitch(
    on: Boolean,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
) {
    val activeColor = HaloColors.Accent
    val mute = HaloColors.Mute
    val borderColor = when {
        disabled -> HaloColors.Line
        on       -> activeColor
        else     -> mute
    }
    val bg = when {
        disabled -> Color.Transparent
        on       -> activeColor.copy(alpha = 0.18f)
        else     -> Color.Transparent
    }
    val labelColor = when {
        disabled -> mute
        on       -> activeColor
        else     -> mute
    }
    val labelRes = when {
        disabled -> com.halo.ring.R.string.common_coming_soon
        on       -> com.halo.ring.R.string.common_on
        else     -> com.halo.ring.R.string.common_off
    }
    Row(
        modifier = modifier
            .width(if (disabled) 96.dp else 64.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (on && !disabled) Arrangement.End else Arrangement.Start,
    ) {
        if (!disabled && on) {
            Text(
                text = androidx.compose.ui.res.stringResource(labelRes),
                style = HaloType.Tab.copy(color = labelColor, fontSize = 12.sp),
            )
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(activeColor))
        } else if (!disabled) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(mute))
            Spacer(Modifier.width(6.dp))
            Text(
                text = androidx.compose.ui.res.stringResource(labelRes),
                style = HaloType.Tab.copy(color = labelColor, fontSize = 12.sp),
            )
        } else {
            Text(
                text = androidx.compose.ui.res.stringResource(labelRes),
                style = HaloType.Caption.copy(color = labelColor, fontSize = 11.sp),
            )
        }
    }
}
