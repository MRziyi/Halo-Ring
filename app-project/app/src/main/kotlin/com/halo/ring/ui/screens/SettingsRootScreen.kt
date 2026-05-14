package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** The list of top-level settings sections. Order matters: most-likely-touched first.
 *  Title is a string-resource id resolved at render time so it follows the current locale. */
enum class SettingsSection(val titleRes: Int, val key: String) {
    PROFILES(R.string.settings_section_profiles, "profiles"),
    SYSTEM_GESTURES(R.string.settings_section_system_gestures, "system_gestures"),
    RING(R.string.settings_section_ring, "ring"),
    POWER(R.string.settings_section_power, "power"),
    FEEDBACK(R.string.settings_section_feedback, "feedback"),
    VITALS_PREFS(R.string.settings_section_vitals_prefs, "vitals_prefs"),
    LANGUAGE(R.string.settings_section_language, "language"),
    TEST_ARENA(R.string.settings_section_test_arena, "test_arena"),
    ADVANCED(R.string.settings_section_advanced, "advanced"),
    ABOUT(R.string.settings_section_about, "about"),
}

/**
 * Job 2: configuration root. Single column of section names. Drilling in lands on detail screens.
 */
@Composable
fun SettingsRootScreen(
    focusedIndex: Int = 0,
    onSectionSelected: (SettingsSection) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        SettingsSection.values().forEachIndexed { i, section ->
            FocusableRow(
                focused = i == focusedIndex,
                onClick = { onSectionSelected(section) },
            ) {
                Text(stringResource(section.titleRes), style = HaloType.Body)
                Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        }
    }
}
