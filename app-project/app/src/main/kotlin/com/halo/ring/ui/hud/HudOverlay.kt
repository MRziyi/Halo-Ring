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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
    // v0.4 on-glasses fix (2026-05-27): on the Rokid waveguide, leaving the ComposeView attached
    // with currentEvent=null still left a faint residual surface ("HUD 消失得不全"). On an additive
    // see-through panel an attached-but-empty window can still emit a backing-surface artefact.
    // So the auto-hide now FULLY removes the view from the WindowManager; show() re-installs it.
    // The per-gesture-hint add/remove cost is negligible vs a visible ghost pill.
    private val hideRunnable = Runnable {
        currentEvent = null
        removeView()
    }

    /** User-selected HUD anchor (Doc/08-ui-design.md §6). Default TopRight: off-axis on both
     *  Rokid (mono right-eye, ~480 px) and RayNeo (binocular 1280×480 — right peripheral). */
    private var hudPosition: HudPosition = HudPosition.TopRight
    /** True if the underlying display is side-by-side binocular (RayNeo X3 Pro: 1280×480 logical,
     *  left half → left eye, right half → right eye). Affects gravity math for `TopCenter`,
     *  which on a side-by-side canvas would otherwise land *between* the eyes (x ≈ 640) — visible
     *  only at the inner edge of each eye. When set, we re-anchor `TopCenter` into the right-eye
     *  region instead. Wired from `DisplayAdapter.isBinocular` via [setBinocular]. */
    @Volatile private var isBinocular: Boolean = false
    /** Position of the currently-installed view (or null if no view installed). Used to detect
     *  when [show] needs to migrate the existing view to a different gravity — previously the
     *  install-time position was sticky, so e.g. Disconnected (Center) would silently stay
     *  wherever the prior HUD was installed. Audit-2026-05-13l. */
    private var installedPosition: HudPosition? = null
    /** Per-event base duration in ms (overridable by [HudEvent] subtypes via [resolveDuration]).
     *  Wired from [com.halo.ring.ui.screens.FeedbackPrefs.hudDurationMs] by the service. */
    @Volatile private var defaultDurationMs: Long = 2_000L
    /** Vertical nudge in body units (each ≈ one pill-height). Top-anchored positions move DOWN,
     *  bottom-anchored move UP (see [buildLayoutParams]) so the HUD can clear a plugin's own HUD.
     *  Wired from `FeedbackPrefs.hudOffsetSteps`. */
    @Volatile private var hudOffsetSteps: Int = 2

    enum class HudPosition { TopRight, TopLeft, TopCenter, BottomRight, BottomLeft, Center }

    fun setPosition(pos: HudPosition) {
        runOnMain {
            if (hudPosition == pos) return@runOnMain
            hudPosition = pos
            view?.let {
                try {
                    wm.updateViewLayout(it, buildLayoutParams(pos))
                    installedPosition = pos
                } catch (_: Exception) {}
            }
        }
    }

    /** Set the base duration for non-special events (Peek / ProfileSwitched / LowBattery). The
     *  short-fixed events (GestureRecognised = 800 ms, Reconnected = 1 s, Disconnected = 4 s + reminder)
     *  keep their own cadence regardless. */
    fun setDefaultDuration(durationMs: Long) {
        defaultDurationMs = durationMs.coerceIn(500L, 10_000L)
    }

    /** Set the vertical HUD offset in body units (0..5); re-layouts a currently-installed view. */
    fun setVerticalOffsetSteps(steps: Int) {
        runOnMain {
            val clamped = steps.coerceIn(0, 5)
            if (hudOffsetSteps == clamped) return@runOnMain
            hudOffsetSteps = clamped
            view?.let { try { wm.updateViewLayout(it, buildLayoutParams(hudPosition)) } catch (_: Exception) {} }
        }
    }

    /** Set the binocular flag (matched to `DisplayAdapter.isBinocular`). Re-layouts a currently
     *  installed view if the new value affects its gravity. */
    fun setBinocular(binocular: Boolean) {
        runOnMain {
            if (isBinocular == binocular) return@runOnMain
            isBinocular = binocular
            view?.let {
                try { wm.updateViewLayout(it, buildLayoutParams(hudPosition)) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Show the event. Replaces any in-flight HUD; cancels any pending hide.
     *
     * Audit-2026-05-13j (B1): may be called from the scheduler thread (the BLE-events subscriber
     * runs there) — but [WindowManager.addView] and Compose state mutation should be on the main
     * thread. Marshal everything via [runOnMain] so callers don't have to know.
     */
    fun show(event: HudEvent) {
        runOnMain {
            main.removeCallbacks(hideRunnable)

            // Audit-2026-05-13l: all events use the user's hudPosition. Previously Disconnected
            // was hard-coded to Center, which (1) breaks the AR rule of "never occlude the line
            // of sight with persistent UI" and (2) was buggy anyway — ensureViewInstalled only
            // applied the position on first install, so an already-installed TopRight view would
            // silently keep its position when Disconnected fired.
            val targetPos = hudPosition
            ensureViewInstalled(targetPos)
            // If the view is already up at a different position (e.g. the user just changed the
            // pref), migrate it now.
            if (installedPosition != null && installedPosition != targetPos) {
                view?.let {
                    try {
                        wm.updateViewLayout(it, buildLayoutParams(targetPos))
                        installedPosition = targetPos
                    } catch (_: Exception) {}
                }
            }

            currentEvent = event
            val durationMs = resolveDuration(event)
            // Audit-2026-05-13l: Disconnected was Long.MAX_VALUE = persistent. Bad for AR glasses
            // (the StatusBar's red ● dot inside the app already serves as the persistent
            // indicator; the HUD is for transient attention). Now finite — the service schedules
            // a periodic re-show (~60 s) until reconnect via DisconnectedReminder.
            main.postDelayed(hideRunnable, durationMs)
        }
    }

    /** Per-event display duration. Disconnect is intentionally short (4 s) — see [show] kdoc. */
    private fun resolveDuration(event: HudEvent): Long = when (event) {
        is HudEvent.GestureRecognised -> 800L           // §10: keep brief, don't chain
        is HudEvent.Reconnected       -> 1_000L         // §3: transient confirmation
        is HudEvent.Disconnected      -> 4_000L         // §3 (was: persistent — see kdoc)
        is HudEvent.SportTick         -> 8_000L         // C5: long enough to glance at, ticks again
        is HudEvent.RingDropped       -> 2_500L         // C6: short — usually a false positive
        is HudEvent.Notice            -> 2_500L         // BT-internet result — long enough to read
        else                          -> defaultDurationMs
    }

    /** Force-hide the HUD (e.g. when Disconnected resolves into Reconnected). P1-9: keeps the
     *  ComposeView installed for the next show; clears the current event so the composable
     *  renders nothing. Use [destroy] for actual teardown. */
    fun hide() {
        runOnMain {
            main.removeCallbacks(hideRunnable)
            currentEvent = null
            removeView()
        }
    }

    /** P1-9: tear down the WindowManager view + Compose composition. Call from
     *  [com.halo.ring.service.HaloRingService.onDestroy] only — `hide()` is for the per-event
     *  auto-hide and no longer removes the view. */
    fun destroy() {
        runOnMain {
            main.removeCallbacks(hideRunnable)
            currentEvent = null
            removeView()
        }
    }

    /** Run [block] on the main thread synchronously if we're already there, else post. */
    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
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
                    currentEvent?.let { ev ->
                        // Binocular (RayNeo): render the pill in BOTH eye halves so it isn't
                        // single-eye. Mono (Rokid): single pill, anchored via layout-params gravity.
                        if (isBinocular) BinocularHudPill(ev, hudPosition, hudOffsetSteps)
                        else HudPill(ev)
                    }
                }
            }
        }
        view = composeView
        try {
            wm.addView(composeView, buildLayoutParams(pos))
            installedPosition = pos
        } catch (e: Exception) {
            // SYSTEM_ALERT_WINDOW not granted yet, or other error — fail gracefully.
            view = null
            installedPosition = null
        }
    }

    private fun removeView() {
        view?.let { v ->
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        view = null
        installedPosition = null
    }

    private fun buildLayoutParams(pos: HudPosition): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        // Binocular (RayNeo X3 Pro): a single anchored pill only reaches one eye. Instead make the
        // overlay a full-screen transparent canvas and let [BinocularHudPill] place one pill in each
        // 640-wide eye half. FLAG_NOT_TOUCHABLE keeps it pass-through; the two small pills render
        // only while a HUD event is showing, so the memory/latency cost is negligible.
        if (isBinocular) {
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type, flags, PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
        }
        // On a side-by-side binocular canvas (RayNeo X3 Pro: 1280×480 logical, left half → left
        // eye, right half → right eye), CENTER_HORIZONTAL lands at x≈640 which is the *inner*
        // edge of both eyes — visible at the user's nose, not centred in their gaze. Re-anchor
        // those positions to land in the right-eye region instead. (We pick right eye to mirror
        // Rokid's mono-right-eye convention; users with strong left-eye dominance can switch
        // physical glasses orientation.)
        val gravity = when {
            pos == HudPosition.TopRight                  -> Gravity.TOP    or Gravity.END
            pos == HudPosition.TopLeft                   -> Gravity.TOP    or Gravity.START
            pos == HudPosition.TopCenter && isBinocular  -> Gravity.TOP    or Gravity.END
            pos == HudPosition.TopCenter                 -> Gravity.TOP    or Gravity.CENTER_HORIZONTAL
            pos == HudPosition.BottomRight               -> Gravity.BOTTOM or Gravity.END
            pos == HudPosition.BottomLeft                -> Gravity.BOTTOM or Gravity.START
            pos == HudPosition.Center && isBinocular     -> Gravity.CENTER_VERTICAL or Gravity.END
            else /* HudPosition.Center */                -> Gravity.CENTER
        }
        // On binocular when we re-anchored TopCenter → END, push it in from the right edge by
        // ~160 px so the HUD pill sits roughly mid-right-eye (right eye occupies x∈[640,1280];
        // pill width ~200–260 px; END+160 puts the pill's centre near x≈940 = right-eye centre).
        //
        // On-glasses fix (2026-05-27): on the Rokid 480×640 waveguide, x=16 was too tight — the
        // pill's right border clipped against the panel edge ("漏一个边"). The waveguide's effective
        // safe area trims the outer ~24-32 px. Bump the edge margin to SAFE_MARGIN so the whole
        // bordered pill stays inside the visible region.
        val xInset = when {
            pos == HudPosition.Center && !isBinocular   -> 0
            pos == HudPosition.TopCenter && isBinocular -> 160
            pos == HudPosition.Center && isBinocular    -> 160
            else                                        -> SAFE_MARGIN_PX
        }
        // User HUD offset (FeedbackPrefs.hudOffsetSteps, in body-units): top-anchored positions
        // move DOWN, bottom-anchored move UP — both achieved by adding to `y` (TOP gravity grows
        // downward, BOTTOM gravity grows upward) — so the HUD can clear a plugin's own HUD
        // (Constellation). Center positions ignore the offset.
        val topAnchored = pos == HudPosition.TopRight || pos == HudPosition.TopLeft || pos == HudPosition.TopCenter
        val bottomAnchored = pos == HudPosition.BottomRight || pos == HudPosition.BottomLeft
        val offsetPx = if (topAnchored || bottomAnchored) hudOffsetSteps * BODY_UNIT_PX else 0
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            x = xInset
            y = SAFE_MARGIN_PX + offsetPx
        }
    }

    private companion object {
        /** Waveguide safe-area margin (px). The outer edge of the Rokid panel is trimmed by the
         *  optics; keep the bordered HUD pill inside this inset so no edge clips. */
        const val SAFE_MARGIN_PX = 32
        /** One "body" unit (px) for the user's HUD vertical offset ≈ one pill-height. */
        const val BODY_UNIT_PX = 60
    }
}

