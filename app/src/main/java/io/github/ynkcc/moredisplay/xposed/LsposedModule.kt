package io.github.ynkcc.moredisplay.xposed

import android.content.Context
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.ynkcc.moredisplay.daemon.DisplayEngine
import io.github.ynkcc.moredisplay.daemon.DisplayPolicyRegistry
import io.github.ynkcc.moredisplay.daemon.ProviderRpc
import io.github.ynkcc.moredisplay.daemon.RecentsGate
import io.github.ynkcc.moredisplay.daemon.SystemDisplayEngine
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap

/**
 * LibXposed API 102 module entry (runs on Vector, and on LSPosed builds that support API 102).
 *
 * The scope only needs `system` (the system_server process, per the libxposed scope rules):
 * 1. Builds the virtual display engine inside system_server and hooks `ContentProvider$Transport#call`
 *    to answer the app's RPCs (see [ProviderRpc]);
 * 2. Relaxes `ActivityTaskSupervisor#isCallerAllowedToLaunchOnDisplay`
 *    so apps can launch themselves onto our managed virtual displays;
 * 3. Filters display visibility by caller uid (list / by-id / events, see
 *    [installDisplayVisibilityHooks]), **without hooking the target app**;
 * 4. Rewrites the screen-recording mirror source to a managed display by caller uid (see [installRecordMirrorHooks]);
 * 5. Rewrites accessibility gestures to a managed display by service package name (see [installAccessibilityRedirectHooks]);
 * 6. Removes managed-display tasks from recent-task query results (see [hookRecentsGate]) --
 *    every ROM's recents UI ultimately goes through `ActivityTaskManagerService#getRecentTasks`.
 */
class LsposedModule : XposedModule() {

    companion object {
        private const val TAG = "MoreDisplay_Lsposed"
        private const val MODULE_PACKAGE = "io.github.ynkcc.moredisplay"
        private const val TRANSPORT_CLASS = "android.content.ContentProvider\$Transport"

        /** Executable -> indexes of its `String` parameters, to avoid repeated reflection on hot provider calls. */
        private val stringArgIndexes = ConcurrentHashMap<Executable, IntArray>()

        /** Sampling counter for provider calls (ROM difference diagnostics). */
        private val transportCalls = java.util.concurrent.atomic.AtomicLong()
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        log(Log.INFO, TAG, "system_server starting, installing hooks")
        hookAmsContext(param.classLoader)
        hookProviderRpc()
        hookLaunchPermission(param.classLoader)
        installDisplayVisibilityHooks(param.classLoader)
        installRecordMirrorHooks(param.classLoader)
        installAccessibilityRedirectHooks(param.classLoader)
        hookRecentsGate(param.classLoader)
        installLaunchRedirectHooks(param.classLoader)
        installOrientationHooks(param.classLoader)
        DisplayPolicyRegistry.statsProvider = {
            "${visibilityHookStats()} | ${recordMirrorHookStats()} | ${accessibilityHookStats()} | ${launchRedirectHookStats()} | ${orientationHookStats()}"
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName == MODULE_PACKAGE) {
            LsposedBridge.markInjected()
            log(Log.INFO, TAG, "module injected into own app process")
        }
    }

