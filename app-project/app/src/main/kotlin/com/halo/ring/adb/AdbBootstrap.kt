package com.halo.ring.adb

import android.content.Context
import android.util.Log
import java.security.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 *   - SPAKE2 pairing handshake: **TODO** — port [`AdbPairingClient.java`](../../../../../../../refs/r08remote-decompiled-v2/sources/com/ring/r08remote/adb/AdbPairingClient.java)
 *     (~800 lines of BigInteger SPAKE2 + BouncyCastle TLS). Needs hardware to validate the cipher
 *     parameters — porting blind is dangerous.
 *   - TLS-wrapped ADB connection: **TODO** — port [`AdbConnection.java`](../../../../../../../refs/r08remote-decompiled-v2/sources/com/ring/r08remote/adb/AdbConnection.java)
 *     (~800 lines). Once SPAKE2 is verified, this is mostly mechanical: connect → CNXN handshake →
 *     STLS upgrade → run the standard ADB service protocol on top.
 */
class AdbBootstrap(private val context: Context) {

    private val mdns = AdbMdnsDiscovery(context)
    private val keyStore = AdbKeyStore(context)

    /** Lazily loaded from [keyStore] on first use, then memoised. */
    @Volatile private var clientKeyPair: KeyPair? = null

    /** Set by [connect]; reused by push / grant / startAgent within the same bootstrap session. */
    @Volatile private var connection: AdbConnection? = null

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

    /**
     * Get-or-create the client identity. First load from [AdbKeyStore]; if nothing's saved,
     * generate a fresh keypair (~50 ms) and persist it. Subsequent calls in the same process
     * return the memoised value.
     */
    suspend fun keyPair(): KeyPair = clientKeyPair ?: run {
        val loaded = keyStore.load()
        if (loaded != null) {
            Log.i(TAG, "loaded persisted ADB keypair")
            synchronized(this) { clientKeyPair = loaded }
            return@run loaded
        }
        val fresh = AdbCrypto.generateRsaKeyPair()
        keyStore.save(fresh)
        Log.i(TAG, "generated + persisted fresh ADB keypair")
        synchronized(this) { clientKeyPair = fresh }
        fresh
    }

    /**
     * Discover the ADB pairing port advertised by the glasses on the local network. Returns the
     * `host:port` of the first match, or null on timeout. The user must already have opened
     * "Wireless debugging → Pair device with pairing code" on the glasses.
     */
    suspend fun discoverPairingEndpoint(): AdbMdnsDiscovery.Endpoint? =
        mdns.discover(AdbMdnsDiscovery.PAIRING_SERVICE_TYPE)

    /**
     * Headless boot-recovery path. Called from [com.halo.ring.service.HaloRingService.onCreate]
     * (which is itself started by [com.halo.ring.receiver.BootReceiver] on BOOT_COMPLETED).
     *
     * Tries to bring the agent back without UI:
     *   1. No persisted keypair → user never paired; do nothing (wizard will run on next app open)
     *   2. Agent already alive (LocalSocket reachable) → done
     *   3. Try to ensure Wireless debugging is on (best-effort; only works if we hold
     *      `WRITE_SECURE_SETTINGS`, which `pm grant` granted us at first wizard run)
     *   4. Connect via mDNS; push agent dex; spawn agent
     *
     * Silent failures. Caller doesn't surface anything to the user — the service can still
     * provide read-only UI even with the agent down (the wizard CTA re-creates it).
     *
     * **Known limitation on OnePlus 9 Pro / OxygenOS** (dev rig): when adbd's wireless transport
     * has been running for hours without a fresh pair, spawned `shell:` children die at stream
     * close even with `setsid` and the 800 ms detach delay. A fresh boot or a toggle of
     * `adb_wifi_enabled` resets the transport, but OnePlus's mDNS advertisement caches the old
     * port for ~minutes after the toggle, so the connect step then fails until the cache
     * refreshes. On real glasses (Rokid/RayNeo stock AOSP), `BOOT_COMPLETED` fires with a
     * freshly-started adbd → no accumulated transport state → spawn should work first try.
     * Verify on C7 / C8.
     */
    suspend fun bootRecoverAgent(): Result = withContext(Dispatchers.IO) {
        if (keyStore.load() == null) {
            Log.i(TAG, "bootRecover: no persisted keypair — user hasn't completed wizard yet, skipping")
            return@withContext Result.Failure("not paired yet")
        }
        if (isAgentAlive()) {
            Log.i(TAG, "bootRecover: agent already alive on @halo.agent, nothing to do")
            return@withContext Result.Success
        }
        ensureWirelessDebugEnabled()

        // Give adbd's wireless debugging a moment to start advertising mDNS after boot.
        // The connect port is persistent across reboots (Android remembers it once the user
        // enabled wireless debug), so this is just a settling delay, not a polling loop.
        kotlinx.coroutines.delay(2_000)

        when (val r = connect()) {
            is Result.Failure -> {
                Log.w(TAG, "bootRecover: connect failed: ${r.message}")
                return@withContext r
            }
            else -> Unit
        }

        // The dex is in app assets; pushing is idempotent (sync.SEND overwrites).
        when (val r = pushAgentDex()) {
            is Result.Failure -> {
                Log.w(TAG, "bootRecover: pushAgentDex failed: ${r.message}")
                disconnect()
                return@withContext r
            }
            else -> Unit
        }
        when (val r = startAgent()) {
            is Result.Failure -> {
                Log.w(TAG, "bootRecover: startAgent failed: ${r.message}")
                disconnect()
                return@withContext r
            }
            else -> Unit
        }
        // Give the agent a beat to settle into its `setsid` session before tearing down the
        // ADB transport — see wizard's runRootedBootstrap which uses the same delay. Without
        // it the agent dies between startAgent returning and us closing the socket.
        kotlinx.coroutines.delay(800)
        disconnect()
        val alive = isAgentAlive()
        Log.i(TAG, "bootRecover: agent re-spawned via wireless ADB; aliveCheck=$alive")
        if (alive) Result.Success else Result.Failure("agent socket not reachable after spawn")
    }

