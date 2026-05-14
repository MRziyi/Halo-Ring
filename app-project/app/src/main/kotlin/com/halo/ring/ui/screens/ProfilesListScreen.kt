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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.R
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.ui.hud.profileFriendlyText
import com.halo.ring.core.action.KeyMapProfile

/**
 * Settings → Profiles & Gestures (mockup §3 D). One row per known profile; the active one carries
 * a small green dot. Tapping a row opens the [ProfileEditorScreen].
 */
@Composable
fun ProfilesListScreen(
    profiles: List<KeyMapProfile>,
    activeProfileId: String,
    onProfileSelected: (KeyMapProfile) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        profiles.forEach { profile ->
            FocusableRow(onClick = { onProfileSelected(profile) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (profile.id == activeProfileId) HaloColors.Accent else HaloColors.Line),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(profileFriendlyText(profile), style = HaloType.Body)
                }
                Text(
                    text = stringResource(R.string.profiles_bindings_count, profile.map.size),
                    style = HaloType.Body.copy(color = HaloColors.Mute, fontSize = 13.sp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.profiles_list_footer),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}
