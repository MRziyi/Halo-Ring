package com.halo.ring.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

private val Context.adbKeyDataStore: DataStore<Preferences> by preferencesDataStore(name = "halo-adb-key")

/**
 * Persists the ADB client RSA-2048 keypair so the user only has to walk the pairing handshake
 * once. After first pair, the public key is recorded in the device's `/data/misc/adb/adb_keys`;
 * we keep the matching private key here, in app-private storage, base64-encoded.
 *
 * Security note: this DataStore is in app-private internal storage (`/data/data/<pkg>/files/...`).
 * The key only authorises ADB to the user's own devices that have already trusted it. If an
 * attacker has root on the user's phone they have bigger problems than this key.
 */
class AdbKeyStore(private val context: Context) {

    private val publicKeyKey = stringPreferencesKey("public_key_x509_b64")
    private val privateKeyKey = stringPreferencesKey("private_key_pkcs8_b64")

    /** Returns the persisted keypair, or null if none has been stored yet. */
    suspend fun load(): KeyPair? {
        val prefs = context.adbKeyDataStore.data.first()
        val pub = prefs[publicKeyKey] ?: return null
        val pri = prefs[privateKeyKey] ?: return null
        return try {
            val kf = KeyFactory.getInstance("RSA")
            val pubKey = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP)))
            val priKey = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(pri, Base64.NO_WRAP)))
            KeyPair(pubKey, priKey)
        } catch (e: Exception) {
            Log.w(TAG, "stored keypair unreadable, will regenerate: ${e.message}")
            null
        }
    }

    suspend fun save(keyPair: KeyPair) {
        val pubB64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val priB64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
        context.adbKeyDataStore.edit {
            it[publicKeyKey] = pubB64
            it[privateKeyKey] = priB64
        }
    }

    suspend fun clear() {
        context.adbKeyDataStore.edit {
            it.remove(publicKeyKey)
            it.remove(privateKeyKey)
        }
    }

    companion object {
        private const val TAG = "AdbKeyStore"
    }
}
