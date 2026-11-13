package io.github.ynkcc.moredisplay.xposed

import android.os.Binder
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.ynkcc.moredisplay.daemon.EyeSwapGate
import io.github.ynkcc.moredisplay.daemon.DisplayPolicyRegistry
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/**
 * Requirement A: filter display visibility by caller uid (blacklist / whitelist).
 *
 * ## Why everything happens on the system_server side
 * The module scope is only `system` (system_server). All `IDisplayManager` binder requests and all
 * `DisplayListener` events originate inside system_server, so filtering here covers every app,
 * **with no need to hook the target app's process at all**.
 *
 * ## Hook points (signatures verified with dexdump on a real device's services.jar, Android 16 / SDK 36)
 * 1. `DisplayManagerService$BinderService#getDisplayIds(Z)[I` (Android 14+; `getDisplayIds()[I` on Android 10-13)
 *    -- the binder entry apps use to get the list (`DisplayManager.getDisplays()` goes through it too).
 *    Observed: `Binder.getCallingUid()` -> `clearCallingIdentity()` -> `LogicalDisplayMapper.getDisplayIdsLocked(uid, includeDisabled)`.
 *    So `Binder.getCallingUid()` must be read **before** `chain.proceed()`, then the returned `int[]` is filtered.
 * 2. `DisplayManagerService#getDisplayInfoInternal(II)Landroid/view/DisplayInfo;`
 *    -- the by-id entry; the second parameter is `callingUid`.
 *    Observed call chain: `BinderService.getDisplayInfo(I)` -> `getDisplayInfoInternal(displayId, callingUid)`;
 *    the system itself goes through `LocalService.getDisplayInfo(I)` -> `getDisplayInfoInternal(displayId, Process.myUid())`.
 *    On a policy match it returns `null` ("guessing the id doesn't help either").
 * 3. Event delivery (per-callback convergence point, two layers of safety)
 *    - `DisplayManagerService$CallbackRecord#notifyDisplayEventAsync(II)Z`
 *      -- the entry `DisplayManagerService.deliverDisplayEvent(...)` calls for each callback (verified via dexdump on a real device).
 *      `this` is the `CallbackRecord`, and its `mUid` field is the receiver's uid; on a policy match it returns `false` (not delivered).
 *    - `DisplayManagerService$CallbackRecord#transmitDisplayEvent(II)V`
 *      -- the final hop `mCallback.onDisplayEvent(displayId, event)`;
 *      `dispatchPending()` (re-delivery of cached events) and `notifyDisplayEventAsync()` both converge here, acting as a fallback.
 *    Together the two hooks cover `onDisplayAdded/onDisplayRemoved/onDisplayChanged`,
 *    including the case where "the callback wasn't ready, the event was cached and re-delivered later by dispatchPending".
 *
 * ## Pass-through by default
 * With no policy entry, or when the caller is not an app uid (uid<10000: system/systemui/shell/root...),
 * every hook takes the original path, behaving exactly as without the module.
 */
private const val TAG = "MoreDisplay_Visibility"

private val listHits = AtomicLong()
private val listTrimmed = AtomicLong()
private val pointHits = AtomicLong()
private val pointBlocked = AtomicLong()
private val notifyHits = AtomicLong()
private val notifySuppressed = AtomicLong()
private val transmitHits = AtomicLong()
private val transmitSuppressed = AtomicLong()
private val uidReadFailures = AtomicLong()

/** Installation state: distinguishes "installed" from "effective". */
private val installed = LinkedHashMap<String, Boolean>()

/** Snapshot taken after installation, for lock-free reads from other threads. */
@Volatile
private var installedSummary: String = "<not installed>"

@Volatile
private var callbackUidField: Field? = null

fun XposedModule.installDisplayVisibilityHooks(classLoader: ClassLoader) {
    hookBinderGetDisplayIds(classLoader)
    hookGetDisplayInfoInternal(classLoader)
    hookCallbackNotifyDisplayEvent(classLoader)
    hookCallbackTransmitDisplayEvents(classLoader)
    hookDeliverDisplayEventDiag(classLoader)
    installedSummary = installed.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }
    log(Log.INFO, TAG, "visibility hooks installed: $installedSummary")
}

