package com.halo.ring.adb

import android.content.Context
import android.util.Log
import java.security.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Embedded ADB-over-Wi-Fi bootstrap (Doc/04 §5, Doc/13 §B12). Coordinates:
 *
 *  1. **Generate** the client RSA-2048 keypair + self-signed cert (via [AdbCrypto]) — needed once,
 *     cached for reuse.
 *  2. **Discover** the pairing port via mDNS (`_adb-tls-pairing._tcp.`) using [AdbMdnsDiscovery].
 *  3. **Pair** with the 6-digit code the user reads off the OS pairing screen (SPAKE2 + cert
 *     exchange) — [AdbPairingClient].
 *  4. **Discover connect port** via mDNS (`_adb-tls-connect._tcp.`).
 *  5. **Connect** over TLS using the cached keypair + cert — [AdbConnection].
 *  6. **Push** the agent dex via ADB's `sync:` service.
 *  7. **Exec** `app_process` to start the agent.
 *
 * **Status: end-to-end verified on OnePlus 9 Pro / Android 14 loopback** (audit-pass-h B12-real;
 * Doc/15 §4 documents the five blockers cleared in that session). Hardware verification on real
 * Rokid + RayNeo glasses is C7/C8 (see Doc/13 §2 Priority C).
 */
class AdbBootstrap(private val context: Context) {

    private val mdns = AdbMdnsDiscovery(context)
    private val keyStore = AdbKeyStore(context)

    /** Lazily loaded from [keyStore] on first use, then memoised. */
    @Volatile private var clientKeyPair: KeyPair? = null

    /** Set by [connect]; reused by push / grant / startAgent within the same bootstrap session. */
    @Volatile private var connection: AdbConnection? = null

    /** Port [connectTo] last connected on. Lets the wizard tell whether we came up on the
     *  Wi-Fi-independent persistent TCP port ([PERSISTENT_TCP_PORT]) or the transient wireless one. */
    @Volatile private var lastConnectedPort = 0

    /** Single-flight guard for [bootRecoverAgent]. The service fires recovery from two places
     *  (onCreate + the Wi-Fi onAvailable callback); without this they ran concurrently on
     *  separate instances and raced on the dex push, crashing one agent with ClassNotFoundException
     *  (observed 2026-05-28). Now that this is an [AppGraph]-scoped singleton, the lock serialises
     *  them: the second caller blocks, then finds the agent already up and returns. */
    private val recoveryMutex = Mutex()

