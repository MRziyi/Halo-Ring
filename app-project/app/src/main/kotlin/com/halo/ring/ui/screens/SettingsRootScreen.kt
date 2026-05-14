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
import androidx.compose.ui.unit.dp
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

/** The list of top-level settings sections. Order matters: most-likely-touched first. */
enum class SettingsSection(val title: String, val key: String) {
    PROFILES("Profiles & Gestures", "profiles"),
    SYSTEM_GESTURES("System Gestures", "system_gestures"),
    RING("Ring", "ring"),
    POWER("Power & Connection", "power"),
    FEEDBACK("Feedback", "feedback"),
    VITALS_PREFS("Vitals", "vitals_prefs"),
    LANGUAGE("Language", "language"),
    ADVANCED("Advanced", "advanced"),
    ABOUT("About", "about"),
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
                Text(section.title, style = HaloType.Body)
                Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        }
    }
}
