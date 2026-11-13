package io.github.ynkcc.moredisplay.xposed

import android.content.pm.ActivityInfo
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.ynkcc.moredisplay.core.OrientationMap
import io.github.ynkcc.moredisplay.core.OrientationOverride
import io.github.ynkcc.moredisplay.daemon.DisplayPolicyRegistry
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/**
 * "Orientation: Landscape": answers an app's portrait requests with landscape, so it fills a landscape virtual
 * display instead of being letterboxed into a portrait strip (a virtual display never rotates).
 *
 * ## Hook point
 * `WindowContainer#setOrientation(int, ...)`: every requested orientation of an activity goes through it, both the
 * one declared in the manifest (set when the `ActivityRecord` is created) and later `setRequestedOrientation` calls
 * (Android 12L+: `setOrientation(int, WindowContainer)`, which `setOrientation(int)` forwards to; Android 11 has a
 * 3-argument variant). For an `ActivityRecord` of an app with the override, the requested value is mapped and the
 * activity's declared `info.screenOrientation` is changed the same way, so Android's size-compatibility logic doesn't
 * letterbox it either.
 */
private const val TAG = "MoreDisplay_Orientation"
private val orientationHits = AtomicLong()

@Volatile
private var orientationHookInstalled = "false"

fun XposedModule.installOrientationHooks(classLoader: ClassLoader) {
    runCatching {
        val container = classLoader.loadClass("com.android.server.wm.WindowContainer")
        val activityRecord = classLoader.loadClass("com.android.server.wm.ActivityRecord")
        val infoField = activityRecord.getDeclaredField("info").apply { isAccessible = true }
        val packageField: Field? = runCatching {
            activityRecord.getDeclaredField("packageName").apply { isAccessible = true }
        }.getOrNull()

        val methods = container.declaredMethods.filter {
            it.name == "setOrientation" && it.parameterCount >= 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        check(methods.isNotEmpty()) { "WindowContainer.setOrientation(int, ...) not found" }
        methods.forEach { method ->
            hook(method).intercept { chain ->
                val target = chain.thisObject
                val requested = chain.getArg(0) as? Int
                if (target != null && requested != null && activityRecord.isInstance(target)) {
                    val mapped = runCatching { mapFor(target, requested, infoField, packageField) }
                        .getOrElse {
                            Log.e(TAG, "orientation override failed", it)
                            requested
                        }
                    if (mapped != requested) {
                        val args = chain.args.toTypedArray()
                        args[0] = mapped
                        return@intercept chain.proceedWith(target, args)
                    }
                }
                chain.proceed()
            }
        }
        orientationHookInstalled = "true(${methods.size})"
        log(Log.INFO, TAG, "orientation hook installed on ${methods.size} method(s)")
    }.onFailure {
        orientationHookInstalled = "false"
        log(Log.ERROR, TAG, "orientation hook failed", it)
    }
}

private fun mapFor(record: Any, requested: Int, infoField: Field, packageField: Field?): Int {
    val info = infoField.get(record) as? ActivityInfo ?: return requested
    val pkg = (packageField?.get(record) as? String) ?: info.packageName
    val uid = info.applicationInfo?.uid ?: return requested
    val override = DisplayPolicyRegistry.orientationFor(pkg, uid)
    if (override == OrientationOverride.APP) return requested
    val mapped = OrientationMap.apply(override, requested)
    // Keep the declared orientation consistent, or size-compat mode would still letterbox the activity.
    val declared = OrientationMap.apply(override, info.screenOrientation)
    if (declared != info.screenOrientation) info.screenOrientation = declared
    if (mapped != requested) {
        val n = orientationHits.incrementAndGet()
        Log.i(TAG, "ORIENTATION $pkg($uid) $requested -> $mapped (hit#=$n)")
    }
    return mapped
}

fun orientationHookStats(): String =
    "orientation(installed=$orientationHookInstalled hit=${orientationHits.get()})"
