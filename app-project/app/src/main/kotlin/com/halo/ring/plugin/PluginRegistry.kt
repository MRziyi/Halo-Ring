package com.halo.ring.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.halo.ring.core.action.GlassAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Halo Ring's discovery layer for Doc/18 plugin apps. Scans every installed package for the
 * `halo.ring.plugin_version = 1` manifest meta-data, then queries each matching package's
 * `ContentProvider` for its action list.
 *
 * ## Caching
 * Results are cached for [CACHE_TTL_MS] (30 s — Doc/18 §3.3). The next read inside the TTL is
 * O(1); outside, it triggers a fresh scan + CP roundtrips on the IO dispatcher (callers should
 * `refresh()` from a coroutine, not synchronously on the scheduler thread).
 *
 * ## Invalidation
 * A package-event [BroadcastReceiver] flips `cacheValid = false` on PACKAGE_ADDED / PACKAGE_REMOVED
 * / PACKAGE_REPLACED. The next read sees a stale cache and re-scans. The receiver is registered
 * by `HaloRingService.onCreate` and unregistered in `onDestroy`.
 *
 * ## Thread-safety
 * Reads can come from anywhere (Compose recomposition, foreground service callbacks). The
 * registry is internally synchronised; the underlying [StateFlow] is the canonical read path
 * for UI.
 */
class PluginRegistry(private val context: Context) {

    companion object {
        private const val TAG = "PluginRegistry"
        const val META_DATA_KEY = "halo.ring.plugin_version"
        const val PROTOCOL_VERSION = 1
        /** Cache freshness — Doc/18 §3.3. Aggressive enough that newly-installed plugins surface
         *  within a few seconds even without the PACKAGE_ADDED broadcast firing. */
        const val CACHE_TTL_MS = 30_000L
    }

    private val _plugins = MutableStateFlow<List<Plugin>>(emptyList())
    /** Latest discovered plugin list. Empty until [refresh] is called at least once. */
    val plugins: StateFlow<List<Plugin>> = _plugins

    @Volatile private var cacheStampMs: Long = 0L
    @Volatile private var cacheValid: Boolean = false

    private val packageEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            // PACKAGE_ADDED / REMOVED / REPLACED → next read forces a fresh scan. We don't auto-
            // refresh here because that would pull on the scheduler/UI thread; let the
            // ExternalPluginsScreen or ActionPicker's next open trigger the rebuild.
            invalidate()
        }
    }

    /** Wire up the package-event receiver. Idempotent (safe to call from service onCreate). */
    fun registerPackageEventReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")  // required for ACTION_PACKAGE_* — otherwise filter never matches
        }
        try {
            context.registerReceiver(packageEventReceiver, filter)
        } catch (e: Exception) {
            // Already registered (re-entrant path) — fine.
            Log.d(TAG, "registerPackageEventReceiver: ${e.message}")
        }
    }

    fun unregisterPackageEventReceiver() {
        try { context.unregisterReceiver(packageEventReceiver) } catch (_: IllegalArgumentException) { /* not registered */ }
    }

    /** Manually invalidate so the next [refresh] re-scans. */
    fun invalidate() {
        cacheValid = false
        cacheStampMs = 0L
    }

    /**
     * Re-scan installed packages + query each plugin's CP. Returns the resulting plugin list.
     * Always runs work — caller should debounce / wrap in a coroutine on Dispatchers.IO.
     *
     * Discovery shape: instead of `getInstalledApplications(GET_META_DATA)` (which on Android
     * 11+ returns only packages we have explicit visibility for, AND requires us to walk every
     * installed app to filter), we use `queryBroadcastReceivers(TRIGGER)` — only packages that
     * declared a receiver for our action are candidates. That's strictly the right superset:
     * a plugin without a TRIGGER receiver couldn't receive triggers anyway. The `<queries>`
     * element in our manifest grants visibility for matching packages, and from there we can
     * read application-level meta-data + query the CP.
     */
    fun refresh(): List<Plugin> {
        val pm = context.packageManager
        val triggerIntent = android.content.Intent(PluginTrigger.ACTION_TRIGGER)
        val receivers = try {
            pm.queryBroadcastReceivers(triggerIntent, 0)
        } catch (e: Exception) {
            Log.w(TAG, "queryBroadcastReceivers failed: ${e.message}")
            emptyList()
        }
        val candidatePkgs = receivers.map { it.activityInfo.packageName }.distinct()
        val pluginPkgs = candidatePkgs.mapNotNull { pkg ->
            val ai = try {
                pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "$pkg vanished mid-scan"); return@mapNotNull null
            }
            val version = ai.metaData?.getInt(META_DATA_KEY, 0) ?: 0
            if (version == 0) return@mapNotNull null
            pkg to version
        }
        val resolver = context.contentResolver
        val plugins = pluginPkgs.map { (pkg, version) ->
            val appName = PluginQuery.appLabel(pm, pkg)
            if (version != PROTOCOL_VERSION) {
                // Doc/18 §13 + test T2: future versions are visible but inert (we know we can't
                // talk to them safely). Surface them in the settings page so the wearer knows
                // there's an out-of-date pairing.
                return@map Plugin(
                    packageName = pkg, appName = appName, protocolVersion = version,
                    actions = emptyList(),
                    queryError = "Unsupported plugin protocol version $version (expected $PROTOCOL_VERSION)",
                )
            }
            val actions = PluginQuery.query(resolver, pkg)
            if (actions == null) {
                Plugin(
                    packageName = pkg, appName = appName, protocolVersion = version,
                    actions = emptyList(),
                    queryError = "No ContentProvider at ${PluginQuery.listUri(pkg)}",
                )
            } else {
                Plugin(pkg, appName, version, actions)
            }
        }
        _plugins.value = plugins
        cacheStampMs = SystemClock.uptimeMillis()
        cacheValid = true
        Log.i(TAG, "refresh: ${plugins.size} plugin(s); ${plugins.sumOf { it.actions.size }} action(s)")
        return plugins
    }

    /**
     * Read the cached list; refresh if stale. Returns the snapshot you'd get from
     * [plugins.value] after — convenient for one-shot callers (e.g. the Action Picker on open).
     */
    fun getOrRefresh(): List<Plugin> {
        val now = SystemClock.uptimeMillis()
        val fresh = cacheValid && (now - cacheStampMs) < CACHE_TTL_MS
        return if (fresh) _plugins.value else refresh()
    }

    /**
     * Find a single plugin by package. Walks the current cached list — refreshes if cold.
     * Used by HaloRingService's [GlassAction.PluginAction] dispatch to verify the owner is still
     * installed before sending the broadcast (cheap defense against firing to a ghost package).
     */
    fun plugin(pkg: String): Plugin? = getOrRefresh().firstOrNull { it.packageName == pkg }
}
