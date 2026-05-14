package com.halo.ring.core.perf

/**
 * Ring-buffer recorder for HR / SpO2 / stress samples emitted during a B6 vitals snapshot. Pure
 * logic — no Android, no coroutines. Mirrors [LatencyLogger]'s shape so the foreground service
 * can record into it on the same thread that handles BLE notifies, and the Advanced screen's
 * "Export vitals log" CTA can pull [toCsv] for write-out.
 *
 * One [Sample] per `RingEvent.Health` we successfully decode. Capacity defaults to 500 samples
 * — at the typical "every 30 min" auto-snapshot cadence (3 readings per snapshot: HR / SpO2 /
 * stress), that's ~80 hours of history; rolls over silently after that.
 *
 * Thread-safety: a single mutex guards the buffer so an export read can race with writes.
 */
class VitalsLogger(val capacity: Int = 500) {

    enum class Kind { HEART_RATE, SPO2, STRESS }

    data class Sample(
        val kind: Kind,
        /** The integer value the ring reported (bpm / % / index, depending on [kind]). */
        val value: Int,
        /** Monotonic ms when the BLE notify arrived (scheduler clock). */
        val capturedAtMs: Long,
    )

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
     * CSV serialisation — header row + one row per sample. `kind` is rendered as its enum name
     * so a `head halo-vitals-*.csv` is debuggable.
     */
    fun toCsv(): String = buildString {
        append("captured_at_ms,kind,value\n")
        snapshot().forEach { s ->
            append(s.capturedAtMs); append(',')
            append(s.kind.name); append(',')
            append(s.value); append('\n')
        }
    }
}
