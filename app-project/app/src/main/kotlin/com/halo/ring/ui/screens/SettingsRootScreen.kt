package com.halo.ring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

/**
 * The MORE-tab sections, as a FLAT list (2026-05-29 reorg). Ring / Power settings live on the RING
 * tab and Vitals settings on the VITALS tab. **System Gestures + Test Arena now live inside
 * Profiles & Gestures**, **External Plugins lives inside Advanced**, and Connection Status was
 * folded into Ring details — so MORE is just the handful of genuinely top-level entries. Enum order
 * = display order.
 */
enum class SettingsSection(val titleRes: Int) {
    PROFILES(R.string.settings_section_profiles),
    FEEDBACK(R.string.settings_section_feedback),
    BT_INTERNET(R.string.settings_section_bt_internet),
    LANGUAGE(R.string.settings_section_language),
    ADVANCED(R.string.settings_section_advanced),
    ABOUT(R.string.settings_section_about),
}

/** MORE-tab content: a single flat list of sections; tap a row to drill into its screen. */
@Composable
fun SettingsRootScreen(
    onSectionSelected: (SettingsSection) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        SettingsSection.values().forEach { section ->
            FocusableRow(onClick = { onSectionSelected(section) }) {
                Text(stringResource(section.titleRes), style = HaloType.Body)
                Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        }
    }
}
