package io.github.ynkcc.moredisplay.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * A display that exists on the device but isn't managed by MoreDisplay (another app's or the system's virtual
 * display, a cast or HDMI screen, ...). Listed so it's clear which display numbers are already taken.
 */
data class OtherDisplay(val displayId: Int, val name: String, val width: Int, val height: Int) {
    companion object {
        fun query(context: Context, managedIds: Set<Int>): List<OtherDisplay> = runCatching {
            val dm = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
            dm.displays
                .filter { it.displayId != Display.DEFAULT_DISPLAY && it.displayId !in managedIds }
                .map { d ->
                    val mode = d.mode
                    OtherDisplay(d.displayId, d.name ?: "Display", mode?.physicalWidth ?: 0, mode?.physicalHeight ?: 0)
                }
                .sortedBy { it.displayId }
        }.getOrDefault(emptyList())
    }
}
