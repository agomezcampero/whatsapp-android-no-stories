package com.gomezcampero.nostories

import android.content.Context

/** Switches that only exist to work out where the cover is landing. */
class DebugOptions(context: Context) {

    private companion object {
        const val FILE = "status-row"
        const val SEE_THROUGH = "see_through"
    }

    private val store = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Paints the cover translucent, so what is underneath stays readable. */
    var seeThrough: Boolean
        get() = store.getBoolean(SEE_THROUGH, false)
        set(value) = store.edit().putBoolean(SEE_THROUGH, value).apply()
}
