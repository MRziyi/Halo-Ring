package com.halo.ring.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.halo.ring.di.RingInfo
import com.halo.ring.ui.screens.AboutScreen
import com.halo.ring.ui.screens.ActionPickerScreen
import com.halo.ring.ui.screens.AdvancedAction
import com.halo.ring.ui.screens.AdvancedPrefs
import com.halo.ring.ui.screens.AdvancedScreen
import com.halo.ring.ui.screens.BluetoothInternetScreen
import com.halo.ring.ui.screens.AppLanguage
import com.halo.ring.ui.screens.LanguageScreen
import com.halo.ring.ui.screens.FeedbackPrefField
import com.halo.ring.ui.screens.FeedbackPrefs
import com.halo.ring.ui.screens.FeedbackScreen
import com.halo.ring.ui.screens.GesturePickerScreen
import com.halo.ring.ui.screens.HomeDashboardScreen
import com.halo.ring.ui.screens.HomeTab
import com.halo.ring.ui.screens.HudPosition
import com.halo.ring.ui.screens.PowerConnectionScreen
import com.halo.ring.ui.screens.ProfileEditorScreen
import com.halo.ring.ui.screens.ProfilesListScreen
import com.halo.ring.ui.screens.RingScreen
import com.halo.ring.ui.screens.SettingsSection
import com.halo.ring.ui.screens.StatusScreen
import com.halo.ring.ui.screens.StatusState
import com.halo.ring.ui.screens.SystemGesturesScreen
import com.halo.ring.ui.screens.VitalsPrefs
import com.halo.ring.ui.screens.VitalsPrefsScreen
import com.halo.ring.ui.screens.VitalsState
import com.halo.ring.core.DeviceProfile
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.action.KeyMapProfile
import com.halo.ring.core.gesture.SystemGestures

/**
 * The root composable.
 *
 * **Home model** (2026-05-29 redesign):
 * - Root is the 3-tab [HomeDashboardScreen] (RING · VITALS · MORE), sized for the glasses' wide,
 *   short canvas: the tab strip is fixed and each tab fits one screen with no vertical scrolling.
 * - RING leads (reconnect / find — the most-used actions); VITALS is the compact HR/SpO₂ surface;
 *   MORE is the full settings tree. Drilling into a MORE group/leaf pushes a [SubScreen], which
 *   renders in a scrollable branch; the home itself never scrolls.
 * - HUD overlay (owned by HaloRingService) is the daily UX surface; the Activity is opened
 *   occasionally for editing.
 * - No InAppFocusController. The base ring gestures dispatch via [ActivitySystemKeyDispatcher] as
 *   system KeyEvents; Compose's standard FocusManager handles DPAD navigation natively.
 */