    /**
     * Captures `ActivityManagerService.mUiContext` (field name is `mUiContext` on Android 16,
     * falling back to `mContext` on older versions) to initialize the engine.
     *
     * Note: this callback fires **before the original constructor body runs**, when the fields are not yet assigned,
     * so they must be read after `chain.proceed()`, otherwise the result is always null.
     */
    private fun hookAmsContext(classLoader: ClassLoader) {
        runCatching {
            val ams = classLoader.loadClass("com.android.server.am.ActivityManagerService")
            val targets = ams.declaredConstructors
                .filter { it.parameterTypes.firstOrNull() == Context::class.java }
            targets.forEach { ctor ->
                hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    runCatching { attachContext(chain.thisObject) }
                        .onFailure { log(Log.ERROR, TAG, "attach daemon context failed", it) }
                    result
                }
            }
            log(Log.INFO, TAG, "ams constructor hooks installed: ${targets.size}")
        }.onFailure { log(Log.ERROR, TAG, "hookAmsContext failed", it) }
    }

    private fun attachContext(ams: Any) {
        val context = readContext(ams, "mUiContext") ?: readContext(ams, "mContext")
        if (context == null) {
            log(Log.WARN, TAG, "no usable context field on ${ams.javaClass.name}")
            return
        }
        SystemDaemon.attach(context)
        log(Log.INFO, TAG, "daemon engine ready in system_server")
    }

    private fun readContext(target: Any, fieldName: String): Context? = runCatching {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(target) as? Context
    }.getOrNull()

    /**
     * Hooks the common entry point of all in-process provider calls, with several fallbacks (ROM differences):
     *
     * 1. `ContentProvider$Transport#call` -- the AOSP binder entry (hit on POCO/AOSP-like ROMs);
     * 2. `ContentProvider#call(String,String,String,Bundle)` -- the `mInterface.call` that Transport
     *    forwards to internally (on ColorOS, Transport.call appears to be AOT-inlined so the hook misses; this catches it);
     * 3. `ContentProvider#call(String,String,Bundle)` -- SDK entry as a last resort (only effective if the subclass doesn't override it).
     *
     * Only calls whose method equals [ProviderRpc.METHOD] are taken over; everything else passes through unchanged.
     * Hit points are marked with sampled logs (`PROVIDER sample ... exe=...`) to identify the active layer per ROM.
     */
    private fun hookProviderRpc() {
        val targets = mutableListOf<java.lang.reflect.Method>()
        runCatching {
            val transport = Class.forName(TRANSPORT_CLASS)
            targets += transport.declaredMethods.filter {
                it.name == "call" && it.parameterTypes.lastOrNull() == Bundle::class.java
            }
        }.onFailure { log(Log.WARN, TAG, "load $TRANSPORT_CLASS failed", it) }
        runCatching {
            val provider = Class.forName("android.content.ContentProvider")
            targets += provider.declaredMethods.filter {
                it.name == "call" && it.parameterTypes.lastOrNull() == Bundle::class.java
            }
        }.onFailure { log(Log.WARN, TAG, "load ContentProvider failed", it) }
        if (targets.isEmpty()) {
            log(Log.ERROR, TAG, "hookProviderRpc: no target methods found")
            return
        }
        targets.forEach { method ->
            hook(method).intercept { chain ->
                val indexes = stringArgIndexes.getOrPut(chain.executable) {
                    chain.executable.parameterTypes.mapIndexedNotNull { i, type ->
                        if (type == String::class.java) i else null
                    }.toIntArray()
                }
                val isRpc = indexes.any { chain.getArg(it) == ProviderRpc.METHOD }
                if (!isRpc) {
                    // Sampled diagnostics: confirm each hook layer actually fires (for investigating ROM differences).
                    val n = transportCalls.incrementAndGet()
                    if (n <= 3 || n % 500 == 0L) {
                        val args = indexes.map { idx -> chain.getArg(idx) as? String }
                        Log.i(
                            TAG,
                            "PROVIDER sample#$n exe=${chain.executable.declaringClass.simpleName}" +
                                "#${chain.executable.name}/${chain.executable.parameterCount} strings=$args"
                        )
                    }
                    chain.proceed()
                } else {
                    val engine = SystemDaemon.engine
                    val extras = chain.getArg(chain.executable.parameterTypes.size - 1) as? Bundle
                    if (engine == null) {
                        ProviderRpc.errorBundle(-2, "daemon engine not ready in system_server")
                    } else {
                        Log.i(TAG, "RPC hit on ${chain.executable.declaringClass.simpleName}#${chain.executable.name}")
                        ProviderRpc.handle(engine, extras, SystemDaemon::isMoreDisplayUid)
                    }
                }
            }
        }
        log(
            Log.INFO,
            TAG,
            "provider rpc hooks installed on ${targets.size} target(s): " +
                targets.joinToString { "${it.declaringClass.simpleName}#${it.name}/${it.parameterCount}" }
        )
    }

    /**
     * Recents gate: hooks `ActivityTaskManagerService#getRecentTasks` (all overloads);
     * the after-callback removes managed-display tasks by displayId.
     *
     * Matching rule: method name getRecentTasks + returns ParceledListSlice (the parameter signature is not hard-coded,
     * so it still installs if a ROM adds or removes parameters on that entry). Target methods and filter logic: see [RecentsGate].
     */
    private fun hookRecentsGate(classLoader: ClassLoader) {
        runCatching {
            val targets = RecentsGate.findTargets(classLoader)
            if (targets.isEmpty()) {
                log(Log.ERROR, TAG, "hookRecentsGate: no target methods found")
                return
            }
            targets.forEach { method ->
                hook(method).intercept { chain -> RecentsGate.filterRecentTasks(chain.proceed()) }
            }
            RecentsGate.isManagedDisplay = { SystemDaemon.isManagedDisplay(it) }
            RecentsGate.markInstalled(targets.size)
            log(
                Log.INFO, TAG,
                "recents gate hooks installed on ${targets.size} target(s): " +
                    targets.joinToString { "getRecentTasks/${it.parameterCount}" }
            )
        }.onFailure { log(Log.ERROR, TAG, "hookRecentsGate failed", it) }
    }

    private fun hookLaunchPermission(classLoader: ClassLoader) {
        // Renamed in Android 12; Android 10/11 have the same method on ActivityStackSupervisor.
        val supervisor = if (android.os.Build.VERSION.SDK_INT >= 31) {
            "com.android.server.wm.ActivityTaskSupervisor"
        } else {
            "com.android.server.wm.ActivityStackSupervisor"
        }
        runCatching {
            val clz = classLoader.loadClass(supervisor)
            val method = clz.declaredMethods.firstOrNull {
                it.name == "isCallerAllowedToLaunchOnDisplay" && it.parameterCount == 4
            } ?: throw NoSuchMethodException("isCallerAllowedToLaunchOnDisplay(IIILActivityInfo;)Z")
            hook(method).intercept { chain ->
                val displayId = chain.getArg(2) as? Int
                if (displayId != null && displayId != 0 && SystemDaemon.isManagedDisplay(displayId)) {
                    true
                } else {
                    chain.proceed()
                }
            }
            log(Log.INFO, TAG, "launch permission hook installed")
        }.onFailure { log(Log.ERROR, TAG, "hookLaunchPermission failed", it) }
    }
}

