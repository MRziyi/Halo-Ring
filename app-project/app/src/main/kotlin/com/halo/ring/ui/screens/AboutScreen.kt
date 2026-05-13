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
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
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

        ListRow("App version", "$versionName ($versionCode)")
        ListRow("Detected device", detectedProfile.label())
        ListRow("BLE protocol source", "reverse-engineered v2 APK")
        ListRow("Phase-0 probe", "phase0/r08_probe.py")

        Spacer(Modifier.height(16.dp))
        Text(
            "Credits",
            style = HaloType.RowKey,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                appendLine("• Touch + gesture protocol reverse-engineered from `com.ring.r08remote` v2")
                appendLine("• Broader R02-family commands from `tahnok/colmi_r02_client`")
                appendLine("• RF03 SoC + firmware research: `atc1441/ATC_RF03_Ring`")
                appendLine("• Rokid platform docs: `buildwithfenna/rokid-docs`")
                appendLine("• Mercury SDK example: `Quad-Labs/RayDesk`")
            }.trim(),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        Text(
            "Documentation",
            style = HaloType.RowKey,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
        )
        Text(
            "Design docs at Doc/ in the source tree. Per-doc index in Doc/README.md.",
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

private fun DeviceProfile.label() = when (this) {
    DeviceProfile.ROKID_GLASSES   -> "Rokid Glasses"
    DeviceProfile.RAYNEO_X3PRO    -> "RayNeo X3 Pro"
    DeviceProfile.GENERIC_ANDROID -> "Generic Android (dev)"
}
