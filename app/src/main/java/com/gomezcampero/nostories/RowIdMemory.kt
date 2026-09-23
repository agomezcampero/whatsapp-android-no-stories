package com.gomezcampero.nostories

import android.content.Context

/**
 * Remembers the view id of the status row once something has found it.
 *
 * The app ships with no ids at all. The first run works the row out from its
 * shape, writes down whatever id that node turned out to have, and every run
 * after that resolves it in a single call. If WhatsApp renames the row in an
 * update, the id stops resolving, the shape pass finds it again, and the new
 * id replaces the old one.
 */
class RowIdMemory(context: Context) {

    private companion object {
        const val FILE = "status-row"
        const val KEY = "view_id"
    }

    private val store = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The id learned on this device, or null before anything has been found. */
    fun learned(): String? = store.getString(KEY, null)

    /** Keeps [id], unless it is null or already what we had. Returns true if new. */
    fun learn(id: String?): Boolean {
        if (id.isNullOrEmpty() || id == learned()) return false
        store.edit().putString(KEY, id).apply()
        return true
    }

    fun forget() {
        store.edit().remove(KEY).apply()
    }
}
