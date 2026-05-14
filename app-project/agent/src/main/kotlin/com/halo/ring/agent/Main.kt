package com.halo.ring.agent

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.reflect.Method
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * The R08 Remote injection agent.
 *
 * Lifecycle: started by `app_process` with shell uid (2000) during the ADB-bootstrap wizard, then
 * stays resident. The :app side spawns a connection per [SOCKET_NAME] when it has work.
 *
 * Hot path (every gesture goes through this):
 *   1. read one line from [LocalSocket]
 *   2. parse → build [KeyEvent] / [MotionEvent]
 *   3. reflect into `InputManager.injectInputEvent(event, MODE_ASYNC)` — ~1–3 ms total
 *   4. write `OK` (or `ERR <msg>`) back
 *
 * Reference impls: scrcpy server, Shizuku, IM_Agent. We choose reflection (vs. a stable shim
 * jar) because the hidden-API surface is process-level — once we're `app_process` we don't need
 * `@SystemApi` shim builds.
 *
 * Line protocol (see also [com.halo.ring.inject.AppProcessAgentBackend]):
 * ```
 *   PING                                     → OK
 *   KEY     <kc>                             → KEYDOWN+KEYUP single keycode
 *   KEYDOWN <kc>     KEYUP <kc>
 *   TAP     <x> <y>                          → MotionEvent DOWN/UP
 *   SWIPE   <x1> <y1> <x2> <y2> <durationMs> → DOWN + N MOVEs + UP, ~120 Hz
 *   AM      <args...>                        → `am <args>` via /system/bin/sh
 *   BC      <action> [k=v]...                → broadcast (am broadcast)
 *   SH      <raw shell>                      → /system/bin/sh -c <raw>
 *   QUIT                                     → close connection (not the process)
 * ```
 * Replies are a single line: `OK` or `ERR <message>`.
 */
object Main {

    private const val SOCKET_NAME = "halo.agent"
    private const val HEARTBEAT_PATH = "/data/local/tmp/halo.agent.heartbeat"
    // P1-7: cadence chosen so the heartbeat is fresh well within the host's
    // HEARTBEAT_STALE_MS=30s window even after one missed write (~3 writes per stale window).
    // Was 5s — that's ~17280 file writes/day on /data/local/tmp + a CPU wake every 5s on the
    // glasses, both wasted when the host is in deep doze.
    private const val HEARTBEAT_INTERVAL_MS = 20_000L

    // InputManager.injectInputEvent modes (`@hide` constants — copy to avoid the reflection cost).
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

