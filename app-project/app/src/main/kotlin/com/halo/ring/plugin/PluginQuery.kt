package com.halo.ring.plugin

import android.content.ContentResolver
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * One-shot wrapper around `ContentResolver.query()` against a plugin's actions provider
 * (Doc/18 §4). Pulls the cursor → list of [PluginAction]. Tolerant of missing columns +
 * `SecurityException` (the plugin's CP may have a stricter readPermission than we hold, or
 * Android may have changed its scoped-storage rules) — we surface an empty list rather than
 * crashing the picker.
 *
 * Caller responsibility: only invoke for packages we've already confirmed are plugins (have the
 * `halo.ring.plugin_version` meta-data). Random package CP queries waste time + risk perm errors.
 */
object PluginQuery {

    private const val TAG = "PluginQuery"

    private const val COL_ACTION_ID   = "action_id"
    private const val COL_LABEL       = "label"
    private const val COL_DESCRIPTION = "description"
    private const val COL_GROUP       = "group"

    /** Build the Doc/18 §4.3 query URI for the given plugin package. */
    fun listUri(pluginPackage: String): Uri =
        Uri.parse("content://$pluginPackage.halo_actions/list")

    /**
     * Query the plugin's CP. Returns null if the provider doesn't exist (caller should record an
     * empty-actions plugin), or a (possibly empty) list of [PluginAction]s otherwise.
     */
    fun query(resolver: ContentResolver, pluginPackage: String): List<PluginAction>? {
        val uri = listUri(pluginPackage)
        val cursor: Cursor? = try {
            resolver.query(uri, null, null, null, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "CP query denied for $pluginPackage: ${e.message}")
            return null
        } catch (e: Exception) {
            // Bad URI, dead provider, etc. — treat like "no provider".
            Log.w(TAG, "CP query failed for $pluginPackage: ${e.message}")
            return null
        }
        cursor ?: return null
        return cursor.use { c -> readAll(c, pluginPackage) }
    }

    private fun readAll(c: Cursor, pluginPackage: String): List<PluginAction> {
        if (!c.moveToFirst()) return emptyList()
        val idxAction = c.getColumnIndex(COL_ACTION_ID).takeIf { it >= 0 } ?: return emptyList()
        val idxLabel  = c.getColumnIndex(COL_LABEL).takeIf { it >= 0 } ?: return emptyList()
        val idxDesc   = c.getColumnIndex(COL_DESCRIPTION)
        val idxGroup  = c.getColumnIndex(COL_GROUP)
        val out = ArrayList<PluginAction>(c.count)
        do {
            val actionId = c.getString(idxAction) ?: continue
            val label    = c.getString(idxLabel) ?: continue
            if (actionId.isEmpty() || label.isEmpty()) continue
            out += PluginAction(
                pluginPackage = pluginPackage,
                actionId      = actionId,
                label         = label,
                description   = if (idxDesc  >= 0) c.getStringOrNull(idxDesc)  else null,
                group         = if (idxGroup >= 0) c.getStringOrNull(idxGroup) else null,
            )
        } while (c.moveToNext())
        return out
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        try { if (isNull(index)) null else getString(index) } catch (_: Exception) { null }

    /** Read the app label for a plugin, falling back to the package name on any error. */
    fun appLabel(pm: PackageManager, pluginPackage: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pluginPackage, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pluginPackage
    } catch (_: Exception) {
        pluginPackage
    }
}
