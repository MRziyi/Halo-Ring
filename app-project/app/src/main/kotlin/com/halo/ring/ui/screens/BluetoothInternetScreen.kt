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
import androidx.compose.ui.unit.sp
import com.halo.ring.R
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloSwitch
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.ScreenPadding

/**
 * Settings → Bluetooth internet. The phone shares its data over Bluetooth (PAN); Android resets the
 * per-device "Internet access" toggle on every reboot and exposes no non-privileged API to set it,
 * so we replay the user's manual tap via the accessibility UI flow.
 *
 *  - CONNECT NOW: run the flow once.
 *  - Auto on boot: re-run it on every boot (the service does this when the agent is up).
 *  - Open Android Settings: jump to the system Settings app.
 */
@Composable
fun BluetoothInternetScreen(
    autoBootOn: Boolean,
    onToggleAutoBoot: () -> Unit = {},
    onConnectNow: () -> Unit = {},
    onOpenAndroidSettings: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.bt_internet_title),
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        Text(
            text = stringResource(R.string.bt_internet_body),
            style = HaloType.Caption.copy(color = HaloColors.Mute),
            modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 8.dp),
        )

        ActionRowBt(
            title = stringResource(R.string.bt_internet_connect_now),
            description = stringResource(R.string.bt_internet_connect_now_desc),
            onClick = onConnectNow,
        )

        ToggleRowBt(
            title = stringResource(R.string.bt_internet_auto_boot),
            description = stringResource(R.string.bt_internet_auto_boot_desc),
            on = autoBootOn,
            onToggle = onToggleAutoBoot,
        )

        Spacer(Modifier.height(16.dp))

        ActionRowBt(
            title = stringResource(R.string.bt_internet_open_android_settings),
            description = stringResource(R.string.bt_internet_open_android_settings_desc),
            onClick = onOpenAndroidSettings,
        )
    }
}

@Composable
private fun ToggleRowBt(title: String, description: String, on: Boolean, onToggle: () -> Unit) {
    FocusableRow(onClick = onToggle) {
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            Text(title, style = HaloType.Body)
            Text(description, style = HaloType.Caption.copy(fontSize = 11.sp))
        }
        HaloSwitch(on = on)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}

@Composable
private fun ActionRowBt(title: String, description: String, onClick: () -> Unit) {
    FocusableRow(onClick = onClick) {
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            Text(title, style = HaloType.Body)
            Text(description, style = HaloType.Caption.copy(fontSize = 11.sp))
        }
        Text("›", style = HaloType.Body.copy(color = HaloColors.Mute))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}
