package com.halo.ring.plugin

import com.halo.ring.core.action.GlassAction

/**
 * One action exposed by an installed plugin app (Doc/18 §4.4). Mirrors the columns in the
 * plugin's `ContentProvider` cursor; consumed by [com.halo.ring.ui.screens.ActionPickerScreen]
 * to render the EXTERNAL APPS group, and converted to [GlassAction.PluginAction] when the user
 * binds it to a gesture.
 *
 * Distinct from [GlassAction.PluginAction] because the picker needs the full set of columns
 * (description, group) for display, but only `pluginPackage` + `actionId` + `label` need to be
 * snapshotted into the bound action for persistence.
 */
data class PluginAction(
    /** Plugin owner package, e.g. `com.constellation.glass`. */
    val pluginPackage: String,
    /** Stable identifier, unique within the plugin. e.g. `voice_invoke`. */
    val actionId: String,
    /** User-visible label (short — Doc/18 §4.4). */
    val label: String,
    /** Optional one-line description shown as a caption under the row. */
    val description: String? = null,
    /** Optional sub-group within the plugin (e.g. `"shortcuts"`, `"core"`). */
    val group: String? = null,
) {
    /** Project this picker-row into a bindable [GlassAction.PluginAction]. */
    fun toBoundAction(): GlassAction.PluginAction =
        GlassAction.PluginAction(pluginPackage = pluginPackage, actionId = actionId, label = label)
}

/**
 * Discovered plugin app — manifest meta-data + the actions its `ContentProvider` returned. The
 * Action Picker iterates [Plugin]s to render sub-headings (one per plugin) followed by their
 * action rows.
 */
data class Plugin(
    /** Owning package, e.g. `com.constellation.glass`. */
    val packageName: String,
    /** Human-readable app name (from `pm.getApplicationLabel`). */
    val appName: String,
    /** Plugin-protocol version declared via meta-data. Currently must == 1 to be loaded. */
    val protocolVersion: Int,
    /** Whatever the plugin's CP returned at discovery. Empty if CP missing / threw. */
    val actions: List<PluginAction>,
    /** Set when the CP query failed — used by ExternalPluginsScreen to show "(no actions)". */
    val queryError: String? = null,
)
