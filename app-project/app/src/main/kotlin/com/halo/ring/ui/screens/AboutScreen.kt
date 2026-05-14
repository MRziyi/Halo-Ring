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
import com.halo.ring.ui.ListRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding
import com.halo.ring.core.DeviceProfile

/**
 * Settings → About (mockup §3 K). Read-only summary: version, detected device profile, BLE-protocol
 * source, credits, link to docs. The link rows are stub-only (no IntentResolver) — implementing
 * deep-link to a webview is B7-style follow-up.
 */
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    detectedProfile: DeviceProfile,
    onShowGuide: () -> Unit = {},
) {
    // No verticalScroll here — the outer host (HaloRingApp's content area) already wraps
    // sub-screens in a vertical scroll. Nesting two scrollables triggers the
    // "infinity maximum height constraints" measurement crash (audit-2026-05-13q).
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(top = 4.dp)) {
        // Brand block — bilingual name + slogan + byline. Pure typography, no logo image
        // (the launcher icon already carries the visual identity).
        Text(
            text = stringResource(R.string.app_name_bilingual),
            style = HaloType.Title,
            color = HaloColors.Accent,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        Text(
            text = stringResource(R.string.app_tagline),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Text(
            text = stringResource(R.string.app_byline),
            style = HaloType.Caption,
            color = HaloColors.Mute,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
        )

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        Spacer(Modifier.height(8.dp))

        ListRow(stringResource(R.string.about_app_version), "$versionName ($versionCode)")
        ListRow(stringResource(R.string.about_detected_device), stringResource(detectedProfile.labelRes()))
        ListRow(stringResource(R.string.about_protocol_source), stringResource(R.string.about_protocol_source_value))
        ListRow(stringResource(R.string.about_phase0_probe), "phase0/r08_probe.py")
        ListRow(stringResource(R.string.about_show_guide), "›", onClick = onShowGuide)

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.about_credits_label),
            style = HaloType.RowKey,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.about_credits_body),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        Text(
            stringResource(R.string.about_source_label),
            style = HaloType.RowKey,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
        )
        Text(
            text = stringResource(R.string.about_source_body),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        Text(
            stringResource(R.string.about_docs_label),
            style = HaloType.RowKey,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
        )
        Text(
            stringResource(R.string.about_docs_body),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(20.dp))
    }
}

private fun DeviceProfile.labelRes() = when (this) {
    DeviceProfile.ROKID_GLASSES   -> R.string.about_device_rokid
    DeviceProfile.RAYNEO_X3PRO    -> R.string.about_device_rayneo
    DeviceProfile.GENERIC_ANDROID -> R.string.about_device_generic
}
