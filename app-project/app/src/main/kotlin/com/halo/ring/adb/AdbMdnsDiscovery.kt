package com.halo.ring.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Discovers ADB pairing / connect ports on the local network via mDNS (Android's [NsdManager]).
 *
 * Wireless ADB advertises two services:
 *   `_adb-tls-pairing._tcp.` — the port where the device accepts the SPAKE2 pairing handshake
 *   `_adb-tls-connect._tcp.` — the port where authenticated clients open the actual TLS session
 *
 * After the pairing wizard, the device generally rotates the connect port on each reboot, so we
 * re-discover on every connect rather than persisting.
 */
class AdbMdnsDiscovery(private val context: Context) {

    private val nsd: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    data class Endpoint(val host: String, val port: Int)

    /**
     * Returns the first matching endpoint within [timeoutMs], or null on timeout.
     *
     * NsdManager's resolve API is async; we hide that behind a suspend wrapper. We pick the first
     * candidate — refining (e.g. preferring a specific MAC) is a future improvement.
     */
    suspend fun discover(serviceType: String, timeoutMs: Long = 10_000L): Endpoint? {
        val manager = nsd ?: return null.also { Log.w(TAG, "NSD service unavailable") }
        return withTimeoutOrNull(timeoutMs) {
            val info: NsdServiceInfo = suspendCancellableCoroutine { cont ->
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(t: String?) = Unit
                    override fun onStartDiscoveryFailed(t: String?, e: Int) {
                        Log.w(TAG, "discovery start failed: $e")
                        if (cont.isActive) cont.resume(NsdServiceInfo())
                    }
                    override fun onStopDiscoveryFailed(t: String?, e: Int) = Unit
                    override fun onDiscoveryStopped(t: String?) = Unit
                    override fun onServiceFound(svc: NsdServiceInfo) {
                        if (svc.serviceType?.startsWith(serviceType.trimEnd('.')) == true) {
                            manager.stopServiceDiscovery(this)
                            if (cont.isActive) cont.resume(svc)
                        }
                    }
                    override fun onServiceLost(svc: NsdServiceInfo?) = Unit
                }
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                cont.invokeOnCancellation {
                    runCatching { manager.stopServiceDiscovery(listener) }
                }
            }
            resolve(manager, info)
        }
    }

    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): Endpoint? =
        suspendCancellableCoroutine { cont ->
            val cb = object : NsdManager.ResolveListener {
                override fun onResolveFailed(svc: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "resolve failed: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(svc: NsdServiceInfo) {
                    val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        @Suppress("DEPRECATION")
                        svc.host?.hostAddress
                    } else {
                        @Suppress("DEPRECATION")
                        svc.host?.hostAddress
                    }
                    if (host == null) {
                        if (cont.isActive) cont.resume(null)
                        return
                    }
                    if (cont.isActive) cont.resume(Endpoint(host, svc.port))
                }
            }
            @Suppress("DEPRECATION")
            manager.resolveService(info, cb)
        }

    companion object {
        private const val TAG = "AdbMdnsDiscovery"
        const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
        const val CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp."
    }
}
