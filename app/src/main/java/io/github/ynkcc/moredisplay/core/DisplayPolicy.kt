package io.github.ynkcc.moredisplay.core

/**
 * Display visibility semantics for a given caller (by uid, optionally with a package name).
 *
 * Design trade-off: the platform's `FLAG_PRIVATE` / `ownerUid` **cannot** express arbitrary per-uid white/blacklists
 * (it can only express "visible to the creator only"), so results must be filtered where DisplayManagerService returns them.
 */
enum class Visibility {
    /** No filtering at all (the default; also the meaning when there is no policy entry). */
    ALL,

    /** Whitelist: the caller can **only** see the displays in [DisplayPolicy.displayIds]. */
    ONLY,

    /** Blacklist: the caller **cannot see** the displays in [DisplayPolicy.displayIds]. */
    HIDE;

    companion object {
        fun fromName(name: String?): Visibility =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ALL
    }
}

/**
 * Visibility policy entry. Keyed by [uid] ([packageName] is for display/readability only).
 *
 */
data class DisplayPolicy(
    val uid: Int,
    val packageName: String? = null,
    val visibility: Visibility = Visibility.ALL,
    val displayIds: Set<Int> = emptySet(),
    val operatedDisplayId: Int? = null,
    val recordDisplayId: Int? = null,
    /**
     * "Open on": every activity of this app is started on this display instead of the one the launcher (or the
     * notification, ...) asked for. Null = Android decides as usual.
     */
    val launchDisplayId: Int? = null
) {
    fun describe(): String {
        val target = packageName?.let { "$it($uid)" } ?: "uid=$uid"
        val ids = displayIds.sorted().joinToString(",")
        val extras = listOfNotNull(
            recordDisplayId?.let { "record=$it" },
            operatedDisplayId?.let { "operated=$it" },
            launchDisplayId?.let { "launch=$it" }
        )
        val suffix = if (extras.isEmpty()) "" else " " + extras.joinToString(" ")
        return "$target ${visibility.name}[$ids]$suffix"
    }
}
