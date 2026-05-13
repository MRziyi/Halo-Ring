package com.halo.ring.ui.screens

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.feedbackPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "r08-feedback-prefs")

/**
 * DataStore-backed persistence for [FeedbackPrefs]. Doc/13 §A7.
 *
 * - Reads expose a [Flow] the foreground service collects (so a change in the Feedback screen
 *   propagates to the [com.halo.ring.ui.hud.HudOverlay] without an intent-broadcast hop).
 * - [updateGestureHintHud] / [updatePrefs] hop onto a coroutine; callers can fire-and-forget.
 * - [armAutoHintAfterPairing] enables hints for the next 5 minutes, then auto-disables.
 *
 * Default values match [FeedbackPrefs] defaults. Booleans default to false on the *underlying*
 * preferences key so we can distinguish "explicit-off" from "never-set"; the latter falls back to
 * the data-class default.
 */
class FeedbackPrefsStore(private val context: Context) {

    private object Keys {
        val GestureHintHud      = booleanPreferencesKey("gesture_hint_hud")
        val GestureHintHudSet   = booleanPreferencesKey("gesture_hint_hud_set")
        val ClickSound          = booleanPreferencesKey("click_sound_on_mode_switch")
        val ClickSoundSet       = booleanPreferencesKey("click_sound_on_mode_switch_set")
        val RingLed             = booleanPreferencesKey("ring_led_feedback")
        val RingLedSet          = booleanPreferencesKey("ring_led_feedback_set")
        val HudPosition         = stringPreferencesKey("hud_position")
        val HudDurationMs       = intPreferencesKey("hud_duration_ms")
        val AutoHintAfterPairing= booleanPreferencesKey("auto_hint_after_pairing")
        val AutoHintAfterPairingSet = booleanPreferencesKey("auto_hint_after_pairing_set")
        val AutoHintExpiresAtMs = androidx.datastore.preferences.core.longPreferencesKey("auto_hint_expires_at_ms")
    }

    val flow: Flow<FeedbackPrefs> = context.feedbackPrefsDataStore.data.map { p ->
        val defaults = FeedbackPrefs()
        val baseHint = if (p[Keys.GestureHintHudSet] == true) p[Keys.GestureHintHud] ?: defaults.gestureHintHud
                       else defaults.gestureHintHud
        val expiry = p[Keys.AutoHintExpiresAtMs] ?: 0L
        val effectiveHint = if (System.currentTimeMillis() < expiry) true else baseHint
        FeedbackPrefs(
            gestureHintHud      = effectiveHint,
            clickSoundOnModeSwitch = if (p[Keys.ClickSoundSet] == true) p[Keys.ClickSound] ?: defaults.clickSoundOnModeSwitch
                                     else defaults.clickSoundOnModeSwitch,
            ringLedFeedback     = if (p[Keys.RingLedSet] == true) p[Keys.RingLed] ?: defaults.ringLedFeedback
                                  else defaults.ringLedFeedback,
            hudPosition         = p[Keys.HudPosition]?.let { runCatching { HudPosition.valueOf(it) }.getOrNull() }
                                  ?: defaults.hudPosition,
            hudDurationMs       = p[Keys.HudDurationMs] ?: defaults.hudDurationMs,
            autoHintAfterPairing= if (p[Keys.AutoHintAfterPairingSet] == true) p[Keys.AutoHintAfterPairing] ?: defaults.autoHintAfterPairing
                                  else defaults.autoHintAfterPairing,
        )
    }

    suspend fun updatePrefs(transform: (FeedbackPrefs) -> FeedbackPrefs) {
        val snapshot = flow.first()
        write(transform(snapshot))
    }

    suspend fun updateGestureHintHud(on: Boolean) {
        context.feedbackPrefsDataStore.edit { p ->
            p[Keys.GestureHintHud]    = on
            p[Keys.GestureHintHudSet] = true
        }
    }

    /** Doc/08 §10: auto-enable gesture-hint HUD for [durationMs] after first-time pairing. */
    suspend fun armAutoHintAfterPairing(durationMs: Long = 5 * 60_000L) {
        context.feedbackPrefsDataStore.edit { p ->
            p[Keys.AutoHintExpiresAtMs] = System.currentTimeMillis() + durationMs
        }
    }

    private suspend fun write(next: FeedbackPrefs) {
        context.feedbackPrefsDataStore.edit { p ->
            p[Keys.GestureHintHud]            = next.gestureHintHud
            p[Keys.GestureHintHudSet]         = true
            p[Keys.ClickSound]                = next.clickSoundOnModeSwitch
            p[Keys.ClickSoundSet]             = true
            p[Keys.RingLed]                   = next.ringLedFeedback
            p[Keys.RingLedSet]                = true
            p[Keys.HudPosition]               = next.hudPosition.name
            p[Keys.HudDurationMs]             = next.hudDurationMs
            p[Keys.AutoHintAfterPairing]      = next.autoHintAfterPairing
            p[Keys.AutoHintAfterPairingSet]   = true
        }
    }
}

