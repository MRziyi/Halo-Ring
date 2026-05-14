package com.halo.ring.core.perf

/**
 * Estimates the negotiated BLE connection interval from observed notify-frame inter-arrival
 * timestamps. Pure, JVM-testable, thread-safe.
 *
 * Why: Android's [android.bluetooth.BluetoothGatt] doesn't expose the negotiated interval —
 * `BluetoothGattCallback.onConnectionUpdated(int interval, …)` is `@hide`. The most accurate
 * thing an app can do is timestamp every received notify and infer the interval statistically.
 *
 * Algorithm:
 *  - Hold the last [sampleCount] timestamps.
 *  - On [estimate]: compute consecutive deltas.
 *  - Drop deltas below [burstFloorMs] — those are multiple notifies the controller queued in a
 *    single BLE connection event (the second / third / … packet arrives microseconds after the
 *    first, not at a new conn-event boundary).
 *  - Return the median of the remaining deltas in ms, or null if fewer than 3 survive.
 *
 * Thread-safety: caller may invoke [record] from the BLE callback thread and [estimate] from a
 * UI thread concurrently; a single [Any]-monitor on the internal buffer keeps them coherent. The
 * critical section is O(1) for [record] (amortised) and O(sampleCount) for [estimate].
 */
class ConnIntervalEstimator(
    private val sampleCount: Int = DEFAULT_SAMPLE_COUNT,
    private val burstFloorMs: Int = DEFAULT_BURST_FLOOR_MS,
) {
    private val lock = Any()
    private val timestamps = ArrayDeque<Long>(sampleCount)

    /** Append a notify timestamp. Newest at the tail; oldest dropped when full. */
    fun record(timestampMs: Long) {
        synchronized(lock) {
            if (timestamps.size >= sampleCount) timestamps.removeFirst()
            timestamps.addLast(timestampMs)
        }
    }

    /** Clear all samples. Call on BLE disconnect so a fresh connection's estimate isn't biased
     *  by stale deltas across the gap. */
    fun reset() {
        synchronized(lock) { timestamps.clear() }
    }

    /** Returns the estimated connection interval in ms, or null if insufficient data. */
    fun estimate(): Int? {
        val snapshot = synchronized(lock) { timestamps.toLongArray() }
        if (snapshot.size < MIN_SAMPLES_FOR_ESTIMATE) return null
        val deltas = IntArray(snapshot.size - 1) { i ->
            (snapshot[i + 1] - snapshot[i]).toInt().coerceAtLeast(0)
        }
        val kept = deltas.filter { it >= burstFloorMs }
        if (kept.size < MIN_KEPT_FOR_ESTIMATE) return null
        val sorted = kept.sorted()
        return sorted[sorted.size / 2]
    }

    /** Current number of stored samples — useful for tests + diagnostics. */
    fun sampleSize(): Int = synchronized(lock) { timestamps.size }

    companion object {
        const val DEFAULT_SAMPLE_COUNT = 16
        /** Deltas below this ms are intra-conn-event bursts, not the real interval. Well below
         *  the tightest HIGH band (15-30 ms) and well above intra-event packet spacing (~100 μs). */
        const val DEFAULT_BURST_FLOOR_MS = 5
        const val MIN_SAMPLES_FOR_ESTIMATE = 4
        const val MIN_KEPT_FOR_ESTIMATE = 3
    }
}
