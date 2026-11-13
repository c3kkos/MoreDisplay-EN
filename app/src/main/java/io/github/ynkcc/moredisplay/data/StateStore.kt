package io.github.ynkcc.moredisplay.data

import android.content.Context
import android.util.Log
import io.github.ynkcc.moredisplay.App

/**
 * Saves [SavedState] in the app's private storage, so displays and policies survive reboots.
 *
 * Every successful change made through [DisplayRepository] (from the app UI or from adb) is written here;
 * [DisplayRepository] reads it back and re-applies it when it connects (see `restoreSavedState`).
 */
object StateStore {
    private const val TAG = "MoreDisplay_State"
    private const val PREFS_NAME = "moredisplay_state"
    private const val KEY_STATE = "state"

    private val lock = Any()

    fun load(): SavedState = synchronized(lock) {
        SavedState.decode(prefs()?.getString(KEY_STATE, null))
    }

    /** Applies [change] to the saved state and writes the result synchronously. */
    fun update(change: (SavedState) -> SavedState): SavedState = synchronized(lock) {
        val next = change(load())
        val ok = prefs()?.edit()?.putString(KEY_STATE, next.encode())?.commit() ?: false
        if (!ok) Log.w(TAG, "failed to save state")
        next
    }

    private fun prefs() = try {
        App.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (e: Exception) {
        Log.w(TAG, "preferences unavailable: ${e.message}")
        null
    }
}