private fun XposedModule.hookBinderGetDisplayIds(classLoader: ClassLoader) = runCatching {
    val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService\$BinderService")
    // Android 14+: getDisplayIds(boolean includeDisabled); Android 10-13: getDisplayIds(). Both read the calling uid
    // and then clear the calling identity, so the uid is read before proceed in either case.
    val method = clz.declaredMethods.firstOrNull {
        it.name == "getDisplayIds" &&
            it.returnType == IntArray::class.java &&
            (it.parameterCount == 0 ||
                it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE)
    } ?: throw NoSuchMethodException("BinderService.getDisplayIds(Z)[I / getDisplayIds()[I")
    hook(method).intercept { chain ->
        // Must be read before proceed: the method calls clearCallingIdentity() internally.
        val callingUid = Binder.getCallingUid()
        val result = chain.proceed()
        val ids = result as? IntArray
        if (ids == null || DisplayPolicyRegistry.get(callingUid) == null) {
            result
        } else {
            val filtered = DisplayPolicyRegistry.filter(callingUid, ids)
            val n = listHits.incrementAndGet()
            if (filtered.size != ids.size) {
                val t = listTrimmed.incrementAndGet()
                Log.i(
                    TAG,
                    "LIST trim uid=$callingUid ${ids.toList()} -> ${filtered.toList()} (call#=$n trim#=$t)"
                )
            } else if (n <= 3 || n % 200 == 0L) {
                Log.i(TAG, "LIST pass uid=$callingUid ${ids.toList()} (call#=$n)")
            }
            filtered
        }
    }
    installed["BinderService.getDisplayIds"] = true
    log(Log.INFO, TAG, "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()} -> ${method.returnType.name}")
}.onFailure {
    installed["BinderService.getDisplayIds"] = false
    log(Log.ERROR, TAG, "hook BinderService.getDisplayIds failed", it)
}

private fun XposedModule.hookGetDisplayInfoInternal(classLoader: ClassLoader) = runCatching {
    val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService")
    val method = clz.declaredMethods.firstOrNull {
        it.name == "getDisplayInfoInternal" &&
            it.parameterCount == 2 &&
            it.parameterTypes[0] == java.lang.Integer.TYPE &&
            it.parameterTypes[1] == java.lang.Integer.TYPE
    } ?: throw NoSuchMethodException("DisplayManagerService.getDisplayInfoInternal(II)")
    hook(method).intercept { chain ->
        val displayId = chain.getArg(0) as? Int
        val uid = chain.getArg(1) as? Int
        val result = chain.proceed()
        if (result == null || displayId == null || uid == null) {
            result
        } else {
            pointHits.incrementAndGet()
            if (!DisplayPolicyRegistry.isVisible(uid, displayId)) {
                val b = pointBlocked.incrementAndGet()
                Log.i(TAG, "POINT block uid=$uid displayId=$displayId -> null (block#=$b)")
                null
            } else {
                result
            }
        }
    }
    installed["DisplayManagerService.getDisplayInfoInternal"] = true
    log(Log.INFO, TAG, "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()}")
}.onFailure {
    installed["DisplayManagerService.getDisplayInfoInternal"] = false
    log(Log.ERROR, TAG, "hook getDisplayInfoInternal failed", it)
}

/** Reads the "display added/removed" event codes, which the eye-swap gate must never swallow. */
private fun resolveEyeGateEventCodes() = runCatching {
    val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
    fun code(name: String) = global.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
    EyeSwapGate.shared.addedEvent = code("EVENT_DISPLAY_ADDED")
    EyeSwapGate.shared.removedEvent = code("EVENT_DISPLAY_REMOVED")
}.onFailure { Log.w(TAG, "event codes not found, using defaults (added=1, removed=3): ${it.message}") }

private fun XposedModule.hookCallbackTransmitDisplayEvents(classLoader: ClassLoader) = runCatching {
    resolveEyeGateEventCodes()
    val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService\$CallbackRecord")
    val method = clz.declaredMethods.firstOrNull {
        it.name == "transmitDisplayEvent" &&
            it.parameterCount == 2 &&
            it.returnType == Void.TYPE
    } ?: throw NoSuchMethodException("CallbackRecord.transmitDisplayEvent(II)V")
    callbackUidField = runCatching {
        clz.getDeclaredField("mUid").apply { isAccessible = true }
    }.getOrNull()
    hook(method).intercept { chain ->
        val displayId = chain.getArg(0) as? Int
        val record = chain.thisObject
        val uid = record?.let { readCallbackUid(it) }
        if (displayId == null || uid == null) {
            chain.proceed()
        } else {
            transmitHits.incrementAndGet()
            val event = chain.getArg(1) as? Int
            if (!DisplayPolicyRegistry.isVisible(uid, displayId)) {
                val s = transmitSuppressed.incrementAndGet()
                Log.i(TAG, "EVENT transmit-suppress uid=$uid displayId=$displayId (suppress#=$s)")
                null
            } else if (event != null && EyeSwapGate.shared.shouldSwallow(displayId, event, uid)) {
                Log.i(TAG, "EVENT eye-swap swallow uid=$uid displayId=$displayId event=$event")
                null
            } else {
                chain.proceed()
            }
        }
    }
    installed["CallbackRecord.transmitDisplayEvent"] = true
    log(Log.INFO, TAG, "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()}")
}.onFailure {
    installed["CallbackRecord.transmitDisplayEvent"] = false
    log(Log.ERROR, TAG, "hook transmitDisplayEvent failed", it)
}

