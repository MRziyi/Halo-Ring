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
import com.halo.ring.ui.screens.HudPosition
import com.halo.ring.ui.screens.PowerConnectionScreen
import com.halo.ring.ui.screens.ProfileEditorScreen
import com.halo.ring.ui.screens.ProfilesListScreen
import com.halo.ring.ui.screens.RingScreen
import com.halo.ring.ui.screens.SettingsGroupScreen
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
 * The root composable.
 *
 * **v0.4 model** (Doc/20 §4-§6):
 * - No top tab strip. Root = [SettingsRootScreen] (the config center).
 * - Vitals dashboard + Status info are reachable as sub-screens from the Settings root.
 * - HUD overlay (owned by HaloRingService) is the daily UX surface; the Activity is opened
 *   occasionally for editing.
 * - No InAppFocusController. The 4 base ring gestures dispatch via
 *   [ActivitySystemKeyDispatcher] as system KeyEvents; Compose's standard FocusManager handles
 *   DPAD navigation natively.
 */
@Composable
fun HaloRingApp(
    initial: AppState = AppState(),
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
        val topKey = state.navStack.lastOrNull()?.let { it::class.simpleName } ?: "root"
        androidx.compose.runtime.LaunchedEffect(topKey) {
            // Retry a few times — the focusable children may not be composed on the first frame.
            repeat(5) {
                kotlinx.coroutines.delay(100)
                val ok = try { contentFocus.requestFocus(); true } catch (_: Throwable) { false }
                if (ok) return@LaunchedEffect
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
                currentMode = state.statusBar.currentMode,
            )
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocus)
                    .focusGroup()
                    .verticalScroll(rememberScrollState()),
            ) {
                val top = state.navStack.lastOrNull()
                // Map a [SettingsSection] to its target [SubScreen]. Used by both the root
                // (when a group has only one item it could short-circuit, but for v0.4 simplicity
                // we always go root → group → leaf) and by SettingsGroupScreen.
                val sectionToSubScreen: (SettingsSection) -> SubScreen = { section ->
                    when (section) {
                        SettingsSection.VITALS           -> SubScreen.VitalsDashboard
                        SettingsSection.STATUS           -> SubScreen.StatusInfo
                        SettingsSection.FEEDBACK         -> SubScreen.Feedback
                        SettingsSection.PROFILES         -> SubScreen.Profiles
                        SettingsSection.SYSTEM_GESTURES  -> SubScreen.SystemGestures
                        SettingsSection.RING             -> SubScreen.Ring
                        SettingsSection.POWER            -> SubScreen.Power
                        SettingsSection.BT_INTERNET      -> SubScreen.BluetoothInternet
                        SettingsSection.ADVANCED         -> SubScreen.Advanced
                        SettingsSection.ABOUT            -> SubScreen.About
                        SettingsSection.VITALS_PREFS     -> SubScreen.VitalsPrefs
                        SettingsSection.LANGUAGE         -> SubScreen.Language
                        SettingsSection.TEST_ARENA       -> SubScreen.TestArena
                        SettingsSection.EXTERNAL_PLUGINS -> SubScreen.ExternalPlugins
                    }
                }

                when (top) {
                    null -> SettingsRootScreen(
                        onGroupSelected = { group -> push(SubScreen.SettingsGroupSubScreen(group)) },
                        // Legacy section-direct path kept as a fallback (no UI surfaces it for now).
                        onSectionSelected = { section -> push(sectionToSubScreen(section)) },
                    )

                    is SubScreen.SettingsGroupSubScreen -> SettingsGroupScreen(
                        group = top.group,
                        onSectionSelected = { section -> push(sectionToSubScreen(section)) },
                    )

                    SubScreen.VitalsDashboard -> VitalsScreen(
                        state = state.vitals,
                        capabilities = ringCapabilities,
                        sportActive = state.vitals.sportActive,
                        sportType = state.vitals.sportType,
                        sportDurationSec = state.vitals.sportDurationSec,
                    )

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
                        onOpenAndroidSettings = { onAdvancedAction(AdvancedAction.OPEN_ANDROID_SETTINGS) },
                    )

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
                }
            }
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
    val currentMode: String? = "Navigation",
)
