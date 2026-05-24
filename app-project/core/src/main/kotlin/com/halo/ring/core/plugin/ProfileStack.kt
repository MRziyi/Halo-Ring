package com.halo.ring.core.plugin

import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.gesture.Gesture

/**
 * Stack of temporary gesture-binding overlays pushed by external plugins (Doc/18 §6). Pure JVM so
 * the push/pop / lookup / dead-owner-prune logic is unit-testable without Android.
 *
 * ## Semantics
 *  - **LIFO**: the most-recently pushed `PushedProfile` wins for any gesture it binds.
 *  - **Fall-through**: if the topmost profile doesn't bind a gesture, the next-newest is consulted,
 *    and so on. A gesture not bound by any pushed profile falls through to the wearer's active
 *    `KeyMapProfile` (handled in `InteractionRouter`, not here).
 *  - **System gestures** (TRIPLE_TAP / QUADRUPLE_TAP / LP+SWIPE / DOUBLE_LONG_PRESS) ALWAYS bypass
 *    this stack — Halo Ring's existing system-gesture priority is preserved upstream of the lookup.
 *  - **Owner-package death**: when an external app's process dies (or `PROFILE_POP` arrives),
 *    every profile it owns is removed atomically. A crashed Constellation can't strand the wearer
 *    on `constellation_hud`.
 *  - **Re-push**: pushing the same `(ownerPackage, profileId)` again replaces the previous entry
 *    in-place at the TOP of the stack (so an overlay app updating its bindings doesn't multiply
 *    its frames).
 *
 * Thread-safety: callers must synchronise. In Halo Ring the foreground service serialises all
 * mutations on the scheduler thread, matching the rest of the pipeline.
 */
class ProfileStack {

    /** One overlay in the stack. */
    data class PushedProfile(
        /** Plugin-defined identifier (e.g. `"constellation_hud"`). Unique per owner package. */
        val profileId: String,
        /** Owning package (the plugin that pushed). Used for dead-owner prune + permission checks. */
        val ownerPackage: String,
        /** Gesture → action mapping for this overlay. */
        val bindings: Map<Gesture, GlassAction>,
        /** Wall-clock ms when pushed (used for stale-cleanup heuristics). */
        val pushedAtMs: Long,
    )

    private val frames = mutableListOf<PushedProfile>()

    /** Read-only snapshot — defensive copy so external observers can't mutate. */
    fun snapshot(): List<PushedProfile> = frames.toList()

    /** Current stack depth. */
    fun size(): Int = frames.size

    /**
     * Push a new overlay. If an entry already exists for `(ownerPackage, profileId)`, it's
     * removed first and the new one lands on top — this matches the spec's "re-push refreshes
     * the bindings" expectation when an overlay app wants to update its map without popping +
     * re-pushing in two broadcasts.
     */
    fun push(profile: PushedProfile) {
        frames.removeAll { it.profileId == profile.profileId && it.ownerPackage == profile.ownerPackage }
        frames.add(profile)
    }

    /**
     * Pop a specific (owner, profileId). Returns true if anything was removed. No-op if not found
     * (idempotent — a duplicate POP from a flaky broadcast is harmless).
     */
    fun pop(ownerPackage: String, profileId: String): Boolean =
        frames.removeAll { it.profileId == profileId && it.ownerPackage == ownerPackage }

    /**
     * Remove every overlay owned by [ownerPackage]. Called when we detect the external process
     * has died (Doc/18 §6.4: crashed overlay app must not strand gestures). Returns the number of
     * frames removed (useful for telemetry / logging).
     */
    fun dropOwner(ownerPackage: String): Int {
        val before = frames.size
        frames.removeAll { it.ownerPackage == ownerPackage }
        return before - frames.size
    }

    /**
     * Look up a binding for [gesture]. Walks the stack top-down; first hit wins. Returns null if
     * no pushed profile binds this gesture — the caller should then consult the wearer's active
     * `KeyMapProfile`.
     */
    fun lookup(gesture: Gesture): GlassAction? {
        // Walk in reverse so the newest entry wins.
        for (i in frames.indices.reversed()) {
            frames[i].bindings[gesture]?.let { return it }
        }
        return null
    }

    /** Drop everything. Test + reset hook; the service uses this on stop(). */
    fun clear() { frames.clear() }
}

/**
 * Parser for the `bindings_json` extra in Doc/18 §6.3. Hand-rolled (no `org.json` dependency in
 * `:core` — that module is pure JVM). Each value is a small JSON object: `{ "type": ..., "package":
 * ..., "action_id": ..., "label": ... }`. Only `type: "external"` is supported in v1; future
 * versions can add `"builtin"` to reference Halo Ring's own action names.
 *
 * The parser is **forgiving**: unknown keys / unknown gesture names / malformed entries are
 * skipped silently rather than crashing the whole push. Returns the subset that successfully
 * parsed. An empty result is legal — it just means the push had no useful bindings.
 *
 * Lives next to [ProfileStack] so the same module that owns the data structure owns its
 * serialisation contract. Pure-string in / map out.
 */
object PluginBindingsParser {

