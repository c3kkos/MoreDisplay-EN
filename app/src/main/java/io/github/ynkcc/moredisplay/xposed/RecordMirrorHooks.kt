package io.github.ynkcc.moredisplay.xposed

import android.os.Binder
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.ynkcc.moredisplay.core.VirtualDisplayFlags
import io.github.ynkcc.moredisplay.daemon.DisplayPolicyRegistry
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/**
 * Requirement B: recording display redirection -- when the target app records the screen, it actually records the managed virtual display instead of the default one.
 *
 * ## Hook point (verified with dexdump on a real device's services.jar, Android 16 / SDK 36)
 * `DisplayManagerService#createVirtualDisplayInternal(...)`.
 * - AOSP (Android 14+): 7 parameters, the last one is callingUid -- `BinderService.createVirtualDisplay` passes it in
 *   after `Binder.getCallingUid()`;
 * - ColorOS (tested on Android 16): 6 parameters, callingUid removed -- in that case read
 *   `Binder.getCallingUid()` before proceed (this point is before clearCallingIdentity, so the identity is still valid).
 *
 * ## Why change `mDisplayIdToMirror` instead of swapping the Surface
 * The whole pipeline has a single source of truth for the mirror source: `VirtualDisplayConfig.mDisplayIdToMirror` (`PRIVATE FINAL int`). Its two observed consumers:
 * 1. `VirtualDisplayAdapter$VirtualDisplayDevice.<init>`: `mDisplayIdToMirror = config.getDisplayIdToMirror()`
 *    -- decides which display the device actually mirrors, and is also where `dumpsys display` prints `mDisplayIdToMirror=` from (acceptance evidence);
 * 2. `createVirtualDisplayInternal` itself then uses the same getter to build
 *    `ContentRecordingSession.createDisplaySession/createOverlaySession(displayIdToMirror)`
 *    -> `IMediaProjectionManager.setContentRecordingSession(...)` (the ContentRecordingSession path on Android 15+).
 * So changing the mirror source in the config at this method's entry covers both paths, **without touching the app or swapping the surface**.
 *
 * Client side (framework.jar) confirmed: all three overloads of `DisplayManager.createVirtualDisplay(...)` unconditionally call
 * `builder.setDisplayIdToMirror(getDisplayIdToMirror())`, and on single-user devices that method always returns
 * `Display.DEFAULT_DISPLAY(0)` (it only returns `getMainDisplayIdAssignedToUser()` when a background user is visible on multi-user setups).
 * In other words, "virtual displays created by apps" all record "mirror display 0" by default -- exactly what recording redirection needs to replace.
 *
 * ## Only applies to genuine mirrored recording (avoid collateral damage)
 * Rewritten only when all of the following hold:
 * 1. The caller is an app uid, and the policy has `recordDisplayId` configured;
 * 2. `getDisplayIdToMirror() == 0` (really mirroring the default display);
 * 3. flags **do not include** `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` (that means "show only own content", not mirroring);
 * 4. It is treated as "mirroring the default display" if either of these holds:
 *    - flags include `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` (0x10);
 *    - a MediaProjection token is present (`IMediaProjection` non-null = really recording).
 *    (The second rule exists because flag combinations vary widely across ROMs/apps, and `DisplayManager.getDisplayIdToMirror()`
 *    always returns 0 on single-user devices, so only the token truly distinguishes "recording" from "self-drawn content display";
 *    `OWN_CONTENT_ONLY` still blocks the "borrow the token but draw itself" case.)
 * 5. It is not a display created by a VirtualDevice (`companion/virtual`).
 *
 * ## Pass-through by default
 * No `recordDisplayId` configured / not an app uid / any condition not met -> plain `proceed()`, behaving exactly as without the module.
 */
private const val TAG = "MoreDisplay_Record"

/** `Display.DEFAULT_DISPLAY`. */
private const val DEFAULT_DISPLAY_ID = 0

private val createHits = AtomicLong()
private val redirectApplied = AtomicLong()
private val redirectSkipped = AtomicLong()

@Volatile
private var mirrorField: Field? = null

@Volatile
private var flagsField: Field? = null

@Volatile
private var recordHookInstalled: String = "false"

