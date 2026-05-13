package com.halo.ring.core.gesture

import java.util.PriorityQueue

/**
 * Virtual-time [Scheduler] for tests. You call [advanceBy] to fire any tasks due in that interval.
 * Single-threaded; mirrors how the production scheduler runs (one looper, one synthesizer).
 */
class ManualScheduler(private var now: Long = 0L) : Scheduler {

    private data class Task(val fireAt: Long, val seq: Long, val action: () -> Unit) : Comparable<Task> {
        override fun compareTo(other: Task): Int =
            fireAt.compareTo(other.fireAt).let { if (it != 0) it else seq.compareTo(other.seq) }
    }

    private var seqCounter = 0L
    private val queue = PriorityQueue<Task>()
    private val cancelled = HashSet<Long>()

    override fun nowMs(): Long = now

    override fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable {
        val seq = seqCounter++
        queue.add(Task(now + delayMs, seq, task))
        return object : Cancellable {
            override fun cancel() { cancelled.add(seq) }
        }
    }

    /** Advance virtual time by [ms]; fire any tasks scheduled within that window in due order. */
    fun advanceBy(ms: Long) {
        val target = now + ms
        while (queue.isNotEmpty() && queue.peek().fireAt <= target) {
            val t = queue.poll()
            now = t.fireAt
            if (t.seq !in cancelled) t.action()
        }
        now = target
    }

    /** Jump straight to [t] (must be >= current). */
    fun jumpTo(t: Long) {
        require(t >= now)
        advanceBy(t - now)
    }
}
