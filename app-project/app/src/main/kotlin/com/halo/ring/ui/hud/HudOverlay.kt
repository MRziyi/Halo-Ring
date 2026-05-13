package com.halo.ring.ui.hud

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.HaloRingTheme

/**
 * A `WindowManager`-hosted Compose view that sits above whatever app is in the foreground. Owned
 * by [com.halo.ring.service.HaloRingService]. Receives [HudEvent]s; renders one at a time;
 * auto-hides after the per-event duration.
 *
 * Requires the SYSTEM_ALERT_WINDOW permission (declared in AndroidManifest.xml and prompted in
 * the first-run wizard).
 *
 * Threading: all calls must happen on [main]. Internally we use a Handler for the auto-hide
 * timer; new events cancel pending hides.
 */
class HudOverlay(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val viewModelStoreOwner: ViewModelStoreOwner,
    private val savedStateRegistryOwner: SavedStateRegistryOwner,
) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: ComposeView? = null
    private var currentEvent by mutableStateOf<HudEvent?>(null)
    private val hideRunnable = Runnable { currentEvent = null; removeView() }

    private var hudPosition: HudPosition = HudPosition.TopRight

    enum class HudPosition { TopRight, TopCenter, BottomRight, Center }

    fun setPosition(pos: HudPosition) {
        hudPosition = pos
        view?.let {
            wm.updateViewLayout(it, buildLayoutParams(pos))
        }
    }

    /** Show the event. Replaces any in-flight HUD; cancels any pending hide. */
    fun show(event: HudEvent) {
        main.removeCallbacks(hideRunnable)

        val targetPos = if (event is HudEvent.Disconnected) HudPosition.Center else hudPosition
        ensureViewInstalled(targetPos)

        currentEvent = event
        val durationMs = when (event) {
            is HudEvent.GestureRecognised -> 800L
            is HudEvent.Reconnected       -> 1000L
            is HudEvent.Disconnected      -> Long.MAX_VALUE        // persistent until hide()
            else                          -> 2000L
        }
        if (durationMs != Long.MAX_VALUE) {
            main.postDelayed(hideRunnable, durationMs)
        }
    }

    /** Force-hide the HUD (e.g. when Disconnected resolves into Reconnected). */
    fun hide() {
        main.removeCallbacks(hideRunnable)
        currentEvent = null
        removeView()
    }

    private fun ensureViewInstalled(pos: HudPosition) {
        if (view != null) return
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(viewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                HaloRingTheme {
                    currentEvent?.let { HudPill(it) }
                }
            }
        }
        view = composeView
        try {
            wm.addView(composeView, buildLayoutParams(pos))
        } catch (e: Exception) {
            // SYSTEM_ALERT_WINDOW not granted yet, or other error — fail gracefully.
            view = null
        }
    }

    private fun removeView() {
        view?.let { v ->
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        view = null
    }

    private fun buildLayoutParams(pos: HudPosition): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val gravity = when (pos) {
            HudPosition.TopRight    -> Gravity.TOP    or Gravity.END
            HudPosition.TopCenter   -> Gravity.TOP    or Gravity.CENTER_HORIZONTAL
            HudPosition.BottomRight -> Gravity.BOTTOM or Gravity.END
            HudPosition.Center      -> Gravity.CENTER
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            x = if (pos == HudPosition.Center) 0 else 16
            y = 16
        }
    }
}

/**
 * The HUD pill composable. Same look for every variant, with subtle colour/border differences.
 * Compact: max ~260 dp wide; dark fill so even on a black canvas there's a visible boundary.
 */
@Composable
fun HudPill(event: HudEvent) {
    val borderColor: Color = when (event) {
        is HudEvent.ProfileSwitched      -> HaloColors.Accent
        is HudEvent.LowBattery           -> HaloColors.Warn
        is HudEvent.Disconnected         -> HaloColors.Bad
        is HudEvent.Reconnected          -> HaloColors.Accent
        is HudEvent.GestureRecognised    -> HaloColors.Line
        is HudEvent.Peek                 -> HaloColors.Line
    }
    val centered = event is HudEvent.Disconnected
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC000000))
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = if (centered) 18.dp else 12.dp, vertical = if (centered) 14.dp else 8.dp),
    ) {
        when (event) {
            is HudEvent.Peek          -> PeekContent(event)
            is HudEvent.ProfileSwitched -> ProfileSwitchedContent(event)
            is HudEvent.LowBattery    -> LowBatteryContent(event)
            is HudEvent.Disconnected  -> DisconnectedContent()
            is HudEvent.Reconnected   -> Text("● Reconnected", style = HaloType.RowVal.copy(
                color = HaloColors.Accent, fontSize = 13.sp,
            ))
            is HudEvent.GestureRecognised -> GestureRecognisedContent(event)
        }
    }
}

@Composable
private fun PeekContent(p: HudEvent.Peek) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(
            if (p.connected) HaloColors.Accent else HaloColors.Bad))
        Text(p.mode, style = HaloType.Body.copy(
            fontSize = 13.sp,
            color = HaloColors.Fg,
        ))
        p.batteryPct?.let {
            Text("${it}%", style = HaloType.Caption.copy(
                fontSize = 12.sp,
            ))
        }
    }
}

@Composable
private fun ProfileSwitchedContent(p: HudEvent.ProfileSwitched) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("↻ →", style = HaloType.Body.copy(color = HaloColors.Accent,
            fontSize = 13.sp))
        Text(p.newMode, style = HaloType.Body.copy(
            fontSize = 13.sp,
        ))
        Text("cycle", style = HaloType.Caption.copy(
            fontSize = 12.sp,
        ))
    }
}

@Composable
private fun LowBatteryContent(p: HudEvent.LowBattery) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(HaloColors.Warn))
        Text(p.ringId, style = HaloType.Body.copy(
            fontSize = 13.sp,
        ))
        Text("${p.pct}%", style = HaloType.Caption.copy(
            fontSize = 12.sp,
            color = HaloColors.Warn,
        ))
    }
}

@Composable
private fun DisconnectedContent() {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(HaloColors.Bad))
        Text("Ring disconnected", style = HaloType.Body.copy(color = HaloColors.Fg))
    }
}

@Composable
private fun GestureRecognisedContent(g: HudEvent.GestureRecognised) {
    val actionColor =
        if (g.resolvedAction is com.halo.ring.core.action.GlassAction.None) HaloColors.Mute
        else if (g.resolvedAction.isSystemLevel()) HaloColors.Accent
        else HaloColors.Fg
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(g.gesture.friendly(), style = HaloType.Caption.copy(
            color = HaloColors.Mute,
            fontSize = 13.sp,
        ))
        Text("→", style = HaloType.Caption.copy(color = HaloColors.Mute,
            fontSize = 13.sp))
        Text(g.resolvedAction.friendly(), style = HaloType.Body.copy(
            color = actionColor,
            fontSize = 13.sp,
        ))
    }
}
