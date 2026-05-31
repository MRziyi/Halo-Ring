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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.R
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.ui.hud.actionFriendlyText
import com.halo.ring.ui.hud.gestureFriendlyText
import com.halo.ring.ui.hud.profileFriendlyText
import com.halo.ring.core.action.DefaultProfiles
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.action.KeyMapProfile
import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.GestureConfig

/**
 * Settings → Profiles → <Profile> (mockup §3 E). One row per [Gesture], showing the action it fires.
 * Tapping a row navigates to [ActionPickerScreen] to rebind it.
 *
 * Gestures that ALSO carry a system role (DOUBLE_TAP wakes the screen when off; LONG_PRESS sleeps
 * it in the Navigation profile) get a small badge — but stay **editable** (user 2026-05-29:
 * "有system绑定的要加个标记，但允许功能编辑"). TRIPLE_TAP=Screenshot is just a preset default, not
 * system-level (user 2026-05-29: "三击截屏不是系统级的操作，而是预设的自定义手势") — no badge. The base-4
 * (TAP/DOUBLE_TAP/swipes) only honour a custom binding in profiles with `useSystemKeyEvents=false`
 * (e.g. Media); elsewhere they pass through as fixed system KeyEvents.
 */
@Composable
fun ProfileEditorScreen(
    profile: KeyMapProfile,
    onGestureTapped: (Gesture) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = profileFriendlyText(profile),
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        val cfg = profile.gestureConfig
        Gesture.values().forEach { gesture ->
            if (gesture in HIDDEN_GESTURES) return@forEach
            val action = profile.actionFor(gesture)
            val roleRes = systemRoleRes(gesture, profile.id)
            // Combo gestures are inert while their group switch (手势组合设置) is off — grey them and
            // explain instead of letting the wearer bind something that can never fire (Zack 2026-05-30).
            val comboOff = comboDisabled(gesture, cfg)
            FocusableRow(onClick = { if (!comboOff) onGestureTapped(gesture) }) {
                // weight(1f) so the gesture name + badge take the left space and the right-hand value
                // can never overlap them (the "Combo off" tag in EN was overrunning — Zack 2026-05-31).
                Row(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        gestureFriendlyText(gesture),
                        style = HaloType.Body.copy(color = if (comboOff) HaloColors.Mute else HaloColors.Fg),
                    )
                    if (roleRes != null && !comboOff) {
                        Spacer(Modifier.width(6.dp))
                        // System-role badge — informational; the binding below is still editable.
                        Text(
                            text = stringResource(roleRes),
                            style = HaloType.Caption.copy(color = HaloColors.Accent, fontSize = 10.sp),
                        )
                    }
                }
                Text(
                    text = if (comboOff) stringResource(R.string.profile_combo_disabled)
                           else actionFriendlyText(action),
                    style = HaloType.RowVal.copy(
                        color = if (comboOff || action is GlassAction.None) HaloColors.Mute else HaloColors.Fg,
                        fontSize = if (comboOff) 11.sp else HaloType.RowVal.fontSize,
                    ),
                    maxLines = 1,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.profile_editor_footer),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

/** A short badge for gestures that also serve a system role (shown next to the gesture name).
 *  LONG_PRESS only sleeps in the Navigation/fallback profile (InteractionRouter gates it there). */
private fun systemRoleRes(g: Gesture, profileId: String): Int? = when (g) {
    Gesture.DOUBLE_TAP -> R.string.gesture_role_wake
    Gesture.LONG_PRESS -> if (profileId == DefaultProfiles.DEFAULT_FALLBACK_ID) R.string.gesture_role_sleep else null
    else -> null
}

/** True when [g] belongs to a combo group whose switch is OFF in [cfg] (Settings → 手势组合设置),
 *  so it can never fire and shouldn't be bindable. */
private fun comboDisabled(g: Gesture, cfg: GestureConfig): Boolean = when (g) {
    Gesture.TAP_SWIPE_UP, Gesture.TAP_SWIPE_DOWN -> !cfg.enableTapSwipe
    // 双击组合 gates DOUBLE_TAP_SWIPE *and* TRIPLE_TAP — both are double-tap extensions that only
    // fire while the 双击组合 hold-window is open (Zack 2026-05-31). Off → neither can fire.
    Gesture.DOUBLE_TAP_SWIPE_UP, Gesture.DOUBLE_TAP_SWIPE_DOWN, Gesture.TRIPLE_TAP -> !cfg.enableDoubleTapSwipe
    Gesture.LONG_PRESS_SWIPE_UP, Gesture.LONG_PRESS_SWIPE_DOWN, Gesture.DOUBLE_LONG_PRESS -> !cfg.awaitLongPressCombos
    else -> false
}

// QUADRUPLE_TAP (disabled) + WRIST_SHAKE (unrouted) — hidden from the editor.
private val HIDDEN_GESTURES = setOf(
    Gesture.QUADRUPLE_TAP,
    Gesture.WRIST_SHAKE,
)
