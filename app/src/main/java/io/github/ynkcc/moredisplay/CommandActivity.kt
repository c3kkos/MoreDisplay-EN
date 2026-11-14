package io.github.ynkcc.moredisplay

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.DisplaySpec
import io.github.ynkcc.moredisplay.core.Visibility
import java.util.concurrent.Executors
import io.github.ynkcc.moredisplay.eye.EyeControl
import io.github.ynkcc.moredisplay.eye.EyeSettings
import io.github.ynkcc.moredisplay.eye.EyeRequest
import io.github.ynkcc.moredisplay.eye.ViewerController
import io.github.ynkcc.moredisplay.cast.CastController
import io.github.ynkcc.moredisplay.cast.CastSettings
import io.github.ynkcc.moredisplay.core.OrientationOverride

class CommandActivity : Activity() {

    companion object {
        private const val TAG = "MoreDisplay_Cmd"

        const val EXTRA_ACTION = "action"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_NAME = "name"
        const val EXTRA_DISPLAY_ID = "display_id"
        const val EXTRA_FLAGS = "flags"

        const val EXTRA_UID = "uid"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DISPLAY_IDS = "display_ids"

        /** Daemon channel to use for create: lsposed (the Xposed channel, Vector or LSPosed) / shell_shizuku (defaults to the highest-priority connected channel). */
        const val EXTRA_PRIVILEGE = "privilege"

        /** Requirement B: rewrite the mirror source to this displayId while recording. (operated_display_id is reserved for requirement C) */
        const val EXTRA_RECORD_DISPLAY_ID = "record_display_id"

        /** "Open on": always start this app's activities on this display. */
        const val EXTRA_LAUNCH_DISPLAY_ID = "launch_display_id"

        /** Orientation override: `app` (default) or `landscape`. */
        const val EXTRA_ORIENTATION = "orientation"
        const val EXTRA_OPERATED_DISPLAY_ID = "operated_display_id"

        private const val ACTION_CREATE = "create"
        private const val ACTION_REMOVE = "remove"
        private const val ACTION_HOLD = "hold"
        private const val ACTION_LIST = "list"
        private const val ACTION_POLICY_SET = "policy-set"
        private const val ACTION_POLICY_LIST = "policy-list"
        private const val ACTION_POLICY_REMOVE = "policy-remove"
        private const val ACTION_DISPLAYS_FOR_UID = "displays-for-uid"

        /** Request Shizuku permission (shows the system dialog; the result is logged after the user taps Allow). */
        private const val ACTION_SHIZUKU_PERMIT = "shizuku-permit"

        /** The eye: show/hide a managed display in the PiP viewer (`--es state on|off|toggle`, default toggle). */
        private const val ACTION_EYE = "eye"

        /** Cast a display to the last used Chromecast (`--es state on|off`, `--ei display_id N`). */
        private const val ACTION_CAST = "cast"
        const val EXTRA_STATE = "state"
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "md-cmd").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra(EXTRA_ACTION)?.lowercase() ?: ACTION_CREATE
        executor.execute {
            val outcome: kotlin.Pair<Boolean, String> = runCatching { runBlocking { execute(action) } }
                .fold(
                    { true to it },
                    { false to "${it::class.java.simpleName}: ${it.message}" }
                )
            runOnUiThread {
                val (ok, msg) = outcome
                if (ok) {
                    Log.i(TAG, "[$action] ok: $msg")
                    Toast.makeText(applicationContext, "[$action] ok: $msg", Toast.LENGTH_LONG).show()
                } else {
                    Log.e(TAG, "[$action] failed: $msg")
                    Toast.makeText(applicationContext, "[$action] failed: $msg", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }

    private fun execute(action: String): String = runBlocking {
        val repo = App.displays()
        // shizuku-permit is used to obtain the permission itself, so it can't depend on connect (connect needs the permission);
        // otherwise the permission dialog would never appear while the permission is not granted (chicken-and-egg problem).
        if (action != ACTION_SHIZUKU_PERMIT) {
            repo.connect().getOrElse { throw IllegalStateException("connect failed: ${it.message}", it) }
        }
        when (action) {
            ACTION_CREATE -> {
                val spec = DisplaySpec(
                    width = intent.getIntExtra(EXTRA_WIDTH, 1080),
                    height = intent.getIntExtra(EXTRA_HEIGHT, 1920),
                    densityDpi = intent.getIntExtra(EXTRA_DPI, 320),
                    name = intent.getStringExtra(EXTRA_NAME) ?: "MoreDisplay",
                    flags = intent.getIntExtra(EXTRA_FLAGS, 0)
                )
                val privilege = intent.getStringExtra(EXTRA_PRIVILEGE)?.let { raw ->
                    io.github.ynkcc.moredisplay.core.Privilege.entries.firstOrNull {
                        it.name.equals(raw, ignoreCase = true)
                    } ?: throw IllegalArgumentException(
                        "unknown privilege '$raw' (candidates: lsposed/root/shell_shizuku)"
                    )
                }
                val info = repo.createDisplay(spec, privilege).getOrElse {
                    throw IllegalStateException("createDisplay failed: ${it.message}", it)
                }
                "display ${info.displayId} ${info.spec.width}x${info.spec.height}@${info.spec.densityDpi} " +
                    "flags=0x${Integer.toHexString(info.spec.flags)} via ${repo.displayOrigin(info.displayId)}"
            }
            ACTION_REMOVE -> {
                val id = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                require(id >= 0) { "missing --ei display_id" }
                repo.removeDisplay(id).getOrElse {
                    throw IllegalStateException("removeDisplay $id failed: ${it.message}", it)
                }
                "display $id removed"
            }
            ACTION_HOLD -> {
                val id = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                require(id >= 0) { "missing --ei display_id" }
                val info = repo.holdDisplay(id).getOrElse {
                    throw IllegalStateException("holdDisplay $id failed: ${it.message}", it)
                }
                "display ${info.displayId} held"
            }
            ACTION_LIST -> {
                // The device's own screen is always listed first as the default managed display.
                val builtIn = io.github.ynkcc.moredisplay.ui.BuiltInDisplay.query(this@CommandActivity)
                    ?.let { "${it.displayId}(${it.width}x${it.height}@${it.densityDpi}@builtin)" }
                    ?: "${io.github.ynkcc.moredisplay.ui.BuiltInDisplay.ID}(builtin)"
                val virtual = repo.listDisplays().getOrElse {
                    throw IllegalStateException("listDisplays failed: ${it.message}", it)
                }.map {
                    val via = repo.displayOrigin(it.displayId)?.let { p -> "@$p" } ?: ""
                    "${it.displayId}(${it.spec.width}x${it.spec.height}@${it.spec.densityDpi}$via)"
                }
                (listOf(builtIn) + virtual).joinToString(prefix = "displays: ")
            }

            ACTION_POLICY_SET -> {
                val uid = resolveUid()
                val mode = Visibility.fromName(intent.getStringExtra(EXTRA_MODE))
                val ids = intent.getIntArrayExtra(EXTRA_DISPLAY_IDS)?.toSet() ?: emptySet()
                if (mode != Visibility.ALL && ids.isEmpty()) {
                    throw IllegalArgumentException("mode=$mode requires --eia display_ids 1,2")
                }
                val policy = DisplayPolicy(
                    uid = uid,
                    packageName = intent.getStringExtra(EXTRA_TARGET_PACKAGE),
                    visibility = mode,
                    displayIds = ids,
                    operatedDisplayId = intent.getIntExtra(EXTRA_OPERATED_DISPLAY_ID, -1).takeIf { it >= 0 },
                    recordDisplayId = intent.getIntExtra(EXTRA_RECORD_DISPLAY_ID, -1).takeIf { it >= 0 },
                    launchDisplayId = intent.getIntExtra(EXTRA_LAUNCH_DISPLAY_ID, -1).takeIf { it >= 0 },
                    orientation = OrientationOverride.fromName(
                        intent.getStringExtra(EXTRA_ORIENTATION)
                    )
                )
                val stored = repo.setDisplayPolicy(policy).getOrElse {
                    throw IllegalStateException("setDisplayPolicy failed: ${it.message}", it)
                }
                "policy stored: ${stored.describe()}"
            }
            ACTION_POLICY_LIST -> {
                val policies = repo.listDisplayPolicies().getOrElse {
                    throw IllegalStateException("listDisplayPolicies failed: ${it.message}", it)
                }
                if (policies.isEmpty()) {
                    "policies: <empty>"
                } else {
                    policies.joinToString(prefix = "policies: ") { it.describe() }
                }
            }

            ACTION_POLICY_REMOVE -> {
                val uid = resolveUid()
                repo.removeDisplayPolicy(uid).getOrElse {
                    throw IllegalStateException("removeDisplayPolicy failed: ${it.message}", it)
                }
                "policy removed uid=$uid"
            }
            ACTION_DISPLAYS_FOR_UID -> {
                val uid = resolveUid()
                val ids = repo.displaysForUid(uid).getOrElse {
                    throw IllegalStateException("displaysForUid failed: ${it.message}", it)
                }
                "uid=$uid visible displays: $ids"
            }
            ACTION_SHIZUKU_PERMIT -> {
                if (!rikka.shizuku.Shizuku.pingBinder()) {
                    // The server isn't running or this process hasn't received the binder yet; without a hint it would just throw
                    // "binder haven't been received", which is hard to diagnose.
                    "shizuku server not running (start via adb: libshizuku.so, or from the Shizuku app)"
                } else if (rikka.shizuku.Shizuku.getVersion() < 11) {
                    "shizuku server too old, upgrade shizuku"
                } else if (rikka.shizuku.Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    "shizuku already granted"
                } else {
                    val result = kotlinx.coroutines.CompletableDeferred<Int>()
                    val listener = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                            if (requestCode == 100) result.complete(grantResult)
                        }
                    }
                    rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener)
                    try {
                        rikka.shizuku.Shizuku.requestPermission(100)
                        val granted = withTimeoutOrNull(30_000) { result.await() }
                        when {
                            granted == null -> "shizuku permission request timed out (dialog dismissed?)"
                            granted == android.content.pm.PackageManager.PERMISSION_GRANTED ->
                                "shizuku granted"
                            else -> "shizuku denied: $granted"
                        }
                    } finally {
                        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(listener)
                    }
                }
            }
            ACTION_EYE -> {
                val eye = EyeControl.ADB
                require(EyeSettings.isEnabled(eye)) {
                    "the eye adb command is turned off in MoreDisplay → Settings"
                }
                val request = EyeRequest.parse(intent.getStringExtra(EXTRA_STATE))
                    ?: throw IllegalArgumentException("--es state must be on, off or toggle")
                val explicit = intent.getIntExtra(EXTRA_DISPLAY_ID, -1).takeIf { it >= 0 }
                val controller = ViewerController
                val target = if (explicit != null) {
                    require(explicit in controller.managedIds()) { "display $explicit is not managed by MoreDisplay" }
                    explicit
                } else {
                    controller.watching.value ?: controller.lastTarget()
                        ?: throw IllegalArgumentException("no display to show yet: pass --ei display_id N")
                }
                controller.apply(this@CommandActivity, request, target)
            }
            ACTION_CAST -> {
                val cast = CastController
                when (intent.getStringExtra(EXTRA_STATE)?.lowercase() ?: "on") {
                    "off", "0", "false" -> {
                        cast.stop(this@CommandActivity)
                        "cast stopped"
                    }
                    "on", "1", "true" -> {
                        val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1).takeIf { it >= 0 }
                            ?: CastSettings.current().displayId
                        require(displayId >= 0) { "no display to cast: pass --ei display_id N or set it in Settings → Cast" }
                        val routeId = CastSettings.lastDeviceId()
                            ?: throw IllegalArgumentException("no Cast device used yet: cast once from the app first")
                        val app = applicationContext
                        // Device discovery runs on the main thread; give it up to 15 s to find the last device.
                        withContext(Dispatchers.Main) { cast.startDiscovery(app) }
                        val device = withTimeoutOrNull(15_000) {
                            cast.devices.first { list -> list.any { it.id == routeId } }.first { it.id == routeId }
                        }
                        // Connect while the search is still running, then stop searching.
                        withContext(Dispatchers.Main) {
                            if (device != null) cast.start(this@CommandActivity, device, displayId)
                            cast.stopDiscovery(app)
                        }
                        device ?: throw IllegalStateException(
                            "${CastSettings.lastDeviceName() ?: "the last Cast device"} wasn't found on the network"
                        )
                        "casting display $displayId to ${device.name}"
                    }
                    else -> throw IllegalArgumentException("--es state must be on or off")
                }
            }
            else -> throw IllegalArgumentException(
                "unknown action: $action (create/remove/hold/list/policy-set/policy-list/" +
                    "policy-remove/displays-for-uid/shizuku-permit/eye/cast)"
            )
        }
    }

    /** Supports `--ei uid N` directly, or `--es target_package io.github.ynkcc.moredisplay.probe` resolved by package name. */
    private fun resolveUid(): Int {
        val explicit = intent.getIntExtra(EXTRA_UID, -1)
        if (explicit >= 0) return explicit
        val pkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
            ?: throw IllegalArgumentException("missing --ei uid or --es target_package")
        return runCatching { packageManager.getApplicationInfo(pkg, 0).uid }
            .getOrElse { throw IllegalArgumentException("package $pkg not installed: ${it.message}") }
    }
}
