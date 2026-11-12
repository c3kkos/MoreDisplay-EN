package io.github.ynkcc.moredisplay.daemon

import android.util.Log

/**
 * "Recents gate": keeps tasks on managed virtual displays out of the main display's recents (single binder-hook approach).
 *
 * ## Why hook `ActivityTaskManagerService#getRecentTasks`
 *
 * Every recents UI on every ROM must fetch its data through `IActivityTaskManager.getRecentTasks`:
 * - AOSP / LineageOS Launcher3 calls that Binder directly;
 * - ColorOS's own `IRecentTasks` (SystemUI Shell `RecentTasksController`) was also observed to converge on
 *   `ActivityTaskManager.getRecentTasks` (verified by decompiling, two real devices on Android 16).
 *
 * That entry is public API; its signature has been `(int maxNum, int flags, int userId)` since Android R
 * and hasn't changed -- far more stable than WM-internal field/method names (DWPC, canShowTasksInHostDeviceRecents, etc.),
 * so this only does **filtering at the binder convergence point**, no WM-internal injection.
 *
 * Filtering happens at the same level as `isVisibleRecentTask` and takes effect live whenever the launcher queries:
 * - unaffected by the flags the launcher passes in (`RECENT_WITH_LIVE_TASKS`, etc.);
 * - only removes tasks whose `displayId is in the managed display set` ([SystemDaemon.isManagedDisplay]);
 *   everything else is returned unchanged -- with no policies the behavior is identical to having no module;
 * - tasks remain in the system's internal task stack (no process killed, no finish); they just don't appear in recents query results.
 *
 * ## Compatibility design (hard constraint: dynamic matching + failures only log, never crash)
 * - dynamically matches all overloads by method name + return type `android.content.pm.ParceledListSlice`,
 *   without hard-coding the parameter signature (a ROM adding parameters to this entry won't make the hook miss);
 * - even if a ROM subclass (e.g. OplusActivityTaskManagerService) overrides and rewrites the result,
 *   this hook still works as long as it calls super;
 * - removal from the list is done in place with `remove` when possible (ATMS passes a freshly created ArrayList); if immutable, the
 *   ParceledListSlice is rebuilt (reflective constructor); failing that it passes through unchanged -- any failing step only logs.
 */
object RecentsGate {

    const val TAG = "MoreDisplay_RecentsGate"
    private const val ATMS_CLASS = "com.android.server.wm.ActivityTaskManagerService"
    private const val SLICE_TYPE = "android.content.pm.ParceledListSlice"

    /** Whether the hook is installed ("installed"; "effective" means [filteredCount] > 0). */
    @Volatile
    var hookInstalled: Boolean = false
        private set

    /** Number of target methods the hook was installed on. */
    @Volatile
    var targetCount: Int = 0
        private set

    /** Filter hit count (incremented only when tasks are actually removed). */
    @Volatile
    var filteredCount: Int = 0
        private set

    fun stats(): String = "installed=$hookInstalled targets=$targetCount filtered=$filteredCount"

    /**
     * Managed-display check callback, injected by the module entry when installing the hook (avoids a daemon <-> xposed reverse dependency).
     * Returns true if the displayId is managed by us and its tasks should be removed from recents.
     */
    @Volatile
    var isManagedDisplay: ((Int) -> Boolean)? = null

    /**
     * Dynamically matches the getRecentTasks target methods on ATMS. Called by [io.github.ynkcc.moredisplay.xposed.LsposedModule]
     * during system_server startup, which then installs the hook.
     *
     * @return number of matched methods (0 = matching failed; the caller logs ERROR).
     */
    fun findTargets(classLoader: ClassLoader): List<java.lang.reflect.Method> {
        val atms = runCatching { classLoader.loadClass(ATMS_CLASS) }
            .getOrElse {
                Log.e(TAG, "load $ATMS_CLASS failed: $it")
                return emptyList()
            }
        val targets = atms.declaredMethods.filter {
            it.name == "getRecentTasks" && it.returnType.name == SLICE_TYPE
        }
        if (targets.isEmpty()) {
            Log.e(TAG, "no getRecentTasks returning $SLICE_TYPE on ${atms.name}")
        }
        return targets
    }

    /** Records the installation result (for logs and stats). */
    fun markInstalled(count: Int) {
        targetCount = count
        hookInstalled = count > 0
    }

    /**
     * The hook's after-callback: removes managed-display tasks from the result. Never throws; on failure passes through unchanged.
     */
    fun filterRecentTasks(result: Any?): Any? {
        if (result == null) return null
        val list = runCatching {
            result.javaClass.getMethod("getList").invoke(result) as? MutableList<Any?>
        }.getOrNull() ?: return result
        if (list.isEmpty()) return result

        var removed = 0
        val iterated = runCatching {
            val it = list.iterator()
            while (it.hasNext()) {
                val t = it.next() ?: continue
                val disp = runCatching { t.javaClass.getField("displayId").getInt(t) }
                    .getOrElse { Int.MIN_VALUE }
                if (disp != Int.MIN_VALUE && isManagedDisplay?.invoke(disp) == true) {
                    it.remove()
                    removed++
                }
            }
            true
        }.getOrDefault(false)

        if (!iterated) {
            // List is immutable: rebuild the slice once as a fallback.
            return runCatching {
                val kept = list.filter { t ->
                    val disp: Int = t?.let {
                        runCatching { it.javaClass.getField("displayId").getInt(it) }.getOrDefault(Int.MIN_VALUE)
                    } ?: Int.MIN_VALUE
                    disp == Int.MIN_VALUE || isManagedDisplay?.invoke(disp) != true
                }
                val ctor = result.javaClass.getConstructor(java.util.List::class.java)
                ctor.isAccessible = true
                ctor.newInstance(kept)
            }.getOrElse {
                Log.e(TAG, "rebuild ParceledListSlice failed, pass through: $it")
                result
            }
        }

        if (removed > 0) {
            filteredCount += removed
            Log.i(TAG, "filtered $removed recent task(s) on managed displays (total=$filteredCount)")
        }
        return result
    }
}
