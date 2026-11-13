package io.github.ynkcc.moredisplay.xposed

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.Display
import io.github.libxposed.api.XposedModule
import io.github.ynkcc.moredisplay.daemon.DisplayPolicyRegistry
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/**
 * "Open on": starts every activity of an app on the display its policy names, whoever launches it (the home
 * screen, a notification, another app, the app itself).
 *
 * ## Hook point
 * `ActivityStarter#executeRequest(Request)` (Android 11+), falling back to `ActivityStarter#execute()` with its
 * `mRequest` field (Android 10). At this point the target activity is resolved (`Request.activityInfo`), so the
 * target package and uid are known. The request's launch options get `launchDisplayId = target`, exactly as if
 * the caller had used `ActivityOptions.setLaunchDisplayId`. Android then places the task on that display; an
 * existing task of the app is moved there when it is brought to front.
 *
 * Launching onto a managed display is allowed by the launch-permission hook (`isCallerAllowedToLaunchOnDisplay`).
 *
 * ## Left alone
 * - apps without an "Open on" display, and targets that aren't managed displays any more;
 * - launches that already ask for a specific display other than the built-in one.
 */
private const val TAG = "MoreDisplay_Launch"
private const val STARTER = "com.android.server.wm.ActivityStarter"
private const val SAFE_OPTIONS = "com.android.server.wm.SafeActivityOptions"

private val launchHits = AtomicLong()
private val launchRedirected = AtomicLong()

@Volatile
private var launchHookInstalled = "false"

private class StarterFields(
    val intent: Field,
    val activityInfo: Field,
    val options: Field,
    val userId: Field?,
    val originalOptions: Field,
    val newSafeOptions: Constructor<*>
)

fun XposedModule.installLaunchRedirectHooks(classLoader: ClassLoader) {
    runCatching {
        val starter = classLoader.loadClass(STARTER)
        val requestClass = classLoader.loadClass("$STARTER\$Request")
        val safeOptions = classLoader.loadClass(SAFE_OPTIONS)
        fun field(clz: Class<*>, name: String) = clz.getDeclaredField(name).apply { isAccessible = true }
        val fields = StarterFields(
            intent = field(requestClass, "intent"),
            activityInfo = field(requestClass, "activityInfo"),
            options = field(requestClass, "activityOptions"),
            userId = runCatching { field(requestClass, "userId") }.getOrNull(),
            originalOptions = field(safeOptions, "mOriginalOptions"),
            newSafeOptions = safeOptions.getDeclaredConstructor(ActivityOptions::class.java)
                .apply { isAccessible = true }
        )

        val byRequest = starter.declaredMethods.firstOrNull {
            it.name == "executeRequest" && it.parameterCount == 1 && it.parameterTypes[0] == requestClass
        }
        if (byRequest != null) {
            hook(byRequest).intercept { chain ->
                chain.getArg(0)?.let { redirect(fields, it) }
                chain.proceed()
            }
        } else {
            val execute = starter.declaredMethods.firstOrNull { it.name == "execute" && it.parameterCount == 0 }
                ?: throw NoSuchMethodException("ActivityStarter.executeRequest(Request) / execute()")
            val mRequest = field(starter, "mRequest")
            hook(execute).intercept { chain ->
                chain.thisObject?.let { mRequest.get(it) }?.let { redirect(fields, it) }
                chain.proceed()
            }
        }
        launchHookInstalled = "true"
        log(Log.INFO, TAG, "launch redirect hook installed (${if (byRequest != null) "executeRequest" else "execute"})")
    }.onFailure {
        launchHookInstalled = "false"
        log(Log.ERROR, TAG, "launch redirect hook failed", it)
    }
}

private fun redirect(fields: StarterFields, request: Any) {
    runCatching {
        val info = fields.activityInfo.get(request) as? ActivityInfo
        val intent = fields.intent.get(request) as? Intent
        val pkg = info?.packageName ?: intent?.component?.packageName ?: intent?.`package` ?: return
        val uid = info?.applicationInfo?.uid
            ?: ((fields.userId?.getInt(request) ?: 0) * 100_000 + android.os.Process.FIRST_APPLICATION_UID)
        val target = DisplayPolicyRegistry.launchDisplayIdFor(pkg, uid) ?: return
        launchHits.incrementAndGet()
        if (target == Display.DEFAULT_DISPLAY || !SystemDaemon.isManagedDisplay(target)) return

        val safe = fields.options.get(request)
        val original = safe?.let { fields.originalOptions.get(it) as? ActivityOptions }
        when {
            original != null -> {
                val asked = original.launchDisplayId
                if (asked != Display.INVALID_DISPLAY && asked != Display.DEFAULT_DISPLAY) return
                original.setLaunchDisplayId(target)
            }
            safe == null -> fields.options.set(
                request,
                fields.newSafeOptions.newInstance(ActivityOptions.makeBasic().setLaunchDisplayId(target))
            )
            else -> fields.originalOptions.set(safe, ActivityOptions.makeBasic().setLaunchDisplayId(target))
        }
        val n = launchRedirected.incrementAndGet()
        Log.i(TAG, "LAUNCH $pkg ($uid) -> display $target (redirect#=$n)")
    }.onFailure { Log.e(TAG, "launch redirect failed", it) }
}

/** For the self-check log line: installed / hits / redirected. */
fun launchRedirectHookStats(): String =
    "launch(installed=$launchHookInstalled hit=${launchHits.get()} redirected=${launchRedirected.get()})"
