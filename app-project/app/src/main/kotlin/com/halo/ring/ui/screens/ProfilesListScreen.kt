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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.ui.hud.profileFriendlyText
import com.halo.ring.core.action.DefaultProfiles
import com.halo.ring.core.action.KeyMapProfile
import com.halo.ring.core.gesture.SystemGestures

/**
 * Settings → Profiles & Gestures (mockup §3 D). One row per known profile; the active one carries
 * a small green dot. Tapping a row opens the [ProfileEditorScreen].
 */
@Composable
fun ProfilesListScreen(
    profiles: List<KeyMapProfile>,
    activeProfileId: String,
    onProfileSelected: (KeyMapProfile) -> Unit = {},
    onSystemGesturesTapped: () -> Unit = {},
    onTestArenaTapped: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        // Profiles first (the headline). System Gestures + Test Arena are sub-entries placed BELOW
        // Restore (2026-05-29) — they're gesture-config extras, not the primary content here.
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

        // Restore defaults — re-seeds the LIVE profile + system-gesture flows from DefaultProfiles
        // (which the persistence layer then writes through to DataStore). Must reset the in-memory
        // graph flows, not just the store — the UI + service read graph.profilesFlow, so clearing
        // only the store left stale data on screen. Two-tap confirm to avoid losing edits.
        val graph = LocalAppGraph.current
        var confirm by remember { mutableStateOf(false) }
        FocusableRow(onClick = {
            if (!confirm) confirm = true
            else {
                confirm = false
                graph.profilesFlow.value = DefaultProfiles.ALL
                graph.systemGesturesFlow.value = SystemGestures()
            }
        }) {
            Text(
                stringResource(if (confirm) R.string.profiles_restore_confirm else R.string.profiles_restore_defaults),
                style = HaloType.Body.copy(color = if (confirm) HaloColors.Warn else HaloColors.Accent),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))

        // Gesture-config extras, set apart from profile management above with a gap + section
        // header so Restore doesn't read as glued to them (Zack 2026-05-29).
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.profiles_gestures_header).uppercase(),
            style = HaloType.Caption.copy(color = HaloColors.Mute),
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
        )
        FocusableRow(onClick = onSystemGesturesTapped) {
            Text(stringResource(R.string.settings_section_system_gestures), style = HaloType.Body)
            Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        FocusableRow(onClick = onTestArenaTapped) {
            Text(stringResource(R.string.settings_section_test_arena), style = HaloType.Body)
            Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.profiles_list_footer),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}