    /**
     * Serialise an externally-driven bootstrap sequence (the wizard's pair / reconnect, which call
     * [connect]/[pushAgentDex]/[startAgent] directly) against [bootRecoverAgent] so the two never
     * run concurrently on this singleton — they'd otherwise race on the shared dex push + agent
     * socket exactly like the two boot-recovery callers used to.
     */
    suspend fun <T> withRecoveryLock(block: suspend () -> T): T = recoveryMutex.withLock { block() }

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
    suspend fun bootRecoverAgent(): Result = recoveryMutex.withLock { withContext(Dispatchers.IO) {
        if (keyStore.load() == null) {
            Log.i(TAG, "bootRecover: no persisted keypair — user hasn't completed wizard yet, skipping")
            return@withContext Result.Failure("not paired yet")
        }
        if (isAgentAlive()) {
            Log.i(TAG, "bootRecover: agent already alive on @halo.agent, nothing to do")
            return@withContext Result.Success
        }
        // Agent is down → drop any stale connection a previous (now-dead) recovery left held,
        // so connect() re-establishes a fresh loopback session instead of reusing a dead socket.
        disconnect()

        // Preferred path: the persistent Wi-Fi-independent TCP port. Once provisioned it's up on
        // every boot with NO Wi-Fi, so try it first and don't touch the radio.
        var connected = connect() is Result.Success
        if (!connected) {
            // Not provisioned yet (or port not up) → fall back to wireless, which needs Wi-Fi.
            Log.i(TAG, "bootRecover: persistent tcp unavailable, falling back to wireless (needs Wi-Fi)")
            ensureWifiEnabled()
            ensureWirelessDebugEnabled()
            // Give adbd time to bind its TLS port after adb_wifi_enabled flips. ~3-4 s on Rokid.
            kotlinx.coroutines.delay(4_000)
            when (val r = connect()) {
                is Result.Failure -> {
                    Log.w(TAG, "bootRecover: connect failed: ${r.message}")
                    return@withContext r
                }
                else -> Unit
            }
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
        // Poll for agent socket within the live ADB session (up to 3 s).
        // On success, leave the connection open — agent runs in shell foreground and dies at disconnect.
        for (i in 0..14) {
            kotlinx.coroutines.delay(200)
            if (isAgentAlive()) {
                Log.i(TAG, "bootRecover: agent up")
                return@withContext Result.Success
            }
        }
        Log.w(TAG, "bootRecover: agent socket never came up (exec failure?)")
        disconnect()
        Result.Failure("agent socket not reachable — exec may have failed")
    } }

    private fun isAgentAlive(): Boolean = try {
        android.net.LocalSocket().use { s ->
            s.connect(android.net.LocalSocketAddress(
                "halo.agent", android.net.LocalSocketAddress.Namespace.ABSTRACT))
            true
        }
    } catch (_: Exception) { false }

    /**
     * Best-effort Wi-Fi auto-enable on boot. Two complementary paths:
     *   1. [WifiManager.setWifiEnabled] — deprecated for third-party apps on Android 10+ but may
     *      work on OEM builds (Rokid YodaOS has relaxed restrictions).
     *   2. Write `wifi_on=1` to [Settings.Global] — some OEM Wi-Fi stacks observe this key at
     *      runtime; noop on stock AOSP but harmless to write.
     * Both are fire-and-forget; callers wait for [ensureWirelessDebugEnabled] + the settling
     * delay anyway, which gives the radio time to come up.
     */
    private fun ensureWifiEnabled() {
        val wm = context.getSystemService(android.net.wifi.WifiManager::class.java) ?: return
        if (wm.isWifiEnabled) return
        @Suppress("DEPRECATION")
        try {
            val ok = wm.setWifiEnabled(true)
            Log.i(TAG, "ensureWifiEnabled: setWifiEnabled(true) returned $ok")
        } catch (e: Exception) {
            Log.i(TAG, "ensureWifiEnabled: setWifiEnabled denied (${e.message})")
        }
        try {
            android.provider.Settings.Global.putInt(context.contentResolver, "wifi_on", 1)
            Log.i(TAG, "ensureWifiEnabled: wrote wifi_on=1 to Settings.Global")
        } catch (_: Exception) {}
    }

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
     * Connect to the device's ADB service over loopback. Must be called before [pushAgentDex] /
     * [grantWriteSecureSettings] / [startAgent]. Idempotent.
     *
     * Transport preference:
     *  1. **Persistent TCP port ([PERSISTENT_TCP_PORT])** — once [migrateToPersistentTcp] has run,
     *     adbd listens here on every boot (driven by `persist.adb.tcp.port`) and it is **independent
     *     of Wi-Fi**: the radio can be off and the user can be miles from any AP. This is the
     *     steady-state path and needs no Wi-Fi.
     *  2. **Wireless TLS port** (`service.adb.tls.port` sysprop / `adb_wifi_port` setting) — only
     *     used during first-run pairing, before we've migrated. Dies when Wi-Fi disconnects.
     *  3. **mDNS** — last-ditch discovery for AOSP builds that store the port elsewhere.
     */
    suspend fun connect(): Result = withContext(Dispatchers.IO) {
        if (connection != null) return@withContext Result.Success

        // 1. Persistent, Wi-Fi-independent loopback port (preferred once provisioned).
        val tcp = connectTo("127.0.0.1", PERSISTENT_TCP_PORT)
        if (tcp is Result.Success) {
            Log.i(TAG, "connect: via persistent tcp $PERSISTENT_TCP_PORT (Wi-Fi-independent)")
            return@withContext tcp
        }
        Log.i(TAG, "connect: persistent tcp $PERSISTENT_TCP_PORT unavailable, trying wireless TLS port")

        // 2. Wireless TLS port — first-run only (before migration), needs Wi-Fi.
        val localPort = readAdbWifiPort()
        if (localPort > 0) {
            Log.i(TAG, "connect: adb_wifi_port=$localPort → 127.0.0.1 (wireless)")
            val w = connectTo("127.0.0.1", localPort)
            if (w is Result.Success) return@withContext w
        }

        // 3. mDNS fallback.
        Log.i(TAG, "connect: no loopback port, trying mDNS")
        val endpoint = mdns.discover(AdbMdnsDiscovery.CONNECT_SERVICE_TYPE)
            ?: return@withContext Result.Failure("no ADB transport (tcp $PERSISTENT_TCP_PORT closed, no wireless port, no mDNS)")
        connectTo(endpoint.host, endpoint.port)
    }

    /**
     * First-run migration: switch adbd onto a **Wi-Fi-independent** loopback TCP port that persists
     * across reboots, then reconnect to it. After this, [connect] uses [PERSISTENT_TCP_PORT] and the
     * agent no longer depends on Wi-Fi or Wireless Debugging (which Android tears down the moment
     * Wi-Fi disconnects / on reboot).
     *
     *  - `persist.adb.tcp.port` → adbd opens the port on every boot (verified: Rokid's init honours
     *    it even on a `user` build, with no Wi-Fi and no agent).
     *  - `service.adb.tcp.port` + `ctl.restart adbd` → opens it right now for the current session.
     *
     * Assumes an active connection with shell access (the wireless one from pairing). That
     * connection is dropped by the adbd restart; we reconnect via [connect] (which now prefers the
     * persistent port). Both setprops are `shell_prop`-writable — no root needed.
     */
    suspend fun migrateToPersistentTcp(): Result = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext Result.Failure("not connected")
        Log.i(TAG, "migrateToPersistentTcp: persist.adb.tcp.port=$PERSISTENT_TCP_PORT + restart adbd")
        conn.exec("setprop persist.adb.tcp.port $PERSISTENT_TCP_PORT", service = "shell")
        conn.exec("setprop service.adb.tcp.port $PERSISTENT_TCP_PORT", service = "shell")
        conn.exec("setprop ctl.restart adbd", service = "shell")  // drops `connection`
        disconnect()
        kotlinx.coroutines.delay(4_000)  // adbd takes ~3-4 s to rebind on Rokid YodaOS
        connect()  // now prefers 127.0.0.1:$PERSISTENT_TCP_PORT
    }

