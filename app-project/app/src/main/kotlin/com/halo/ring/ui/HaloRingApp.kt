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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.halo.ring.di.RingInfo
import com.halo.ring.ui.screens.AboutScreen
import com.halo.ring.ui.screens.ActionPickerScreen
import com.halo.ring.ui.screens.AdvancedAction
import com.halo.ring.ui.screens.AdvancedPrefs
import com.halo.ring.ui.screens.AdvancedScreen
import com.halo.ring.ui.screens.AppLanguage
import com.halo.ring.ui.screens.GuidedTour
import com.halo.ring.ui.screens.LanguageScreen
import com.halo.ring.ui.screens.FeedbackPrefField
import com.halo.ring.ui.screens.FeedbackPrefs
import com.halo.ring.ui.screens.FeedbackScreen
import com.halo.ring.ui.screens.GesturePickerScreen
import com.halo.ring.ui.screens.HudPosition
import com.halo.ring.ui.screens.PowerConnectionScreen
import com.halo.ring.ui.screens.ProfileEditorScreen
import com.halo.ring.ui.screens.ProfilesListScreen
import com.halo.ring.ui.screens.RingScreen
import com.halo.ring.ui.screens.SettingsRootScreen
import com.halo.ring.ui.screens.SettingsSection
import com.halo.ring.ui.screens.StatusScreen
import com.halo.ring.ui.screens.StatusState
import com.halo.ring.ui.screens.SystemGesturesScreen
import com.halo.ring.ui.screens.VitalsPrefs
import com.halo.ring.ui.screens.VitalsPrefsScreen
import com.halo.ring.ui.screens.VitalsScreen
import com.halo.ring.ui.screens.VitalsState
import com.halo.ring.core.DeviceProfile
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.action.KeyMapProfile
import com.halo.ring.core.gesture.SystemGestures

/**
 * The root composable. Hosts the three tabs + a typed navigation stack ([SubScreen]) for
 * settings drilldown. Owns the [InAppFocusController] attachment lifecycle.
 *
 * Navigation model: each tab has its own root (empty stack); pushing a [SubScreen] drills in,
 * popping returns to the parent. A tab change clears the stack.
 */
