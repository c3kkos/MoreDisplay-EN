package io.github.ynkcc.moredisplay.eye

import io.github.ynkcc.moredisplay.data.SavedState

/** What an eye control asks for. */
enum class EyeRequest {
    ON, OFF, TOGGLE;

    companion object {
        /** Parses the adb `state` argument (`on`, `off`, `toggle`; default toggle). Null if invalid. */
        fun parse(value: String?): EyeRequest? = when (value?.trim()?.lowercase()) {
            null, "", "toggle" -> TOGGLE
            "on", "1", "true" -> ON
            "off", "0", "false" -> OFF
            else -> null
        }
    }
}

/** The four places an eye control can live; each has its own switch in Settings. */
enum class EyeControl(val key: String, val label: String, val description: String) {
    CARD_BUTTON("eye_card", "Eye button on display cards", "Toggle the viewer from each display card in the Displays tab."),
    QUICK_TILE("eye_tile", "Quick Settings tile", "Toggle the last watched display from the notification shade (add the tile yourself)."),
    WIDGET("eye_widget", "Home-screen widget", "Toggle the last watched display from the home screen (add the widget yourself)."),
    ADB("eye_adb", "adb command", "Allow `--es action eye` from a computer.")
}

/** Pure decisions behind the eye controls (unit-tested on the JVM). */
object EyeLogic {

    /** What to do for [request] on [displayId], given which display (if any) is being watched now. */
    fun decide(request: EyeRequest, displayId: Int, watching: Int?): Action = when (request) {
        EyeRequest.ON -> if (watching == displayId) Action.NOTHING else Action.SHOW
        EyeRequest.OFF -> if (watching == displayId) Action.HIDE else Action.NOTHING
        EyeRequest.TOGGLE -> if (watching == displayId) Action.HIDE else Action.SHOW
    }

    enum class Action { SHOW, HIDE, NOTHING }

    /**
     * Which display a control acts on: an explicit id if given, otherwise the last watched display.
     * The last display is remembered by its saved key (display ids change after a reboot) with its raw id as a
     * fallback. Returns null if there's no candidate or it isn't a managed display right now.
     */
    fun resolveTarget(
        explicitId: Int?,
        lastKey: String?,
        lastRawId: Int?,
        saved: SavedState,
        managedIds: Set<Int>
    ): Int? {
        if (explicitId != null) return explicitId.takeIf { it in managedIds }
        val byKey = lastKey?.let { key -> saved.displays.firstOrNull { it.key == key }?.lastId }
        val candidate = byKey?.takeIf { it >= 0 } ?: lastRawId
        return candidate?.takeIf { it in managedIds }
    }

    /** The saved key of [displayId], used to remember it across reboots. */
    fun keyOf(displayId: Int, saved: SavedState): String? =
        saved.displays.firstOrNull { it.lastId == displayId && displayId >= 0 }?.key
}