@Composable
fun HaloRingApp(
    initial: AppState = AppState(),
    /** Active home tab. Single source of truth is `AppGraph.homeTabIndexFlow`, driven by the in-app
     *  LONG_PRESS gesture (routed via the service) plus focus-overflow cycling. */
    homeTab: HomeTab = HomeTab.RING,
    /** Called when focus overflows the active tab (DPAD_DOWN past the last item or DPAD_UP past
     *  the first). The host writes the chosen tab to `AppGraph.homeTabIndexFlow`. */
    onSelectTab: (HomeTab) -> Unit = {},
    profiles: List<KeyMapProfile> = emptyList(),
    activeProfileId: String = "",
    systemGestures: SystemGestures = SystemGestures(),
    ringInfo: RingInfo = RingInfo(),
    /** v0.4 C4 — SPEC v3 capability bitmap; threads into Vitals + Ring sub-screens. */
    ringCapabilities: Set<String> = emptySet(),
    advancedPrefs: AdvancedPrefs = AdvancedPrefs(),
    vitalsPrefs: VitalsPrefs = VitalsPrefs(),
    deviceProfile: DeviceProfile = DeviceProfile.GENERIC_ANDROID,
    versionName: String = "0.1.0",
    versionCode: Int = 1,
    onPrefsChanged: (FeedbackPrefs) -> Unit = {},
    onProfileUpdated: (KeyMapProfile) -> Unit = {},
    onSystemGesturesChanged: (SystemGestures) -> Unit = {},
    onAdvancedPrefsChanged: (AdvancedPrefs) -> Unit = {},
    onVitalsPrefsChanged: (VitalsPrefs) -> Unit = {},
    onAdvancedAction: (AdvancedAction) -> Unit = {},
    /** Current app-locale override; the LanguageScreen renders this as the selected row. */
    currentLanguage: AppLanguage = AppLanguage.SYSTEM,
    /** User picked a language from Settings → Language; caller persists + applies. */
    onLanguageSelected: (AppLanguage) -> Unit = {},
    /** When the back stack is empty and the user does Back, leave the app and return
     *  to the system launcher. Wired by [MainActivity] to `moveTaskToBack(true)`. */
    onExitToSystem: () -> Unit = {},
) {
    HaloRingTheme {
        var state by remember { mutableStateOf(initial) }
        // Burn-in 2026-05-27 fix: `initial` carries the externally-driven snapshots
        // (StatusBarState / VitalsState / StatusState / feedbackPrefs) that MainActivity rebuilds
        // every time ringInfoFlow / vitalsSnapshotFlow / feedbackPrefs flows emit. Without this
        // sync, `state` was captured once on first composition and never refreshed.
        androidx.compose.runtime.LaunchedEffect(
            initial.statusBar, initial.vitals, initial.status, initial.feedbackPrefs,
        ) {
            state = state.copy(
                statusBar = initial.statusBar,
                vitals = initial.vitals,
                status = initial.status,
                feedbackPrefs = initial.feedbackPrefs,
            )
        }

        val focusManager = LocalFocusManager.current

        // On AR glasses there's no touchscreen — the wearer can't tap to grab focus on the first
        // focusable element. Without an initial focus owner, the temple touchpad's KeyEvents
        // (Rokid fires KEYCODE_ENTER on click + DPAD_* on swipe) have nothing to act on, so the
        // UI appears frozen. We anchor focus into the content via a FocusRequester on a
        // focusGroup() — requesting focus on a group delegates to its first focusable child.
        // This is the reliable idiom; `focusManager.moveFocus(Down)` no-ops when there's no
        // current focus owner (the exact "ring 点不出来" bug Doc/20 §2.1 calls out).
        val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        // True once a content element actually holds focus. A single requestFocus() silently no-op'd
        // on cold launch (first-frame race), leaving the RING tab with no cursor until a tab switch
        // re-fired the effect — so we keep requesting until focus is *confirmed* landed (Zack
        // 2026-05-29). Tracked via onFocusChanged on the focus-group column below.
        var contentHasFocus by remember { mutableStateOf(false) }
        // When focus overflowed UPward into a previous tab, we want focus to land on the *last*
        // content element of the new tab (not the first), so the user's swipe-up feels continuous.
        // Set by the onKeyEvent handler below; honoured by the LaunchedEffect after focus lands.
        var pendingFocusEnd by remember { mutableStateOf(false) }
        val topKey = state.navStack.lastOrNull()?.let { it::class.simpleName } ?: "root"
        // Re-anchor focus on sub-screen change AND on home-tab change (long-press switches tabs
        // without touching the nav stack) so focus lands on the new tab's first content element.
        androidx.compose.runtime.LaunchedEffect(topKey, homeTab) {
            contentHasFocus = false
            repeat(12) {
                if (contentHasFocus) {
                    // If we got here by overflowing UPward into the previous tab, walk the focus
                    // down until it can't move — lands on the last content element.
                    if (pendingFocusEnd) {
                        while (focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) { /* keep going */ }
                        pendingFocusEnd = false
                    }
                    return@LaunchedEffect          // confirmed — stop (don't re-yank)
                }
                try { contentFocus.requestFocus() } catch (_: Throwable) {}
                kotlinx.coroutines.delay(120)
            }
        }

        fun push(s: SubScreen) { state = state.copy(navStack = state.navStack + s) }
        fun pop() { state = state.copy(navStack = state.navStack.dropLast(1)) }

        // DOUBLE_TAP from the ring fires KEYCODE_BACK at the system level; Android dispatches it
        // to the Activity's onBackPressed (and Compose has an onBackPressedDispatcher hook).
        // We hook onto LocalOnBackPressedDispatcherOwner so the Compose stack can pop sub-screens
        // before Android's default (= finish the Activity) takes effect.
        val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
            ?.onBackPressedDispatcher
        androidx.compose.runtime.DisposableEffect(backDispatcher) {
            val cb = object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (state.navStack.isNotEmpty()) {
                        state = state.copy(navStack = state.navStack.dropLast(1))
                    } else {
                        // Root level — leave the app (return to system launcher).
                        onExitToSystem()
                    }
                }
            }
            backDispatcher?.addCallback(cb)
            onDispose { cb.remove() }
        }

        Box(modifier = Modifier.fillMaxSize().background(HaloColors.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(HaloColors.Bg),
        ) {
            StatusBar(
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp),
                connected = state.statusBar.connected,
                ringId = state.statusBar.ringId,
                batteryPct = state.statusBar.batteryPct,
                charging = state.statusBar.charging,
                currentMode = state.statusBar.currentMode,
            )
            Spacer(Modifier.height(8.dp))

            val top = state.navStack.lastOrNull()
            // Map a [SettingsSection] to its target [SubScreen] (the MORE-tab flat list).
            val sectionToSubScreen: (SettingsSection) -> SubScreen = { section ->
                when (section) {
                    SettingsSection.PROFILES    -> SubScreen.Profiles
                    SettingsSection.FEEDBACK    -> SubScreen.Feedback
                    SettingsSection.BT_INTERNET -> SubScreen.BluetoothInternet
                    SettingsSection.LANGUAGE    -> SubScreen.Language
                    SettingsSection.ADVANCED    -> SubScreen.Advanced
                    SettingsSection.ABOUT       -> SubScreen.About
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { contentHasFocus = it.hasFocus }
                    .focusRequester(contentFocus)
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        // Focus-overflow tab switching (Zack 2026-05-29). We use **preview** + explicit
                        // [focusManager.moveFocus] so the cycle ONLY fires when focus is genuinely at
                        // the boundary: try to move within the tab first; if it moved, just consume
                        // (we did the same job as the default DPAD handler). Only when moveFocus
                        // returns false — meaning there's no focusable above/below — do we cycle
                        // tabs. The earlier `onKeyEvent` approach fired before the focus search and
                        // switched tabs on every swipe — the bug Zack hit.
                        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (top != null) return@onPreviewKeyEvent false
                        val n = HomeTab.values().size
                        when (event.key) {
                            androidx.compose.ui.input.key.Key.DirectionDown -> {
                                if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) {
                                    onSelectTab(HomeTab.values()[(homeTab.ordinal + 1) % n])
                                    pendingFocusEnd = false
                                }
                                true   // always consume DOWN on home (we either moved or cycled)
                            }
                            androidx.compose.ui.input.key.Key.DirectionUp -> {
                                if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)) {
                                    onSelectTab(HomeTab.values()[(homeTab.ordinal - 1 + n) % n])
                                    pendingFocusEnd = true
                                }
                                true
                            }
                            else -> false   // left/right + others: default behaviour
                        }
                    },
            ) {
                if (top == null) {
                    // The 3-tab home (RING · VITALS · MORE). Fixed tab strip, NO vertical scroll —
                    // each tab fits the glasses' short canvas. Drilling into a MORE-tab group/leaf
                    // pushes a SubScreen, which renders in the scrollable branch below.
                    HomeDashboardScreen(
                        homeTab = homeTab,
                        vitals = state.vitals,
                        ringInfo = ringInfo,
                        capabilities = ringCapabilities,
                        onOpenRingDetails = { push(SubScreen.Ring) },
                        onOpenPower = { push(SubScreen.Power) },
                        onOpenVitalsSettings = { push(SubScreen.VitalsPrefs) },
                        onSectionSelected = { section -> push(sectionToSubScreen(section)) },
                    )
                } else {
                  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (top) {
                    SubScreen.StatusInfo -> StatusScreen(
                        state.status.copy(
                            connected = ringInfo.connected,
                            rssiDbm = ringInfo.rssiDbm,
                            connIntervalMs = ringInfo.estimatedConnIntervalMs,
                            intervalMode = ringInfo.intervalMode,
                            profileName = profiles.firstOrNull { it.id == activeProfileId }?.name
                                ?: state.status.profileName,
                            activeBackend = ringInfo.activeBackendId,
                        )
                    )

                    is SubScreen.Feedback -> FeedbackScreen(
                        prefs = state.feedbackPrefs,
                        onToggle = { field ->
                            val p = state.feedbackPrefs
                            val updated = when (field) {
                                FeedbackPrefField.GESTURE_HINT             -> p.copy(gestureHintHud = !p.gestureHintHud)
                                FeedbackPrefField.CLICK_SOUND              -> p.copy(clickSoundOnModeSwitch = !p.clickSoundOnModeSwitch)
                                FeedbackPrefField.RING_LED                 -> p.copy(ringLedFeedback = !p.ringLedFeedback)
                                FeedbackPrefField.AUTO_HINT_AFTER_PAIRING  -> p.copy(autoHintAfterPairing = !p.autoHintAfterPairing)
                                else -> p
                            }
                            state = state.copy(feedbackPrefs = updated)
                            onPrefsChanged(updated)
                        },
                        onCyclePosition = {
                            val positions = HudPosition.values()
                            val next = positions[(state.feedbackPrefs.hudPosition.ordinal + 1) % positions.size]
                            val p = state.feedbackPrefs.copy(hudPosition = next)
                            state = state.copy(feedbackPrefs = p)
                            onPrefsChanged(p)
                        },
                        onCycleOffset = {
                            val max = com.halo.ring.ui.screens.HUD_OFFSET_MAX
                            val next = (state.feedbackPrefs.hudOffsetSteps + 1) % (max + 1)   // 0..max, wrap
                            val p = state.feedbackPrefs.copy(hudOffsetSteps = next)
                            state = state.copy(feedbackPrefs = p)
                            onPrefsChanged(p)
                        },
                        onCycleDuration = {
                            val cycle = listOf(1000, 1500, 2000, 3000)
                            val curr = state.feedbackPrefs.hudDurationMs
                            val next = cycle[(cycle.indexOf(curr).coerceAtLeast(0) + 1) % cycle.size]
                            val p = state.feedbackPrefs.copy(hudDurationMs = next)
                            state = state.copy(feedbackPrefs = p)
                            onPrefsChanged(p)
                        },
                    )

                    SubScreen.Profiles -> ProfilesListScreen(
                        profiles = profiles,
                        activeProfileId = activeProfileId,
                        onProfileSelected = { push(SubScreen.ProfileEditor(it.id)) },
                        onSystemGesturesTapped = { push(SubScreen.SystemGestures) },
                        onTestArenaTapped = { push(SubScreen.TestArena) },
                    )

                    is SubScreen.ProfileEditor -> {
                        val profile = profiles.firstOrNull { it.id == top.profileId }
                        if (profile == null) {
                            pop()
                        } else {
                            ProfileEditorScreen(
                                profile = profile,
                                onGestureTapped = { gesture ->
                                    push(SubScreen.ActionPicker(profileId = top.profileId, gesture = gesture))
                                },
                            )
                        }
                    }

                    is SubScreen.ActionPicker -> {
                        val profile = profiles.firstOrNull { it.id == top.profileId }
                        if (profile == null) {
                            pop()
                        } else {
                            ActionPickerScreen(
                                gesture = top.gesture,
                                currentBinding = profile.actionFor(top.gesture),
                                onActionSelected = { newAction: GlassAction ->
                                    onProfileUpdated(profile.withMapping(top.gesture, newAction))
                                    pop()
                                },
                            )
                        }
                    }

                    SubScreen.SystemGestures -> SystemGesturesScreen(
                        gestures = systemGestures,
                        onSlotTapped = { slot -> push(SubScreen.GesturePicker(slot)) },
                    )

                    is SubScreen.GesturePicker -> GesturePickerScreen(
                        slot = top.slot,
                        currentGestures = systemGestures,
                        onGestureSelected = { g ->
                            onSystemGesturesChanged(systemGestures.withSlot(top.slot.toCore(), g))
                            pop()
                        },
                    )

                    SubScreen.Ring -> RingScreen(
                        info = ringInfo,
                        onOpenPairing = { push(SubScreen.RingPairing) },
                        capabilities = ringCapabilities,
                    )

                    SubScreen.RingPairing -> com.halo.ring.ui.screens.RingPairingScreen(onPaired = { pop() })

                    SubScreen.Power -> {
                        val active = profiles.firstOrNull { it.id == activeProfileId }
                        if (active == null) pop()
                        else PowerConnectionScreen(
                            activeProfile = active,
                            onActiveProfileUpdated = onProfileUpdated,
                        )
                    }

                    SubScreen.BluetoothInternet -> BluetoothInternetScreen(
                        autoBootOn = advancedPrefs.btInternetAutoBoot,
                        onToggleAutoBoot = { onAdvancedPrefsChanged(advancedPrefs.copy(btInternetAutoBoot = !advancedPrefs.btInternetAutoBoot)) },
                        onConnectNow = { onAdvancedAction(AdvancedAction.BT_INTERNET_CONNECT_NOW) },
                    )

                    SubScreen.Advanced -> AdvancedScreen(
                        onActionTriggered = onAdvancedAction,
                        onOpenPlugins = { push(SubScreen.ExternalPlugins) },
                    )

                    SubScreen.About -> AboutScreen(
                        versionName = versionName,
                        versionCode = versionCode,
                        detectedProfile = deviceProfile,
                    )

                    SubScreen.VitalsPrefs -> VitalsPrefsScreen(
                        prefs = vitalsPrefs,
                        onUpdated = onVitalsPrefsChanged,
                    )

                    SubScreen.Language -> LanguageScreen(
                        current = currentLanguage,
                        onSelect = { onLanguageSelected(it) },
                    )

                    SubScreen.TestArena -> com.halo.ring.ui.screens.TestArenaScreen(
                        onExit = { pop() },
                    )

                    SubScreen.ExternalPlugins -> com.halo.ring.ui.screens.ExternalPluginsScreen()
                    }   // close when(top)
                  }     // close inner scroll Column
                }       // close else
            }           // close focus-group Column
        }               // close outer Column
        }   // close the outer Box
    }
}

