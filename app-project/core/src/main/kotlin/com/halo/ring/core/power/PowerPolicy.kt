package com.halo.ring.core.power

/**
 * Pure logic for the resident power state machine (Doc/06-performance-and-power.md §3.5).
 *
 * Given the current world ([Inputs]), tells the caller what BLE-side actions to take ([Decision]).
 * Stateless — caller keeps the inputs current and re-evaluates whenever any input changes.
 *
 * The policy table (Doc/06 §3.1 + §3.2):
 *
 *  | worn  | screen | recent gesture | → touchEnabled | intervalMode | disconnect |
 *  |-------|--------|----------------|----------------|--------------|------------|
 *  | false | (n/a)  | (n/a)          | false          | BALANCED     | iff off ≥ 5 min |
 *  | true  | off    | (n/a)          | true           | **SLOW**     | no |
 *  | true  | on     | <10 s          | true           | HIGH         | no |
 *  | true  | on     | ≥10 s          | true           | BALANCED     | no |
 *
 * "Recent gesture" is meant in the resident-process sense: a debounce off the last raw event the
 * ring sent, not the synthesised gesture (otherwise the debounce starts late by up to ~280 ms).
 *
 * The SLOW row is the biggest win after `TOUCH_DISABLE`: the screen-off-while-worn state is often
 * the longest fraction of the day (between wake gestures). Dropping the BLE interval to ~200-500 ms
 * cuts the radio's per-second wake-ups by ~4-5× vs. BALANCED.
 */
object PowerPolicy {

    /** Time since the most-recent ring activity. */
    const val ACTIVE_WINDOW_MS: Long = 10_000L
    /** Time off-wrist before we tear down the BLE link entirely (Doc/06 §3.2). */
    const val NOT_WORN_DISCONNECT_MS: Long = 5L * 60L * 1000L

    /**
     * BLE connection-interval band. Maps to `BluetoothGatt.CONNECTION_PRIORITY_*` on Android:
     *  - [HIGH]     → `CONNECTION_PRIORITY_HIGH`      (~15-30 ms; for active gesture streams)
     *  - [BALANCED] → `CONNECTION_PRIORITY_BALANCED`  (~75-100 ms; idle while screen on)
     *  - [SLOW]     → `CONNECTION_PRIORITY_LOW_POWER` (~200-500 ms; worn but screen off)
     */
    enum class IntervalMode { HIGH, BALANCED, SLOW }

    data class Inputs(
        val worn: Boolean,
        val screenOn: Boolean,
        /** Monotonic timestamp of the last raw BLE event from the ring (Long.MIN_VALUE if none). */
        val lastActivityMs: Long,
        /** Monotonic timestamp the ring was last reported worn (Long.MIN_VALUE if never). */
        val lastWornMs: Long,
        /** Monotonic now. */
        val nowMs: Long,
    )

    data class Decision(
        /** TOUCH_ENABLE if true, TOUCH_DISABLE if false. */
        val touchEnabled: Boolean,
        /** Which connection-priority band the BLE client should request. */
        val intervalMode: IntervalMode,
        /** Disconnect from the ring entirely (release the BLE link). */
        val disconnect: Boolean,
    )

    fun decide(i: Inputs): Decision {
        val recentlyActive = (i.nowMs - i.lastActivityMs) < ACTIVE_WINDOW_MS
        val notWornLongEnough = i.lastWornMs != Long.MIN_VALUE &&
            (i.nowMs - i.lastWornMs) > NOT_WORN_DISCONNECT_MS
        val intervalMode = when {
            !i.worn        -> IntervalMode.BALANCED                          // about to disconnect anyway
            !i.screenOn    -> IntervalMode.SLOW                              // worn + screen-off → wake-ready, low rate
            recentlyActive -> IntervalMode.HIGH                              // worn + on + active stream
            else           -> IntervalMode.BALANCED                          // worn + on + idle
        }
        return Decision(
            touchEnabled = i.worn,
            intervalMode = intervalMode,
            disconnect = !i.worn && notWornLongEnough,
        )
    }
}
