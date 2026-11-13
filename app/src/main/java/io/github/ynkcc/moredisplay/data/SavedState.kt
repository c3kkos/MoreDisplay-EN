package io.github.ynkcc.moredisplay.data

import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.DisplaySpec
import io.github.ynkcc.moredisplay.core.OrientationOverride
import io.github.ynkcc.moredisplay.core.Privilege
import io.github.ynkcc.moredisplay.core.Visibility
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What MoreDisplay remembers across reboots: the managed virtual displays and the per-app policies.
 *
 * ## Why policies point at display *keys*, not display ids
 * Display ids are handed out by the system when a display is created, and a recreated display gets a
 * new id after every reboot. So each saved display gets a stable [SavedDisplay.key], policies reference
 * displays by that key, and on restore the keys are mapped to whatever ids the recreated displays got.
 * Displays MoreDisplay doesn't manage (for example the built-in screen) are kept as raw ids.
 *
 * Pure Kotlin (no Android types), so the encoding and the id remapping are unit-testable on the JVM.
 */
data class SavedDisplay(
    val key: String,
    val channel: Privilege,
    val spec: DisplaySpec,
    /** The id the display had the last time it was created or restored; [NOT_PRESENT] if it couldn't be restored yet. */
    val lastId: Int
) {
    companion object {
        const val NOT_PRESENT = -1
    }
}

/** A reference to a display inside a saved policy: a managed display's key, or a raw id for anything else. */
sealed class DisplayRef {
    data class Key(val key: String) : DisplayRef()
    data class Id(val id: Int) : DisplayRef()
}

data class SavedPolicy(
    val uid: Int,
    val packageName: String?,
    val visibility: Visibility,
    val displays: Set<DisplayRef>,
    val operated: DisplayRef?,
    val record: DisplayRef?,
    val launch: DisplayRef? = null,
    val orientation: OrientationOverride = OrientationOverride.APP
)