/**
 * Holder of the daemon engine inside system_server: one per process, repeated attaches are idempotent.
 */
object SystemDaemon {
    private const val TAG = "MoreDisplay_Lsposed"

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var engineRef: DisplayEngine? = null

    val engine: DisplayEngine?
        get() = engineRef ?: createEngine()

    fun attach(ctx: Context) {
        if (systemContext == null) systemContext = ctx
        createEngine()
    }

    /** True if [uid] belongs to the MoreDisplay app (the only caller allowed to attach a viewer). */
    fun isMoreDisplayUid(uid: Int): Boolean = runCatching {
        val packages = systemContext?.packageManager?.getPackagesForUid(uid) ?: return false
        MORE_DISPLAY_PACKAGE in packages
    }.getOrDefault(false)

    private const val MORE_DISPLAY_PACKAGE = "io.github.ynkcc.moredisplay"

    private fun createEngine(): DisplayEngine? {
        engineRef?.let { return it }
        val ctx = systemContext ?: return null
        synchronized(this) {
            engineRef?.let { return it }
            return SystemDisplayEngine(ctx).also {
                engineRef = it
                Log.i(TAG, "system daemon engine created pid=${android.os.Process.myPid()}")
            }
        }
    }

    fun isManagedDisplay(displayId: Int): Boolean =
        engineRef?.listDisplays()?.any { it.displayId == displayId } == true
}
