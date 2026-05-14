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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.ui.text.input.KeyboardType
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
 * System-overlay panel that lets the user type the 6-digit pairing code WITHOUT having to leave
 * the Settings → Wireless debugging → "Pair with code" dialog. The Android settings dialog
 * tears down its mDNS advertisement the moment it loses foreground focus, so a plain in-app
 * dialog doesn't work — by the time the user comes back to our app, the pairing service is
 * already gone.
 *
 * Owned LifecycleOwner: we keep our own [LifecycleRegistry] pinned at RESUMED for the
 * overlay's whole life. Hooking it to the Activity's lifecycle made the Compose tree stop
 * composing the moment the user switched to Settings, which made the overlay look like it
 * had vanished.
 *
 * Window flags: TYPE_APPLICATION_OVERLAY + NOT_TOUCH_MODAL so touches OUTSIDE the panel's
 * bounds fall through to whatever's underneath. Without NOT_TOUCH_MODAL the entire screen's
 * touch input gets eaten while the overlay is up.
 *
 * Requires `SYSTEM_ALERT_WINDOW`. Caller should check [hasPermission] first.
 */
class AdbPairingOverlay(private val appContext: Context) :
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
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
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
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
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
            // Width chosen to fit Rokid's mono 480 px content area at 1.5x density (≈ 320dp wide)
            // with margin; on RayNeo binocular 1280×480 (≈ 640dp per eye at 1x), this stays well
            // within the safe area. Doc/08 §2 caps useful width at ~320dp for single-eye displays.
            .width(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(HaloColors.Bg)
            .padding(16.dp)
            // verticalScroll so the panel never gets clipped on small AR displays even when the
            // text field + status text + NumPad all stack up.
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
    ) {
        Text(stringResource(R.string.adb_pair_title), style = HaloType.Title)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.adb_pair_body),
            style = HaloType.Caption,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            singleLine = true,
            enabled = !isRunning,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        // Audit-2026-05-13o: ring-navigable NumPad. AR glasses without an external keyboard
        // can't type into the field above; this 3x4 grid is fully reachable via Compose's
        // focus traversal (DPAD_UP/DOWN/LEFT/RIGHT from injected swipes or the temple's
        // TempleAction.Slide{Forward,Backward,Upwards,Downwards}). DPAD_CENTER = TAP commits.
        Spacer(Modifier.height(10.dp))
        NumberPad(
            enabled = !isRunning,
            onDigit = { d -> if (code.length < 6) code += d },
            onBackspace = { if (code.isNotEmpty()) code = code.dropLast(1) },
        )
        if (status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = HaloType.Body)
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, enabled = !isRunning) { Text(stringResource(R.string.adb_pair_cancel)) }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { onSubmit(code) },
                enabled = code.length == 6 && !isRunning,
            ) { Text(stringResource(R.string.adb_pair_submit)) }
        }
    }
}

/**
 * Ring-navigable 3x4 number pad: 1 2 3 / 4 5 6 / 7 8 9 / DEL 0 ENTER-style PAIR-area.
 * Each cell is a `clickable` focusable Box — DPAD_CENTER (injected by the ring as `TAP`)
 * fires its onClick. NavPrev/NavNext from ring SWIPE_UP/DOWN walks the grid via Compose
 * focus traversal.
 *
 * Layout chosen so the most-used digits (1-9) form a phone-style grid; row 4 has DEL
 * (backspace) and 0. The "PAIR" trigger lives outside the grid (in the parent's TextButton
 * row) so it's reachable after the digits are entered.
 */
@Composable
private fun NumberPad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", ""),  // backspace, zero, spacer (PAIR is in the parent row)
    )
    Column(modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { label ->
                    if (label.isEmpty()) {
                        Spacer(Modifier.weight(1f).aspectRatio(3.0f))
                    } else {
                        NumberPadKey(
                            label = label,
                            enabled = enabled,
                            modifier = Modifier.weight(1f).aspectRatio(3.0f),
                            onClick = {
                                if (label == "⌫") onBackspace() else onDigit(label[0])
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberPadKey(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) HaloColors.Accent else HaloColors.Line
    val bg = if (focused) HaloColors.FocusTint else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (enabled) HaloColors.Fg else HaloColors.Mute
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HaloType.Title.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = fg,
            ),
        )
    }
}
