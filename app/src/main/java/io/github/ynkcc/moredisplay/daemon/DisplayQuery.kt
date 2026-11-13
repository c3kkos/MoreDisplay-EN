package io.github.ynkcc.moredisplay.daemon

import android.util.Log

/**
 * Asks the platform directly, inside the daemon process, for "the list of displayIds that currently really exist".
 *
 * Uses `DisplayManagerGlobal.getInstance()` (static, retries fetching the service each time), **not** `DisplayManager`:
 * the engine is created during AMS construction, before DMS is registered, so the `mGlobal` captured when `DisplayManager` is constructed stays null forever.
 *
 * This is called with the daemon's own identity (Xposed = uid 1000, Shizuku = uid 2000),
 * a system/privileged identity, so it gets the full list **without our per-uid filtering**,
 * which is exactly what is needed to compute "what a given target uid actually sees".
 */
object DisplayQuery {

    private const val TAG = "MoreDisplay_DisplayQuery"

    /** Every logical display id that exists right now (empty if the platform query fails). */
    fun rawDisplayIds(): List<Int> = runCatching { HiddenApi.displayIds().toList() }.getOrElse {
        Log.w(TAG, "rawDisplayIds failed: ${it.message}")
        emptyList()
    }

    /** The displayIds a given uid actually sees (with policies applied). */
    fun visibleDisplayIds(uid: Int): List<Int> =
        rawDisplayIds().filter { DisplayPolicyRegistry.isVisible(uid, it) }
}
