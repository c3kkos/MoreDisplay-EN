package io.github.ynkcc.moredisplay.ui

import android.content.Context
import io.github.ynkcc.moredisplay.App

/** A virtual display size the user used recently (width x height @ dpi). */
data class DisplaySizeHistory(val width: Int, val height: Int, val dpi: Int) {
    override fun toString(): String = "$width×$height@$dpi"
}

/**
 * History of virtual display creation: persists up to the user's 2 most recent size/dpi combinations,
 * with no preset defaults; only combinations the user actually created are recorded.
 */
object CreateHistoryStore {

    private const val PREFS_NAME = "create_history"
    private const val KEY_COUNT = "count"
    private const val KEY_WIDTH = "w%d"
    private const val KEY_HEIGHT = "h%d"
    private const val KEY_DPI = "dpi%d"

    /** Maximum number of history entries kept. */
    const val MAX_ENTRIES = 2

    /** Reads all history, newest first. */
    fun load(): List<DisplaySizeHistory> {
        val prefs = prefs() ?: return emptyList()
        val count = (prefs.getInt(KEY_COUNT, 0)).coerceIn(0, MAX_ENTRIES)
        return (0 until count).mapNotNull { i ->
            val w = prefs.getInt(KEY_WIDTH.format(i), 0)
            val h = prefs.getInt(KEY_HEIGHT.format(i), 0)
            val dpi = prefs.getInt(KEY_DPI.format(i), 0)
            if (w in 1..7680 && h in 1..7680 && dpi in 10..2000) {
                DisplaySizeHistory(w, h, dpi)
            } else null
        }
    }

    /** Records one creation; a duplicate moves to the front, entries beyond [MAX_ENTRIES] are evicted. */
    fun record(width: Int, height: Int, dpi: Int) {
        val prefs = prefs() ?: return
        val existing = load().toMutableList()
        existing.removeAll { it.width == width && it.height == height && it.dpi == dpi }
        existing.add(0, DisplaySizeHistory(width, height, dpi))
        while (existing.size > MAX_ENTRIES) existing.removeAt(existing.lastIndex)

        prefs.edit().apply {
            putInt(KEY_COUNT, existing.size)
            existing.forEachIndexed { i, e ->
                putInt(KEY_WIDTH.format(i), e.width)
                putInt(KEY_HEIGHT.format(i), e.height)
                putInt(KEY_DPI.format(i), e.dpi)
            }
        }.apply()
    }

    private fun prefs() = try {
        App.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }
}
