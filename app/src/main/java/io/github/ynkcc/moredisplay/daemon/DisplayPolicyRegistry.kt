package io.github.ynkcc.moredisplay.daemon

import android.os.Process
import android.util.Log
import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.OrientationOverride
import io.github.ynkcc.moredisplay.core.Visibility

/**
 * In-process policy table (in Xposed mode this lives inside the system_server process).
 *
 * Like `SystemDisplayEngine`, it uses `LinkedHashMap` + `synchronized(lock)`:
 * thread-safe and insertion-ordered (stable `list` output).
 *
 * ## Pass-through by default (hard constraint)
 * - No entries at all -> behaves exactly as without the module;
 * - **Non-app uids** (uid<10000: root/system/systemui/shell/mediaserver...) are always passed through,
 *   so a policy can never break the system.
 */
object DisplayPolicyRegistry {

    private const val TAG = "MoreDisplay_Policy"

    const val NO_POLICY_UID = -1

    /** Multi-user uid layout: uid = userId * 100000 + appId. */
    private const val PER_USER_RANGE = 100000

    /**
     * The default display (`Display.DEFAULT_DISPLAY`) is **never filtered**.
     *
     * Observed: if display 0 is removed from an app's view, the framework's
     * `com.android.internal.policy.DecorContext.<init>` gets a null `Display` and throws an NPE
     * (`Attempt to invoke virtual method 'int android.view.Display.getDisplayId()' on a null object reference`),
     * and the app fails to start. It is also the display the caller's own window lives on, so semantically it must not be hidden.
     * For "full isolation" scenarios, use a managed virtual display (launch the app onto it) instead of hiding display 0.
     */
    const val DEFAULT_DISPLAY_ID = 0

    /**
     * Provider of the "hook hit counter" snapshot, injected by the xposed layer (non-null only in Xposed mode).
     * Used to print "installed vs effective" evidence in `policy-list` / `displays-for-uid`.
     */
    @Volatile
    var statsProvider: (() -> String)? = null

    private val lock = Any()
    private val policies = LinkedHashMap<Int, DisplayPolicy>()

    /** Cache of [policedDisplayIds], to avoid allocating collections repeatedly on hot event paths. */
    @Volatile
    private var policedIds: Set<Int> = emptySet()

    fun set(policy: DisplayPolicy): DisplayPolicy = synchronized(lock) {
        val sanitized = if (policy.visibility != Visibility.ALL &&
            policy.displayIds.contains(DEFAULT_DISPLAY_ID)
        ) {
            Log.w(TAG, "Policy ${policy.describe()} contains default display $DEFAULT_DISPLAY_ID; entry ignored (the default display is never filtered)")
            policy.copy(displayIds = policy.displayIds - DEFAULT_DISPLAY_ID)
        } else {
            policy
        }
        policies[sanitized.uid] = sanitized
        rebuildPolicedIdsLocked()
        sanitized
    }

    fun remove(uid: Int): Boolean = synchronized(lock) {
        val removed = policies.remove(uid) != null
        if (removed) rebuildPolicedIdsLocked()
        removed
    }

    fun get(uid: Int): DisplayPolicy? = synchronized(lock) { policies[uid] }

    fun list(): List<DisplayPolicy> = synchronized(lock) { policies.values.toList() }

    fun clear() = synchronized(lock) {
        policies.clear()
        rebuildPolicedIdsLocked()
    }

    /** Union of every displayId that appears in any policy (used only for log filtering, to avoid flooding). */
    fun policedDisplayIds(): Set<Int> = policedIds

    private fun rebuildPolicedIdsLocked() {
        policedIds = policies.values.flatMapTo(mutableSetOf()) { it.displayIds }
    }

    /**
     * Requirement B: the recording mirror target display for this uid.
     * Non-null only when "it is an app uid and recordDisplayId is configured"; otherwise null (= no intervention).
     */
    fun recordDisplayIdFor(uid: Int): Int? {
        if (!isAppUid(uid)) return null
        return get(uid)?.recordDisplayId
    }

