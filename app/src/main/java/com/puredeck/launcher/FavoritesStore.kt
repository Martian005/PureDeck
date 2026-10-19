package com.puredeck.launcher

import android.content.Context

/** Ordered package names of the apps pinned to the glass top row. */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences("puredeck", Context.MODE_PRIVATE)

    /** Null on first run, so the caller can seed a default row. */
    fun load(): List<String>? =
        prefs.getString(KEY, null)?.split(',')?.filter { it.isNotEmpty() }

    // Package names cannot contain commas, so a joined string is safe.
    fun save(packages: List<String>) =
        prefs.edit().putString(KEY, packages.joinToString(",")).apply()

    private companion object {
        const val KEY = "pinned"
    }
}