/** Mapping between the UI's [SystemGestureSlot] and the core's [SystemGestures.Slot]. */
private fun SystemGestureSlot.toCore(): SystemGestures.Slot = when (this) {
    SystemGestureSlot.WAKE            -> SystemGestures.Slot.WAKE
    SystemGestureSlot.SLEEP           -> SystemGestures.Slot.SLEEP
    SystemGestureSlot.PROFILE_CYCLE   -> SystemGestures.Slot.PROFILE_CYCLE
    SystemGestureSlot.PEEK_HUD        -> SystemGestures.Slot.PEEK_HUD
    SystemGestureSlot.AI_ASSISTANT    -> SystemGestures.Slot.AI_ASSISTANT
}

/**
 * Top-level app state. The settings UI mutates the [navStack]; settings data (profiles,
 * systemGestures) is owned by [com.halo.ring.di.AppGraph] and threaded through HaloRingApp's
 * params so changes flow back to the foreground service.
 */
data class AppState(
    val navStack: List<SubScreen> = emptyList(),
    val statusBar: StatusBarState = StatusBarState(),
    val vitals: VitalsState = VitalsState(),
    val status: StatusState = StatusState(),
    val feedbackPrefs: FeedbackPrefs = FeedbackPrefs(),
)

data class StatusBarState(
    val connected: Boolean = false,
    val ringId: String = "R08_…",
    val batteryPct: Int? = null,
    val charging: Boolean = false,
    val currentMode: String? = "Navigation",
)