    /** True if the current connection is on the Wi-Fi-independent persistent TCP port. */
    fun isOnPersistentTcp(): Boolean = connection != null && lastConnectedPort == PERSISTENT_TCP_PORT

    private fun readAdbWifiPort(): Int {
        val cr = context.contentResolver
        for (read in listOf<() -> Int>(
            // Rokid YodaOS: adbd writes the TLS port here, Settings.Secure is empty.
            { sysprop("service.adb.tls.port") },
            // Stock Android 11–13: Settings.Secure / Settings.Global.
            { android.provider.Settings.Secure.getInt(cr, "adb_wifi_port", 0) },
            { android.provider.Settings.Global.getInt(cr, "adb_wifi_port", 0) },
        )) {
            try { val p = read(); if (p > 0) return p } catch (_: Exception) {}
        }
        return 0
    }

    /** Read a system property via reflection (android.os.SystemProperties is @hide). */
    private fun sysprop(key: String): Int {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        return (get.invoke(null, key, "0") as? String)?.toIntOrNull() ?: 0
    }

    /** Connect directly to a known host:port (skips mDNS — useful for local-loopback test rigs). */
    suspend fun connectTo(host: String, port: Int): Result = withContext(Dispatchers.IO) {
        if (connection != null) return@withContext Result.Success
        val conn = AdbConnection(host, port, keyPair())
        if (!conn.connect()) return@withContext Result.Failure("ADB connect $host:$port failed")
        connection = conn
        lastConnectedPort = port
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
     * Grant SYSTEM_ALERT_WINDOW (the "draw over other apps" appop) so the HUD overlay can be created.
     * Without it `Settings.canDrawOverlays` is false and every `hud?.show()` is a silent no-op — the
     * user sees no profile-switch / status prompts (root-caused 2026-05-28). It's an appop, so we use
     * `appops set` (shell uid via the agent), not `pm grant`. Best-effort: log + continue on failure.
     */
    suspend fun grantOverlayPermission(): Result = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext Result.Failure("not connected")
        val pkg = context.packageName
        val out = conn.exec("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        if (out.isBlank() || out.contains("Success", ignoreCase = true)) Result.Success
        else Result.Failure("appops set returned: ${out.trim()}")
    }

    /**
     * Enable our [HaloRingAccessibilityService] via shell. Direct `Settings.Secure.putString` from the
     * app (even with WRITE_SECURE_SETTINGS) writes the value but Android refuses to bind the service
     * for security (verified on-device 2026-05-29: setting was written but no events fired). Doing
     * it through the agent (shell uid) is honoured. Idempotent — appends only if missing.
     */
    suspend fun grantAccessibilityService(): Result = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext Result.Failure("not connected")
        val pkg = context.packageName
        val svc = "$pkg/com.halo.ring.accessibility.HaloRingAccessibilityService"
        // Idempotent overwrite — the only a11y service this build cares about. (`settings put` is
        // silent on success, prints to stderr on permission denial.)
        val out = conn.exec(
            "settings put secure enabled_accessibility_services $svc; " +
                "settings put secure accessibility_enabled 1"
        )
        if (out.isBlank()) Result.Success else Result.Failure("a11y enable returned: ${out.trim()}")
    }