    // Cached reflection handles (resolved once at startup, then hot-path is just `invoke`).
    private lateinit var inputManager: Any
    private lateinit var injectMethod: Method

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            resolveInputManager()
        } catch (t: Throwable) {
            System.err.println("[halo.agent] FATAL: cannot resolve InputManager: ${t.message}")
            t.printStackTrace(System.err)
            exitProcess(2)
        }

        startHeartbeat()

        val server = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: IOException) {
            System.err.println("[halo.agent] FATAL: cannot bind LocalServerSocket(\"$SOCKET_NAME\"): ${e.message}")
            exitProcess(3)
        }
        System.err.println("[halo.agent] listening on abstract:$SOCKET_NAME (uid=${android.os.Process.myUid()})")

        // Single-threaded accept loop — :app is the only client. If we ever want multi-client,
        // wrap each `accept()` in a worker thread.
        while (true) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                System.err.println("[halo.agent] accept() failed: ${e.message}; continuing")
                continue
            }
            try {
                handleClient(client)
            } catch (t: Throwable) {
                System.err.println("[halo.agent] client loop crashed: ${t.message}")
            } finally {
                try { client.close() } catch (_: IOException) {}
            }
        }
    }

    // ── reflection setup ──────────────────────────────────────────────────────────────────────

    /**
     * Resolve [inputManager] + [injectMethod] across Android API levels:
     *
     *   - **Android 13+ (API 33+)**: `InputManager.getInstance()` is gutted (returns a stub or
     *     NPEs internally — see Android 14's `InputManager.java:271`). The replacement is the
     *     `android.hardware.input.InputManagerGlobal` singleton which still owns the
     *     `injectInputEvent` method. scrcpy / Shizuku take this path.
     *   - **Android 12 and earlier**: use the legacy `InputManager.getInstance()` which returns
     *     an `InputManager` with `injectInputEvent` on it.
     *
     * Both code paths surface the same hot-path: `injectMethod.invoke(receiver, event, MODE_ASYNC)`.
     */
    private fun resolveInputManager() {
        // Try the modern path first — it works on Android 13+ and the agent's min-API target
        // (per `:agent:packageDex` --min-api 26) means we still must fall back gracefully.
        try {
            val img = Class.forName("android.hardware.input.InputManagerGlobal")
            val getInstance = img.getMethod("getInstance")
            inputManager = getInstance.invoke(null) ?: error("InputManagerGlobal.getInstance() returned null")
            injectMethod = img.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            System.err.println("[halo.agent] resolved InputManagerGlobal (Android 13+ path)")
            return
        } catch (_: ClassNotFoundException) {
            // Pre-Android-13 — fall through.
        }

        val im = Class.forName("android.hardware.input.InputManager")
        val getInstance = im.getMethod("getInstance")
        inputManager = getInstance.invoke(null) ?: error("InputManager.getInstance() returned null")
        injectMethod = im.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        System.err.println("[halo.agent] resolved legacy InputManager (Android 12 or earlier)")
    }

    private fun inject(event: InputEvent): Boolean =
        injectMethod.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC) as Boolean

    // ── per-client request loop ───────────────────────────────────────────────────────────────

    private fun handleClient(client: LocalSocket) {
        val reader = BufferedReader(InputStreamReader(client.inputStream, Charsets.UTF_8))
        val writer = OutputStreamWriter(client.outputStream, Charsets.UTF_8)
        while (true) {
            val line = reader.readLine() ?: return  // EOF — caller closed
            val reply = try {
                handleLine(line.trim())
            } catch (t: Throwable) {
                "ERR ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"
            }
            try {
                writer.write(reply)
                writer.write("\n")
                writer.flush()
            } catch (e: IOException) {
                return  // client gone
            }
            if (reply == "BYE") return
        }
    }

    /**
     * Dispatch one line. Returns the reply string (without trailing newline).
     * Internal sentinel `"BYE"` is sent in response to QUIT and signals the loop to close.
     */
    private fun handleLine(line: String): String {
        if (line.isEmpty()) return "OK"
        val parts = line.split(' ').filter { it.isNotEmpty() }
        return when (parts[0]) {
            "PING"   -> "OK"
            "QUIT"   -> "BYE"
            "KEY"     -> { requireArgs(parts, 1); doKey(parts[1].toInt(), down = true,  up = true); "OK" }
            "KEYDOWN" -> { requireArgs(parts, 1); doKey(parts[1].toInt(), down = true,  up = false); "OK" }
            "KEYUP"   -> { requireArgs(parts, 1); doKey(parts[1].toInt(), down = false, up = true); "OK" }
            "TAP"     -> { requireArgs(parts, 2); doTap(parts[1].toFloat(), parts[2].toFloat()); "OK" }
            "SWIPE"   -> {
                requireArgs(parts, 5)
                doSwipe(parts[1].toFloat(), parts[2].toFloat(),
                    parts[3].toFloat(), parts[4].toFloat(), parts[5].toInt())
                "OK"
            }
            "AM" -> { runShell("am " + parts.drop(1).joinToString(" ")) }
            "BC" -> {
                requireArgs(parts, 1)
                val action = parts[1]
                val extras = parts.drop(2).mapNotNull { kv ->
                    val eq = kv.indexOf('=')
                    if (eq <= 0) null else "--es ${kv.substring(0, eq)} ${quote(kv.substring(eq + 1))}"
                }.joinToString(" ")
                runShell("am broadcast -a $action $extras")
            }
            "SH" -> {
                val raw = line.substringAfter(' ', missingDelimiterValue = "")
                if (raw.isEmpty()) "ERR SH needs a command" else runShell(raw)
            }
            else -> "ERR unknown command: ${parts[0]}"
        }
    }

    private fun requireArgs(parts: List<String>, n: Int) {
        if (parts.size <= n) throw IllegalArgumentException("${parts[0]} needs $n args, got ${parts.size - 1}")
    }

    private fun quote(s: String): String =
        if (s.any { it == ' ' || it == '"' || it == '\'' }) "\"${s.replace("\"", "\\\"")}\"" else s

    // ── injection primitives ──────────────────────────────────────────────────────────────────

    private fun doKey(keycode: Int, down: Boolean, up: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (down) {
            val e = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
            inject(e)
        }
        if (up) {
            val t = if (down) now + 1 else now
            val e = KeyEvent(if (down) now else t, t, KeyEvent.ACTION_UP, keycode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
            inject(e)
        }
    }

    private fun doTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up   = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, x, y, 0)
        try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source   = InputDevice.SOURCE_TOUCHSCREEN
            inject(down)
            inject(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun doSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        val total = max(durationMs, 16)
        // ~120 Hz between moves: 8 ms steps. Clamp to [4, 30] events so we don't spam or starve.
        val steps = (total / 8).coerceIn(4, 30)
        val stepMs = total.toFloat() / steps
        val startTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(startTime, startTime, MotionEvent.ACTION_DOWN, x1, y1, 0)
        try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            inject(down)
        } finally { down.recycle() }

        for (i in 1..steps - 1) {
            val t = i.toFloat() / steps
            val x = x1 + (x2 - x1) * t
            val y = y1 + (y2 - y1) * t
            val eventTime = startTime + (stepMs * i).roundToInt()
            val mv = MotionEvent.obtain(startTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0)
            try {
                mv.source = InputDevice.SOURCE_TOUCHSCREEN
                inject(mv)
            } finally { mv.recycle() }
            // Spinning sleep so the upstream system sees realistic timestamps.
            try { Thread.sleep(stepMs.toLong().coerceAtLeast(1)) } catch (_: InterruptedException) {}
        }

        val endTime = startTime + total
        val up = MotionEvent.obtain(startTime, endTime, MotionEvent.ACTION_UP, x2, y2, 0)
        try {
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            inject(up)
        } finally { up.recycle() }
    }

    // ── shell helpers ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs a /system/bin/sh -c <cmd> synchronously. Returns "OK" iff exit 0, otherwise the
     * collected stderr (truncated) so the client can log it.
     */
    private fun runShell(cmd: String): String {
        val proc = ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim().take(512)
        val code = proc.waitFor()
        return if (code == 0) "OK" else "ERR exit=$code ${out.replace('\n', '|')}"
    }

    // ── heartbeat thread ──────────────────────────────────────────────────────────────────────

    private fun startHeartbeat() {
        thread(name = "halo.agent-heartbeat", isDaemon = true) {
            val f = File(HEARTBEAT_PATH)
            while (true) {
                try { f.writeText(System.currentTimeMillis().toString()) }
                catch (_: IOException) { /* /data/local/tmp not writable? keep trying anyway */ }
                try { Thread.sleep(HEARTBEAT_INTERVAL_MS) } catch (_: InterruptedException) { return@thread }
            }
        }
    }
}

// android.view.KeyCharacterMap constant we'd otherwise pull in for the device id; reflection-free.
@Suppress("unused")
private object KeyCharacterMap {
    /** android.view.KeyCharacterMap.VIRTUAL_KEYBOARD = -1 */
    const val VIRTUAL_KEYBOARD: Int = -1
}
