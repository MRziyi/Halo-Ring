package com.halo.ring.inject

import android.util.Log
import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.inject.AgentWireProtocol
import com.halo.ring.core.inject.ExecutorBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Fallback [ExecutorBackend] (priority 60). Used when the [AppProcessAgentBackend]'s persistent
 * process dies or was never bootstrapped — but the user has at least run the one-shot ADB
 * bootstrap that installed our `inotifyd` helper script under `/data/local/tmp/`.
 *
 * Architecture (Doc/04 §5.1, mirrored from the `小猪遥控戒指` reference app but event-driven):
 *
 *     :app                                   :device (shell uid, persistent)
 *     ─────                                  ──────────────────────────────────
 *     perform(action)                        inotifyd /system/bin/sh -c
 *         │                                    'on=$1; cmd=$(cat /data/local/tmp/halo.cmd);
 *         │  AgentWireProtocol.encode →         input/am/sh $cmd' /data/local/tmp/halo.cmd:c
 *         │  "KEY 23"  /  "TAP x y"  / ...
 *         │  write to /data/local/tmp/halo.cmd
 *         ▼
 *     halo.cmd modified
 *         ─────────────────────────────────►  inotifyd fires
 *                                              shell parses the command, executes
 *                                              `input keyevent 23`  /  `input tap x y` etc.
 *
 * The shell side is *not* started by this class — it's a one-shot ADB bootstrap step
 * (TODO: implement in [com.halo.ring.adb.AdbBootstrap.setupInotifyd] alongside the agent push).
 * Until that bootstrap step ships, [isReady] returns false, the [ActionRouter] skips us
 * entirely, and [AppProcessAgentBackend] remains the only working backend.
 *
 * Latency budget (per Doc/04 §5): ~50–150 ms per action — `input keyevent` spawns a fresh JVM
 * per call. Acceptable as a fallback; not the primary path. Three orders of magnitude slower
 * than the agent (1–3 ms) but two orders of magnitude faster than no input at all.
 */
class InotifydScriptBackend(
    private val mapper: GlassActionMapper,
    private val commandFile: File = File(CMD_PATH),
    private val heartbeatFile: File = File(HEARTBEAT_PATH),
) : ExecutorBackend {

    override val id = "inotifyd-script"
    override val priority = 60

    private val ioLock = Mutex()

    // Capability subset: anything the agent can do via input/am/sh, the inotifyd helper can
    // also do — minus the speed. Accessibility-only paths (BACK/HOME/RECENTS) are not
    // claimed here; they're served by AccessibilityBackend at priority 80.
    override fun capabilities(): Set<Capability> = CAPS

    override fun isReady(): Boolean {
        // Same heartbeat-freshness contract as AppProcessAgentBackend. The shell-side launcher
        // (deployed during ADB bootstrap) `touch`es this file every 5 s while alive.
        val mtime = try { heartbeatFile.lastModified() } catch (_: SecurityException) { 0L }
        if (mtime == 0L) return false
        return System.currentTimeMillis() - mtime < HEARTBEAT_STALE_MS
    }

    override suspend fun perform(action: GlassAction): Boolean {
        // CPU-bound prep on the scheduler thread; only the file write hops to IO.
        val primitives = mapper.primitives(action)
        if (primitives.isEmpty()) return true   // no-op action
        val lines = primitives.mapNotNull { AgentWireProtocol.encode(it) }
        if (lines.isEmpty()) return false

        return withContext(Dispatchers.IO) {
            ioLock.withLock {
                writeCommands(lines)
            }
        }
    }

    /**
     * Append each command line to [commandFile] and force a sync to disk. inotifyd's `:c`
     * (close-write) trigger fires for each line. We rely on the shell-side script to read,
     * execute, and clear the file — we just keep writing fresh content.
     *
     * Returns true if at least one line was written successfully; false on I/O error.
     */
    private fun writeCommands(lines: List<String>): Boolean {
        return try {
            // Re-create the file each call so the shell side always sees a fresh "modify" event.
            // The shell helper truncates after reading; this is belt-and-braces.
            RandomAccessFile(commandFile, "rw").use { raf ->
                raf.setLength(0)
                val payload = (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
                raf.write(payload)
                raf.fd.sync()
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "writeCommands failed: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "writeCommands denied: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "InotifydBackend"

        /** Path the inotifyd shell helper watches. Re-using the existing /data/local/tmp/
         *  conventions so the bootstrap step can set everything up in one shot. */
        const val CMD_PATH = "/data/local/tmp/halo.cmd"

        /** Heartbeat file the shell helper touches every 5 s while alive. */
        const val HEARTBEAT_PATH = "/data/local/tmp/halo.inotifyd.heartbeat"

        /** Same staleness window as the agent — generous slack for a transient hiccup. */
        const val HEARTBEAT_STALE_MS = 30_000L

        val CAPS: Set<Capability> = setOf(
            Capability.NAVIGATE,
            Capability.KEY_EVENT,
            Capability.TAP_SWIPE,
            Capability.LAUNCH_INTENT,
            Capability.SHELL,
        )
    }
}
