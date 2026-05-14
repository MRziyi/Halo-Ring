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

/**
 * Settings → Language. Three radio-style choices: System / English / Chinese. Tap-to-select;
 * change applies immediately (AppCompatDelegate.setApplicationLocales recreates the Activity,
 * Compose rebinds with the new locale's strings).
 *
 * Design-conformance per Doc/03 + Doc/08:
 *  - All choices reachable via ring SWIPE_UP/DOWN (FocusableRow + DPAD focus traversal)
 *  - Single mint-green accent on the selected row's left bar (haloFocus pattern)
 *  - Font ≥ 16 sp throughout
 *  - No `pointerInput`, no multi-touch
 */
@Composable
fun LanguageScreen(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(HaloColors.Bg)) {
        Column(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp)) {
            Text(stringResource(R.string.language_title), style = HaloType.Title)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.language_description),
                style = HaloType.Caption,
            )
        }
        AppLanguage.values().forEach { lang ->
            LanguageRow(
                lang = lang,
                selected = lang == current,
                onClick = { onSelect(lang) },
            )
        }
    }
}

@Composable
private fun LanguageRow(
    lang: AppLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FocusableRow(focused = selected, onClick = onClick) {
        // Resolved via stringResource so the language label itself is localised — e.g. when the
        // current locale is zh, "Follow system" reads as "跟随系统".
        val label = stringResource(
            when (lang) {
                AppLanguage.SYSTEM -> R.string.language_follow_system
                AppLanguage.EN     -> R.string.language_english
                AppLanguage.ZH     -> R.string.language_chinese
            }
        )
        Text(label, style = HaloType.Body)
        Text(
            if (selected) "●" else "○",
            style = HaloType.Body.copy(
                color = if (selected) HaloColors.Accent else HaloColors.Mute,
            ),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}
