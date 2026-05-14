package com.halo.ring.inject

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.halo.ring.core.action.Capability
import com.halo.ring.core.action.GlassAction
import com.halo.ring.core.device.GlassActionMapper
import com.halo.ring.core.inject.AgentWireProtocol
import com.halo.ring.core.inject.ExecutorBackend
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.Executors

/**
 * Highest-priority [ExecutorBackend]. Speaks to the `:agent` running as a shell-uid process over an
 * abstract LocalSocket. The agent reflects `InputManager.injectInputEvent` directly — ~1–3 ms per
 * event versus ~50–150 ms for `adb shell input` / `am`. The single biggest performance win in the
 * stack.
 *
 * Connection lifecycle:
 *  - Lazy: the socket is opened the first time [perform] is called (or [reconnectIfStale]).
 *  - Persistent: kept open for the lifetime of the app; we only close on I/O error.
 *  - Reconnect-on-error: any [IOException] during send/receive triggers a single retry with a fresh
 *    socket. If that fails too, [perform] returns false and the [com.halo.ring.core.action.ActionRouter]
 *    falls through to the next backend.
 *  - Readiness: gated by the heartbeat file the agent writes to [HEARTBEAT_PATH]. If the mtime is
 *    older than [HEARTBEAT_STALE_MS] we report not ready, which prompts the service to re-spawn the
 *    agent via the ADB-bootstrap wizard.
 *
 * Threading: a [Mutex] serialises send/receive so commands don't interleave on the wire. The pure
 * CPU work ([GlassActionMapper.primitives] and [AgentWireProtocol.encode]) stays on the caller's
 * thread (the scheduler) — only the blocking socket I/O hops to [Dispatchers.IO]. This keeps the
 * IO dispatcher's queue short, avoiding an extra dispatch hop for every gesture (Doc/06 §1.2 —
 * every millisecond on the hot path counts).
 */
