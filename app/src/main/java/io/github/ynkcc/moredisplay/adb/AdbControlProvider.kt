package io.github.ynkcc.moredisplay.adb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import io.github.ynkcc.moredisplay.cast.CastController
import io.github.ynkcc.moredisplay.cast.CastSettings
import io.github.ynkcc.moredisplay.eye.EyeControl
import io.github.ynkcc.moredisplay.eye.EyeSettings

/**
 * Every setting from a computer, without touching the screen:
 *
 * ```
 * adb shell content call --uri content://io.github.ynkcc.moredisplay.adb --method help
 * adb shell content call --uri content://io.github.ynkcc.moredisplay.adb --method get [--arg cast.sound]
 * adb shell content call --uri content://io.github.ynkcc.moredisplay.adb --method set --arg cast.sound=off,cast.fps=30
 * adb shell content call --uri content://io.github.ynkcc.moredisplay.adb --method status
 * ```
 *
 * The answer is in the printed Bundle (`result=…`, or `error=…`). Only the adb shell (uid 2000) and root may call
 * it: the provider is exported so that `content call` can reach it, and every other app is refused. Starting or
 * stopping the eye and the cast stays in CommandActivity, because Android 12+ only lets a foreground activity start
 * the cast's foreground service.
 */
class AdbControlProvider : ContentProvider() {

    companion object {
        private const val TAG = "MoreDisplay_Adb"
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID && uid != Process.myUid()) {
            Log.w(TAG, "refused $method from uid $uid")
            throw SecurityException("MoreDisplay adb control is for the adb shell only")
        }
        val out = Bundle()
        runCatching { run(method.lowercase(), arg) }
            .onSuccess { out.putString("result", it) }
            .onFailure {
                Log.w(TAG, "$method($arg) failed: ${it.message}")
                out.putString("error", it.message ?: it.javaClass.simpleName)
            }
        return out
    }

    private fun run(method: String, arg: String?): String {
        // Binder threads carry the caller's identity; settings are our own files, so act as ourselves.
        val token = Binder.clearCallingIdentity()
        try {
            return when (method) {
                "help" -> "methods: help, get [key], set key=value[,key=value], status\n" + AdbSettings.help()
                "get" -> AdbSettings.get(snapshot(), arg)
                "set" -> {
                    val before = snapshot()
                    val after = AdbSettings.set(before, arg ?: "")
                    apply(before, after)
                    Log.i(TAG, "set $arg")
                    AdbSettings.get(snapshot())
                }
                "status" -> status()
                else -> throw IllegalArgumentException("unknown method '$method' (help, get, set, status)")
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun snapshot() = AllSettings(
        cast = CastSettings.current(),
        eye = EyeControl.entries.associateWith { EyeSettings.isEnabled(it) },
        touch = EyeSettings.touchOptions()
    )

    /** Writes only what changed, through the same stores the Settings screen uses (so the screen updates too). */
    private fun apply(before: AllSettings, after: AllSettings) {
        if (after.cast != before.cast) CastSettings.update { after.cast }
        for (control in EyeControl.entries) {
            val on = after.eye[control] ?: true
            if (on != before.eye[control]) EyeSettings.setEnabled(control, on)
        }
        if (after.touch != before.touch) EyeSettings.setTouch(after.touch)
    }

    private fun status(): String = buildString {
        append("cast=").append(CastController.state.value).append('\n')
        append("tablet=").append(CastController.tabletStats.value ?: "-").append('\n')
        append("tv=").append(CastController.receiverSummary()).append('\n')
        append("eye.watching=").append(io.github.ynkcc.moredisplay.eye.ViewerController.watching.value ?: "-")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
