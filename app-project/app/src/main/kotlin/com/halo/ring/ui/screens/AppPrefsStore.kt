package com.halo.ring.ui.screens

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "halo-app-prefs")

/**
 * App-wide preferences that don't fit a specific feature module:
 *  - [guideSeenFlow]      : has the user dismissed the post-wizard interface guide?
 *  - [languageFlow]       : language override — `SYSTEM` (follow OS), `EN`, or `ZH`.
 *
 * Lives in its own DataStore file (`halo-app-prefs`) separate from feature-specific stores
 * (`r08-first-run`, `r08-feedback-prefs`, `r08-profiles`, etc.) so reads stay cheap and these
 * cross-cutting flags don't bloat any one store.
 */
class AppPrefsStore(private val context: Context) {

    private val guideSeenKey = booleanPreferencesKey("guide_seen")
    private val languageKey = stringPreferencesKey("language")

    val guideSeenFlow: Flow<Boolean> =
        context.appPrefsDataStore.data.map { it[guideSeenKey] ?: false }

    val languageFlow: Flow<AppLanguage> =
        context.appPrefsDataStore.data.map { p ->
            p[languageKey]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.SYSTEM
        }

    suspend fun markGuideSeen() {
        context.appPrefsDataStore.edit { it[guideSeenKey] = true }
    }

    /** Re-show the guide on next launch (used by Settings → About → "Show operation guide"). */
    suspend fun resetGuideSeen() {
        context.appPrefsDataStore.edit { it[guideSeenKey] = false }
    }

    suspend fun setLanguage(lang: AppLanguage) {
        context.appPrefsDataStore.edit { it[languageKey] = lang.name }
    }
}

/**
 * Language override for the app's UI strings (Doc/08 §2). `SYSTEM` means "follow whatever
 * Android's locale resolver picks for our app" — the default. Other values force a specific
 * language regardless of the device's system locale.
 *
 * Mapped to `androidx.core.os.LocaleListCompat` via [com.halo.ring.HaloRingApplication] using
 * `AppCompatDelegate.setApplicationLocales`. On Android 13+ this is the system-managed
 * per-app language; on older versions AppCompat emulates by recreating Activities.
 */
enum class AppLanguage(val labelEn: String, val labelZh: String, val tag: String?) {
    /** Use the device's system locale. */
    SYSTEM(labelEn = "Follow system", labelZh = "跟随系统", tag = null),
    /** English. */
    EN(labelEn = "English", labelZh = "English", tag = "en"),
    /** Simplified Chinese. */
    ZH(labelEn = "中文 (Chinese)", labelZh = "中文", tag = "zh-CN"),
}
