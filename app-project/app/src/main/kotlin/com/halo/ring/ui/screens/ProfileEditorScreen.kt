package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.halo.ring.R
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.ui.hud.actionFriendlyText
import com.halo.ring.ui.hud.gestureFriendlyText
import com.halo.ring.ui.hud.profileFriendlyText
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.action.KeyMapProfile
import com.halo.ring.core.gesture.Gesture

/**
 * Settings → Profiles → <Profile> (mockup §3 E). 12 rows, one per [Gesture], showing what action
 * fires when the user does it. Tapping a row navigates to [ActionPickerScreen] for that gesture.
 *
 * System slots (TRIPLE_TAP / QUADRUPLE_TAP / LONG_PRESS_SWIPE_DOWN / DOUBLE_LONG_PRESS) are listed
 * but greyed out — they're intercepted by the [com.halo.ring.core.gesture.InteractionRouter]'s
 * system layer before the profile is consulted (Doc/05 §5). The user can rebind those in the
 * **System Gestures** screen instead.
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
        val systemValueText = stringResource(R.string.profile_editor_system_value)
        Gesture.values().forEach { gesture ->
            val isSystem = gesture in SYSTEM_SLOTS
            val action = profile.actionFor(gesture)
            // Audit-pass 2026-05-13t: SettingsCatalog.entryFor was returning a hard-coded English
            // `friendly` String which never got localised. Now we always go through
            // [actionFriendlyText], which routes via R.string.action_* — same source of truth
            // used by the HUD's GestureRecognised pill and the Test Arena rows.
            val valueText = if (isSystem) systemValueText else actionFriendlyText(action)
            FocusableRow(onClick = { if (!isSystem) onGestureTapped(gesture) }) {
                Text(gestureFriendlyText(gesture), style = HaloType.Body)
                Text(
                    text = valueText,
                    style = HaloType.RowVal.copy(
                        color = when {
                            isSystem -> HaloColors.Mute
                            action is GlassAction.None -> HaloColors.Mute
                            else -> HaloColors.Fg
                        },
                    ),
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

private val SYSTEM_SLOTS = setOf(
    Gesture.TRIPLE_TAP,
    Gesture.QUADRUPLE_TAP,
    Gesture.LONG_PRESS_SWIPE_DOWN,
    Gesture.DOUBLE_LONG_PRESS,
)
