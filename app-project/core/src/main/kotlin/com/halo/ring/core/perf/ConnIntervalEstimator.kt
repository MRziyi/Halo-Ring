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
    private val ceilingMs: Int = DEFAULT_CEILING_MS,
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
        // Keep only deltas in a PLAUSIBLE conn-interval band: above the intra-event burst floor
        // and below [ceilingMs]. The ceiling matters because this firmware doesn't emit periodic
        // notifies — frames arrive only on gestures (and the ~60 s passive heartbeats). The gaps
        // *between* user gestures (hundreds of ms to seconds) are NOT the connection interval; left
        // in, they dominated the median and made the UI read "565 ms / 1290 ms" for what is really
        // a 15–500 ms link. A real negotiated interval never exceeds the supervision budget (well
        // under ceilingMs), so anything above it is a between-gesture idle gap — drop it. When too
        // few plausible deltas survive (sparse gesturing), return null → the UI shows nothing rather
        // than a fabricated number. (2026-05-29)
        val kept = deltas.filter { it in burstFloorMs..ceilingMs }
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
        /** Deltas above this ms are between-gesture idle gaps, not the connection interval. A real
         *  negotiated BLE interval is bounded by the supervision timeout and never approaches this;
         *  SLOW band tops out ~500 ms. 600 leaves headroom while killing the multi-hundred-ms /
         *  >1 s contamination this firmware's event-driven (non-periodic) notifies produce. */
        const val DEFAULT_CEILING_MS = 600
        const val MIN_SAMPLES_FOR_ESTIMATE = 4
        const val MIN_KEPT_FOR_ESTIMATE = 3
    }
}
