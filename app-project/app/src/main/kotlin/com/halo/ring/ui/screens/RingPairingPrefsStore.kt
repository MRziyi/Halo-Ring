package com.halo.ring.ui.screens

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ringPairingDataStore: DataStore<Preferences> by preferencesDataStore(name = "halo-ring-pairing")

/**
 * Persists which ring the user has paired with. Set after the user picks one in the pairing
 * picker UI; consumed by [com.halo.ring.service.HaloRingService] to decide whether to
 * auto-scan on service startup.
 *
 * If null, the service does NOT scan on its own — user must explicitly open the pairing UI.
 * This fixes the "auto-connect to a phantom device" bug from the 2026-05-27 burn-in.
 */
class RingPairingPrefsStore(private val context: Context) {

    private object Keys {
        val PairedMac          = stringPreferencesKey("paired_mac")
        val PairedAdvertisedName = stringPreferencesKey("paired_advertised_name")
    }

    val pairedMacFlow: Flow<String?> = context.ringPairingDataStore.data.map { it[Keys.PairedMac] }
    val pairedNameFlow: Flow<String?> = context.ringPairingDataStore.data.map { it[Keys.PairedAdvertisedName] }

    suspend fun setPaired(mac: String, advertisedName: String?) {
        context.ringPairingDataStore.edit {
            it[Keys.PairedMac] = mac.uppercase()
            if (advertisedName != null) it[Keys.PairedAdvertisedName] = advertisedName
            else it.remove(Keys.PairedAdvertisedName)
        }
    }

    suspend fun clear() {
        context.ringPairingDataStore.edit {
            it.remove(Keys.PairedMac)
            it.remove(Keys.PairedAdvertisedName)
        }
    }
}
