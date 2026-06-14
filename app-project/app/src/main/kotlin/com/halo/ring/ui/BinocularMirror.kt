package com.halo.ring.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Runtime-tunable binocular disparity (px) for [BinocularMirror], shared by the config UI and the
 * HUD so the whole RayNeo presentation sits at one focal plane. **Positive = uncrossed disparity →
 * content recedes (appears farther / behind the screen plane); negative = crossed → pops toward the
 * wearer.** Kept small (clamped) to avoid vergence-accommodation discomfort on the fixed-focus
 * waveguide. A Compose state so changing it live (debug `TEST_DISPARITY` broadcast) re-draws both
 * eyes without recomposition. Default 0 = flat at the screen plane until a comfortable value is
 * dialled in on-device.
 */
object BinocularTuning {
    /** Clamp bound — a few % of the 640-px eye width; beyond this the eyes can't fuse comfortably. */
    const val MAX_DISPARITY_PX = 48
    /** Comfortable default dialled in on real RayNeo X3 Pro hardware (2026-06-14, Zack): a small
     *  uncrossed disparity that floats the UI just behind the screen plane. */
    const val DEFAULT_DISPARITY_PX = 12
    var disparityPx by mutableIntStateOf(DEFAULT_DISPARITY_PX)
        private set
    fun setDisparity(px: Int) { disparityPx = px.coerceIn(-MAX_DISPARITY_PX, MAX_DISPARITY_PX) }
}

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
                // … then paint it into both eyes. A half-disparity is applied in opposite directions
                // so the fused image sits off the screen plane (see [BinocularTuning]). Reading the
                // state here (draw phase) re-draws on change — no recomposition.
                val half = BinocularTuning.disparityPx / 2f
                translate(left = -half) { drawLayer(layer) }                       // left eye
                translate(left = eyeWidthDp.dp.toPx() + half) { drawLayer(layer) } // right eye
            },
    ) {
        Box(Modifier.width(eyeWidthDp.dp).fillMaxHeight()) { content() }
    }
}
