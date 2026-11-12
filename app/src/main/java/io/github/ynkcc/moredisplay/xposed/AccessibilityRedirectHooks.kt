package io.github.ynkcc.moredisplay.xposed

import android.content.ComponentName
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.ynkcc.moredisplay.daemon.DisplayPolicyRegistry
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/**
 * Requirement C: accessibility display redirection -- the target app's accessibility gestures are rewritten to a managed virtual display.
 *
 * ## Hook point (verified with dexdump on a real device's services.jar, Android 16 / SDK 36)
 * `AccessibilityServiceConnection.dispatchGesture(I, ParceledListSlice, I)V`
 * (inherited from `AbstractAccessibilityServiceConnection`; the overriding implementation lives in the subclass).
 * Call chain verified from the real device's method body (offset 2f4054):
 * ```
 * dispatchGesture(sequence, steps, displayId)
 *   -> SystemSupport.getMotionEventInjectorForDisplayLocked(displayId)
 *   -> MotionEventInjector.injectEvents(list, client, sequence, displayId, isAccessibilityTool)
 * ```
 * The third argument is the target displayId, passed all the way to the gesture injector; rewriting it here makes the whole injection chain land on the managed display.
 *
 * Client side (framework) confirmed: `AccessibilityService.dispatchGesture(...)` passes
 * `gesture.getDisplayId()` to the binder unchanged; the default of `GestureDescription.Builder.mDisplayId`
 * is `Display.DEFAULT_DISPLAY(0)`. So typical auto-click/swipe scripts that don't call
 * `setDisplayId` explicitly send their gestures to the default display -- exactly what needs to be replaced.
 *
 * ## Why match by "package name + mUserId" instead of uid
 * The accessibility connection object (`AbstractAccessibilityServiceConnection`) has no uid field,
 * only `mComponentName` and `mUserId` (verified against the instance field table via dexdump on a real device). A policy entry's
 * uid already encodes the user (`uid / 100000` == mUserId), so matching by "package name + owning user of the service"
 * is precise, and also avoids resolving `PackageManager.getPackageUidAsUser` every time.
 * On multi-user setups the same package is installed once per user (with different uids), so matching by package name alone is ambiguous;
 * hence mUserId is a required dimension.
 *
 * ## Only rewrites when targeting the default display (avoid collateral damage)
 * Rewritten only when all of the following hold:
 * 1. The connected service's package has `operatedDisplayId` configured in the policy;
 * 2. The gesture's target displayId == 0 (the default display; services explicitly targeting another display are passed through);
 * 3. The target displayId is a virtual display managed by this daemon engine (`SystemDaemon.isManagedDisplay`).
 *
 * ## Pass-through by default
 * No `operatedDisplayId` configured / package mismatch / any condition not met -> plain `proceed()`,
 * behaving exactly as without the module. System-level actions such as `performGlobalAction` are not on this path and are unaffected.
 */
private const val TAG = "MoreDisplay_A11y"

/** `Display.DEFAULT_DISPLAY`. */
private const val DEFAULT_DISPLAY_ID = 0

private val gestureHits = AtomicLong()
private val redirectApplied = AtomicLong()
private val redirectSkipped = AtomicLong()

/** mComponentName field (declared on the abstract parent `AbstractAccessibilityServiceConnection`). */
@Volatile
private var componentField: Field? = null

/** mUserId field (on `AccessibilityServiceConnection`; the user that owns the service). */
@Volatile
private var userIdField: Field? = null

@Volatile
private var accessibilityHookInstalled: String = "false"

fun XposedModule.installAccessibilityRedirectHooks(classLoader: ClassLoader) {
    runCatching {
        val clz = classLoader.loadClass("com.android.server.accessibility.AccessibilityServiceConnection")
        val method = clz.declaredMethods.firstOrNull {
            it.name == "dispatchGesture" &&
                it.parameterCount == 3 &&
                it.parameterTypes[0] == java.lang.Integer.TYPE &&
                it.parameterTypes[1].name == "android.content.pm.ParceledListSlice" &&
                it.parameterTypes[2] == java.lang.Integer.TYPE
        } ?: throw NoSuchMethodException(
            "AccessibilityServiceConnection.dispatchGesture(I,ParceledListSlice,I)V"
        )
        val superClz = clz.superclass
        componentField = runCatching {
            clz.getDeclaredField("mComponentName").apply { isAccessible = true }
        }.recoverCatching {
            superClz.getDeclaredField("mComponentName").apply { isAccessible = true }
        }.getOrNull() ?: throw NoSuchFieldException(
            "mComponentName not found on $clz / $superClz"
        )
        userIdField = runCatching {
            clz.getDeclaredField("mUserId").apply { isAccessible = true }
        }.recoverCatching {
            superClz.getDeclaredField("mUserId").apply { isAccessible = true }
        }.getOrNull()
        hook(method).intercept { chain ->
            val displayId = chain.getArg(2) as? Int
            val handled = if (displayId != null) {
                runCatching { tryRedirectGesture(chain, displayId) }
                    .onFailure { Log.e(TAG, "gesture redirect failed", it) }
                    .getOrDefault(false)
            } else {
                false
            }
            if (!handled) chain.proceed() else null
        }
        accessibilityHookInstalled = "true"
        log(
            Log.INFO,
            TAG,
            "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()} " +
                "(componentField=${componentField != null})"
        )
    }.onFailure {
        accessibilityHookInstalled = "false"
        log(Log.ERROR, TAG, "hook AccessibilityServiceConnection.dispatchGesture failed", it)
    }
}

/**
 * Returns true if the call was completed with the rewritten arguments (the caller must not proceed again).
 */
private fun tryRedirectGesture(chain: XposedInterface.Chain, displayId: Int): Boolean {
    val connection = chain.thisObject ?: return false
    val field = componentField ?: return false
    val componentName = field.get(connection) as? ComponentName ?: return false
    val packageName = componentName.packageName
    // Owning user of the service: on multi-user setups each user has its own copy of the package (different uid), so userId is needed to disambiguate.
    val userId = userIdField?.getInt(connection) ?: 0

    val target = DisplayPolicyRegistry.operatedDisplayIdForPackage(packageName, userId)
        ?: return false

    gestureHits.incrementAndGet()

    val redirectable = displayId == DEFAULT_DISPLAY_ID &&
        target != DEFAULT_DISPLAY_ID &&
        SystemDaemon.isManagedDisplay(target)

    if (!redirectable) {
        val s = redirectSkipped.incrementAndGet()
        if (s <= 5 || s % 100 == 0L) {
            Log.i(TAG, "A11Y skip pkg=$packageName displayId=$displayId target=$target (skip#=$s)")
        }
        return false
    }

    // Continue the original flow with the rewritten arguments: injector selection and injectEvents all use this displayId.
    val args = chain.args.toTypedArray()
    args[2] = target
    redirectApplied.incrementAndGet()
    Log.i(
        TAG,
        "A11Y redirect pkg=$packageName user=$userId gesture $displayId -> $target " +
            "(applied#${redirectApplied.get()})"
    )
    chain.proceedWith(chain.thisObject, args)
    return true
}

/** For acceptance/self-check: requirement C hook hit counters (distinguishes "installed" from "effective"). */
fun accessibilityHookStats(): String =
    "a11y(installed=$accessibilityHookInstalled call=${gestureHits.get()} " +
        "applied=${redirectApplied.get()} skip=${redirectSkipped.get()})"
