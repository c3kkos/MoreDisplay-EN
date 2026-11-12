package io.github.ynkcc.moredisplay.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/**
 * The device's own built-in screen (`Display.DEFAULT_DISPLAY`, id 0), e.g. the tablet's panel.
 *
 * It is always present and is listed as the first, default managed display. It can't be removed, and it
 * is never hidden from apps (hiding display 0 crashes them, see `DisplayPolicyRegistry.DEFAULT_DISPLAY_ID`).
 * Read from the public DisplayManager API in the app process; display 0 is never filtered by MoreDisplay,
 * so this always works.
 */
data class BuiltInDisplay(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val refreshRate: Float
) {
    val sizeLabel: String get() = "${width}×${height} @ ${densityDpi}dpi"

    companion object {
        const val ID = Display.DEFAULT_DISPLAY

        fun query(context: Context): BuiltInDisplay? = runCatching {
            val dm = context.getSystemService(DisplayManager::class.java) ?: return null
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            // Physical panel size in its natural orientation; fall back to the current real size.
            val mode = display.mode
            val width = mode?.physicalWidth?.takeIf { it > 0 } ?: metrics.widthPixels
            val height = mode?.physicalHeight?.takeIf { it > 0 } ?: metrics.heightPixels
            BuiltInDisplay(
                displayId = display.displayId,
                name = display.name ?: "Built-in screen",
                width = width,
                height = height,
                densityDpi = metrics.densityDpi,
                refreshRate = display.refreshRate
            )
        }.getOrNull()
    }
}