    /**
     * True if the agent's Unix abstract socket is reachable on this device.
     * Use as a cheap liveness probe during and after a bootstrap session.
     */
    suspend fun checkAgentAlive(): Boolean = withContext(Dispatchers.IO) { isAgentAlive() }

    /**
     * Spawn the agent via `app_process` and keep the ADB shell stream open.
     *
     * On Rokid YodaOS, TCP/TLS connections from the app kill ALL background children
     * (`&`) of the shell session the moment the shell exits — regardless of `nohup` or
     * `setsid` (tested 2026-05-28). The only reliable path is to run the agent in the
     * shell FOREGROUND (no `&`) so the shell stays alive, and keep the ADB connection
     * open in a fire-and-forget coroutine. The agent lifetime is then tied to the loopback
     * ADB connection, which survives Wi-Fi toggle (127.0.0.1 is always routable).
     */
    suspend fun startAgent(): Result = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext Result.Failure("not connected")
        // Step 1: write the launcher script via shell: so that '>' redirection is honoured.
        val scriptContent = "CLASSPATH=$AGENT_DEX_PATH exec app_process /system/bin " +
            "--nice-name=halo-agent com.halo.ring.agent.Main"
        val writeOut = conn.exec(
            "echo '$scriptContent' > $AGENT_START_SCRIPT && chmod 755 $AGENT_START_SCRIPT && echo ok",
            service = "shell",
        )
        if (!writeOut.trim().endsWith("ok")) {
            Log.w(TAG, "startAgent: write launcher script unexpected output: '$writeOut'")
        }
        // Step 2: open a persistent shell stream (no CMD_CLSE) so the agent runs in the
        // stream's foreground for the lifetime of this AdbConnection. On Rokid YodaOS the
        // TCP/loopback ADB transport kills backgrounded ('&') children at stream close, but
        // a process in the stream foreground stays alive as long as the socket is open.
        val launched = conn.execDetach("$AGENT_START_SCRIPT </dev/null >$AGENT_LOG_PATH 2>&1")
        Log.i(TAG, "startAgent: execDetach launched=$launched")
        if (!launched) return@withContext Result.Failure("failed to open detached stream for agent")
        Result.Success
    }

    /** Releases the ADB connection. Safe to call multiple times. */
    fun disconnect() {
        connection?.close()
        connection = null
        lastConnectedPort = 0
    }

    companion object {
        private const val TAG = "AdbBootstrap"
        /** Asset name `:agent:packageDex` produces — the wizard ships this to /data/local/tmp/. */
        const val AGENT_ASSET_PATH = "halo-agent.dex"
        /** Where the agent dex lives on-device after [pushAgentDex]. */
        const val AGENT_DEX_PATH = "/data/local/tmp/halo-agent.dex"
        /** Launcher script written by [startAgent]; survives ADB sessions. */
        const val AGENT_START_SCRIPT = "/data/local/tmp/halo-start.sh"
        /** stdout+stderr from the agent process; should have JVM startup lines if healthy. */
        const val AGENT_LOG_PATH = "/data/local/tmp/halo-agent.log"
        /** Wi-Fi-independent loopback ADB port. adbd binds it on every boot once
         *  `persist.adb.tcp.port` is set (see [migrateToPersistentTcp]); 127.0.0.1 is always
         *  routable, so the agent survives Wi-Fi off / leaving the AP / reboot. */
        const val PERSISTENT_TCP_PORT = 5555
    }
}