    /**
     * Requirement C: the accessibility operation target display for this package (the package hosting the accessibility service).
     *
     * The accessibility connection object has no uid, only the service's `ComponentName` and `mUserId`,
     * so matching is by "package name + user owning the service" (`DisplayPolicy.packageName` +
     * the userId encoded in the policy uid, i.e. `uid / 100000`). On multi-user setups the same package is installed
     * once per user (with different uids), so userId is needed to disambiguate.
     *
     * Non-null only when `operatedDisplayId` is configured; otherwise null (= no intervention).
     */
    fun operatedDisplayIdForPackage(packageName: String?, userId: Int): Int? {
        if (packageName.isNullOrEmpty()) return null
        return synchronized(lock) {
            policies.values.firstOrNull {
                it.packageName == packageName && it.uid / PER_USER_RANGE == userId
            }?.operatedDisplayId
        }
    }

    /**
     * "Open on": the display every activity of this app should start on, or null (= no intervention).
     * Matched by package name + user, like [operatedDisplayIdForPackage], because the starter knows the target
     * activity's package and user; [uid] is used when it is known.
     */
    fun launchDisplayIdFor(packageName: String?, uid: Int): Int? {
        if (!isAppUid(uid) && packageName.isNullOrEmpty()) return null
        return synchronized(lock) {
            policies[uid]?.launchDisplayId
                ?: policies.values.firstOrNull {
                    !packageName.isNullOrEmpty() && it.packageName == packageName &&
                        it.uid / PER_USER_RANGE == uid / PER_USER_RANGE
                }?.launchDisplayId
        }
    }

    /** The orientation override for an app's activity ([packageName] + [uid], matched like [launchDisplayIdFor]). */
    fun orientationFor(packageName: String?, uid: Int): OrientationOverride {
        if (policies.isEmpty()) return OrientationOverride.APP
        return synchronized(lock) {
            policies[uid]?.orientation
                ?: policies.values.firstOrNull {
                    !packageName.isNullOrEmpty() && it.packageName == packageName &&
                        it.uid / PER_USER_RANGE == uid / PER_USER_RANGE
                }?.orientation
                ?: OrientationOverride.APP
        }
    }

    /** Uids of the apps set to "Open on" [displayId] (whose sound is cast with that display). */
    fun uidsOpeningOn(displayId: Int): Set<Int> = synchronized(lock) {
        policies.values.filter { it.launchDisplayId == displayId }.mapTo(mutableSetOf()) { it.uid }
    }

    /** Whether this uid should see this displayId. */
    fun isVisible(uid: Int, displayId: Int): Boolean {
        // The default display is never filtered (see the DEFAULT_DISPLAY_ID comment: removing it makes DecorContext NPE).
        if (displayId == DEFAULT_DISPLAY_ID) return true
        if (!isAppUid(uid)) return true
        val policy = get(uid) ?: return true
        return when (policy.visibility) {
            Visibility.ALL -> true
            Visibility.ONLY -> policy.displayIds.contains(displayId)
            Visibility.HIDE -> !policy.displayIds.contains(displayId)
        }
    }

    /** Filters a displayId list by policy; with no policy / a non-app uid it is returned unchanged (no new array allocated). */
    fun filter(uid: Int, displayIds: IntArray): IntArray {
        if (!isAppUid(uid)) return displayIds
        if (get(uid) == null) return displayIds
        return displayIds.filter { isVisible(uid, it) }.toIntArray()
    }

    /**
     * App uid check: on multi-user setups uid = userId*100000 + appId, with appId in [10000,19999] (isolated processes 90000+ included).
     * System/root/shell are never app uids.
     */
    fun isAppUid(uid: Int): Boolean {
        if (uid < Process.FIRST_APPLICATION_UID) return false
        val appId = uid % 100000
        return appId >= Process.FIRST_APPLICATION_UID
    }
}