/**
 * Binocular HUD layout (RayNeo X3 Pro). The panel is one 1280×480 surface split down the middle —
 * left 640 → left eye, right 640 → right eye. A single pill placed once would only reach one eye
 * (the "HUD 只在右眼" bug), so we render the SAME pill in BOTH halves, each centred horizontally in
 * its eye and anchored vertically to match [HudPosition]. Fused by the wearer's binocular vision
 * into one element. Rokid (mono) never calls this — it keeps the single anchored [HudPill].
 *
 * Vertical anchor + offset mirror the mono [HudOverlay.buildLayoutParams] math (SAFE_MARGIN +
 * hudOffsetSteps body-units), in dp (RayNeo panel is 160 dpi so dp≈px).
 */
@Composable
fun BinocularHudPill(event: HudEvent, position: HudOverlay.HudPosition, offsetSteps: Int) {
    val topAnchored = position == HudOverlay.HudPosition.TopRight ||
        position == HudOverlay.HudPosition.TopLeft || position == HudOverlay.HudPosition.TopCenter
    val bottomAnchored = position == HudOverlay.HudPosition.BottomRight ||
        position == HudOverlay.HudPosition.BottomLeft
    val align = when {
        topAnchored    -> Alignment.TopCenter
        bottomAnchored -> Alignment.BottomCenter
        else           -> Alignment.Center   // Center
    }
    // Same units as buildLayoutParams: SAFE_MARGIN_PX(32) + offsetSteps*BODY_UNIT_PX(60).
    val edgePad = (32 + offsetSteps * 60).dp
    val pad = when {
        topAnchored    -> Modifier.padding(top = edgePad)
        bottomAnchored -> Modifier.padding(bottom = edgePad)
        else           -> Modifier
    }
    Row(Modifier.fillMaxSize()) {
        // One eye-half per child; weight(1f) splits the 1280 canvas into two 640 columns.
        repeat(2) {
            Box(Modifier.weight(1f).fillMaxHeight().then(pad), contentAlignment = align) {
                HudPill(event)
            }
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
        is HudEvent.TargetReached        -> HaloColors.Accent
        is HudEvent.SportTick            -> HaloColors.Accent
        is HudEvent.RingDropped          -> HaloColors.Bad
        is HudEvent.Notice               -> if (event.bad) HaloColors.Bad else HaloColors.Accent
    }
    // Disconnected gets slightly larger padding because it carries a two-line message (status +
    // reconnect hint); the rest are single-line pills. The variable's old name `centered` referred
    // to its previous centre-of-eye placement, removed in audit-2026-05-13l.
    val prominent = event is HudEvent.Disconnected
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC000000))
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = if (prominent) 14.dp else 12.dp, vertical = if (prominent) 10.dp else 8.dp),
    ) {
        when (event) {
            is HudEvent.Peek          -> PeekContent(event)
            is HudEvent.ProfileSwitched -> ProfileSwitchedContent(event)
            is HudEvent.LowBattery    -> LowBatteryContent(event)
            is HudEvent.Disconnected  -> DisconnectedContent()
            is HudEvent.Reconnected   -> Text(androidx.compose.ui.res.stringResource(com.halo.ring.R.string.hud_reconnected), style = HaloType.RowVal.copy(
                color = HaloColors.Accent, fontSize = 11.sp,
            ))
            is HudEvent.TargetReached -> Text(androidx.compose.ui.res.stringResource(com.halo.ring.R.string.hud_target_reached), style = HaloType.RowVal.copy(
                color = HaloColors.Accent, fontSize = 11.sp,
            ))
            is HudEvent.GestureRecognised -> GestureRecognisedContent(event)
            is HudEvent.SportTick -> {
                val mins = event.durationSec / 60
                val secs = event.durationSec % 60
                val durationText = "%02d:%02d".format(mins, secs)
                val hrText = event.hrBpm?.let { " · ❤ $it" } ?: ""
                Text(
                    "🏃 $durationText$hrText",
                    style = HaloType.RowVal.copy(color = HaloColors.Accent, fontSize = 11.sp),
                )
            }
            is HudEvent.RingDropped -> Text(
                androidx.compose.ui.res.stringResource(com.halo.ring.R.string.hud_ring_dropped, event.ringId),
                style = HaloType.RowVal.copy(color = HaloColors.Bad, fontSize = 11.sp),
            )
            is HudEvent.Notice -> Text(
                event.text,
                style = HaloType.RowVal.copy(
                    color = if (event.bad) HaloColors.Bad else HaloColors.Accent, fontSize = 11.sp,
                ),
            )
        }
    }
}

