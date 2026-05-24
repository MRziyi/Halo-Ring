package com.halo.ring.testplugin

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Reference [ContentProvider] for the Halo Ring plugin protocol (Doc/18 §4). Returns four sample
 * actions covering both the "core" and "shortcuts" sub-groups so the picker's grouping logic
 * has something to render. Cursor schema matches Doc/18 §4.4 exactly.
 */
class HaloActionsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.path?.trimEnd('/') != "/list") return null

        val cols = arrayOf("action_id", "label", "description", "group")
        val c = MatrixCursor(cols)
        c.addRow(arrayOf("voice_invoke", "Voice invoke",          "Open mic + photo",       "core"))
        c.addRow(arrayOf("shortcut_1",   "Quick capture person",  "Identify + log",         "shortcuts"))
        c.addRow(arrayOf("shortcut_2",   "OCR & save",            "Scan text → today's twin", "shortcuts"))
        c.addRow(arrayOf("shortcut_3",   "Drop a thought",        "Mic only, no photo",     "shortcuts"))
        return c
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/vnd.halo.ring.action"

    // CP write API is unused.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
