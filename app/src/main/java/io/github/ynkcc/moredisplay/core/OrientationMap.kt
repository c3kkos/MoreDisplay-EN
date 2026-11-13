package io.github.ynkcc.moredisplay.core

/**
 * Pure mapping behind "Orientation: Landscape" (unit-tested). Values are `ActivityInfo.SCREEN_ORIENTATION_*`.
 */
object OrientationMap {
    const val LANDSCAPE = 0
    private const val PORTRAIT = 1
    private const val SENSOR_PORTRAIT = 7
    private const val REVERSE_PORTRAIT = 9
    private const val USER_PORTRAIT = 12

    private val portraitFamily = setOf(PORTRAIT, SENSOR_PORTRAIT, REVERSE_PORTRAIT, USER_PORTRAIT)

    /** The orientation an activity gets for a [requested] one under [override]. */
    fun apply(override: OrientationOverride, requested: Int): Int = when (override) {
        OrientationOverride.APP -> requested
        OrientationOverride.LANDSCAPE -> if (requested in portraitFamily) LANDSCAPE else requested
    }
}
