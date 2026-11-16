package io.github.ynkcc.moredisplay.adb

import io.github.ynkcc.moredisplay.cast.CastConfig
import io.github.ynkcc.moredisplay.eye.EyeControl
import io.github.ynkcc.moredisplay.eye.TouchOptions

/** Every value on the Settings screen, as one snapshot. */
data class AllSettings(
    val cast: CastConfig,
    val eye: Map<EyeControl, Boolean>,
    val touch: TouchOptions
)

/**
 * The Settings screen as text keys, for [AdbControlProvider] (`adb shell content call …`). One key per switch or
 * chip group; values are checked against what the screen offers. Pure Kotlin (unit-tested).
 */
object AdbSettings {

    class Key(
        val name: String,
        /** Allowed values, for `help`. */
        val values: String,
        val read: (AllSettings) -> String,
        /** The new snapshot with [String] applied; throws [IllegalArgumentException] on a bad value. */
        val write: (AllSettings, String) -> AllSettings
    )

    private val bitrates = listOf(0, 2_000, 4_000, 6_000, 8_000, 12_000, 16_000, 20_000)

    private fun bool(key: String, raw: String): Boolean = when (raw.trim().lowercase()) {
        "on", "true", "1", "yes" -> true
        "off", "false", "0", "no" -> false
        else -> throw IllegalArgumentException("$key: '$raw' is not on/off")
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private fun eyeKey(control: EyeControl, name: String) = Key(
        name, "on|off",
        read = { onOff(it.eye[control] ?: true) },
        write = { s, v -> s.copy(eye = s.eye + (control to bool(name, v))) }
    )

    val keys: List<Key> = listOf(
        eyeKey(EyeControl.CARD_BUTTON, "eye.card_button"),
        eyeKey(EyeControl.QUICK_TILE, "eye.quick_tile"),
        eyeKey(EyeControl.WIDGET, "eye.widget"),
        eyeKey(EyeControl.ADB, "eye.adb"),
        Key("touch.enabled", "on|off", { onOff(it.touch.enabled) }) { s, v ->
            s.copy(touch = s.touch.copy(enabled = bool("touch.enabled", v)))
        },
        Key("touch.show_touches", "on|off", { onOff(it.touch.showTouches) }) { s, v ->
            s.copy(touch = s.touch.copy(showTouches = bool("touch.show_touches", v)))
        },
        Key("cast.display", "display id (number)", { it.cast.displayId.toString() }) { s, v ->
            val id = v.trim().toIntOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("cast.display: '$v' is not a display id")
            s.copy(cast = s.cast.copy(displayId = id))
        },
        Key(
            "cast.bitrate", "auto|" + bitrates.drop(1).joinToString("|") { "${it / 1_000}" } + " (Mbit/s)",
            { if (it.cast.bitrateKbps == 0) "auto" else "${it.cast.bitrateKbps / 1_000}" }
        ) { s, v ->
            val kbps = if (v.trim().equals("auto", ignoreCase = true)) 0
            else v.trim().toIntOrNull()?.times(1_000)?.takeIf { it in bitrates }
                ?: throw IllegalArgumentException("cast.bitrate: '$v' is not auto or one of the offered Mbit/s")
            s.copy(cast = s.cast.copy(bitrateKbps = kbps))
        },
        Key("cast.playback", "smooth|low_delay", { if (it.cast.smooth) "smooth" else "low_delay" }) { s, v ->
            val smooth = when (v.trim().lowercase()) {
                "smooth" -> true
                "low_delay", "low-delay", "lowdelay" -> false
                else -> throw IllegalArgumentException("cast.playback: '$v' is not smooth or low_delay")
            }
            s.copy(cast = s.cast.copy(smooth = smooth))
        },
        Key("cast.fps", "30|60", { it.cast.fps.toString() }) { s, v ->
            val fps = v.trim().toIntOrNull()?.takeIf { it == 30 || it == 60 }
                ?: throw IllegalArgumentException("cast.fps: '$v' is not 30 or 60")
            s.copy(cast = s.cast.copy(fps = fps))
        },
        Key("cast.sound", "on|off", { onOff(it.cast.sound) }) { s, v ->
            s.copy(cast = s.cast.copy(sound = bool("cast.sound", v)))
        },
        Key("cast.keep_awake", "on|off", { onOff(it.cast.keepAwake) }) { s, v ->
            s.copy(cast = s.cast.copy(keepAwake = bool("cast.keep_awake", v)))
        }
    )

    private fun key(name: String): Key = keys.firstOrNull { it.name == name.trim() }
        ?: throw IllegalArgumentException("unknown setting '$name' (run the help method for the list)")

    /** `key=value` lines, all keys or just [name]. */
    fun get(s: AllSettings, name: String? = null): String =
        if (name.isNullOrBlank()) keys.joinToString("\n") { "${it.name}=${it.read(s)}" }
        else key(name).let { "${it.name}=${it.read(s)}" }

    /**
     * Applies `key=value[,key=value…]` to [s]. Every pair is checked first, so a bad pair changes nothing.
     * Throws [IllegalArgumentException].
     */
    fun set(s: AllSettings, assignments: String): AllSettings {
        val pairs = assignments.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        require(pairs.isNotEmpty()) { "nothing to set: pass --arg key=value[,key=value]" }
        return pairs.fold(s) { acc, pair ->
            val eq = pair.indexOf('=')
            require(eq > 0) { "'$pair' is not key=value" }
            key(pair.substring(0, eq)).write(acc, pair.substring(eq + 1))
        }
    }

    fun help(): String = keys.joinToString("\n") { "${it.name}: ${it.values}" }
}
