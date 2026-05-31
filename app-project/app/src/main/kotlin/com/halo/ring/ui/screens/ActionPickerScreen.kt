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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.gesture.Gesture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    // Audit-pass-x+1: also surface BEST_EFFORT (verified-bindable but unverified to actually fire
    // on this device) with a "(beta)" tag so the user knows it's exploratory.
    val graph = LocalAppGraph.current
    val mapper = graph?.mapper
    // Doc/18 plugin actions, refreshed when the picker opens. Reading directly from
    // pluginRegistry.plugins is reactive (StateFlow), so a refresh kicked off elsewhere updates
    // this composition automatically. Off-graph (preview / tests) → empty.
    val plugins: List<com.halo.ring.plugin.Plugin> =
        if (graph != null) graph.pluginRegistry.plugins.collectAsState().value else emptyList()
    LaunchedEffect(graph) {
        graph?.pluginRegistry?.let { reg ->
            // getOrRefresh is cheap if cache is fresh; otherwise re-scans. Do it on IO so the
            // PackageManager calls don't block the Compose composition.
            withContext(Dispatchers.IO) { reg.getOrRefresh() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = "${gestureFriendlyText(gesture)} →",
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )

        // ── Doc/18 §8.1 — EXTERNAL APPS group, pinned to the TOP (Zack 2026-05-31) ────────────
        // Plugin actions (e.g. Constellation) are the wearer's most-bound custom targets, so they
        // lead the picker rather than trailing the built-in catalog.
        Text(
            text = stringResource(R.string.action_group_external).uppercase(),
            style = HaloType.Caption.copy(color = HaloColors.Mute),
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
        )
        if (plugins.isEmpty()) {
            Text(
                text = stringResource(R.string.action_picker_external_empty),
                style = HaloType.Caption.copy(color = HaloColors.Mute, fontSize = 14.sp),
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
            )
        } else {
            for (plugin in plugins) {
                Text(
                    text = plugin.appName,
                    style = HaloType.Caption.copy(color = HaloColors.Mute, fontSize = 14.sp, letterSpacing = 1.sp),
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                )
                if (plugin.actions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.action_picker_external_no_actions),
                        style = HaloType.Caption.copy(color = HaloColors.Mute),
                        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 6.dp),
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
                    continue
                }
                for (pluginAction in plugin.actions) {
                    val bound = pluginAction.toBoundAction()
                    val selected = currentBinding is GlassAction.PluginAction &&
                        (currentBinding as GlassAction.PluginAction).pluginPackage == bound.pluginPackage &&
                        (currentBinding as GlassAction.PluginAction).actionId == bound.actionId
                    FocusableRow(onClick = { onActionSelected(bound) }) {
                        Text(
                            text = (if (selected) "● " else "") + pluginAction.label,
                            style = HaloType.Body.copy(
                                color = if (selected) HaloColors.Accent else HaloColors.Fg,
                            ),
                        )
                        if (selected) {
                            Text(stringResource(R.string.action_picker_current), style = HaloType.Caption.copy(color = HaloColors.Mute))
                        } else if (pluginAction.description != null) {
                            Text(
                                pluginAction.description,
                                style = HaloType.Caption.copy(color = HaloColors.Mute, fontSize = 12.sp),
                                maxLines = 1,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

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
            val level = mapper?.supportLevel(entry.action) ?: GlassActionMapper.SupportLevel.SUPPORTED
            val bindable = level != GlassActionMapper.SupportLevel.UNSUPPORTED
            val entryText = actionFriendlyText(entry.action)
            FocusableRow(onClick = { if (bindable) onActionSelected(entry.action) }) {
                Text(
                    text = (if (selected) "● " else "") + entryText,
                    style = HaloType.Body.copy(
                        color = when {
                            !bindable                                             -> HaloColors.Mute
                            selected                                              -> HaloColors.Accent
                            entry.group == SettingsCatalog.ActionGroup.SYSTEM     -> HaloColors.Mute
                            else                                                  -> HaloColors.Fg
                        },
                    ),
                )
                when {
                    level == GlassActionMapper.SupportLevel.UNSUPPORTED ->
                        Text(stringResource(R.string.common_coming_soon), style = HaloType.Caption.copy(color = HaloColors.Mute))
                    level == GlassActionMapper.SupportLevel.BEST_EFFORT ->
                        Text(stringResource(R.string.common_best_effort), style = HaloType.Caption.copy(color = HaloColors.Warn))
                    selected ->
                        Text(stringResource(R.string.action_picker_current), style = HaloType.Caption.copy(color = HaloColors.Mute))
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
