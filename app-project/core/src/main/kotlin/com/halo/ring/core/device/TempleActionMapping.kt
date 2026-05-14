package com.halo.ring.core.device

import com.halo.ring.core.action.GlassAction

/**
 * Pure mapping from a Mercury SDK `TempleAction` subclass `simpleName` to a [GlassAction] so the
 * X3 Pro temple touchpad can drive in-app navigation through the same plumbing as ring gestures.
 *
 * Lives in `:core` (instead of `:app/src/main/.../ui/TempleFocusBridge.kt`) so it's covered by
 * the JVM test suite without needing a Mercury AAR / Compose / Android Activity in scope.
 *
 * The mapping mirrors the ring's default profile + system gestures (Doc/05 §4 / §5) so users have
 * one mental model regardless of which input source they use:
 *
 *  | TempleAction subclass | GlassAction |
 *  |---|---|
 *  | `SlideForward` / `SlideDownwards` | [GlassAction.NavNext] (ring's SWIPE_DOWN equivalent) |
 *  | `SlideBackward` / `SlideUpwards`  | [GlassAction.NavPrev] (ring's SWIPE_UP equivalent) |
 *  | `Click`                           | [GlassAction.Confirm] (ring's TAP) |
 *  | `DoubleClick`                     | [GlassAction.Back] (ring's DOUBLE_TAP) |
 *  | `TripleClick`                     | [GlassAction.ProfileCycle] (ring's TRIPLE_TAP system gesture) |
 *  | `LongClick`, `Idle`, `DoubleFingerClick`, … | null (let Mercury handle natively) |
 */
fun mapTempleActionToGlassAction(simpleName: String): GlassAction? = when (simpleName) {
    "SlideForward", "SlideDownwards" -> GlassAction.NavNext
    "SlideBackward", "SlideUpwards"  -> GlassAction.NavPrev
    "Click"                          -> GlassAction.Confirm
    "DoubleClick"                    -> GlassAction.Back
    "TripleClick"                    -> GlassAction.ProfileCycle
    else                             -> null
}
