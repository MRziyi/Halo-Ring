package com.halo.ring.ui.screens

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.firstRunDataStore: DataStore<Preferences> by preferencesDataStore(name = "r08-first-run")

/**
 * Tracks whether the user has completed the first-run wizard (Doc/13 §B9). Single boolean flag; no
 * step-level state — if a user backs out mid-wizard we just show it again next launch.
 */
class FirstRunPrefsStore(private val context: Context) {

    private val key = booleanPreferencesKey("first_run_completed")

    val completedFlow: Flow<Boolean> = context.firstRunDataStore.data.map { it[key] ?: false }

    suspend fun markCompleted() {
        context.firstRunDataStore.edit { it[key] = true }
    }

    /** For "re-run wizard from Advanced": clear the flag so the next launch shows the wizard again. */
    suspend fun reset() {
        context.firstRunDataStore.edit { it[key] = false }
    }
}
