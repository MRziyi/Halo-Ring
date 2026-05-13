package com.halo.ring.adb

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.KeyPair

/**
 * Dev-rig shortcut: when running on a rooted Android (the OnePlus / Pixel test devices), skip
 * the SPAKE2 pairing dance entirely and write our public key straight into
 * `/data/misc/adb/adb_keys`. adbd auto-reloads that file on each connect, so on the next
 * `connect()` to the persistent TLS-connect port the client cert matches and TLS auth
 * succeeds. No pairing dialog needed.
 *
 * This unblocks the OnePlus-loopback workflow where the system pairing dialog auto-closes
 * the moment the user leaves Settings (taking adbd's pair port with it). On real AR glasses
 * we expect to NOT have root, so the wizard's overlay-based pairing flow remains the primary
 * path — this is a developer affordance, not the production code path.
 */
object RootBypass {

    private const val TAG = "RootBypass"
    private const val ADB_KEYS_PATH = "/data/misc/adb/adb_keys"

    data class Result(val ok: Boolean, val output: String)

    /** True if `su -c true` returns 0 within ~1s. Cached after first call. */
    @Volatile private var rootCache: Boolean? = null

    suspend fun isRootAvailable(): Boolean = rootCache ?: run {
        val ok = runRoot("true").ok
        rootCache = ok
        ok
    }

    /**
     * Append our public key to `/data/misc/adb/adb_keys` (idempotent — checked for duplicate
     * before append). The file is restored to system:shell 0640 after write since su writes
     * as root.
     */
    suspend fun installKey(keyPair: KeyPair, username: String = "halo-ring@phone"): Result {
        val pubKeyBytes = AdbCrypto.encodeAdbPublicKey(keyPair)
        val pubKeyB64 = Base64.encodeToString(pubKeyBytes, Base64.NO_WRAP)
        val keyLine = "$pubKeyB64 $username"

        val script = """
            set -e
            mkdir -p /data/misc/adb
            touch $ADB_KEYS_PATH
            # Idempotent: append only if line not already present.
            if ! grep -qxF '$keyLine' $ADB_KEYS_PATH 2>/dev/null; then
                echo '$keyLine' >> $ADB_KEYS_PATH
            fi
            chown system:shell $ADB_KEYS_PATH || true
            chmod 0640 $ADB_KEYS_PATH
            # SELinux: restore the adb_keys label so adbd is allowed to read it.
            restorecon $ADB_KEYS_PATH 2>/dev/null || true
            echo OK
        """.trimIndent()
        return runRoot(script)
    }

    /** Best-effort: nudge adbd to reload adb_keys. Usually unnecessary (adbd re-reads on each
     *  auth check), but harmless. We avoid `stop adbd / start adbd` because that kills the
     *  USB transport our dev workflow depends on. */
    suspend fun reloadAdbKeys(): Result = runRoot("kill -HUP $(pidof adbd) || true; echo OK")

    private suspend fun runRoot(cmd: String): Result = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val code = process.waitFor()
            Log.d(TAG, "su exit=$code cmd=${cmd.take(80)}…  out=${out.trim().take(200)}")
            Result(code == 0, out)
        } catch (e: Exception) {
            Log.d(TAG, "su not available: ${e.message}")
            Result(false, e.message.orEmpty())
        }
    }
}
