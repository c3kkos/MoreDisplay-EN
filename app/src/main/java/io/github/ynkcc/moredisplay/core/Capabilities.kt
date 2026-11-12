package io.github.ynkcc.moredisplay.core

/**
 * Bit-for-bit aligned with `android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_*`.
 *
 * These values are passed unchanged to `DisplayManager.createVirtualDisplay(..., flags)` /
 * `VirtualDisplayConfig#setFlags`, so the platform's real bit definitions must be used,
 * not a home-made "compact bit order".
 */
object VirtualDisplayFlags {
    const val PUBLIC = 1 shl 0
    const val PRESENTATION = 1 shl 1
    const val SECURE = 1 shl 2
    const val OWN_CONTENT_ONLY = 1 shl 3
    const val AUTO_MIRROR = 1 shl 4
    const val CAN_SHOW_WITH_INSECURE_KEYGUARD = 1 shl 5
    const val SUPPORTS_TOUCH = 1 shl 6
    const val ROTATES_WITH_CONTENT = 1 shl 7
    const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    const val TRUSTED = 1 shl 10
    const val OWN_DISPLAY_GROUP = 1 shl 11
    const val ALWAYS_UNLOCKED = 1 shl 12
    const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val OWN_FOCUS = 1 shl 14
    const val DEVICE_DISPLAY_GROUP = 1 shl 15
    const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    /** API 37+: allows switching between content modes (must be combined with PUBLIC + TRUSTED; don't use together with AUTO_MIRROR etc.). */
    const val ALLOWS_CONTENT_MODE_SWITCH = 1 shl 17
}

data class DaemonCapabilities(
    val maxDisplayCount: Int,
    val supportedFlags: Int,
    val launchOnDisplayBypass: Boolean,
    val unlockedDisplay: Boolean,
    val privilegedSurface: Boolean,
    /** Requirement A: whether display visibility can be filtered by caller uid (blacklist/whitelist). */
    val perUidDisplayVisibility: Boolean = false,
    /** Requirement B (not implemented): recording display redirection. */
    val recordRedirection: Boolean = false,
    /** Requirement C: accessibility display redirection (implemented in Xposed mode, see AccessibilityRedirectHooks). */
    val accessibilityRedirection: Boolean = false,
    /** Requirement D: remove managed-display tasks from recents (implemented in Xposed mode, see RecentsGate). */
    val recentsGate: Boolean = false
) {
    fun supportsFlags(flags: Int): Boolean = flags and supportedFlags.inv() == 0

    companion object {
        fun none() = DaemonCapabilities(
            maxDisplayCount = 0,
            supportedFlags = 0,
            launchOnDisplayBypass = false,
            unlockedDisplay = false,
            privilegedSurface = false
        )
    }
}
