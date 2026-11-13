package io.github.ynkcc.moredisplay.eye

import android.content.Context
import io.github.ynkcc.moredisplay.App
import io.github.ynkcc.moredisplay.data.StateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Settings → Eye controls: one switch per [EyeControl] (all on by default), plus the last watched display. */
object EyeSettings {
    private const val PREFS_NAME = "eye_settings"
    private const val KEY_LAST_KEY = "last_display_key"
    private const val KEY_LAST_ID = "last_display_id"

    private val enabledFlow = MutableStateFlow(EyeControl.entries.associateWith { true })

    /** Live switch values, for the Settings screen and the display cards. */
    val enabled: StateFlow<Map<EyeControl, Boolean>> get() {
        refresh()
        return enabledFlow
    }

    fun isEnabled(control: EyeControl): Boolean =
        prefs()?.getBoolean(control.key, true) ?: true

    fun setEnabled(control: EyeControl, on: Boolean) {
        prefs()?.edit()?.putBoolean(control.key, on)?.apply()
        refresh()
    }

    private val touchFlow = MutableStateFlow(TouchOptions())

    /** Touch control options (Settings → Eye controls). */
    val touch: StateFlow<TouchOptions> get() {
        touchFlow.value = touchOptions()
        return touchFlow
    }

    fun touchOptions(): TouchOptions = TouchOptions(
        enabled = prefs()?.getBoolean("touch_enabled", true) ?: true,
        showTouches = prefs()?.getBoolean("show_touches", false) ?: false
    )

    fun setTouch(options: TouchOptions) {
        prefs()?.edit()?.putBoolean("touch_enabled", options.enabled)?.putBoolean("show_touches", options.showTouches)?.apply()
        touchFlow.value = options
    }

    /** Remembers [displayId] as the last watched display (by saved key, so it survives a reboot). */
    fun rememberLast(displayId: Int) {
        val key = EyeLogic.keyOf(displayId, StateStore.load())
        prefs()?.edit()?.putString(KEY_LAST_KEY, key)?.putInt(KEY_LAST_ID, displayId)?.apply()
    }

    fun lastKey(): String? = prefs()?.getString(KEY_LAST_KEY, null)

    fun lastRawId(): Int? = prefs()?.getInt(KEY_LAST_ID, -1)?.takeIf { it >= 0 }

    private fun refresh() {
        enabledFlow.value = EyeControl.entries.associateWith { isEnabled(it) }
    }

    private fun prefs() = try {
        App.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }
}

/** Touch control in the full-screen eye viewer. */
data class TouchOptions(val enabled: Boolean = true, val showTouches: Boolean = false)