    private fun isAgentAlive(): Boolean = try {
        android.net.LocalSocket().use { s ->
            s.connect(android.net.LocalSocketAddress(
                "halo.agent", android.net.LocalSocketAddress.Namespace.ABSTRACT))
            true
        }
    } catch (_: Exception) { false }

    /**
     * If we hold `WRITE_SECURE_SETTINGS` (granted by `pm grant` during first wizard run on
     * stock AOSP devices; OnePlus / Xiaomi block this), make sure `adb_wifi_enabled` is on.
     * On stock AOSP the user's toggle survives reboot already, so this is a belt-and-braces.
     */
    private fun ensureWirelessDebugEnabled() {
        val haveSettingsPerm = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!haveSettingsPerm) return
        val current = android.provider.Settings.Global.getInt(
            context.contentResolver, "adb_wifi_enabled", 0,
        )
        if (current == 1) return
        try {
            android.provider.Settings.Global.putInt(
                context.contentResolver, "adb_wifi_enabled", 1,
            )
            Log.i(TAG, "auto-enabled adb_wifi_enabled (had WRITE_SECURE_SETTINGS)")
        } catch (e: SecurityException) {
            Log.w(TAG, "putInt adb_wifi_enabled denied despite perm: ${e.message}")
        }
    }

    /**
     * Root-only shortcut: write our public key straight into `/data/misc/adb/adb_keys`,
     * skipping the SPAKE2 pairing dialog entirely. Only useful on rooted dev rigs (and on
     * Android phones whose Settings UI auto-closes the pairing dialog the moment we leave
     * Settings, taking adbd's pair port with it). On real glasses we don't have root, so the
     * wizard's [pairWithCode] path remains the production code path.
     */
    suspend fun installKeyViaRoot(): Result = withContext(Dispatchers.IO) {
        if (!RootBypass.isRootAvailable()) {
            return@withContext Result.Failure("root not available")
        }
        val install = RootBypass.installKey(keyPair())
        if (!install.ok) return@withContext Result.Failure("adb_keys write failed: ${install.output.trim()}")
        // adbd re-reads adb_keys on each auth attempt, so no restart is strictly needed.
        // Send SIGHUP anyway — costs nothing and tightens the race.
        RootBypass.reloadAdbKeys()
        Log.i(TAG, "installed pubkey into /data/misc/adb/adb_keys via root")
        Result.Success
    }

    /**
     * Run the ADB-over-Wi-Fi pairing handshake. Delegates to [AdbPairingClient] for the actual
     * TLS + HKDF + AES-GCM dance; we only sequence the result. On success, the target's adbd has
     * added our public key to `/data/misc/adb/adb_keys`, so subsequent [AdbConnection] (A-2
     * step 2) connects without re-pairing.
     *
     * Threading: [AdbPairingClient.pair] is suspending + blocking I/O; runs on `Dispatchers.IO`
     * internally. Safe to call from the main thread; the wizard does.
     */
    suspend fun pairWithCode(code: String, endpoint: AdbMdnsDiscovery.Endpoint): Result {
        Log.i(TAG, "pairWithCode($code, ${endpoint.host}:${endpoint.port}) starting")
        val client = AdbPairingClient(endpoint.host, endpoint.port, code, keyPair())
        return when (val r = client.pair()) {
            is AdbPairingClient.Result.Success -> {
                Log.i(TAG, "pairing OK")
                Result.Success
            }
            is AdbPairingClient.Result.Failure -> {
                Log.w(TAG, "pairing failed: ${r.message}")
                Result.Failure(r.message)
            }
        }
    }

    /**
     * Discover (via mDNS) and connect to the device's TLS-wrapped ADB service. Must be called
     * before [pushAgentDex] / [grantWriteSecureSettings] / [startAgent]. Idempotent.
     */
    suspend fun connect(): Result = withContext(Dispatchers.IO) {
        if (connection != null) return@withContext Result.Success
        val endpoint = mdns.discover(AdbMdnsDiscovery.CONNECT_SERVICE_TYPE)
            ?: return@withContext Result.Failure("mDNS: no _adb-tls-connect._tcp service found")
        connectTo(endpoint.host, endpoint.port)
    }

    /** Connect directly to a known host:port (skips mDNS — useful for local-loopback test rigs). */
    suspend fun connectTo(host: String, port: Int): Result = withContext(Dispatchers.IO) {
        if (connection != null) return@withContext Result.Success
        val conn = AdbConnection(host, port, keyPair())
        if (!conn.connect()) return@withContext Result.Failure("ADB connect $host:$port failed")
        connection = conn
        Log.i(TAG, "ADB connected to $host:$port")
        Result.Success
    }

    /** Push the agent dex from app assets to [AGENT_DEX_PATH] via the ADB `sync:` service. */
    suspend fun pushAgentDex(): Result = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext Result.Failure("not connected — call connect() first")
        val bytes = context.assets.open(AGENT_ASSET_PATH).use { it.readBytes() }
        if (conn.pushFile(bytes, AGENT_DEX_PATH)) {
            Log.i(TAG, "pushed ${bytes.size}B agent dex → $AGENT_DEX_PATH")
            Result.Success
        } else {
            Result.Failure("sync push to $AGENT_DEX_PATH failed")
        }
    }

    /**
     * `pm grant <pkg> WRITE_SECURE_SETTINGS`. Once granted, the app can flip wireless debugging
     * itself in the future (`Settings.Global.putInt("adb_wifi_enabled", 1)`), avoiding the
     * repeat-pairing UX on every reboot.
     */
    suspend fun grantWriteSecureSettings(): Result = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext Result.Failure("not connected")
        val pkg = context.packageName
        val out = conn.exec("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        // `pm grant` prints nothing on success, error text on failure.
        if (out.isBlank()) Result.Success
        else Result.Failure("pm grant returned: ${out.trim()}")
    }

    /**
     * Spawn the agent via `app_process`. After this, [AppProcessAgentBackend] should connect
     * to the agent's LocalSocket within a few hundred ms.
     *
     * The `(setsid ... &)` subshell-with-background pattern double-detaches: the inner
     * `setsid` creates a new session (so SIGHUP from the exec: shell's exit can't reach the
     * agent), and the outer subshell + `&` lets the foreground command return immediately so
     * the exec: stream can close cleanly. `nohup &` alone wasn't enough — the agent died
     * when the exec: stream closed.
     */
    suspend fun startAgent(): Result = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext Result.Failure("not connected")
        // Must use the `shell:` service, not `exec:`. The wireless-TLS adbd implementation
        // appears to track and kill processes spawned from an `exec:` transport when that
        // stream closes, defeating both `nohup` and `setsid`. The `shell:` service spawns
        // a pty-attached shell that survives stream close → backgrounded children inherit
        // the surviving shell, then we setsid them out of its session.
        val cmd = "setsid sh -c 'CLASSPATH=$AGENT_DEX_PATH exec app_process /system/bin " +
                "--nice-name=halo-agent com.halo.ring.agent.Main' " +
                "</dev/null >/data/local/tmp/halo-agent.log 2>&1 &"
        val out = conn.exec(cmd, service = "shell")
        Log.i(TAG, "startAgent issued; shell output: '${out.trim()}'")
        Result.Success
    }

    /** Releases the ADB connection. Safe to call multiple times. */
    fun disconnect() {
        connection?.close()
        connection = null
    }

    companion object {
        private const val TAG = "AdbBootstrap"
        /** Asset name `:agent:packageDex` produces — the wizard ships this to /data/local/tmp/. */
        const val AGENT_ASSET_PATH = "halo-agent.dex"
        /** Where the agent dex lives on-device after [pushAgentDex]. */
        const val AGENT_DEX_PATH = "/data/local/tmp/halo-agent.dex"
    }
}
