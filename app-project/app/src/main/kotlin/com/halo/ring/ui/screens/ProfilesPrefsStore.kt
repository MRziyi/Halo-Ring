package com.halo.ring.ui.screens

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.halo.ring.core.action.DefaultProfiles
import com.halo.ring.core.action.GlassActionCodec
import com.halo.ring.core.action.KeyMapProfile
import com.halo.ring.core.gesture.Gesture
import com.halo.ring.core.gesture.GestureConfig
import com.halo.ring.core.gesture.SystemGestures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.profilesPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "r08-profiles-prefs")

/**
 * DataStore-backed persistence for user-edited [KeyMapProfile]s and [SystemGestures] (Doc/13 §B13).
 *
 * Format: two top-level Preferences keys, each holding a JSON string. We use `org.json` (Android
 * built-in) instead of kotlinx-serialization to avoid a new dep + plugin. GlassAction values are
 * encoded via the pure [GlassActionCodec] in `:core` so round-trip is unit-testable.
 *
 * Missing / corrupt prefs ⇒ fall back to [DefaultProfiles.ALL] and a default [SystemGestures]; we
 * never crash on bad bytes. (See [GlassActionCodec.decode]'s forgiving fall-through.)
 *
 * **Persistence is incremental**: every change to the flow re-serialises the whole document. Cheap
 * because the document is tiny (<5 KB) and user edits are infrequent.
 */
class ProfilesPrefsStore(private val context: Context) {

    private object Keys {
        val ProfilesJson       = stringPreferencesKey("profiles_json")
        val SystemGesturesJson = stringPreferencesKey("system_gestures_json")
    }

    val profilesFlow: Flow<List<KeyMapProfile>> =
        context.profilesPrefsDataStore.data.map { p ->
            val json = p[Keys.ProfilesJson]
            if (json.isNullOrBlank()) DefaultProfiles.ALL
            else runCatching { decodeProfiles(json) }
                .onFailure { Log.w(TAG, "decodeProfiles failed; falling back to defaults: ${it.message}") }
                .getOrDefault(DefaultProfiles.ALL)
        }

    val systemGesturesFlow: Flow<SystemGestures> =
        context.profilesPrefsDataStore.data.map { p ->
            val json = p[Keys.SystemGesturesJson]
            if (json.isNullOrBlank()) SystemGestures()
            else runCatching { decodeSystemGestures(json) }
                .onFailure { Log.w(TAG, "decodeSystemGestures failed; falling back to defaults: ${it.message}") }
                .getOrDefault(SystemGestures())
        }

    suspend fun saveProfiles(profiles: List<KeyMapProfile>) {
        context.profilesPrefsDataStore.edit { it[Keys.ProfilesJson] = encodeProfiles(profiles) }
    }

    suspend fun saveSystemGestures(sg: SystemGestures) {
        context.profilesPrefsDataStore.edit { it[Keys.SystemGesturesJson] = encodeSystemGestures(sg) }
    }

    // ── encode ──────────────────────────────────────────────────────────────────────────────────

    private fun encodeProfiles(list: List<KeyMapProfile>): String {
        val arr = JSONArray()
        for (p in list) {
            val obj = JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("triggerPackages", JSONArray(p.triggerPackages))
                .put("config", encodeConfig(p.gestureConfig))
            val mapObj = JSONObject()
            for ((g, a) in p.map) mapObj.put(g.name, GlassActionCodec.encode(a))
            obj.put("map", mapObj)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun encodeConfig(c: GestureConfig): JSONObject = JSONObject()
        .put("multiTapWindowMs",          c.multiTapWindowMs)
        .put("comboWindowMs",             c.comboWindowMs)
        .put("optimisticSingleTap",       c.optimisticSingleTap)
        .put("awaitCombos",               c.awaitCombos)
        .put("enableTripleTap",           c.enableTripleTap)
        .put("enableQuadrupleTap",        c.enableQuadrupleTap)
        .put("awaitLongPressCombos",      c.awaitLongPressCombos)
        .put("longPressFollowupWindowMs", c.longPressFollowupWindowMs)
        .put("enableDoubleLongPress",     c.enableDoubleLongPress)
        .put("minRawIntervalMs",          c.minRawIntervalMs)
        .put("wakeSwallowCount",          c.wakeSwallowCount)

    private fun encodeSystemGestures(sg: SystemGestures): String = JSONObject()
        .put("wake",            sg.wake?.name)
        .put("sleep",           sg.sleep?.name)
        .put("profileCycle",    sg.profileCycle?.name)
        .put("peekHud",         sg.peekHud?.name)
        .put("forceReconnect",  sg.forceReconnect?.name)
        .toString()

    // ── decode ──────────────────────────────────────────────────────────────────────────────────

    private fun decodeProfiles(json: String): List<KeyMapProfile> {
        val arr = JSONArray(json)
        val out = ArrayList<KeyMapProfile>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val mapObj = obj.optJSONObject("map") ?: JSONObject()
            val bindings = mutableMapOf<Gesture, com.halo.ring.core.action.GlassAction>()
            for (key in mapObj.keys()) {
                val gesture = runCatching { Gesture.valueOf(key) }.getOrNull() ?: continue
                bindings[gesture] = GlassActionCodec.decode(mapObj.optString(key, ""))
            }
            val triggers = obj.optJSONArray("triggerPackages")?.let { jt ->
                List(jt.length()) { jt.getString(it) }
            } ?: emptyList()
            out += KeyMapProfile(
                id = obj.getString("id"),
                name = obj.optString("name", obj.getString("id")),
                map = bindings,
                gestureConfig = decodeConfig(obj.optJSONObject("config")),
                triggerPackages = triggers,
            )
        }
        return out
    }

    private fun decodeConfig(j: JSONObject?): GestureConfig {
        val d = GestureConfig()    // defaults; overlay any explicit fields
        if (j == null) return d
        return GestureConfig(
            multiTapWindowMs          = j.optLong("multiTapWindowMs",          d.multiTapWindowMs),
            comboWindowMs             = j.optLong("comboWindowMs",             d.comboWindowMs),
            optimisticSingleTap       = j.optBoolean("optimisticSingleTap",    d.optimisticSingleTap),
            awaitCombos               = j.optBoolean("awaitCombos",            d.awaitCombos),
            enableTripleTap           = j.optBoolean("enableTripleTap",        d.enableTripleTap),
            enableQuadrupleTap        = j.optBoolean("enableQuadrupleTap",     d.enableQuadrupleTap),
            awaitLongPressCombos      = j.optBoolean("awaitLongPressCombos",   d.awaitLongPressCombos),
            longPressFollowupWindowMs = j.optLong("longPressFollowupWindowMs", d.longPressFollowupWindowMs),
            enableDoubleLongPress     = j.optBoolean("enableDoubleLongPress",  d.enableDoubleLongPress),
            minRawIntervalMs          = j.optLong("minRawIntervalMs",          d.minRawIntervalMs),
            wakeSwallowCount          = j.optInt("wakeSwallowCount",           d.wakeSwallowCount),
        )
    }

    private fun decodeSystemGestures(json: String): SystemGestures {
        val obj = JSONObject(json)
        fun g(key: String): Gesture? =
            obj.optString(key, "").takeIf { it.isNotEmpty() }?.let {
                runCatching { Gesture.valueOf(it) }.getOrNull()
            }
        return SystemGestures(
            wake = g("wake"),
            sleep = g("sleep"),
            profileCycle = g("profileCycle"),
            peekHud = g("peekHud"),
            forceReconnect = g("forceReconnect"),
        )
    }

    companion object { private const val TAG = "ProfilesPrefsStore" }
}