/**
 * Per-callback entry of event delivery: `deliverDisplayEvent` calls it for every callback.
 * Events for hidden displayIds are swallowed right here, at the finest granularity.
 */
private fun XposedModule.hookCallbackNotifyDisplayEvent(classLoader: ClassLoader) = runCatching {
    val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService\$CallbackRecord")
    val method = clz.declaredMethods.firstOrNull {
        it.name == "notifyDisplayEventAsync" &&
            it.parameterCount == 2 &&
            it.parameterTypes[0] == java.lang.Integer.TYPE &&
            it.parameterTypes[1] == java.lang.Integer.TYPE
    } ?: throw NoSuchMethodException("CallbackRecord.notifyDisplayEventAsync(II)Z")
    // Android 14+ returns boolean (false = not delivered); Android 12/13 return void.
    val swallowed: Any? = if (method.returnType == java.lang.Boolean.TYPE) false else null
    hook(method).intercept { chain ->
        val displayId = chain.getArg(0) as? Int
        val record = chain.thisObject
        val uid = record?.let { readCallbackUid(it) }
        if (displayId == null || uid == null) {
            chain.proceed()
        } else {
            notifyHits.incrementAndGet()
            val event = chain.getArg(1) as? Int
            if (!DisplayPolicyRegistry.isVisible(uid, displayId)) {
                val s = notifySuppressed.incrementAndGet()
                Log.i(TAG, "EVENT notify-suppress uid=$uid displayId=$displayId (suppress#=$s)")
                swallowed
            } else if (event != null && EyeSwapGate.shared.shouldSwallow(displayId, event, uid)) {
                Log.i(TAG, "EVENT eye-swap swallow uid=$uid displayId=$displayId event=$event")
                swallowed
            } else {
                chain.proceed()
            }
        }
    }
    installed["CallbackRecord.notifyDisplayEventAsync"] = true
    log(Log.INFO, TAG, "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()}")
}.onFailure {
    installed["CallbackRecord.notifyDisplayEventAsync"] = false
    log(Log.ERROR, TAG, "hook notifyDisplayEventAsync failed", it)
}

/**
 * Read-only diagnostics: confirms events really pass through DMS's `deliverDisplayEvent` convergence point.
 * Only logs when "the displayId appears in some policy", to avoid flooding the log.
 */
private fun XposedModule.hookDeliverDisplayEventDiag(classLoader: ClassLoader) = runCatching {
    val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService")
    val method = clz.declaredMethods.firstOrNull {
        // 4 parameters on Android 14+, 3 (displayId, uids, event) on Android 12/13
        it.name == "deliverDisplayEvent" && (it.parameterCount == 4 || it.parameterCount == 3)
    } ?: throw NoSuchMethodException("DisplayManagerService.deliverDisplayEvent(I,ArraySet,I,...)")
    hook(method).intercept { chain ->
        val displayId = chain.getArg(0) as? Int
        if (displayId != null && DisplayPolicyRegistry.policedDisplayIds().contains(displayId)) {
            Log.i(
                TAG,
                "EVENT deliver funnel displayId=$displayId event=${chain.getArg(2)} uids=${chain.getArg(1)}"
            )
        }
        chain.proceed()
    }
    installed["DisplayManagerService.deliverDisplayEvent"] = true
    log(Log.INFO, TAG, "hooked (diag) ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()}")
}.onFailure {
    installed["DisplayManagerService.deliverDisplayEvent"] = false
    log(Log.WARN, TAG, "diag hook deliverDisplayEvent failed", it)
}

private fun readCallbackUid(record: Any): Int? = runCatching {
    val field = callbackUidField ?: record.javaClass.getDeclaredField("mUid").apply {
        isAccessible = true
        callbackUidField = this
    }
    field.getInt(record)
}.getOrElse {
    uidReadFailures.incrementAndGet()
    null
}

/** For acceptance/self-check: snapshot of hook hit counters (distinguishes "installed" from "effective"). */
fun visibilityHookStats(): String =
    "installed=$installedSummary " +
        "list(call=${listHits.get()},trim=${listTrimmed.get()}) " +
        "point(call=${pointHits.get()},block=${pointBlocked.get()}) " +
        "notify(call=${notifyHits.get()},suppress=${notifySuppressed.get()}) " +
        "transmit(call=${transmitHits.get()},suppress=${transmitSuppressed.get()},uidErr=${uidReadFailures.get()})"
