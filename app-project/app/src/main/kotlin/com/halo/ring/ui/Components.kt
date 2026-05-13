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
            Text(currentMode, style = HaloType.Mono.copy(color = HaloColors.Mute))
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
    content: @Composable () -> Unit,
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
    ) { content() }
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

/** A primary-action button: bordered outline (default) or filled (when focused). */
@Composable
fun Cta(
    text: String,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit = {},
) {
    val accent = if (danger) HaloColors.Bad else HaloColors.Accent
    val bg = if (focused) accent else Color.Transparent
    val fg = if (focused) HaloColors.Bg else accent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = accent)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = TextStyle(
                fontSize = 14.sp,
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
