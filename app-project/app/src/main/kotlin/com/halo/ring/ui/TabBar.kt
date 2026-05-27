package com.halo.ring.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.halo.ring.R

/** The three top-level tabs of the app. Labels are looked up via stringResource at render
 *  time so they live in the current locale (Doc/08 §2 i18n; audit-2026-05-13s). */
enum class AppTab(val labelRes: Int) {
    VITALS(R.string.tab_vitals),
    SETTINGS(R.string.tab_settings),
    STATUS(R.string.tab_status),
}

/**
 * Top tab strip. Three labels, green underline on the active one. Custom (not Material `TabRow`)
 * to keep chrome minimal. Spans full width; no horizontal padding so the underline aligns with
 * screen edges naturally.
 */
@Composable
fun TabBar(
    selected: AppTab,
    modifier: Modifier = Modifier,
    onSelect: (AppTab) -> Unit = {},
) {
    // Track whether any tab is currently focused, so InAppFocusController can remap
    // NavPrev/NavNext (= SWIPE_UP/DOWN on the ring) to tab-cycling when on the strip
    // (Doc/08-ui-design.md §9.1.1 — the ring has no left/right swipes).
    var anyTabFocused by remember { mutableStateOf(false) }
    DisposableEffect(anyTabFocused) {
        android.util.Log.i("HaloFocus", "focusOnTabStrip = $anyTabFocused")
        InAppFocusController.focusOnTabStrip = anyTabFocused
        onDispose { /* leave flag as-is; next focus event will clear it */ }
    }

    // One FocusRequester per tab so we can programmatically move Compose focus to the new
    // active tab when the user cycles via NavPrev/NavNext from the ring (which calls
    // tabController.prev()/next() in HaloRingApp → state.tab changes). Without this, Compose
    // focus stayed on whichever tab Box originally had focus, so the haloFocus highlight + the
    // selected-tab underline could drift apart. Audit-2026-05-13s.
    val requesters = remember { AppTab.values().map { FocusRequester() } }
    LaunchedEffect(selected, anyTabFocused) {
        if (anyTabFocused) {
            try { requesters[selected.ordinal].requestFocus() } catch (_: Exception) {}
        }
    }
    // Burn-in 2026-05-27: anchor app's initial focus on the currently-selected tab. The wearer
    // can't tap to grab focus on AR glasses, so we MUST guarantee focus lands somewhere on launch
    // — otherwise SWIPE_UP/DOWN (NavPrev/NavNext) silently no-ops because there's no focused
    // element to traverse from. Previous attempt used `focusManager.moveFocus(Down)` from a
    // 80ms-delayed LaunchedEffect, but that was racy (sometimes the Compose tree wasn't ready in
    // time). Direct FocusRequester.requestFocus() is reliable because Compose guarantees the
    // requester is bound by the time LaunchedEffect runs.
    LaunchedEffect(Unit) {
        // Two-stage delay tolerates very fast launches AND first-frame layout costs.
        kotlinx.coroutines.delay(120)
        repeat(3) { attempt ->
            try {
                android.util.Log.i("HaloFocus", "TabBar initial requestFocus attempt=$attempt on tab=${selected.name}")
                requesters[selected.ordinal].requestFocus()
                android.util.Log.i("HaloFocus", "TabBar initial requestFocus completed (attempt=$attempt)")
                return@LaunchedEffect
            } catch (e: Exception) {
                android.util.Log.w("HaloFocus", "TabBar initial requestFocus attempt=$attempt failed: ${e.message}")
                kotlinx.coroutines.delay(100)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.values().forEachIndexed { i, tab ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(requesters[i])
                        .onFocusChanged {
                            // Any tab focused ⇒ we're "on the strip".
                            if (it.isFocused) anyTabFocused = true
                            else if (it.hasFocus.not()) {
                                // Recompute: are *any* siblings still focused? Cheap heuristic —
                                // when a single tab loses focus, if focus moved to another tab
                                // we'll get the new one's isFocused = true immediately; if it
                                // moved into content, no other tab will be focused. So just clear
                                // here after a microtask.
                                anyTabFocused = false
                            }
                        }
                        .clickable { onSelect(tab) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(tab.labelRes),
                        style = if (tab == selected) HaloType.TabActive else HaloType.Tab,
                    )
                }
            }
        }
        // 2 dp underline: grey under inactive segments, accent under the selected one.
        Row(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            AppTab.values().forEach { tab ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (tab == selected) HaloColors.Accent else HaloColors.Line),
                )
            }
        }
    }
}