class AppProcessAgentBackend(
    private val mapper: GlassActionMapper,
    private val socketName: String = SOCKET_NAME,
    private val heartbeatFile: File = File(HEARTBEAT_PATH),
) : ExecutorBackend {

    override val id = "app-process-agent"
    override val priority = 100

    @Volatile private var socket: LocalSocket? = null
    @Volatile private var writer: Writer? = null
    @Volatile private var reader: BufferedReader? = null
    private val ioLock = Mutex()

    /** P3-15: dedicated single-thread dispatcher for the agent socket I/O. Replaces
     *  `Dispatchers.IO`, which spreads our work across the 64-thread shared IO pool — wasteful
     *  here because [ioLock] already serialises everything. With one dedicated thread, the
     *  Mutex contention pattern becomes a simple wait-for-thread-free, the resume hop on the
     *  same thread is cheap, and we avoid waking unrelated IO workers (~0.5–1 ms per gesture). */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "halo.agent-io").apply { isDaemon = true }
    }
    private val ioDispatcher = ioExecutor.asCoroutineDispatcher()

    /** P3-16: cache the heartbeat-mtime check. [ActionRouter.dispatch] calls [isReady] for every
     *  candidate backend on every gesture; an uncached file-stat syscall costs 50–200 µs on
     *  the hot path. The agent's own heartbeat cadence is measured in seconds (P1-7 = 20 s) so
     *  caching the answer for 1 s is conservative — we'll never wrongly report ready when stale,
     *  because the cache window is way shorter than [HEARTBEAT_STALE_MS]. */
    @Volatile private var lastReadyCheckMs: Long = 0L
    @Volatile private var lastReadyResult: Boolean = false

    override fun capabilities(): Set<Capability> = AGENT_CAPS

    override fun isReady(): Boolean {
        // P3-16: cache for [READY_CACHE_MS]. Heartbeat mtime moves on a 20 s cadence so a 1 s
        // cache window is two orders of magnitude tighter than the staleness threshold — no
        // false-positive risk.
        val now = System.currentTimeMillis()
        if (now - lastReadyCheckMs < READY_CACHE_MS) return lastReadyResult
        // Heartbeat is authoritative — even if the socket is open, a hung agent isn't ready.
        val mtime = try { heartbeatFile.lastModified() } catch (_: SecurityException) { 0L }
        val ready = mtime != 0L && now - mtime < HEARTBEAT_STALE_MS
        lastReadyCheckMs = now
        lastReadyResult = ready
        return ready
    }

    override suspend fun perform(action: GlassAction): Boolean {
        // CPU-bound prep happens on the caller's thread (scheduler) — no need to bounce to IO.
        val primitives = mapper.primitives(action)
        if (primitives.isEmpty()) return true  // no-op action (e.g. Modal sentinels)
        val lines = primitives.mapNotNull { AgentWireProtocol.encode(it) }  // skip A11y, etc.
        if (lines.isEmpty()) return false

        return withContext(ioDispatcher) {
            var anySucceeded = false
            ioLock.withLock {
                for (line in lines) {
                    if (sendWithRetry(line)) anySucceeded = true
                }
            }
            anySucceeded
        }
    }

    /** Send one wire line and read the reply. Reconnects once on IOException. */
    private fun sendWithRetry(line: String): Boolean {
        repeat(2) { attempt ->
            try {
                ensureConnected()
                val w = writer ?: return@repeat
                val r = reader ?: return@repeat
                w.write(line)
                w.write("\n")
                w.flush()
                val reply = r.readLine() ?: throw IOException("agent EOF after sending: $line")
                if (reply == "OK" || reply == "BYE") return true
                Log.w(TAG, "agent ERR on \"$line\": $reply")
                return false  // ERR is not retryable
            } catch (e: IOException) {
                Log.w(TAG, "agent send failed (attempt ${attempt + 1}): ${e.message}")
                closeQuietly()  // force reconnect on next iteration
            }
        }
        return false
    }

    private fun ensureConnected() {
        if (socket != null) return
        val s = LocalSocket()
        s.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
        s.soTimeout = SOCKET_TIMEOUT_MS
        socket = s
        writer = OutputStreamWriter(s.outputStream, Charsets.UTF_8)
        reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        writer = null
        reader = null
    }

    /** Called by the foreground service after re-bootstrapping the agent (e.g. heartbeat stale). */
    fun reconnectIfStale() {
        if (!isReady()) closeQuietly()
    }

    /** Cleanly close on service shutdown. P3-15: also shut down our dedicated IO executor. */
    fun close() {
        try {
            writer?.write("QUIT\n")
            writer?.flush()
        } catch (_: IOException) {}
        closeQuietly()
        try { ioDispatcher.close() } catch (_: Throwable) {}
        try { ioExecutor.shutdownNow() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "AgentBackend"
        const val SOCKET_NAME = "halo.agent"
        const val HEARTBEAT_PATH = "/data/local/tmp/halo.agent.heartbeat"
        // Agent ticks every 20s (P1-7); 30 s leaves headroom for one missed write before we
        // flip not-ready and the service re-bootstraps.
        const val HEARTBEAT_STALE_MS = 30_000L
        const val SOCKET_TIMEOUT_MS = 2_000
        /** P3-16: how long [isReady] caches its file-stat result. Way shorter than the staleness
         *  threshold, so a freshly-stale agent is detected within 1 s of going quiet. */
        const val READY_CACHE_MS = 1_000L

        /**
         * Capabilities the agent provides. BACK/HOME/RECENTS/NOTIFICATIONS are claimed because the
         * agent can fall back to KEYCODE_BACK / KEYCODE_HOME / etc. — the action mappers always
         * supply a key-event primitive alongside the A11yGlobal one for exactly this reason.
         */
        val AGENT_CAPS = setOf(
            Capability.NAVIGATE, Capability.KEY_EVENT, Capability.TAP_SWIPE,
            Capability.LAUNCH_INTENT, Capability.SHELL,
            Capability.BACK, Capability.HOME, Capability.RECENTS, Capability.NOTIFICATIONS,
        )
    }
}
