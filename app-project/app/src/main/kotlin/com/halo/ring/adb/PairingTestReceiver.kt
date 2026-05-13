package com.halo.ring.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.halo.ring.HaloRingApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only broadcast receiver for A-2 development. Lets the host machine kick off a pairing
 * attempt without UI interaction — useful while iterating on the SPAKE2 / TLS code path because
 * the pairing dialog on the device shows a fresh `host:port` + 6-digit code every time, and
 * rebuilding the APK to hard-code those would be painful.
 *
 * Usage from a host shell:
 * ```
 *   adb shell am broadcast \
 *       -n com.halo.ring.rokid/com.halo.ring.adb.PairingTestReceiver \
 *       -a com.halo.ring.TEST_PAIR \
 *       --es host 127.0.0.1 --ei port 39567 --es code 771335
 * ```
 *
 * Then watch `adb logcat -s AdbPairingClient:* AdbBootstrap:* PairTestRcv:*` for the result.
 *
 * Production note: this receiver does nothing security-sensitive on its own (the user has to
 * have the pairing dialog open + know the 6-digit code, which is the system-side enforcement),
 * but it's still an unprotected entry point and should NOT ship in release. The component is
 * registered with `tools:node="remove"` in the release manifest below.
 */
class PairingTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            Log.w(TAG, "ignoring intent with action=${intent.action}")
            return
        }
        val host = intent.getStringExtra("host") ?: "127.0.0.1"
        val port = intent.getIntExtra("port", 0)
        val code = intent.getStringExtra("code")
        val connectPort = intent.getIntExtra("connectPort", 0)  // optional: triggers full bootstrap
        if (port == 0 || code.isNullOrBlank()) {
            Log.e(TAG, "missing --ei port or --es code; got host=$host port=$port code=$code")
            return
        }

        Log.i(TAG, "BEGIN  host=$host  pairPort=$port  code=$code  connectPort=$connectPort")

        // Reuse the AppGraph so the keypair we generate here is the one the wizard / agent backend
        // will trust later in this process. (Caching to DataStore comes in A-2 Step 4.)
        val graph = (context.applicationContext as HaloRingApplication).graph
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val bootstrap = AdbBootstrap(context.applicationContext)
                val endpoint = AdbMdnsDiscovery.Endpoint(host, port)
                when (val pair = bootstrap.pairWithCode(code, endpoint)) {
                    is AdbBootstrap.Result.Success -> Log.i(TAG, "STEP 1 OK — paired")
                    is AdbBootstrap.Result.Failure -> {
                        Log.w(TAG, "END  pair FAIL — ${pair.message}")
                        return@launch
                    }
                }
                if (connectPort == 0) {
                    Log.i(TAG, "END  pair-only run (no connectPort given) — done")
                    return@launch
                }

                when (val c = bootstrap.connectTo(host, connectPort)) {
                    is AdbBootstrap.Result.Success -> Log.i(TAG, "STEP 2 OK — TLS connected")
                    is AdbBootstrap.Result.Failure -> {
                        Log.w(TAG, "END  connect FAIL — ${c.message}")
                        return@launch
                    }
                }

                when (val p = bootstrap.pushAgentDex()) {
                    is AdbBootstrap.Result.Success -> Log.i(TAG, "STEP 3 OK — agent dex pushed")
                    is AdbBootstrap.Result.Failure -> Log.w(TAG, "STEP 3 push FAIL — ${p.message}")
                }

                when (val g = bootstrap.grantWriteSecureSettings()) {
                    is AdbBootstrap.Result.Success -> Log.i(TAG, "STEP 4 OK — WRITE_SECURE_SETTINGS granted")
                    is AdbBootstrap.Result.Failure -> Log.w(TAG, "STEP 4 grant FAIL — ${g.message}")
                }

                when (val s = bootstrap.startAgent()) {
                    is AdbBootstrap.Result.Success -> Log.i(TAG, "STEP 5 OK — startAgent issued (verify via halo.agent socket)")
                    is AdbBootstrap.Result.Failure -> Log.w(TAG, "STEP 5 start FAIL — ${s.message}")
                }

                // Give the agent a beat to settle into its new session before tearing down the
                // ADB transport — anecdotally the agent disappears if we slam disconnect onto
                // the heels of the startAgent OKAY/CLSE.
                kotlinx.coroutines.delay(800)
                bootstrap.disconnect()
                Log.i(TAG, "END  full bootstrap done")
            } catch (t: Throwable) {
                Log.e(TAG, "END    EXCEPTION — ${t.message}", t)
            }
        }
    }

    companion object {
        private const val TAG = "PairTestRcv"
        const val ACTION = "com.halo.ring.TEST_PAIR"
    }
}
