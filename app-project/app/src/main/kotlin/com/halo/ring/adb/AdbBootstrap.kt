package com.halo.ring.adb

import android.content.Context
import android.util.Log
import java.security.KeyPair

/**
 * Embedded ADB-over-Wi-Fi bootstrap (Doc/04 §5, Doc/13 §B12). Coordinates:
 *
 *  1. **Generate** the client RSA-2048 keypair + self-signed cert (via [AdbCrypto]) — needed once,
 *     cached for reuse.
 *  2. **Discover** the pairing port via mDNS (`_adb-tls-pairing._tcp.`) using [AdbMdnsDiscovery].
 *  3. **Pair** with the 6-digit code the user reads off the OS pairing screen (SPAKE2 + cert
 *     exchange). **TODO** — see [pairWithCode] kdoc.
 *  4. **Discover connect port** via mDNS (`_adb-tls-connect._tcp.`).
 *  5. **Connect** over TLS using the cached keypair + cert. **TODO** — see [pushAgentDex] kdoc.
 *  6. **Push** the agent dex via ADB's `sync:` service.
 *  7. **Exec** `app_process` to start the agent.
 *
 * Status at handoff time (Doc/13 §B12-real):
 *   - Keypair + cert generation: **implemented** ([AdbCrypto])
 *   - mDNS discovery: **implemented** ([AdbMdnsDiscovery])
 *   - ADB wire packet: **implemented** ([AdbMessage])
 *   - SPAKE2 pairing handshake: **TODO** — port [`AdbPairingClient.java`](../../../../../../../decompiled/v2/sources/com/ring/r08remote/adb/AdbPairingClient.java)
 *     (~800 lines of BigInteger SPAKE2 + BouncyCastle TLS). Needs hardware to validate the cipher
 *     parameters — porting blind is dangerous.
 *   - TLS-wrapped ADB connection: **TODO** — port [`AdbConnection.java`](../../../../../../../decompiled/v2/sources/com/ring/r08remote/adb/AdbConnection.java)
 *     (~800 lines). Once SPAKE2 is verified, this is mostly mechanical: connect → CNXN handshake →
 *     STLS upgrade → run the standard ADB service protocol on top.
 */
class AdbBootstrap(private val context: Context) {

    private val mdns = AdbMdnsDiscovery(context)

    /** Lazily generated; consider caching to DataStore once we trust the impl on real hardware. */
    @Volatile private var clientKeyPair: KeyPair? = null

    sealed class State {
        object Idle : State()
        data class Pairing(val pairingCode: String) : State()
        object Pairing_Done : State()
        object Connecting : State()
        object Connected : State()
        data class Error(val message: String) : State()
    }

    sealed class Result {
        object Success : Result()
        data class Failure(val message: String) : Result()
    }

    /** Get-or-create the client identity. ~50 ms one-shot RSA-2048 cost. */
    fun keyPair(): KeyPair = clientKeyPair ?: synchronized(this) {
        clientKeyPair ?: AdbCrypto.generateRsaKeyPair().also { clientKeyPair = it }
    }

    /**
     * Discover the ADB pairing port advertised by the glasses on the local network. Returns the
     * `host:port` of the first match, or null on timeout. The user must already have opened
     * "Wireless debugging → Pair device with pairing code" on the glasses.
     */
    suspend fun discoverPairingEndpoint(): AdbMdnsDiscovery.Endpoint? =
        mdns.discover(AdbMdnsDiscovery.PAIRING_SERVICE_TYPE)

    /**
     * Run the SPAKE2 pairing handshake.
     *
     * **NOT YET IMPLEMENTED — needs hardware validation.** Port [`AdbPairingClient.java`](../../../../../../../decompiled/v2/sources/com/ring/r08remote/adb/AdbPairingClient.java).
     * The flow is:
     *
     *  1. Open a TLS socket to [endpoint.host]:[endpoint.port] (any cert OK at this stage —
     *     SPAKE2 authenticates the channel separately).
     *  2. Derive `pwd_hash = hkdf(pairingCode)`; perform SPAKE2 (RFC9382) with X, Y, K_a/b
     *     transcript hashes.
     *  3. Once the shared secret is verified, send our X.509 cert encoded in
     *     `wire_msg: { type: PEER_INFO, payload: cert_pem }`.
     *  4. Receive the device cert (mTLS-like) so future connect sessions trust it.
     *  5. Mark the device as paired in our app-side store.
     *
     * Cryptographically sensitive — verify against a known-good ADB client (e.g. Android Studio's
     * pairing dialog) on real hardware before shipping.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun pairWithCode(code: String, endpoint: AdbMdnsDiscovery.Endpoint): Result {
        Log.w(TAG, "pairWithCode($code, ${endpoint.host}:${endpoint.port}) — SPAKE2 port NOT yet implemented (B12-real)")
        // Ensure the keypair is ready so the UI can show "generating keys…" feedback at least.
        keyPair()
        return Result.Failure("ADB pairing (SPAKE2) not yet implemented; port from decompiled/v2/.../AdbPairingClient.java needed")
    }

    /**
     * Push the agent dex from app assets to `/data/local/tmp/halo-agent.dex` via the ADB
     * `sync:` service.
     *
     * **NOT YET IMPLEMENTED.** Port [`AdbConnection.java`](../../../../../../../decompiled/v2/sources/com/ring/r08remote/adb/AdbConnection.java).
     * Once the TLS connection is up, the sync protocol is:
     *
     *   send `sync:` via A_OPEN → A_OKAY → SEND <path>,<mode> → DATA <chunk>... → DONE <mtime> →
     *   read OKAY (or FAIL with error).
     *
     * The asset path (relative to the APK's assets/) is [AGENT_ASSET_PATH].
     */
    suspend fun pushAgentDex(): Result {
        Log.w(TAG, "pushAgentDex — ADB sync push NOT yet implemented (B12-real)")
        return Result.Failure("ADB sync push not yet implemented; port from decompiled/v2/.../AdbConnection.java needed")
    }

    /**
     * `pm grant <pkg> WRITE_SECURE_SETTINGS`. Once granted, the app can flip wireless debugging
     * itself in the future (`Settings.Global.putInt("adb_wifi_enabled", 1)`), avoiding the
     * repeat-pairing UX on every reboot.
     */
    suspend fun grantWriteSecureSettings(): Result {
        Log.w(TAG, "grantWriteSecureSettings — ADB shell exec NOT yet implemented (B12-real)")
        return Result.Failure("ADB shell exec not yet implemented")
    }

    /** Start the agent via `app_process`. After this [AppProcessAgentBackend] should report ready. */
    suspend fun startAgent(): Result {
        Log.w(TAG, "startAgent — ADB shell exec NOT yet implemented (B12-real)")
        return Result.Failure("ADB shell exec not yet implemented")
    }

    companion object {
        private const val TAG = "AdbBootstrap"
        /** Asset name `:agent:packageDex` produces — the wizard ships this to /data/local/tmp/. */
        const val AGENT_ASSET_PATH = "halo-agent.dex"
        /** Where the agent dex lives on-device after [pushAgentDex]. */
        const val AGENT_DEX_PATH = "/data/local/tmp/halo-agent.dex"
    }
}
