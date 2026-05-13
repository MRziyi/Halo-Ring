package com.halo.ring.ui

import androidx.compose.runtime.compositionLocalOf
import com.halo.ring.di.AppGraph

/**
 * Compose CompositionLocal so deep composables can reach the [AppGraph] without threading it
 * through every parameter. Provided by `MainActivity.setContent { CompositionLocalProvider(...) }`.
 *
 * Use sparingly — most screens should still take typed inputs in their params (data + callbacks)
 * for testability. This is a back-door for cases where we need to read a flow or invoke a
 * one-shot side effect (e.g. "Find ring" button) that isn't worth a dedicated callback.
 */
val LocalAppGraph = compositionLocalOf<AppGraph> { error("AppGraph not provided") }
