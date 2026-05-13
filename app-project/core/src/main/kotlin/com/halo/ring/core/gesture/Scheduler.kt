package com.halo.ring.core.gesture

/**
 * Minimal timer abstraction so [GestureSynthesizer] stays pure-Kotlin and testable.
 * Production impl (in :app) wraps a coroutine scope or an Android Handler on the same
 * single-thread looper the synthesizer runs on. Tests use a manual/virtual implementation.
 */
interface Scheduler {
    /** Run [task] after [delayMs]. Returns a handle to cancel it before it fires. */
    fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable

    /** Monotonic "now", in the same units as the timestamps passed to [GestureSynthesizer.onRaw]. */
    fun nowMs(): Long
}

interface Cancellable {
    fun cancel()
}

/** A no-op handle, for code paths that didn't actually schedule anything. */
internal object NoopCancellable : Cancellable {
    override fun cancel() {}
}