    /**
     * Parse a JSON object string into a `Map<Gesture, GlassAction>`. Unparseable input returns an
     * empty map (caller can detect via .isEmpty() if it wants to log a warning).
     */
    fun parse(json: String): Map<Gesture, GlassAction> {
        val entries = parseTopLevelObject(json) ?: return emptyMap()
        val out = mutableMapOf<Gesture, GlassAction>()
        for ((gestureName, valueJson) in entries) {
            val gesture = parseGesture(gestureName) ?: continue
            val action = parseAction(valueJson) ?: continue
            out[gesture] = action
        }
        return out
    }

    /** Recognise the canonical [Gesture] enum names; also accept a couple of compact aliases the
     *  spec uses in examples (`LP+SWIPE_UP` etc.). */
    private fun parseGesture(name: String): Gesture? {
        Gesture.values().firstOrNull { it.name == name }?.let { return it }
        return when (name) {
            "LP", "LONG_PRESS"                -> Gesture.LONG_PRESS
            "LP+SWIPE_UP"                     -> Gesture.LONG_PRESS_SWIPE_UP
            "LP+SWIPE_DOWN"                   -> Gesture.LONG_PRESS_SWIPE_DOWN
            "DT", "DOUBLE_TAP"                -> Gesture.DOUBLE_TAP
            "DT+SWIPE_UP"                     -> Gesture.DOUBLE_TAP_SWIPE_UP
            "DT+SWIPE_DOWN"                   -> Gesture.DOUBLE_TAP_SWIPE_DOWN
            "TT", "TRIPLE_TAP"                -> Gesture.TRIPLE_TAP
            "QT", "QUADRUPLE_TAP", "QUAD_TAP" -> Gesture.QUADRUPLE_TAP
            "2xLP", "DOUBLE_LP", "2LP"        -> Gesture.DOUBLE_LONG_PRESS
            else -> null
        }
    }

    private fun parseAction(valueJson: String): GlassAction? {
        val fields = parseInnerObject(valueJson) ?: return null
        val type = fields["type"] ?: return null
        if (type != "external") return null  // v1 only supports external; ignore other types
        val pkg = fields["package"]?.takeIf { it.isNotEmpty() } ?: return null
        val actionId = fields["action_id"]?.takeIf { it.isNotEmpty() } ?: return null
        val label = fields["label"] ?: actionId  // optional; fall back to actionId
        return GlassAction.PluginAction(pkg, actionId, label)
    }

    /** Minimal JSON object parser — accepts `{"k": "v", "k2": {"nested": ...}}`. Returns null on
     *  malformed input. The value strings are returned verbatim (still JSON-formatted) so a value
     *  that's itself an object can be re-parsed by [parseInnerObject]. */
    internal fun parseTopLevelObject(s: String): Map<String, String>? {
        val trimmed = s.trim()
        if (trimmed.length < 2 || trimmed.first() != '{' || trimmed.last() != '}') return null
        val body = trimmed.substring(1, trimmed.length - 1)
        return scanEntries(body)
    }

    /** Inner object: same shape but all values are bare strings (no nested objects). */
    internal fun parseInnerObject(s: String): Map<String, String>? {
        val trimmed = s.trim()
        if (trimmed.length < 2 || trimmed.first() != '{' || trimmed.last() != '}') return null
        val body = trimmed.substring(1, trimmed.length - 1)
        val raw = scanEntries(body) ?: return null
        // Unquote string values (drop surrounding "" if present).
        return raw.mapValues { (_, v) ->
            val vt = v.trim()
            if (vt.length >= 2 && vt.first() == '"' && vt.last() == '"') {
                unescapeJsonString(vt.substring(1, vt.length - 1))
            } else {
                vt
            }
        }
    }

    /** Walk a comma-separated `"key": value` list. Values can be quoted strings OR balanced
     *  `{...}` objects. Returns null if any entry is malformed. */
    private fun scanEntries(body: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = body.length
        while (i < n) {
            // Skip whitespace + commas.
            while (i < n && (body[i].isWhitespace() || body[i] == ',')) i++
            if (i >= n) break
            // Key: expect "string"
            if (body[i] != '"') return null
            val keyEnd = findClosingQuote(body, i + 1) ?: return null
            val key = unescapeJsonString(body.substring(i + 1, keyEnd))
            i = keyEnd + 1
            // Colon.
            while (i < n && body[i].isWhitespace()) i++
            if (i >= n || body[i] != ':') return null
            i++
            while (i < n && body[i].isWhitespace()) i++
            if (i >= n) return null
            // Value: either "string" or {object}
            val valueStart = i
            when (body[i]) {
                '"' -> {
                    val end = findClosingQuote(body, i + 1) ?: return null
                    out[key] = body.substring(valueStart, end + 1)  // keep quotes
                    i = end + 1
                }
                '{' -> {
                    val end = findClosingBrace(body, i) ?: return null
                    out[key] = body.substring(valueStart, end + 1)
                    i = end + 1
                }
                else -> return null  // numbers / bools not in our spec
            }
        }
        return out
    }

    private fun findClosingQuote(s: String, from: Int): Int? {
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { i += 2; continue }
            if (c == '"') return i
            i++
        }
        return null
    }

    private fun findClosingBrace(s: String, openAt: Int): Int? {
        var depth = 0
        var i = openAt
        var inString = false
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\' && i + 1 < s.length) { i += 2; continue }
                if (c == '"') inString = false
                i++
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    private fun unescapeJsonString(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> { sb.append(c); sb.append(s[i + 1]) }
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
