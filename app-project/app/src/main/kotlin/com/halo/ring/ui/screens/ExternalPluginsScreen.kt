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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.ring.R
import com.halo.ring.ui.Cta
import com.halo.ring.ui.FocusableRow
import com.halo.ring.ui.HaloColors
import com.halo.ring.ui.HaloType
import com.halo.ring.ui.LocalAppGraph
import com.halo.ring.ui.ScreenPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → External plugins (Doc/18 §8.2). Lists every discovered plugin app — its app name,
 * declared protocol version, action count, and a status indicator. A "REFRESH PLUGINS" CTA
 * forces a re-scan (otherwise the registry self-invalidates on package events with a 30 s TTL).
 *
 * No per-plugin disable toggle in v1 — out of scope per Doc/18 §12. If the wearer wants to stop
 * a plugin firing, they unbind its actions in the Action Picker.
 */
@Composable
fun ExternalPluginsScreen() {
    val graph = LocalAppGraph.current
    val plugins: List<com.halo.ring.plugin.Plugin> =
        if (graph != null) graph.pluginRegistry.plugins.collectAsState().value else emptyList()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(graph) {
        graph?.pluginRegistry?.let { reg ->
            withContext(Dispatchers.IO) { reg.getOrRefresh() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.external_plugins_title),
            style = HaloType.Title,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        Text(
            text = stringResource(R.string.external_plugins_subtitle),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(12.dp))

        if (plugins.isEmpty()) {
            Text(
                text = stringResource(R.string.external_plugins_count_zero),
                style = HaloType.Body.copy(color = HaloColors.Mute),
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
            )
        } else {
            for (plugin in plugins) {
                PluginRow(plugin)
            }
        }

        Spacer(Modifier.height(20.dp))
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            Cta(
                text = stringResource(R.string.external_plugins_refresh),
                onClick = {
                    if (refreshing) return@Cta
                    val reg = graph?.pluginRegistry ?: return@Cta
                    refreshing = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { reg.refresh() }
                        } finally {
                            refreshing = false
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.external_plugins_footer),
            style = HaloType.Caption,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

@Composable
private fun PluginRow(plugin: com.halo.ring.plugin.Plugin) {
    FocusableRow(onClick = { /* no-op: read-only list in v1 */ }) {
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            Text(plugin.appName, style = HaloType.Body)
            Text(
                text = plugin.packageName,
                style = HaloType.Caption.copy(color = HaloColors.Mute, fontSize = 12.sp),
                maxLines = 1,
            )
            val countLine = stringResource(R.string.external_plugins_actions_count, plugin.actions.size) +
                "  ·  " +
                stringResource(R.string.external_plugins_protocol_version, plugin.protocolVersion)
            Text(
                text = countLine,
                style = HaloType.Caption.copy(color = HaloColors.Mute, fontSize = 12.sp),
            )
        }
        if (plugin.queryError == null) {
            Text(
                stringResource(R.string.external_plugins_status_alive),
                style = HaloType.Caption.copy(color = HaloColors.Accent, fontSize = 12.sp),
            )
        } else {
            // Error state — surface in warn color but truncate.
            Text(
                stringResource(R.string.external_plugins_status_error, plugin.queryError!!.take(40)),
                style = HaloType.Caption.copy(color = HaloColors.Warn, fontSize = 12.sp),
                maxLines = 2,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.Line))
}
