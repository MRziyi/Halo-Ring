package com.halo.ring.core.inject

import com.halo.ring.core.device.InjectionPrimitive

/**
 * Pure encoder for the line protocol spoken by [com.halo.ring.agent.Main]. Lives in `:core` so the
 * format is JVM-testable without spinning up an Android emulator.
 *
 * See `agent/src/main/kotlin/com/r08remote/agent/Main.kt` for the full protocol description.
 *
 * - Returns the wire string for a primitive the agent supports (KEY / TAP / SWIPE / AM / BC / SH).
 * - Returns `null` for primitives the agent **cannot** execute (currently only `A11yGlobal`, which
 *   only the `AccessibilityBackend` can do). The router treats unencodable primitives as
 *   "skip" rather than "fail" so a fallback primitive on the same action's list can still fire.
 */
object AgentWireProtocol {

    fun encode(primitive: InjectionPrimitive): String? = when (primitive) {
        is InjectionPrimitive.Key   -> "KEY ${primitive.keycode}"
        is InjectionPrimitive.Tap   -> "TAP ${primitive.x} ${primitive.y}"
        is InjectionPrimitive.Swipe ->
            "SWIPE ${primitive.x1} ${primitive.y1} ${primitive.x2} ${primitive.y2} ${primitive.durationMs}"
        is InjectionPrimitive.StartActivity -> buildString {
            append("AM start -n ").append(primitive.component)
            primitive.extras.forEach { (k, v) ->
                append(" --es ").append(k).append(' ').append(quoteShell(v))
            }
        }
        is InjectionPrimitive.Broadcast -> buildString {
            append("BC ").append(primitive.action)
            primitive.extras.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
        }
        is InjectionPrimitive.Shell    -> "SH ${primitive.cmd}"
        is InjectionPrimitive.A11yGlobal -> null  // not supported by the agent
    }

    /** Shell-quote a value if it contains whitespace or quotes. The agent runs raw via /system/bin/sh -c. */
    private fun quoteShell(v: String): String =
        if (v.any { it == ' ' || it == '\t' || it == '"' || it == '\'' || it == '\\' || it == '`' || it == '$' })
            "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        else v
}
