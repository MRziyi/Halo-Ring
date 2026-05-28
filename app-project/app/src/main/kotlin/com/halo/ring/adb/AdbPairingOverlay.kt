package com.halo.ring.adb

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloRingTheme
import com.halo.ring.ui.HaloType

/**
 * Overlay panel that lets the user type the 6-digit pairing code WITHOUT having to leave the
 * Settings → Wireless Debugging → "Pair with code" dialog.
 *
 * Two window types are supported — the caller passes [windowType]:
 *
 *  - `TYPE_APPLICATION_OVERLAY` (default): requires `SYSTEM_ALERT_WINDOW`. Blocked over
 *    Settings on Android 12+ because Settings declares `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`.
 *
 *  - `TYPE_ACCESSIBILITY_OVERLAY`: can only be used when an accessibility service is running
 *    in the same process. Explicitly exempt from `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` — this is
 *    the only window type that survives on top of Settings without system-app privileges.
 *    Pass `this` (the service context) as [appContext] from within the accessibility service.
 *
 * Owned LifecycleOwner: we keep our own [LifecycleRegistry] pinned at RESUMED for the
 * overlay's whole life. Hooking it to the Activity's lifecycle made the Compose tree stop
 * composing the moment the user switched to Settings.
 *
 * Window flags: NOT_TOUCH_MODAL so touches OUTSIDE the panel fall through to Settings.
 */
class AdbPairingOverlay(
    private val appContext: Context,
    private val windowType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
) :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var statusState: ((String) -> Unit)? = null

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** Show the overlay. Calling again after [hide] is allowed. */
    fun show(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
        runOnMain {
            if (view != null) return@runOnMain
            var status by mutableStateOf("")
            statusState = { status = it }

            val composeView = ComposeView(appContext).apply {
                setViewTreeLifecycleOwner(this@AdbPairingOverlay)
                setViewTreeViewModelStoreOwner(this@AdbPairingOverlay)
                setViewTreeSavedStateRegistryOwner(this@AdbPairingOverlay)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    HaloRingTheme {
                        PairingPanel(
                            status = status,
                            onSubmit = onSubmit,
                            onCancel = { runOnMain { hide(); onCancel() } },
                        )
                    }
                }
            }
            view = composeView
            try {
                wm.addView(composeView, buildLayoutParams())
            } catch (e: Exception) {
                Log.e(TAG, "addView failed (SYSTEM_ALERT_WINDOW not granted?): ${e.message}")
                view = null
            }
        }
    }

    /** Update the in-overlay status text. Safe from any thread. */
    fun updateStatus(text: String) {
        runOnMain { statusState?.invoke(text) }
    }

    /** Remove the overlay window. Idempotent. */
    fun hide() {
        runOnMain {
            view?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
            view = null
            statusState = null
        }
    }

    /** Call from MainActivity.onDestroy to release lifecycle observers. */
    fun destroy() {
        hide()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = windowType
        // NOT_TOUCH_MODAL is critical: without it, the overlay window eats every touch on the
        // screen (including ones outside its visible bounds), breaking the home screen and the
        // Settings dialog we're floating over.
        //
        // ALT_FOCUSABLE_IM lets the IME bind to our window's text field even though the parent
        // Activity is unfocused.
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            // TOP matches HudOverlay positioning — BOTTOM is off-screen on Rokid's AR lens.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 32
        }
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    companion object {
        private const val TAG = "AdbPairingOverlay"

        /** True if "Display over other apps" is granted. */
        fun hasPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
            else true

        /** Deep-link to the per-app "Display over other apps" switch. */
        fun permissionIntent(context: Context): android.content.Intent =
            android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Compact pairing panel — no title, no body, no TextField.
 *
 * Layout (target ≈ 180dp tall on a 240dp-wide panel):
 *   [●][●][●][_][_][_]  [⌫]   ← 6 code-slot dots + inline backspace
 *   [1]  [2]  [3]               ┐
 *   [4]  [5]  [6]               ├ 3 × 24dp digit rows
 *   [7]  [8]  [9]               ┘
 *   [✕]  [0]  [PAIR]            ← action row, PAIR highlights when 6 digits ready
 *   status…                     ← single truncated line, only when non-empty
 */
@Composable
private fun PairingPanel(
    status: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val isRunning = status.isNotBlank() && !status.startsWith("✓") && !status.startsWith("✗")

    Column(
        modifier = Modifier
            .width(240.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(HaloColors.Bg)
            .padding(8.dp),
    ) {
        // Code slots row + inline backspace
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (0 until 6).forEach { i ->
                val filled = i < code.length
                val isCursor = i == code.length
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (filled) HaloColors.Accent.copy(alpha = 0.12f) else Color.Transparent)
                        .border(1.dp, if (isCursor) HaloColors.Accent else HaloColors.Line, RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (filled) Text("●", fontSize = 9.sp, color = HaloColors.Accent)
                }
            }
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(status, fontSize = 11.sp,
                color = if (status.startsWith("✗")) HaloColors.Bad else HaloColors.Fg,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(5.dp))

        // Digit rows 1-9
        listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9")).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { d ->
                    PadKey(d, enabled = code.length < 6 && !isRunning,
                        modifier = Modifier.weight(1f).height(24.dp)) {
                        if (code.length < 6 && !isRunning) code += d
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
        }

        // Action row: ⌫ | 0 | PAIR  (backspace replaces cancel — user exits via Back gesture)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            PadKey("⌫", enabled = code.isNotEmpty() && !isRunning,
                modifier = Modifier.weight(1f).height(24.dp)) {
                if (code.isNotEmpty()) code = code.dropLast(1)
            }
            PadKey("0", enabled = code.length < 6 && !isRunning,
                modifier = Modifier.weight(1f).height(24.dp)) {
                if (code.length < 6 && !isRunning) code += "0"
            }
            // PAIR key — accent-filled when 6 digits ready
            val pairReady = code.length == 6 && !isRunning
            var pairFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .weight(1f).height(24.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (pairReady) HaloColors.Accent else if (pairFocused) HaloColors.FocusTint else Color.Transparent)
                    .border(1.dp, if (pairReady || pairFocused) HaloColors.Accent else HaloColors.Line, RoundedCornerShape(3.dp))
                    .onFocusChanged { pairFocused = it.isFocused }
                    .clickable(enabled = pairReady) { onSubmit(code) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.adb_pair_submit),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (pairReady) HaloColors.Bg else HaloColors.Mute,
                )
            }
        }
    }
}

/** Single focusable key cell used in the compact NumPad. */
@Composable
private fun PadKey(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (focused) HaloColors.FocusTint else Color.Transparent)
            .border(1.dp, if (focused) HaloColors.Accent else HaloColors.Line, RoundedCornerShape(3.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) HaloColors.Fg else HaloColors.Mute,
        )
    }
}