@Composable
private fun PeekContent(p: HudEvent.Peek) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(
            if (p.connected) HaloColors.Accent else HaloColors.Bad))
        Text(profileFriendlyTextByName(p.mode), style = HaloType.Body.copy(
            fontSize = 11.sp,
            color = HaloColors.Fg,
        ))
        p.batteryPct?.let {
            Text(androidx.compose.ui.res.stringResource(com.halo.ring.R.string.ring_battery_pct, it), style = HaloType.Caption.copy(
                fontSize = 10.sp,
            ))
        }
        // P0-4: optional vitals fields. Service populates only when the matching pref is on AND a
        // recent reading exists. Both null in the common case → no extra glyphs.
        p.heartRateBpm?.let {
            Text("♥ $it", style = HaloType.Caption.copy(
                fontSize = 10.sp,
                color = HaloColors.Accent,
            ))
        }
        p.activitySteps?.let {
            Text(androidx.compose.ui.res.stringResource(com.halo.ring.R.string.hud_steps_value, it), style = HaloType.Caption.copy(
                fontSize = 10.sp,
            ))
        }
    }
}

@Composable
private fun ProfileSwitchedContent(p: HudEvent.ProfileSwitched) {
    // Redesigned 2026-05-28: profiles are now auto-inferred (never manually cycled), so the old
    // "↻ → … (cycle)" rotate icon + wording is gone. The icon shows the profile's *content* — what
    // app context you're in — and we just name the mode.
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(profileIconByName(p.newMode), style = HaloType.Body.copy(fontSize = 14.sp))
        Text(profileFriendlyTextByName(p.newMode), style = HaloType.Body.copy(
            fontSize = 11.sp, color = HaloColors.Accent,
        ))
    }
}

