package io.github.ynkcc.moredisplay.cast

import android.content.Context
import io.github.ynkcc.moredisplay.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Settings → Cast. */
data class CastConfig(
    /** The managed display to cast, or -1 if none was picked. */
    val displayId: Int = -1,
    /** Video bitrate in kbit/s, 0 = Auto. */
    val bitrateKbps: Int = 0,
    val fps: Int = 30,
    val sound: Boolean = true,
    val keepAwake: Boolean = false
) {
    /** Auto: 4 Mbit/s up to 720p, 8 Mbit/s up to 1080p, 12 Mbit/s above. */
    fun bitrateFor(width: Int, height: Int): Int = when {
        bitrateKbps > 0 -> bitrateKbps * 1_000
        width * height <= 1280 * 720 -> 4_000_000
        width * height <= 1920 * 1080 -> 8_000_000
        else -> 12_000_000
    }
}

object CastSettings {
    private const val PREFS = "cast_settings"

    private val flow = MutableStateFlow(CastConfig())

    val config: StateFlow<CastConfig> get() {
        flow.value = load()
        return flow
    }

    fun current(): CastConfig = load()

    fun update(change: (CastConfig) -> CastConfig) {
        val next = change(load())
        prefs()?.edit()
            ?.putInt("display_id", next.displayId)
            ?.putInt("bitrate_kbps", next.bitrateKbps)
            ?.putInt("fps", next.fps)
            ?.putBoolean("sound", next.sound)
            ?.putBoolean("keep_awake", next.keepAwake)
            ?.apply()
        flow.value = next
    }

    /** The Cast device used last (for adb and for "Cast" without picking again). */
    fun rememberDevice(routeId: String, name: String) {
        prefs()?.edit()?.putString("route_id", routeId)?.putString("route_name", name)?.apply()
    }

    fun lastDeviceId(): String? = prefs()?.getString("route_id", null)
    fun lastDeviceName(): String? = prefs()?.getString("route_name", null)

    private fun load(): CastConfig {
        val p = prefs() ?: return CastConfig()
        return CastConfig(
            displayId = p.getInt("display_id", -1),
            bitrateKbps = p.getInt("bitrate_kbps", 0),
            fps = p.getInt("fps", 30),
            sound = p.getBoolean("sound", true),
            keepAwake = p.getBoolean("keep_awake", false)
        )
    }

    private fun prefs() = try {
        App.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }
}
