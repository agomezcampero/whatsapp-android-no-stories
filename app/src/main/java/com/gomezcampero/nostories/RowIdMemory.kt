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
        const val LEARNED_UNDER = "learned_under"

        /**
         * Bumped whenever detection changes in a way that could have taught
         * the app the wrong node. An id learned under an older rule is
         * dropped rather than carried forward, because a remembered id is
         * used ahead of everything else and a wrong one is invisible and
         * permanent. Version 2 stopped guessing outside the Chats screen,
         * where a selection toolbar could be learned as the row.
         */
        const val RULES_VERSION = 2
    }

    private val store = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        if (store.getInt(LEARNED_UNDER, 0) != RULES_VERSION) {
            store.edit().remove(KEY).putInt(LEARNED_UNDER, RULES_VERSION).apply()
        }
    }

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