/** A content glyph per built-in profile (matches DefaultProfiles ids). Falls back to a neutral dot
 *  for custom profiles. The icon shows *what context you're in*, not "switching". */
private fun profileIconByName(name: String): String = when (name.lowercase()) {
    "media"      -> "🎵"
    "camera"     -> "📷"
    "reader"     -> "📖"
    "navigation" -> "🧭"
    else         -> "●"
}

@Composable
private fun LowBatteryContent(p: HudEvent.LowBattery) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(HaloColors.Warn))
        Text(p.ringId, style = HaloType.Body.copy(
            fontSize = 11.sp,
        ))
        Text(androidx.compose.ui.res.stringResource(com.halo.ring.R.string.ring_battery_pct, p.pct), style = HaloType.Caption.copy(
            fontSize = 10.sp,
            color = HaloColors.Warn,
        ))
    }
}

@Composable
private fun DisconnectedContent() {
    // Compact two-line pill: status + actionable hint. The hint mirrors the default
    // ForceReconnect system gesture (Doc/05 §5: DOUBLE_LONG_PRESS) so the wearer has a path
    // out without opening the app. If the user has rebound ForceReconnect, the hint becomes
    // slightly stale — acceptable trade-off vs. making the HUD reach into runtime state.
    androidx.compose.foundation.layout.Column {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(HaloColors.Bad))
            Text(androidx.compose.ui.res.stringResource(com.halo.ring.R.string.hud_disconnected), style = HaloType.Body.copy(color = HaloColors.Fg, fontSize = 11.sp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            androidx.compose.ui.res.stringResource(com.halo.ring.R.string.hud_disconnected_hint),
            style = HaloType.Caption.copy(fontSize = 10.sp, color = HaloColors.Mute),
        )
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
        Text(gestureFriendlyText(g.gesture), style = HaloType.Caption.copy(
            color = HaloColors.Mute,
            fontSize = 11.sp,
        ))
        Text("→", style = HaloType.Caption.copy(color = HaloColors.Mute,
            fontSize = 11.sp))
        Text(actionFriendlyText(g.resolvedAction), style = HaloType.Body.copy(
            color = actionColor,
            fontSize = 11.sp,
        ))
    }
}
