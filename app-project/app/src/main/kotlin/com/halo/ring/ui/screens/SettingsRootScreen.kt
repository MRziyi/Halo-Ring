package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.halo.ring.R
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.ScreenPadding

/**
 * v0.4 C3 (Doc/20 §7): five top-level groups instead of 13 flat items. Tap a group to drill into
 * the items inside.
 */
enum class SettingsGroup(val titleRes: Int) {
    RING(R.string.settings_group_ring),
    VITALS(R.string.settings_group_vitals),
    GESTURES(R.string.settings_group_gestures),
    PLUGINS(R.string.settings_group_plugins),
    MORE(R.string.settings_group_more),
}

/** Leaf entries under each group. Order matters: most-likely-touched first. */
enum class SettingsSection(val titleRes: Int, val group: SettingsGroup) {
    // Ring group — covers pair, info, find, forget, reconnect (all surfaced inside RingScreen).
    RING(R.string.settings_section_ring, SettingsGroup.RING),

    // Vitals group — dashboard + prefs (sport session + spatial features land here in C5/C6).
    VITALS(R.string.settings_section_vitals, SettingsGroup.VITALS),
    VITALS_PREFS(R.string.settings_section_vitals_prefs, SettingsGroup.VITALS),

    // Gestures group — the value-add UI. None of these get deleted.
    PROFILES(R.string.settings_section_profiles, SettingsGroup.GESTURES),
    SYSTEM_GESTURES(R.string.settings_section_system_gestures, SettingsGroup.GESTURES),
    TEST_ARENA(R.string.settings_section_test_arena, SettingsGroup.GESTURES),

    // Plugins group — Doc/18 plugin protocol (Constellation is the first client).
    EXTERNAL_PLUGINS(R.string.settings_section_external_plugins, SettingsGroup.PLUGINS),

    // More group — less-frequently-touched config.
    POWER(R.string.settings_section_power, SettingsGroup.MORE),
    FEEDBACK(R.string.settings_section_feedback, SettingsGroup.MORE),
    LANGUAGE(R.string.settings_section_language, SettingsGroup.MORE),
    STATUS(R.string.settings_section_status, SettingsGroup.MORE),
    ADVANCED(R.string.settings_section_advanced, SettingsGroup.MORE),
    ABOUT(R.string.settings_section_about, SettingsGroup.MORE),
}

/** Root: 5 group rows. Drilling in shows [SettingsGroupScreen] — unless the group has exactly
 *  one member, in which case we navigate straight to that leaf (avoids the redundant "Ring → Ring"
 *  / "Plugins → External plugins" double-tap the user hit on glasses). */
@Composable
fun SettingsRootScreen(
    onSectionSelected: (SettingsSection) -> Unit = {},
    onGroupSelected: (SettingsGroup) -> Unit = {},
) {
    // Surface the plugin count on the Plugins group row (audit-pass 2026-05-24 + Doc/20 §7).
    val graph = LocalAppGraph.current
    val plugins: List<com.halo.ring.plugin.Plugin> =
        if (graph != null) graph.pluginRegistry.plugins.collectAsState().value else emptyList()

    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        SettingsGroup.values().forEach { group ->
            val members = SettingsSection.values().filter { it.group == group }
            FocusableRow(onClick = {
                // Single-member group → skip the group screen, go straight to the leaf.
                if (members.size == 1) onSectionSelected(members.first()) else onGroupSelected(group)
            }) {
                Text(stringResource(group.titleRes), style = HaloType.Body)
                if (group == SettingsGroup.PLUGINS && plugins.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_external_plugins_summary, plugins.size),
                        style = HaloType.Caption.copy(color = HaloColors.Mute),
                    )
                }
                Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        }
    }
}

/** Group sub-screen: lists the [SettingsSection]s that belong to [group]. */
@Composable
fun SettingsGroupScreen(
    group: SettingsGroup,
    onSectionSelected: (SettingsSection) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        SettingsSection.values()
            .filter { it.group == group }
            .forEach { section ->
                FocusableRow(onClick = { onSectionSelected(section) }) {
                    Text(stringResource(section.titleRes), style = HaloType.Body)
                    Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
            }
    }
}
