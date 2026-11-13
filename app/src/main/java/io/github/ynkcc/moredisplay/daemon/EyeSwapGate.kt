package io.github.ynkcc.moredisplay.daemon

import java.util.concurrent.ConcurrentHashMap

/**
 * Hides the eye from the apps on a watched display.
 *
 * Swapping a virtual display's output (sink <-> viewer) changes nothing about the display, but the system may
 * still send a "display changed" event to apps listening for display events. For a short window after each
 * swap, those events for that display are swallowed for app uids. Added/removed events are never swallowed,
 * and system uids (< 10000, e.g. SystemUI) are never affected.
 *
 * Only effective on the Vector/Xposed channel, where the event delivery hooks live (DisplayVisibilityHooks).
 * Pure Kotlin so it is unit-testable; the clock is injectable.
 */
class EyeSwapGate(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val lastSwap = ConcurrentHashMap<Int, Long>()

    /** Event codes that are never swallowed (resolved from DisplayManagerGlobal at hook install time). */
    @Volatile var addedEvent: Int = 1
    @Volatile var removedEvent: Int = 3

    fun markSwap(displayId: Int) {
        lastSwap[displayId] = clock()
    }

    fun forget(displayId: Int) {
        lastSwap.remove(displayId)
    }

    fun shouldSwallow(displayId: Int, event: Int, uid: Int): Boolean {
        if (event == addedEvent || event == removedEvent) return false
        if (uid % PER_USER_RANGE < FIRST_APP_UID) return false
        val swappedAt = lastSwap[displayId] ?: return false
        return clock() - swappedAt in 0..windowMs
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 1_500L
        private const val PER_USER_RANGE = 100_000
        private const val FIRST_APP_UID = 10_000

        /** The gate shared by the viewer switch and the event hooks inside one process. */
        val shared = EyeSwapGate()
    }
}
