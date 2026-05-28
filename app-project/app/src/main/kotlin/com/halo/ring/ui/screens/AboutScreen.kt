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
 * Settings → About. v0.4-slimmed (Doc/20 §4): 3 rows — version, detected device, license disclaimer.
 * GuidedTour was deleted; the row that re-opened it ("Show operation guide") is gone with it.
 */
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    detectedProfile: DeviceProfile,
) {
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.app_name_bilingual),
            style = HaloType.Title,
            color = HaloColors.Accent,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )

        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
        Spacer(Modifier.height(4.dp))

        ListRow(stringResource(R.string.about_app_version), "$versionName ($versionCode)")
        ListRow(stringResource(R.string.about_detected_device), stringResource(detectedProfile.labelRes()))

        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(20.dp))
    }
}

private fun DeviceProfile.labelRes() = when (this) {
    DeviceProfile.ROKID_GLASSES   -> R.string.about_device_rokid
    DeviceProfile.RAYNEO_X3PRO    -> R.string.about_device_rayneo
    DeviceProfile.GENERIC_ANDROID -> R.string.about_device_generic
}
