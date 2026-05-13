package com.halo.ring.runtime

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.halo.ring.core.gesture.Cancellable
import com.halo.ring.core.gesture.Scheduler
import kotlinx.coroutines.android.asCoroutineDispatcher

/**
 * Production [Scheduler]: a single dedicated [HandlerThread] so the synthesizer is naturally
 * single-threaded. BLE callbacks (binder thread) MUST `post` onto this handler before calling
 * [com.halo.ring.core.gesture.GestureSynthesizer.onRaw] — that keeps the state machine race-free.
 *
 * Timestamps use [SystemClock.uptimeMillis] (monotonic, not affected by wall-clock jumps), which
 * matches what Android's input system uses internally.
 */
class AndroidScheduler private constructor(
    private val handler: Handler,
    private val thread: HandlerThread,
) : Scheduler {

    override fun nowMs(): Long = SystemClock.uptimeMillis()

    override fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable {
        val r = Runnable { task() }
        handler.postDelayed(r, delayMs)
        return Cancellable { handler.removeCallbacks(r) }
    }

    /** Run [block] on the scheduler thread (use this in BLE callbacks). */
    fun post(block: () -> Unit) { handler.post(block) }

    /**
     * Coroutine dispatcher bound to the scheduler's [HandlerThread]. Pass into a [CoroutineScope]
     * so suspending pipeline work (e.g. [com.halo.ring.core.gesture.InteractionRouter.onGesture])
     * stays on the same thread as the synthesiser — preserving the "no shared mutable state across
     * threads" discipline documented in Doc/06 §2.2.
     */
    val coroutineDispatcher = handler.asCoroutineDispatcher("R08-pipeline")

    /** Shut down the looper. */
    fun stop() { thread.quitSafely() }

    companion object {
        fun start(name: String = "R08-pipeline"): AndroidScheduler {
            val t = HandlerThread(name, /* priority */ android.os.Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
            return AndroidScheduler(Handler(t.looper), t)
        }
    }
}

/** Helper so callers can write `Cancellable { ... }`. */
private fun Cancellable(block: () -> Unit) = object : Cancellable {
    override fun cancel() = block()
}
