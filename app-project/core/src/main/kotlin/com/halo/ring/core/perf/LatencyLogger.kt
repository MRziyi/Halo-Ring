package com.halo.ring.core.perf

/**
 * Ring-buffer recorder for end-to-end gesture latency samples. Pure logic — no Android, no
 * coroutines, no scheduler. The caller (typically the foreground service) supplies the
 * monotonic timestamps from whatever clock the pipeline uses.
 *
 * Doc/06 §4 describes the debug HUD's "Latency measurement mode": with [enabled] = true, the
 * pipeline pushes a [Sample] into the buffer for every recognised gesture. The Advanced screen
 * → "Export latency log" CTA pulls [toCsv] and writes it to disk.
 *
 * Three timestamps per sample, captured in sequence on the scheduler thread:
 *  1. `tBleMs` — the **last** BLE event that contributed to this gesture (e.g. the 2nd TOUCH of
 *     a double-tap). The user perceives latency relative to their last physical action.
 *  2. `tEmittedMs` — when [com.halo.ring.core.gesture.GestureSynthesizer] emitted the gesture
 *     (after timing windows / combo logic). `tEmittedMs - tBleMs` = synth cost.
 *  3. `tDispatchedMs` — after the action was dispatched through the
 *     [com.halo.ring.core.gesture.InteractionRouter] + [com.halo.ring.core.action.ActionRouter]
 *     pipeline. `tDispatchedMs - tEmittedMs` = router + backend cost.
 *
 * Thread-safety: a single mutex guards the buffer so the export read can race with the
 * pipeline writes.
 */
class LatencyLogger(val capacity: Int = 100) {

    data class Sample(
        val gestureName: String,
        val actionName: String,
        val tBleMs: Long,
        val tEmittedMs: Long,
        val tDispatchedMs: Long,
    ) {
        /** Time from the user's last physical touch to the synthesised gesture. */
        val recognitionMs: Long get() = tEmittedMs - tBleMs
        /** Time from synth emit to backend dispatch (router + executor path). */
        val dispatchMs: Long get() = tDispatchedMs - tEmittedMs
        /** End-to-end latency (the headline number for Doc/06 §5). */
        val totalMs: Long get() = tDispatchedMs - tBleMs
    }

    @Volatile var enabled: Boolean = false

    private val lock = Any()
    private val buffer = ArrayDeque<Sample>()

    fun record(sample: Sample) {
        if (!enabled) return
        synchronized(lock) {
            while (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(sample)
        }
    }

    fun snapshot(): List<Sample> = synchronized(lock) { buffer.toList() }

    fun size(): Int = synchronized(lock) { buffer.size }

    fun reset(): Unit = synchronized(lock) { buffer.clear() }

    /**
     * CSV serialisation — header row + one row per sample. The columns are intentionally human-
     * readable so a `head halo-latency-*.csv` is debuggable.
     */
    fun toCsv(): String = buildString {
        append("timestamp_ble_ms,timestamp_emitted_ms,timestamp_dispatched_ms,")
        append("gesture,action,recognition_ms,dispatch_ms,total_ms\n")
        snapshot().forEach { s ->
            append(s.tBleMs); append(',')
            append(s.tEmittedMs); append(',')
            append(s.tDispatchedMs); append(',')
            append(s.gestureName); append(',')
            append(s.actionName); append(',')
            append(s.recognitionMs); append(',')
            append(s.dispatchMs); append(',')
            append(s.totalMs); append('\n')
        }
    }
}