data class SavedState(
    val displays: List<SavedDisplay> = emptyList(),
    val policies: List<SavedPolicy> = emptyList(),
    /** False until the first save; used to adopt displays/policies that existed before persistence was added. */
    val initialized: Boolean = false
) {

    // ---- Changes recorded as they happen ----

    fun withDisplay(display: SavedDisplay): SavedState =
        copy(displays = displays.filter { it.key != display.key } + display, initialized = true)

    /** Forgets a removed display and drops it from every policy that pointed at it. */
    fun withoutDisplayId(displayId: Int): SavedState {
        if (displayId < 0) return this
        val gone = displays.filter { it.lastId == displayId }.map { it.key }.toSet()
        fun keep(ref: DisplayRef?) = ref != null && !(ref is DisplayRef.Key && ref.key in gone) &&
            !(ref is DisplayRef.Id && ref.id == displayId)
        return copy(
            displays = displays.filter { it.lastId != displayId },
            policies = policies.map { p ->
                p.copy(
                    displays = p.displays.filter { keep(it) }.toSet(),
                    operated = p.operated?.takeIf { keep(it) },
                    record = p.record?.takeIf { keep(it) },
                    launch = p.launch?.takeIf { keep(it) }
                )
            },
            initialized = true
        )
    }

    /** Stores a policy (replacing any policy for the same uid), translating display ids to keys. */
    fun withPolicy(policy: DisplayPolicy): SavedState {
        val saved = SavedPolicy(
            uid = policy.uid,
            packageName = policy.packageName,
            visibility = policy.visibility,
            displays = policy.displayIds.map { refFor(it) }.toSet(),
            operated = policy.operatedDisplayId?.let { refFor(it) },
            record = policy.recordDisplayId?.let { refFor(it) },
            launch = policy.launchDisplayId?.let { refFor(it) },
            orientation = policy.orientation
        )
        return copy(policies = policies.filter { it.uid != policy.uid } + saved, initialized = true)
    }

    fun withoutPolicy(uid: Int): SavedState =
        copy(policies = policies.filter { it.uid != uid }, initialized = true)

    private fun refFor(displayId: Int): DisplayRef =
        displays.firstOrNull { it.lastId == displayId && displayId >= 0 }?.let { DisplayRef.Key(it.key) }
            ?: DisplayRef.Id(displayId)

    // ---- Restore ----

    /** Records the id a display got when it was restored ([SavedDisplay.NOT_PRESENT] if it couldn't be). */
    fun withRestoredId(key: String, newId: Int): SavedState =
        copy(displays = displays.map { if (it.key == key) it.copy(lastId = newId) else it })

    /**
     * Matches saved displays against the displays that currently exist, and decides which must be recreated.
     *
     * A saved display counts as still alive only if a current display has its last id, the same channel and the
     * same size/density/name, and no other saved display already claimed it. (After a reboot ids are reused, so
     * an id alone proves nothing.) Returns key -> current id for the alive ones; the rest need recreating.
     */
    fun matchAlive(current: List<Pair<Int, Pair<Privilege, DisplaySpec>>>): Map<String, Int> {
        val byId = current.toMap()
        val claimed = mutableSetOf<Int>()
        val alive = linkedMapOf<String, Int>()
        for (d in displays) {
            val (channel, spec) = byId[d.lastId] ?: continue
            if (d.lastId in claimed || channel != d.channel) continue
            if (spec.width != d.spec.width || spec.height != d.spec.height ||
                spec.densityDpi != d.spec.densityDpi || spec.name != d.spec.name
            ) continue
            claimed += d.lastId
            alive[d.key] = d.lastId
        }
        return alive
    }

    /**
     * Turns the saved policies back into policies with current display ids. References to managed displays
     * that couldn't be restored are dropped (a policy can't point at a display that doesn't exist).
     */
    fun resolvedPolicies(): List<DisplayPolicy> {
        val idOf = displays.filter { it.lastId >= 0 }.associate { it.key to it.lastId }
        fun resolve(ref: DisplayRef?): Int? = when (ref) {
            is DisplayRef.Key -> idOf[ref.key]
            is DisplayRef.Id -> ref.id
            null -> null
        }
        return policies.map { p ->
            DisplayPolicy(
                uid = p.uid,
                packageName = p.packageName,
                visibility = p.visibility,
                displayIds = p.displays.mapNotNull { resolve(it) }.toSet(),
                operatedDisplayId = resolve(p.operated),
                recordDisplayId = resolve(p.record),
                launchDisplayId = resolve(p.launch),
                orientation = p.orientation
            )
        }
    }

    // ---- Encoding: one record per line, fields separated by '|', free text URL-encoded ----

    fun encode(): String = buildString {
        appendLine("V|1|${if (initialized) 1 else 0}")
        displays.forEach { d ->
            appendLine(
                listOf(
                    "D", enc(d.key), d.channel.name, d.spec.width, d.spec.height, d.spec.densityDpi,
                    d.spec.flags, d.lastId, enc(d.spec.name)
                ).joinToString("|")
            )
        }
        policies.forEach { p ->
            appendLine(
                listOf(
                    "P", p.uid, enc(p.packageName ?: ""), p.visibility.name,
                    p.displays.joinToString(",") { refString(it) },
                    p.operated?.let { refString(it) } ?: "",
                    p.record?.let { refString(it) } ?: "",
                    p.launch?.let { refString(it) } ?: "",
                    p.orientation.name
                ).joinToString("|")
            )
        }
    }

    companion object {
        fun decode(text: String?): SavedState {
            if (text.isNullOrBlank()) return SavedState()
            var initialized = false
            val displays = mutableListOf<SavedDisplay>()
            val policies = mutableListOf<SavedPolicy>()
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val f = line.split('|')
                runCatching {
                    when (f[0]) {
                        "V" -> initialized = f.getOrNull(2) == "1"
                        "D" -> displays += SavedDisplay(
                            key = dec(f[1]),
                            channel = Privilege.valueOf(f[2]),
                            spec = DisplaySpec(
                                width = f[3].toInt(), height = f[4].toInt(), densityDpi = f[5].toInt(),
                                name = dec(f[8]), flags = f[6].toInt()
                            ),
                            lastId = f[7].toInt()
                        )
                        "P" -> policies += SavedPolicy(
                            uid = f[1].toInt(),
                            packageName = dec(f[2]).ifEmpty { null },
                            visibility = Visibility.fromName(f[3]),
                            displays = f[4].split(',').mapNotNull { parseRef(it) }.toSet(),
                            operated = parseRef(f[5]),
                            record = parseRef(f[6]),
                            launch = parseRef(f.getOrNull(7)), // absent in files saved before 1.5
                            orientation = OrientationOverride.fromName(f.getOrNull(8)) // absent before 1.7
                        )
                    }
                } // a corrupt line is skipped instead of losing everything
            }
            return SavedState(displays, policies, initialized)
        }

        private fun refString(ref: DisplayRef): String = when (ref) {
            is DisplayRef.Key -> "k:" + enc(ref.key)
            is DisplayRef.Id -> "i:" + ref.id
        }

        private fun parseRef(s: String?): DisplayRef? = when {
            s.isNullOrEmpty() -> null
            s.startsWith("k:") -> DisplayRef.Key(dec(s.substring(2)))
            s.startsWith("i:") -> s.substring(2).toIntOrNull()?.let { DisplayRef.Id(it) }
            else -> null
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")
    }
}
