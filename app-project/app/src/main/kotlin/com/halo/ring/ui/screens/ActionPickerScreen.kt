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
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.SettingsCatalog
import com.halo.ring.ui.hud.actionFriendlyText
import com.halo.ring.ui.hud.gestureFriendlyText
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.gesture.Gesture

/**
 * Settings → Profiles → <Profile> → <Gesture>: pick a [GlassAction] (mockup §3 E action picker).
 * Grouped by [SettingsCatalog.ActionGroup] so the user can scan past the categories they don't
 * care about. The currently bound action carries an accent prefix `● `.
 *
 * Returning the selection: [onActionSelected] fires with the chosen action; the caller pops the
 * stack (we don't auto-pop here so the consumer controls navigation).
 */
@Composable
fun ActionPickerScreen(
    gesture: Gesture,
    currentBinding: GlassAction,
    onActionSelected: (GlassAction) -> Unit = {},
) {
    // Audit-pass 2026-05-13t: query the active GlassActionMapper to find actions that have no
    // device-side implementation (e.g. RayNeo X3 Pro has no known Visual-AI / Translate / Chat
    // package yet). Grey those out + "(coming soon)" so users don't bind a silent no-op.
    val mapper = LocalAppGraph.current?.mapper
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = "${gestureFriendlyText(gesture)} →",
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )

        // Walk the catalog in order, emitting a small group header each time the group changes.
        var lastGroup: SettingsCatalog.ActionGroup? = null
        for (entry in SettingsCatalog.ENTRIES) {
            if (entry.group != lastGroup) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(entry.group.titleRes).uppercase(),
                    style = HaloType.Caption.copy(color = HaloColors.Mute),
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                )
                lastGroup = entry.group
            }
            val selected = entry.action == currentBinding
            val supported = mapper?.supports(entry.action) ?: true
            val entryText = actionFriendlyText(entry.action)
            FocusableRow(onClick = { if (supported) onActionSelected(entry.action) }) {
                Text(
                    text = (if (selected) "● " else "") + entryText,
                    style = HaloType.Body.copy(
                        color = when {
                            !supported                                            -> HaloColors.Mute
                            selected                                              -> HaloColors.Accent
                            entry.group == SettingsCatalog.ActionGroup.SYSTEM     -> HaloColors.Mute
                            else                                                  -> HaloColors.Fg
                        },
                    ),
                )
                when {
                    !supported -> Text(stringResource(R.string.common_coming_soon), style = HaloType.Caption.copy(color = HaloColors.Mute))
                    selected   -> Text(stringResource(R.string.action_picker_current), style = HaloType.Caption.copy(color = HaloColors.Mute))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.action_picker_footer),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}