fun XposedModule.installRecordMirrorHooks(classLoader: ClassLoader) {
    runCatching {
        val configClass = classLoader.loadClass("android.hardware.display.VirtualDisplayConfig")
        mirrorField = configClass.getDeclaredField("mDisplayIdToMirror").apply { isAccessible = true }
        flagsField = configClass.getDeclaredField("mFlags").apply { isAccessible = true }
        val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService")
        // AOSP (Android 14+): 7 parameters, the last one is callingUid;
        // ColorOS/others: 6 parameters (callingUid removed); in that case read Binder.getCallingUid() before proceed.
        val method = clz.declaredMethods.firstOrNull {
            it.name == "createVirtualDisplayInternal" &&
                it.parameterTypes[0].name == "android.hardware.display.VirtualDisplayConfig" &&
                (it.parameterCount == 7 && it.parameterTypes[6] == java.lang.Integer.TYPE ||
                    it.parameterCount == 6)
        } ?: throw NoSuchMethodException(
            "DisplayManagerService.createVirtualDisplayInternal(VirtualDisplayConfig,...)"
        )
        val uidArgIndex = if (method.parameterCount == 7) 6 else -1
        hook(method).intercept { chain ->
            val config = chain.getArg(0)
            val uid = (uidArgIndex.takeIf { it >= 0 }?.let { chain.getArg(it) } as? Int)
                ?: Binder.getCallingUid()
            // After the elvis chain uid is always a non-null Int, so only config needs checking here
            if (config != null) {
                runCatching { tryRedirectMirror(this, chain, config, uid) }
                    .onFailure { Log.e(TAG, "mirror redirect failed uid=$uid", it) }
            }
            chain.proceed()
        }
        recordHookInstalled = "true"
        log(
            Log.INFO,
            TAG,
            "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()} " +
                "(params=${method.parameterCount}, mirrorField=${mirrorField != null}, flagsField=${flagsField != null})"
        )
    }.onFailure {
        recordHookInstalled = "false"
        log(Log.ERROR, TAG, "hook createVirtualDisplayInternal failed", it)
    }
}

private fun tryRedirectMirror(
    module: XposedModule,
    chain: XposedInterface.Chain,
    config: Any,
    uid: Int
) {
    val target = DisplayPolicyRegistry.recordDisplayIdFor(uid) ?: return
    createHits.incrementAndGet()

    val field = mirrorField ?: throw IllegalStateException("mDisplayIdToMirror field not resolved")
    val flags = (flagsField?.getInt(config)) ?: 0
    val current = field.getInt(config)
    val projection = chain.getArg(2)
    val virtualDevice = chain.getArg(3)

    val mirroringDefault = current == DEFAULT_DISPLAY_ID
    val autoMirror = flags and VirtualDisplayFlags.AUTO_MIRROR != 0
    val ownContentOnly = flags and VirtualDisplayFlags.OWN_CONTENT_ONLY != 0

    // Two equivalent paths for deciding "this is mirroring the default display":
    //   1. AUTO_MIRROR set explicitly;
    //   2. a MediaProjection token (recording) is present and OWN_CONTENT_ONLY is not declared.
    // Both require the mirror source to really be display 0 -- only "mirror the default display" is changed, nothing else is touched.
    val mirroringDefaultDisplay = mirroringDefault && !ownContentOnly &&
        (autoMirror || projection != null)

    if (!mirroringDefaultDisplay || virtualDevice != null) {
        val s = redirectSkipped.incrementAndGet()
        Log.i(
            TAG,
            "RECORD skip uid=$uid mirror=$current flags=0x${Integer.toHexString(flags)} " +
                "autoMirror=$autoMirror ownContentOnly=$ownContentOnly " +
                "vdev=${virtualDevice != null} projection=${projection != null} (skip#=$s)"
        )
        return
    }

    field.setInt(config, target)
    val a = redirectApplied.incrementAndGet()
    Log.i(
        TAG,
        "RECORD redirect uid=$uid mirror $DEFAULT_DISPLAY_ID -> $target " +
            "flags=0x${Integer.toHexString(flags)} projection=${projection != null} (applied#=$a)"
    )
}

/** For acceptance/self-check: requirement B hook hit counters (distinguishes "installed" from "effective"). */
fun recordMirrorHookStats(): String =
    "record(installed=$recordHookInstalled call=${createHits.get()} " +
        "applied=${redirectApplied.get()} skip=${redirectSkipped.get()})"
