package com.halo.ring.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Binocular mirror for the RayNeo X3 Pro panel (one 1280×480 surface split down the middle: left
 * 640 px → left eye, right 640 px → right eye). A normal full-width Compose tree puts centred content
 * across the x=640 nose-seam, so each eye sees only half — the "左右眼割裂" bug.
 *
 * When [enabled], this composes [content] **once** in a single [eyeWidthDp]-wide column (left eye),
 * records its drawing into a [androidx.compose.ui.graphics.layer.GraphicsLayer], then re-draws that
 * layer shifted into the right-eye half. Result: both eyes show identical pixels from **one**
 * composition — a single NavHost back-stack and a single set of state (no two-tree divergence),
 * interaction living in the left-eye copy. The only per-frame cost is one extra layer blit, NOT a
 * second composition — within the energy/latency budget even on the always-on path.
 *
 * When [enabled] is false (Rokid / any mono display) it is a pass-through with zero overhead, so
 * wrapping shared UI in it never affects Rokid.
 *
 * Stereo-depth hook: giving the two `drawLayer` calls a small opposing horizontal offset introduces
 * binocular disparity → the UI floats at a focal plane. Kept at 0 here (flat, eyes aligned); a later
 * design pass can lift specific layers for depth (see the RayNeo in-app-design phase).
 */
@Composable
fun BinocularMirror(
    enabled: Boolean,
    eyeWidthDp: Int = 640,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val layer = rememberGraphicsLayer()
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                // Record the (left-eye-width) child once …
                layer.record { this@drawWithContent.drawContent() }
                // … then paint it into the left eye (in place) and the right eye (+eyeWidth).
                drawLayer(layer)
                translate(left = eyeWidthDp.dp.toPx()) { drawLayer(layer) }
            },
    ) {
        Box(Modifier.width(eyeWidthDp.dp).fillMaxHeight()) { content() }
    }
}