@Composable
fun HaloRingApp(
    initial: AppState = AppState(),
    profiles: List<KeyMapProfile> = emptyList(),
    activeProfileId: String = "",
    systemGestures: SystemGestures = SystemGestures(),
    ringInfo: RingInfo = RingInfo(),
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
    /** When true, an interactive coachmark tour ([GuidedTour]) is overlaid on top of the app —
     *  drives navigation through every tab + key sub-screen with a dim layer + callout card.
     *  Audit-2026-05-13p. */
    tourActive: Boolean = false,
    /** Tour fires this on SKIP or final DONE. Caller persists `guide_seen = true` + clears the
     *  `tourActive` flag. */
    onTourDismissed: () -> Unit = {},
    /** Settings → About → "Show operation guide" — caller flips tourActive=true on this. */
    onRequestTour: () -> Unit = {},
) {
    HaloRingTheme {
        var state by remember { mutableStateOf(initial) }

        val focusManager = LocalFocusManager.current
        DisposableEffect(focusManager) {
            val backController = object : BackController {
                override fun pop(): Boolean {
                    if (state.navStack.isNotEmpty()) {
                        state = state.copy(navStack = state.navStack.dropLast(1))
                        return true
                    }
                    return false
                }
                override fun exit() { /* finish() — handled at Activity level */ }
            }
            val tabController = object : TabController {
                override val current get() = state.tab
                override fun select(tab: AppTab) { state = state.copy(tab = tab, navStack = emptyList()) }
                override fun prev() {
                    val ords = AppTab.values()
                    state = state.copy(
                        tab = ords[(state.tab.ordinal - 1 + ords.size) % ords.size],
                        navStack = emptyList(),
                    )
                }
                override fun next() {
                    val ords = AppTab.values()
                    state = state.copy(
                        tab = ords[(state.tab.ordinal + 1) % ords.size],
                        navStack = emptyList(),
                    )
                }
            }
            InAppFocusController.attach(focusManager, backController, tabController)
            onDispose { InAppFocusController.detach() }
        }

        fun push(s: SubScreen) { state = state.copy(navStack = state.navStack + s) }
        fun pop() { state = state.copy(navStack = state.navStack.dropLast(1)) }
        fun popTo(s: SubScreen) {
            val i = state.navStack.indexOf(s)
            state = state.copy(navStack = if (i < 0) emptyList() else state.navStack.subList(0, i + 1))
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
                currentMode = state.statusBar.currentMode,
            )
            TabBar(
                selected = state.tab,
                onSelect = { state = state.copy(tab = it, navStack = emptyList()) },
            )
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                val top = state.navStack.lastOrNull()
                when (top) {
                    null -> when (state.tab) {
                        AppTab.VITALS -> VitalsScreen(state.vitals)
                        AppTab.SETTINGS -> SettingsRootScreen(
                            onSectionSelected = { section ->
                                push(when (section) {
                                    SettingsSection.FEEDBACK         -> SubScreen.Feedback
                                    SettingsSection.PROFILES         -> SubScreen.Profiles
                                    SettingsSection.SYSTEM_GESTURES  -> SubScreen.SystemGestures
                                    SettingsSection.RING             -> SubScreen.Ring
                                    SettingsSection.POWER            -> SubScreen.Power
                                    SettingsSection.ADVANCED         -> SubScreen.Advanced
                                    SettingsSection.ABOUT            -> SubScreen.About
                                    SettingsSection.VITALS_PREFS     -> SubScreen.VitalsPrefs
                                    SettingsSection.LANGUAGE         -> SubScreen.Language
                                })
                            },
                        )
                        AppTab.STATUS -> StatusScreen(
                            // Build the status snapshot freshly from the live `ringInfo` flow
                            // each composition — the stale `state.status` (set at construction)
                            // would never update otherwise.
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
                    }

                    is SubScreen.Feedback -> FeedbackScreen(
                        prefs = state.feedbackPrefs,
                        focusedIndex = 0,
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
                    )

                    is SubScreen.ProfileEditor -> {
                        val profile = profiles.firstOrNull { it.id == top.profileId }
                        if (profile == null) {
                            // The profile vanished underneath us (e.g. user removed it). Drop back.
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

                    SubScreen.Ring -> RingScreen(info = ringInfo)

                    SubScreen.Power -> {
                        val active = profiles.firstOrNull { it.id == activeProfileId }
                        if (active == null) pop()   // no active profile (shouldn't happen) → drop back
                        else PowerConnectionScreen(
                            activeProfile = active,
                            onActiveProfileUpdated = onProfileUpdated,
                        )
                    }

                    SubScreen.Advanced -> AdvancedScreen(
                        prefs = advancedPrefs,
                        onToggleDebugHud = { onAdvancedPrefsChanged(advancedPrefs.copy(debugHudEnabled = !advancedPrefs.debugHudEnabled)) },
                        onToggleLatency  = { onAdvancedPrefsChanged(advancedPrefs.copy(latencyMeasurementEnabled = !advancedPrefs.latencyMeasurementEnabled)) },
                        onToggleSpatial  = { onAdvancedPrefsChanged(advancedPrefs.copy(spatialModeEnabled = !advancedPrefs.spatialModeEnabled)) },
                        onActionTriggered = onAdvancedAction,
                    )

                    SubScreen.About -> AboutScreen(
                        versionName = versionName,
                        versionCode = versionCode,
                        detectedProfile = deviceProfile,
                        // Re-trigger the interactive tour. SubScreen.Guide is unused now (the
                        // static cheatsheet was replaced by GuidedTour); the About row instead
                        // tells the host to flip tourActive=true.
                        onShowGuide = onRequestTour,
                    )

                    SubScreen.VitalsPrefs -> VitalsPrefsScreen(
                        prefs = vitalsPrefs,
                        onUpdated = onVitalsPrefsChanged,
                    )

                    SubScreen.Language -> LanguageScreen(
                        current = currentLanguage,
                        onSelect = { onLanguageSelected(it) },
                    )
                }
            }
        }

        // Interactive coachmark tour overlay (audit-2026-05-13p). Drawn on top of the entire
        // app when tourActive=true so it can dim the underlying UI + drive navigation through
        // every key tab + sub-screen.
        if (tourActive) {
            GuidedTour(
                onDismiss = onTourDismissed,
                onSelectTab = { tab ->
                    state = state.copy(tab = tab, navStack = emptyList())
                },
                onPush = { screen -> push(screen) },
                onPopAll = { state = state.copy(navStack = emptyList()) },
            )
        }
        }   // close the outer Box
    }
}

/** Mapping between the UI's [SystemGestureSlot] and the core's [SystemGestures.Slot]. */
private fun SystemGestureSlot.toCore(): SystemGestures.Slot = when (this) {
    SystemGestureSlot.WAKE            -> SystemGestures.Slot.WAKE
    SystemGestureSlot.SLEEP           -> SystemGestures.Slot.SLEEP
    SystemGestureSlot.PROFILE_CYCLE   -> SystemGestures.Slot.PROFILE_CYCLE
    SystemGestureSlot.PEEK_HUD        -> SystemGestures.Slot.PEEK_HUD
    SystemGestureSlot.FORCE_RECONNECT -> SystemGestures.Slot.FORCE_RECONNECT
}

/**
 * Top-level app state. The settings UI mutates the [navStack]; settings data (profiles,
 * systemGestures) is owned by [com.halo.ring.di.AppGraph] and threaded through HaloRingApp's params
 * so changes flow back to the foreground service.
 */
data class AppState(
    val tab: AppTab = AppTab.VITALS,
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
    val currentMode: String? = "Navigation",
)
